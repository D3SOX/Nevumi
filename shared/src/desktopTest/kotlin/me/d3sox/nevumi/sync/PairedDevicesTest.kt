package me.d3sox.nevumi.sync

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PairedDevicesTest {
    private fun vault() = object : CredentialVault {
        private val secrets = ConcurrentHashMap<String, String>()
        override suspend fun save(key: String, secret: String) { secrets[key] = secret }
        override suspend fun read(key: String) = secrets[key]
        override suspend fun delete(key: String) { secrets.remove(key) }
    }

    @Test
    fun removingTheLastPeerDuringSnapshotPreventsALateOutgoingConnection() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-revoked-snapshot-sync")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val song = library.importTrack(TrackMetadata("Night walk"), TrackSource("local:night", SourceKind.LOCAL, "night.flac"))
        library.setFavorite(song.id, true)
        val snapshotStarted = CompletableDeferred<Unit>()
        val finishSnapshot = CountDownLatch(1)
        val sync = LibrarySync(store, library, audioContentId = {
            snapshotStarted.complete(Unit)
            check(finishSnapshot.await(10, TimeUnit.SECONDS)) { "Snapshot was not released" }
            null
        })
        val devices = JvmPairedDevices(store, sync, vault(), "Linux desktop")
        val remote = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)); soTimeout = 2_000 }
        try {
            val peer = SyncPeer("11111111-1111-4111-8111-111111111111", "Remote device", "127.0.0.1", remote.localPort, "0".repeat(64))
            store.put("sync:peer:${peer.deviceId}", "sync-peers", peer)
            devices.start()
            val result = async { runCatching { devices.synchronize(peer.deviceId) } }
            withTimeout(5_000) { snapshotStarted.await() }
            devices.forget(peer.deviceId)
            assertEquals(null, devices.state.value.listeningPort)
            val connected = async(Dispatchers.IO) {
                try { remote.accept().use { }; true }
                catch (_: java.net.SocketTimeoutException) { false }
            }
            finishSnapshot.countDown()
            assertTrue(withTimeout(5_000) { result.await() }.isFailure)
            assertEquals(false, connected.await(), "A sync must not open a connection after its listener was stopped")
            assertEquals(emptyList(), devices.state.value.peers)
        } finally {
            finishSnapshot.countDown()
            remote.close(); devices.closeAndJoin(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun removingAPeerWhileNetworkAccessIsPendingPreventsSyncAndDoesNotRestoreThePairing() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-revoked-pending-sync")
        val first = openMusicStore(directory.resolve("phone.db").toString())
        val second = openMusicStore(directory.resolve("desktop.db").toString())
        val phoneLibrary = MusicLibrary(first)
        val desktopLibrary = MusicLibrary(second)
        val metadata = TrackMetadata("Night walk", durationMs = 120_000)
        val source = TrackSource("server:fixture:night", SourceKind.SERVER, "night")
        val song = phoneLibrary.importTrack(metadata, source)
        val remote = desktopLibrary.importTrack(metadata, source)
        desktopLibrary.setFavorite(remote.id, true)
        val requested = CompletableDeferred<Unit>()
        val allowed = CompletableDeferred<Unit>()
        var waiting = false
        val phone = JvmPairedDevices(first, LibrarySync(first, phoneLibrary), vault(), "Android phone", networkAccess = {
            if (waiting) { requested.complete(Unit); allowed.await() }
        })
        val desktop = JvmPairedDevices(second, LibrarySync(second, desktopLibrary), vault(), "Linux desktop")
        try {
            phone.pair(desktop.createInvitation("127.0.0.1"))
            val peer = phone.state.value.peers.single().deviceId
            waiting = true
            val result = async { runCatching { phone.synchronize(peer) } }
            withTimeout(5_000) { requested.await() }
            phone.forget(peer)
            allowed.complete(Unit)
            withTimeout(10_000) { result.await() }
            assertEquals(emptyList(), phone.state.value.peers)
            assertEquals(false, phoneLibrary.findTrack(song.id)!!.favorite)
            assertEquals(null, phone.state.value.listeningPort)
        } finally {
            allowed.complete(Unit)
            phone.closeAndJoin(); desktop.closeAndJoin(); first.close(); second.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancelingAnUnusedInvitationClosesItsNetworkListener() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-cancel-pairing")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val devices = JvmPairedDevices(store, LibrarySync(store, MusicLibrary(store)), vault(), "Linux desktop")
        try {
            devices.start()
            assertEquals(null, devices.state.value.listeningPort)
            devices.createInvitation("127.0.0.1")
            val port = assertNotNull(devices.state.value.listeningPort)
            java.net.Socket().use { probe ->
                // Reserve a different client port before closing the listener to prevent TCP self-connects.
                probe.bind(java.net.InetSocketAddress("127.0.0.1", 0))
                devices.cancelInvitation()
                assertEquals(null, devices.state.value.invitation)
                assertEquals(null, devices.state.value.listeningPort)
                kotlin.test.assertFailsWith<java.net.ConnectException> {
                    probe.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
                }
            }
            Unit
        } finally { devices.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anUnpairedDeviceCannotAnnounceALargeMessageWhileAnInvitationIsOpen() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-pairing-frame")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val devices = JvmPairedDevices(store, LibrarySync(store, MusicLibrary(store)), vault(), "Linux desktop")
        try {
            devices.createInvitation("127.0.0.1")
            val port = assertNotNull(devices.state.value.listeningPort)
            // Any certificate passes the handshake while the invitation is open, before the secret is checked.
            val stranger = SyncTls.load(vault(), "22222222-2222-4222-8222-222222222222").context { true }
            val rejected = withContext(Dispatchers.IO) {
                (stranger.socketFactory.createSocket("127.0.0.1", port) as javax.net.ssl.SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.startHandshake()
                    java.io.DataOutputStream(socket.outputStream).apply { writeInt(32 * 1024 * 1024); flush() }
                    try { socket.inputStream.read() == -1 }
                    catch (_: java.net.SocketTimeoutException) { false }
                    catch (_: java.io.IOException) { true }
                }
            }
            assertTrue(rejected, "The listener must refuse a large message before the device proves it was invited")
        } finally { devices.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun invitationsRejectAlterationAndReuseAndRemovingAPeerRevokesItsAccess() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-pairing-authorization")
        val stores = (1..3).map { openMusicStore(directory.resolve("device-$it.db").toString()) }
        val devices = stores.mapIndexed { index, store -> JvmPairedDevices(store, LibrarySync(store, MusicLibrary(store)), vault(), "Device ${index + 1}") }
        val phone = devices[0]
        val desktop = devices[1]
        try {
            val invitation = phone.createInvitation("127.0.0.1")
            fun altered(key: String, value: String): String {
                val json = java.util.Base64.getUrlDecoder().decode(invitation.removePrefix("nevumi://pair/")).toString(Charsets.UTF_8)
                val changed = json.replace(Regex("\"$key\":\"[^\"]*\""), "\"$key\":\"$value\"")
                return "nevumi://pair/" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(changed.toByteArray(Charsets.UTF_8))
            }
            kotlin.test.assertFails { desktop.pair(altered("certificateSha256", "0".repeat(64))) }
            kotlin.test.assertFails { desktop.pair(altered("secret", "a".repeat(43))) }
            assertEquals(emptyList(), phone.state.value.peers)
            assertEquals(emptyList(), desktop.state.value.peers)
            desktop.pair(invitation)
            kotlin.test.assertFails { devices[2].pair(invitation) }
            assertEquals(1, phone.state.value.peers.size)
            val phoneId = desktop.state.value.peers.single().deviceId
            phone.forget(phone.state.value.peers.single().deviceId)
            assertEquals(null, phone.state.value.listeningPort)
            kotlin.test.assertFails { desktop.synchronize(phoneId) }
            assertEquals(emptyList(), phone.state.value.peers)
            Unit
        } finally {
            devices.forEach { it.closeAndJoin() }; stores.forEach { it.close() }; directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun pairingTwoDevicesExchangesTheirLibraryChangesAndTrustSurvivesRestart() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-paired-devices")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        val firstVault = vault()
        val secondVault = vault()
        val firstLibrary = MusicLibrary(firstStore)
        val secondLibrary = MusicLibrary(secondStore)
        val phoneTrack = firstLibrary.importTrack(TrackMetadata("Night walk", durationMs = 120_000),
            TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
        val desktopTrack = secondLibrary.importTrack(TrackMetadata("Night walk", durationMs = 120_000),
            TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
        var phone = JvmPairedDevices(firstStore, LibrarySync(firstStore, firstLibrary), firstVault, "Android phone")
        var desktop = JvmPairedDevices(secondStore, LibrarySync(secondStore, secondLibrary), secondVault, "Linux desktop")
        try {
            firstLibrary.setFavorite(phoneTrack.id, true)
            secondLibrary.setRating(desktopTrack.id, 4)
            phone.start(); desktop.start()
            desktop.pair(phone.createInvitation("127.0.0.1"))
            val phonePeer = desktop.state.value.peers.single()
            val desktopPeer = phone.state.value.peers.single()
            assertEquals("Android phone", phonePeer.name)
            assertEquals("Linux desktop", desktopPeer.name)
            desktop.synchronize(phonePeer.deviceId)
            assertEquals(true, secondLibrary.findTrack(desktopTrack.id)!!.favorite)
            assertEquals(4, firstLibrary.findTrack(phoneTrack.id)!!.rating)
            assertNotNull(desktop.state.value.peers.single().lastSyncedAt)
            phone.closeAndJoin(); desktop.closeAndJoin()
            phone = JvmPairedDevices(firstStore, LibrarySync(firstStore, firstLibrary), firstVault, "Android phone")
            desktop = JvmPairedDevices(secondStore, LibrarySync(secondStore, secondLibrary), secondVault, "Linux desktop")
            phone.start(); desktop.start()
            firstLibrary.setFavorite(phoneTrack.id, false)
            desktop.synchronize(phonePeer.deviceId)
            assertEquals(false, secondLibrary.findTrack(desktopTrack.id)!!.favorite)
            assertEquals(desktopPeer.certificateSha256, phone.state.value.peers.single().certificateSha256)
            Unit
        } finally {
            phone.closeAndJoin(); desktop.closeAndJoin()
            firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively()
        }
    }
}
