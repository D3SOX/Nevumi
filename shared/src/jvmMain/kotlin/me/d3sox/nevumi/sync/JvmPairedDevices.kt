package me.d3sox.nevumi.sync

import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.security.CredentialVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

@Serializable
private data class PairInvitation(val version: Int = 1, val deviceId: String, val name: String, val host: String,
    val port: Int, val certificateSha256: String, val secret: String, val expiresAt: Long) {
    override fun toString() = "PairInvitation(<private>)"
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class SyncRequest(val version: Int = 1, val action: String, val deviceId: String, val name: String,
    val listeningPort: Int, val secret: String? = null, val snapshot: SyncSnapshot? = null,
    val playback: PlaybackTransfer? = null, val transferId: String? = null, val positionMs: Long? = null,
    val playWhenReady: Boolean? = null,
    /** Listens the requester already holds from this peer start before this time; omitted on a first sync. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val listensSince: Long? = null) {
    override fun toString() = "SyncRequest($action, <private>)"
}

@Serializable
private data class SyncResponse(val version: Int = 1, val deviceId: String, val name: String, val listeningPort: Int,
    val snapshot: SyncSnapshot? = null, val playback: PlaybackTransfer? = null, val error: String? = null,
    val skippedSongs: Int = 0)

class JvmPairedDevices(private val store: MusicStore, override val library: LibrarySync,
    private val vault: CredentialVault, private val name: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val networkAccess: suspend (String) -> Unit = {}) : PairedDevices {
    private val mutableState = MutableStateFlow(PairedDevicesState())
    override val state = mutableState.asStateFlow()
    override val localAddresses: List<String> get() = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
        .filter { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isMulticastAddress }
        .sortedByDescending { it.isSiteLocalAddress }.map { it.hostAddress }.distinct()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val operations = Mutex()
    private val incoming = Mutex()
    private val slots = Semaphore(4)
    private val offer = AtomicReference<PairInvitation?>()
    private val sockets = ConcurrentHashMap.newKeySet<SSLSocket>()
    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var server: SSLServerSocket? = null
    private var acceptor: Job? = null
    private var tls: SyncTls? = null
    private var ownId: String? = null
    private var closed = false
    @Volatile private var playbackReceiver: PlaybackReceiver? = null
    override fun setPlaybackReceiver(receiver: PlaybackReceiver) { playbackReceiver = receiver }

    override suspend fun preparePlayback(deviceId: String, playback: PlaybackTransfer): Int =
        playbackRequest(deviceId, "prepare-playback", playback = playback).skippedSongs
    override suspend fun activatePlaybackTransfer(deviceId: String, transferId: String, positionMs: Long, playWhenReady: Boolean) {
        playbackRequest(deviceId, "activate-playback", transferId = transferId, positionMs = positionMs, playWhenReady = playWhenReady)
    }
    override suspend fun returnPlayback(deviceId: String, transferId: String): PlaybackTransfer {
        val response = playbackRequest(deviceId, "return-playback", transferId = transferId)
        library.merge(requireNotNull(response.snapshot))
        return library.remapPlayback(deviceId, requireNotNull(response.playback))
    }
    override suspend fun cancelPlaybackTransfer(deviceId: String, transferId: String) {
        playbackRequest(deviceId, "cancel-playback", transferId = transferId)
    }
    private suspend fun playbackRequest(deviceId: String, action: String, playback: PlaybackTransfer? = null,
        transferId: String? = null, positionMs: Long? = null, playWhenReady: Boolean? = null): SyncResponse = operation {
        val peer = requireNotNull(state.value.peers.firstOrNull { it.deviceId == deviceId }) { "Choose a paired device" }
        networkAccess("")
        lifecycle.withLock {
            require(state.value.peers.any { it.deviceId == peer.deviceId && it.certificateSha256 == peer.certificateSha256 }) { "This device is no longer paired" }
            listen()
        }
        var result: SyncResponse? = null
        val snapshot = playback?.let { library.snapshot(deviceId, it.queue.entries.map { entry -> entry.trackId }.toSet()) }
        connect(peer.host, peer.port, peer.certificateSha256) { socket ->
            if (action == "prepare-playback") socket.soTimeout = MATCHING_TIMEOUT_MS
            write(socket, SyncRequest(action = action, deviceId = requireNotNull(ownId), name = name,
                listeningPort = requireNotNull(server).localPort, snapshot = snapshot, playback = playback, transferId = transferId,
                positionMs = positionMs, playWhenReady = playWhenReady))
            val response = read<SyncResponse>(socket)
            validate(response)
            require(response.deviceId == peer.deviceId && (response.snapshot == null || response.snapshot.deviceId == peer.deviceId)) { "Device identity does not match" }
            require(state.value.peers.any { it.deviceId == peer.deviceId && it.certificateSha256 == peer.certificateSha256 }) { "This device is no longer paired" }
            require(response.error == null) { requireNotNull(response.error) }
            result = response
        }
        requireNotNull(result)
    }

    override suspend fun start() = lifecycle.withLock {
        check(!closed) { "Device sync is closed" }
        reloadPeers()
        if (state.value.peers.isNotEmpty()) listen()
    }

    private suspend fun listen() {
        if (server != null) return
        check(!closed) { "Device sync is closed" }
        val id = library.deviceId().also { ownId = it }
        val identity = SyncTls.load(vault, id).also { tls = it }
        val context = identity.context { pin -> state.value.peers.any { it.certificateSha256 == pin } ||
            offer.get()?.let { it.expiresAt > clock() } == true }
        val listener = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            needClientAuth = true
        }
        try { listener.bind(InetSocketAddress(store.read<Int>("sync:listening-port") ?: 0)) }
        catch (error: Exception) { listener.close(); throw error }
        server = listener
        store.put("sync:listening-port", "sync-settings", listener.localPort)
        mutableState.update { it.copy(listeningPort = listener.localPort) }
        acceptor = scope.launch {
            while (isActive && !listener.isClosed) {
                val socket = try { listener.accept() as SSLSocket } catch (error: Exception) {
                    if (listener.isClosed) break else throw error
                }
                if (!slots.tryAcquire()) { socket.close(); continue }
                sockets.add(socket)
                // Handlers run outside the accept loop, so stopping the listener only waits for accept to return.
                scope.launch {
                    try { socket.use { handle(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Untrusted connections receive no library data. */ }
                    finally { sockets.remove(socket); slots.release() }
                }
            }
        }
    }

    override suspend fun createInvitation(host: String): String = lifecycle.withLock {
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '/' || it == '@' }) {
            "Enter this device’s local network address"
        }
        networkAccess("")
        listen()
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val invitation = PairInvitation(deviceId = requireNotNull(ownId), name = name, host = host,
            port = requireNotNull(server).localPort, certificateSha256 = requireNotNull(tls).fingerprint,
            secret = secret, expiresAt = clock() + 300_000)
        offer.set(invitation)
        val link = "nevumi://pair/" + Base64.getUrlEncoder().withoutPadding().encodeToString(wire.encodeToString(invitation).toByteArray(Charsets.UTF_8))
        mutableState.update { it.copy(invitation = link, invitationExpiresAt = invitation.expiresAt) }
        scope.launch {
            delay(300_000)
            endInvitation(invitation)
        }
        link
    }

    override suspend fun cancelInvitation() = endInvitation()

    private suspend fun endInvitation(expected: PairInvitation? = null) = incoming.withLock {
        lifecycle.withLock {
            if (expected != null && !offer.compareAndSet(expected, null)) return@withLock
            offer.set(null)
            mutableState.update { it.copy(invitation = null, invitationExpiresAt = null) }
            if (state.value.peers.isEmpty()) stopListening()
        }
    }

    private suspend fun stopListening() = withContext(NonCancellable) {
        server?.close()
        server = null
        sockets.toList().forEach { runCatching { it.close() } }
        // A thread blocked in accept can keep the port listening briefly after close, so wait for it to exit.
        acceptor?.join()
        acceptor = null
        // Accept may have returned another socket after the first close pass.
        sockets.toList().forEach { runCatching { it.close() } }
        mutableState.update { it.copy(listeningPort = null) }
    }

    override suspend fun pair(invitation: String) = operation {
        require(invitation.startsWith("nevumi://pair/") && invitation.length <= 4096) { "Invalid pairing invitation" }
        val parsed = try { wire.decodeFromString<PairInvitation>(Base64.getUrlDecoder().decode(invitation.removePrefix("nevumi://pair/")).toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw IllegalArgumentException("Invalid pairing invitation") }
        require(parsed.version == 1 && parsed.expiresAt > clock() && parsed.secret.length == 43 &&
            parsed.certificateSha256.matches(Regex("[0-9a-f]{64}")) && parsed.port in 1..65535) { "The pairing invitation is invalid or expired" }
        networkAccess("")
        lifecycle.withLock { listen() }
        require(parsed.deviceId != ownId) { "Choose a different device" }
        connect(parsed.host, parsed.port, parsed.certificateSha256) { socket ->
            write(socket, SyncRequest(action = "pair", deviceId = requireNotNull(ownId), name = name,
                listeningPort = requireNotNull(server).localPort, secret = parsed.secret))
            val response = read<SyncResponse>(socket)
            validate(response)
            require(response.deviceId == parsed.deviceId) { "The invitation belongs to a different device" }
            savePeer(SyncPeer(response.deviceId, response.name, parsed.host, response.listeningPort, parsed.certificateSha256))
        }
    }

    override suspend fun synchronize(deviceId: String) = operation {
        val peer = requireNotNull(state.value.peers.firstOrNull { it.deviceId == deviceId }) { "Choose a paired device" }
        networkAccess("")
        lifecycle.withLock {
            require(state.value.peers.any { it.deviceId == peer.deviceId && it.certificateSha256 == peer.certificateSha256 }) {
                "This device is no longer paired"
            }
            listen()
        }
        val snapshot = library.snapshot(peer.deviceId, listensSince = listensSince(peer))
        connect(peer.host, peer.port, peer.certificateSha256) { socket ->
            socket.soTimeout = MATCHING_TIMEOUT_MS
            write(socket, SyncRequest(action = "sync", deviceId = requireNotNull(ownId), name = name,
                listeningPort = requireNotNull(server).localPort, snapshot = snapshot, listensSince = listensSince(peer)))
            val response = read<SyncResponse>(socket)
            validate(response)
            require(response.deviceId == peer.deviceId && response.snapshot?.deviceId == peer.deviceId) { "Device identity does not match" }
            incoming.withLock {
                require(state.value.peers.any { it.deviceId == peer.deviceId && it.certificateSha256 == peer.certificateSha256 }) {
                    "This device is no longer paired"
                }
                library.merge(requireNotNull(response.snapshot))
                savePeer(peer.copy(name = response.name, port = response.listeningPort, lastSyncedAt = clock()))
            }
        }
    }

    private suspend fun handle(socket: SSLSocket) {
        socket.soTimeout = 15_000
        socket.startHandshake()
        val pin = SyncTls.fingerprint(socket.session.peerCertificates.single())
        val request = read<SyncRequest>(socket)
        require(request.version == 1 && request.deviceId != ownId && validId(request.deviceId) &&
            request.name.length in 1..80 && request.listeningPort in 1..65535) { "Invalid device request" }
        incoming.withLock {
            when (request.action) {
                "pair" -> {
                    val invitation = offer.get()
                    require(invitation != null && invitation.expiresAt > clock() && request.secret != null &&
                        MessageDigest.isEqual(invitation.secret.toByteArray(Charsets.UTF_8), request.secret.toByteArray(Charsets.UTF_8)) &&
                        offer.compareAndSet(invitation, null)) { "Pairing was not authorized" }
                    require(state.value.peers.none { it.deviceId == request.deviceId && it.certificateSha256 != pin }) { "Remove the previous pairing first" }
                    savePeer(SyncPeer(request.deviceId, request.name, socket.inetAddress.hostAddress, request.listeningPort, pin))
                    mutableState.update { it.copy(invitation = null, invitationExpiresAt = null) }
                    write(socket, SyncResponse(deviceId = requireNotNull(ownId), name = name, listeningPort = requireNotNull(server).localPort))
                }
                "sync" -> {
                    val peer = requireNotNull(state.value.peers.firstOrNull { it.deviceId == request.deviceId && it.certificateSha256 == pin }) {
                        "This device is not paired"
                    }
                    require(request.snapshot?.deviceId == peer.deviceId) { "Device identity does not match" }
                    library.merge(requireNotNull(request.snapshot))
                    write(socket, SyncResponse(deviceId = requireNotNull(ownId), name = name, listeningPort = requireNotNull(server).localPort,
                        // The requester states what it already received; this device's own clock only says what it sent.
                        snapshot = library.snapshot(peer.deviceId, listensSince = request.listensSince)))
                    savePeer(peer.copy(host = socket.inetAddress.hostAddress, port = request.listeningPort, name = request.name, lastSyncedAt = clock()))
                }
                "prepare-playback", "activate-playback", "return-playback", "cancel-playback" -> {
                    require(state.value.peers.any { it.deviceId == request.deviceId && it.certificateSha256 == pin }) { "This device is not paired" }
                    try {
                        val receiver = requireNotNull(playbackReceiver) { "Open Nevumi on the receiving device first" }
                        var returned: PlaybackTransfer? = null
                        var skippedSongs = 0
                        when (request.action) {
                            "prepare-playback" -> {
                                require(request.snapshot?.deviceId == request.deviceId) { "Device identity does not match" }
                                library.merge(requireNotNull(request.snapshot))
                                skippedSongs = receiver.receive(request.deviceId, library.remapPlayback(request.deviceId, requireNotNull(request.playback)))
                            }
                            "activate-playback" -> receiver.activate(request.deviceId, requireNotNull(request.transferId),
                                requireNotNull(request.positionMs), requireNotNull(request.playWhenReady))
                            "return-playback" -> returned = receiver.release(request.deviceId, requireNotNull(request.transferId))
                            else -> receiver.cancel(request.deviceId, requireNotNull(request.transferId))
                        }
                        write(socket, SyncResponse(deviceId = requireNotNull(ownId), name = name, listeningPort = requireNotNull(server).localPort,
                            skippedSongs = skippedSongs, playback = returned,
                            snapshot = returned?.let { library.snapshot(request.deviceId, it.queue.entries.map { entry -> entry.trackId }.toSet()) }))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        write(socket, SyncResponse(deviceId = requireNotNull(ownId), name = name, listeningPort = requireNotNull(server).localPort,
                            error = error.message ?: "The receiving device could not play this queue"))
                    }
                }
                else -> error("Unsupported device request")
            }
        }
    }

    override suspend fun forget(deviceId: String) = incoming.withLock {
        store.database.records().delete("sync:peer:$deviceId")
        reloadPeers()
        lifecycle.withLock { if (state.value.peers.isEmpty() && offer.get() == null) stopListening() }
    }

    // lastSyncedAt is saved only after a response was received, so the peer holds everything older than the last month.
    private fun listensSince(peer: SyncPeer): Long? = peer.lastSyncedAt?.let { it - 30L * 24 * 60 * 60 * 1_000 }

    private suspend fun savePeer(peer: SyncPeer) {
        store.put("sync:peer:${peer.deviceId}", "sync-peers", peer)
        reloadPeers()
    }
    private suspend fun reloadPeers() {
        val peers = store.list<SyncPeer>("sync-peers").sortedBy { it.name }
        mutableState.update { it.copy(peers = peers) }
    }
    private suspend fun <T> operation(block: suspend () -> T): T = operations.withLock {
        mutableState.update { it.copy(busy = true, error = null) }
        try { withContext(Dispatchers.IO) { block() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutableState.update { it.copy(error = "Device connection failed. Check the pairing and local network.") }
            throw error
        } finally { mutableState.update { it.copy(busy = false) } }
    }
    private suspend fun connect(host: String, port: Int, pin: String, block: suspend (SSLSocket) -> Unit) {
        val socket = lifecycle.withLock {
            check(!closed && server != null) { "Device sync is not listening" }
            (requireNotNull(tls).context { it == pin }.socketFactory.createSocket() as SSLSocket).also { sockets.add(it) }
        }
        try {
            socket.use {
                it.enabledProtocols = it.supportedProtocols.filter { protocol -> protocol == "TLSv1.3" || protocol == "TLSv1.2" }.toTypedArray()
                it.soTimeout = 15_000
                it.connect(InetSocketAddress(host, port), 10_000)
                it.startHandshake()
                block(it)
            }
        } finally { sockets.remove(socket) }
    }
    private fun validate(response: SyncResponse) {
        require(response.version == 1 && validId(response.deviceId) && response.name.length in 1..80 &&
            response.listeningPort in 1..65535) { "Invalid device response" }
    }
    private fun validId(value: String) = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    private inline fun <reified T> write(socket: SSLSocket, value: T) {
        val bytes = wire.encodeToString(value).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME) { "The shared library exceeds the transfer limit" }
        DataOutputStream(socket.outputStream).apply { writeInt(bytes.size); write(bytes); flush() }
    }
    private inline fun <reified T> read(socket: SSLSocket): T {
        val input = DataInputStream(socket.inputStream)
        val size = input.readInt()
        require(size in 1..MAX_FRAME) { "Invalid device message size" }
        return wire.decodeFromString<T>(ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8))
    }
    override fun close() {
        closed = true
        offer.set(null)
        server?.close()
        sockets.toList().forEach { runCatching { it.close() } }
        supervisor.cancel()
    }
    override suspend fun closeAndJoin() = withContext(NonCancellable) { close(); supervisor.join() }

    companion object {
        private const val MAX_FRAME = 32 * 1024 * 1024
        private const val MATCHING_TIMEOUT_MS = 5 * 60_000
    }
}
