package me.d3sox.nevumi.sftp

import java.io.File
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.PacketType
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPEngine
import net.schmizz.sshj.sftp.SFTPException
import java.io.DataInputStream
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import me.d3sox.nevumi.tunnel.Tailscale
import me.d3sox.nevumi.tunnel.TailscaleSignInRequired

class SftpFailure(val kind: Kind) : IOException(when (kind) {
    Kind.HOST_KEY -> "The SFTP server's host key does not match the verified fingerprint"
    Kind.AUTHENTICATION -> "The SFTP server rejected the credentials"
    Kind.CONNECTION -> "Could not connect to the SFTP server"
    Kind.FILE_OPERATION -> "Could not complete the SFTP file operation"
    Kind.CONFLICT -> "A file already exists there or the server file changed"
    Kind.TAILSCALE -> "Sign in to Tailscale in Tunnel settings"
}) {
    enum class Kind { HOST_KEY, AUTHENTICATION, CONNECTION, FILE_OPERATION, CONFLICT, TAILSCALE }
}

private object SftpCrypto {
    init {
        val algorithms = BouncyCastleProvider()
        val provider = object : Provider("NevumiSftpCrypto", 1.0, "Nevumi SFTP cryptography") {
            init { putAll(algorithms) }
        }
        Security.addProvider(provider)
        SecurityUtils.setSecurityProvider(provider.name)
    }
    fun initialize() {}
}

// Requests kept in flight per transfer, as in sshj's own get and put; waiting for each reply makes tunnelled transfers crawl.
private const val PIPELINED_REQUESTS = 16
private const val BLOCK = 16 * 1024L
private const val LINGER_SECONDS = 30L

private val closer = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "sftp-closer").apply { isDaemon = true } }

// Reads a server file in cached blocks, so parsing tags fetches each part once, with pipelined requests for longer runs.
private class RemoteBytes(private val file: RemoteFile) {
    val attributes: FileAttributes = file.fetchAttributes()
    val size = attributes.size
    private val blocks = HashMap<Long, ByteArray>()

    fun read(offset: Long, length: Int): ByteArray {
        val end = minOf(offset + length, size)
        if (offset >= end) return ByteArray(0)
        val first = offset / BLOCK
        val last = (end - 1) / BLOCK
        var block = first
        while (block <= last) {
            if (block in blocks) { block++; continue }
            var run = block
            while (run < last && run + 1 !in blocks) run++
            val start = block * BLOCK
            val data = ByteArray((minOf((run + 1) * BLOCK, size) - start).toInt())
            file.ReadAheadRemoteFileInputStream(PIPELINED_REQUESTS, start, data.size.toLong()).use { DataInputStream(it).readFully(data) }
            for (index in block..run) blocks[index] = data.copyOfRange(((index - block) * BLOCK).toInt(), minOf((index - block + 1) * BLOCK, data.size.toLong()).toInt())
            block = run + 1
        }
        val result = ByteArray((end - offset).toInt())
        for (index in first..last) {
            val data = blocks.getValue(index)
            val start = maxOf(offset, index * BLOCK)
            val stop = minOf(end, index * BLOCK + data.size)
            data.copyInto(result, (start - offset).toInt(), (start - index * BLOCK).toInt(), (stop - index * BLOCK).toInt())
        }
        return result
    }
}

private fun InputStream.limited(length: Long): InputStream = object : java.io.FilterInputStream(this) {
    var remaining = length
    override fun read(): Int = if (remaining <= 0) -1 else super.read().also { if (it >= 0) remaining-- }
    override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        if (remaining <= 0) return -1
        return super.read(buffer, offset, minOf(size.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
    }
}

/** [tailscale] carries the connection over the tailnet when set. */
class SftpFiles(private val profile: SftpProfile, private val authentication: SftpAuthentication,
    private val tailscale: Tailscale? = null) {
    private class Connection(val ssh: SSHClient, val sftp: SFTPClient) : AutoCloseable {
        override fun close() {
            try { sftp.close() } catch (_: IOException) { }
            try { ssh.close() } catch (_: IOException) { }
        }
    }
    @Volatile private var sessionOpen = false
    @Volatile private var shared: Connection? = null
    private var closing: ScheduledFuture<*>? = null

    /**
     * Runs [block] with one SSH connection for all of its file steps; connecting through a tunnel takes seconds each time.
     * The connection stays open briefly afterwards, since saving tags usually follows opening the tag editor.
     */
    suspend fun <T> session(block: suspend () -> T): T {
        if (sessionOpen) return block()
        synchronized(this) {
            closing?.cancel(false)
            sessionOpen = true
        }
        try { return block() }
        finally {
            synchronized(this) {
                sessionOpen = false
                closing = shared?.let { connection ->
                    // The closer thread keeps the network off Android's main thread, where callers such as the tag editor run.
                    closer.schedule({
                        synchronized(this) { if (!sessionOpen && shared === connection) { shared = null; connection.close() } }
                    }, LINGER_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
    }

    fun verifyDirectory() = connected { client ->
        val directory = client.canonicalize(profile.musicDirectory)
        check(client.stat(directory).type == FileMode.Type.DIRECTORY) { "Choose the server's music directory" }
    }

    fun upload(relativePath: String, source: File, transferId: String = UUID.randomUUID().toString()) = connected { client ->
        val target = path(client, relativePath, mustExist = false, createParents = true)
        if (client.statExistence(target) != null) throw SftpFailure(SftpFailure.Kind.CONFLICT)
        publish(client, target, source, originalFingerprint = null, transferId = transferId)
    }

    // The original is compared with originalFingerprint just before the staged upload replaces it.
    fun replace(relativePath: String, source: File, originalFingerprint: String) = connected { client ->
        publish(client, path(client, relativePath), source, originalFingerprint)
    }

    /** Copies only the tags of a server file into a local file of the same size, leaving the audio as unallocated zeros. */
    fun image(relativePath: String, destination: File) = connected { client ->
        client.open(path(client, relativePath)).use { remote ->
            val bytes = RemoteBytes(remote)
            TagLayout.of(relativePath, bytes.size, bytes::read).writeImage(destination, bytes::read)
        }
    }

    /** Identifies a server file by its size and tags without reading its audio. */
    fun fingerprint(relativePath: String): String = connected { client -> fingerprint(client, path(client, relativePath)) }

    /**
     * Replaces a server file with a tag edit of its [image]. Only the tags are sent; the server copies the audio
     * from the original, which is compared with originalFingerprint just before the edit replaces it.
     */
    fun replaceTags(relativePath: String, edited: File, originalFingerprint: String) = connected { client ->
        val target = path(client, relativePath)
        val (after, local) = TagLayout.of(edited, relativePath)
        val staged = target.substringBeforeLast('/') + "/.nevumi-${UUID.randomUUID()}.part"
        open(client, target, setOf(OpenMode.READ)).use { original ->
            val bytes = RemoteBytes(original)
            val before = TagLayout.of(relativePath, bytes.size, bytes::read)
            if (before.fingerprint(bytes::read) != originalFingerprint) throw SftpFailure(SftpFailure.Kind.CONFLICT)
            check(keepsAudio(before, bytes::read, after, local)) { "Nevumi can't save tags to this file on the server" }
            var owned = false
            var published = false
            try {
                open(client, staged, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { output ->
                    owned = true
                    val chunk = client.sftpEngine.subsystem.remoteMaxPacketSize - output.outgoingPacketOverhead
                    fun send(range: LongRange) = FileInputStream(edited).use { input ->
                        input.channel.position(range.first)
                        output.RemoteFileOutputStream(range.first, PIPELINED_REQUESTS).use { input.limited(range.length).copyTo(it, chunk) }
                    }
                    send(0 until after.audio.first)
                    copyAudio(client, original, before.audio, output, after.audio.first, chunk)
                    send(after.audio.last + 1 until after.size)
                }
                // Every write and the server's copy were confirmed, so only a change to the original is left to rule out.
                if (fingerprint(client, target) != originalFingerprint) throw SftpFailure(SftpFailure.Kind.CONFLICT)
                client.chmod(staged, bytes.attributes.mode.mask)
                client.rename(staged, target, setOf(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
                published = true
            } finally {
                if (owned && !published) try { if (client.statExistence(staged) != null) client.rm(staged) } catch (_: IOException) { }
            }
        }
    }

    // OpenSSH copies the audio on the server; other servers send it through this device and back.
    private fun copyAudio(client: SFTPClient, from: HandleFile, audio: LongRange, to: HandleFile, offset: Long, chunk: Int) {
        if (audio.isEmpty()) return
        val engine = client.sftpEngine
        try {
            engine.request(engine.newExtendedRequest("copy-data").putString(from.id).putUInt64(audio.first)
                .putUInt64(audio.length).putString(to.id).putUInt64(offset))
                .retrieve(engine.timeoutMs.toLong(), TimeUnit.MILLISECONDS).ensureStatusPacketIsOK()
            return
        } catch (_: SFTPException) {
            // Servers without copy-data refuse it in different ways; sending the audio instead fills the same bytes.
        }
        from.ReadAheadRemoteFileInputStream(PIPELINED_REQUESTS, audio.first, audio.length).use { input ->
            to.RemoteFileOutputStream(offset, PIPELINED_REQUESTS).use { input.limited(audio.length).copyTo(it, chunk) }
        }
    }

    // A tag edit must move the audio unchanged: the edited image holds the original's audio bytes where the tag reader
    // needed them and zeros elsewhere, exactly like the original's image.
    private fun keepsAudio(before: TagLayout, original: (Long, Int) -> ByteArray, after: TagLayout, edited: (Long, Int) -> ByteArray): Boolean {
        if (before.audio.length != after.audio.length) return false
        var position = 0L
        val length = before.audio.length
        while (position < length) {
            val size = minOf(1L shl 20, length - position).toInt()
            val expected = ByteArray(size)
            for (range in before.tags) {
                val start = maxOf(range.first, before.audio.first + position)
                val end = minOf(range.last + 1, before.audio.first + position + size)
                if (start < end) original(start, (end - start).toInt()).copyInto(expected, (start - before.audio.first - position).toInt())
            }
            if (!edited(after.audio.first + position, size).contentEquals(expected)) return false
            position += size
        }
        return true
    }

    private fun fingerprint(client: SFTPClient, target: String): String = client.open(target).use { remote ->
        val bytes = RemoteBytes(remote)
        TagLayout.of(target, bytes.size, bytes::read).fingerprint(bytes::read)
    }

    fun exists(relativePath: String): Boolean = connected { client ->
        validateRelativePath(relativePath)
        var parent = client.canonicalize(profile.musicDirectory).trimEnd('/')
        for (component in relativePath.split('/').dropLast(1)) {
            parent += "/$component"
            if (client.statExistence(parent) == null) return@connected false
            require(client.canonicalize(parent) == parent) { "Choose a music folder without symbolic links" }
        }
        client.statExistence(path(client, relativePath, mustExist = false)) != null
    }

    fun hash(relativePath: String): String = connected { client -> hash(client, path(client, relativePath)) }

    fun rename(relativePath: String, destination: String) = connected { client ->
        val source = path(client, relativePath)
        val target = path(client, destination, mustExist = false)
        if (client.statExistence(target) != null) throw SftpFailure(SftpFailure.Kind.CONFLICT)
        client.rename(source, target)
    }

    fun delete(relativePath: String) = connected { client -> client.rm(path(client, relativePath)) }

    private fun publish(client: SFTPClient, target: String, source: File, originalFingerprint: String?,
        transferId: String = UUID.randomUUID().toString()) {
        require(source.isFile) { "Choose an existing music file to upload" }
        require(UUID.fromString(transferId).toString() == transferId) { "Invalid upload identifier" }
        val staged = target.substringBeforeLast('/') + "/.nevumi-$transferId.part"
        var owned = false
        try {
            client.statExistence(staged)?.let {
                check(it.type == FileMode.Type.REGULAR && client.canonicalize(staged) == staged) {
                    "The interrupted upload is not a regular file"
                }
                client.rm(staged)
            }
            client.open(staged, setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)).use { remote ->
                owned = true
                val chunk = client.sftpEngine.subsystem.remoteMaxPacketSize - remote.outgoingPacketOverhead
                source.inputStream().use { input -> remote.RemoteFileOutputStream(0, PIPELINED_REQUESTS).use { input.copyTo(it, chunk) } }
            }
            // SSH already protects the data in transit, so reading the upload back would only double the transfer.
            check(client.stat(staged).size == source.length()) { "The server did not save the whole file" }
            if (originalFingerprint == null) client.rename(staged, target)
            else {
                if (fingerprint(client, target) != originalFingerprint) throw SftpFailure(SftpFailure.Kind.CONFLICT)
                client.chmod(staged, client.stat(target).mode.mask)
                client.rename(staged, target, setOf(RenameFlags.OVERWRITE, RenameFlags.ATOMIC))
            }
        } finally {
            if (owned) try { if (client.statExistence(staged) != null) client.rm(staged) } catch (_: IOException) { }
        }
    }

    private fun hash(client: SFTPClient, target: String): String =
        client.open(target).use { remote -> remote.ReadAheadRemoteFileInputStream(PIPELINED_REQUESTS).use(::sha256) }

    private fun sha256(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    // Opens through a request of its own because sshj keeps the handle that copy-data needs to itself.
    private fun open(client: SFTPClient, target: String, modes: Set<OpenMode>): HandleFile {
        val engine = client.sftpEngine
        val response = engine.request(engine.newRequest(PacketType.OPEN).putString(target, engine.subsystem.remoteCharset)
            .putUInt32(OpenMode.toMask(modes).toLong()).putFileAttributes(FileAttributes.EMPTY))
            .retrieve(engine.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        response.ensurePacketTypeIs(PacketType.HANDLE)
        return HandleFile(engine, target, response.readBytes())
    }

    private class HandleFile(engine: SFTPEngine, path: String, handle: ByteArray) : RemoteFile(engine, path, handle) {
        val id: ByteArray get() = handle
    }

    fun download(relativePath: String, destination: File) = connected { client ->
        client.get(path(client, relativePath), destination.path)
    }

    private fun validateRelativePath(relativePath: String) {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') &&
            relativePath.none { it in "\\\u0000\r\n" } && relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Choose a file inside the server music directory"
        }
    }

    private fun path(client: SFTPClient, relativePath: String, mustExist: Boolean = true, createParents: Boolean = false): String {
        validateRelativePath(relativePath)
        val root = client.canonicalize(profile.musicDirectory).trimEnd('/')
        if (createParents) {
            var parent = root
            for (component in relativePath.split('/').dropLast(1)) {
                parent += "/$component"
                if (client.statExistence(parent) == null) client.mkdir(parent)
                require(client.canonicalize(parent) == parent && client.stat(parent).type == FileMode.Type.DIRECTORY) {
                    "Choose a music folder without symbolic links"
                }
            }
        }
        val parent = relativePath.substringBeforeLast('/', "").let { if (it.isEmpty()) root else "$root/$it" }
        require(client.canonicalize(parent) == parent) { "Choose a music folder without symbolic links" }
        val result = "$root/$relativePath"
        if (mustExist) require(client.canonicalize(result) == result) { "Choose a music file without symbolic links" }
        return result
    }

    private fun <T> connected(operation: (SFTPClient) -> T): T {
        require(profile.port in 1..65535 && profile.host.isNotBlank() && profile.username.isNotBlank()) { "Enter a valid SFTP host, port and username" }
        require(profile.musicDirectory.startsWith('/') && profile.musicDirectory != "/") { "Enter the absolute server music directory" }
        require(profile.hostKeySha256.startsWith("SHA256:")) { "Enter the server's SHA-256 host-key fingerprint" }
        var stage = SftpFailure.Kind.CONNECTION
        shared?.takeUnless { it.ssh.isConnected }?.let { it.close(); shared = null }
        var connection = shared?.takeIf { sessionOpen }
        try {
            if (connection == null) {
                SftpCrypto.initialize()
                val ssh = SSHClient()
                try {
                    ssh.connectTimeout = 10_000
                    ssh.timeout = 20_000
                    ssh.addHostKeyVerifier(FingerprintVerifier.getInstance(profile.hostKeySha256))
                    if (tailscale != null) {
                        val tunnel = tailscale.connect(profile.host, profile.port, 30_000, ssh.timeout)
                        try { ssh.connectVia(tunnel.input, tunnel.output) } catch (failure: IOException) { tunnel.close(); throw failure }
                    } else ssh.connect(profile.host, profile.port)
                    stage = SftpFailure.Kind.AUTHENTICATION
                    if (authentication.privateKey != null) {
                        val finder = authentication.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                        ssh.authPublickey(profile.username, ssh.loadKeys(authentication.privateKey, null, finder))
                    } else ssh.authPassword(profile.username, requireNotNull(authentication.password) { "Enter an SFTP password or SSH private key" })
                    stage = SftpFailure.Kind.FILE_OPERATION
                    connection = Connection(ssh, ssh.newSFTPClient())
                } catch (failure: Exception) { try { ssh.close() } catch (_: IOException) { }; throw failure }
                if (sessionOpen) shared = connection
            }
            stage = SftpFailure.Kind.FILE_OPERATION
            return operation(connection.sftp)
        } catch (failure: IOException) {
            if (failure is SftpFailure) throw failure
            // The next step of a session connects again instead of reusing a connection that failed.
            if (connection === shared) shared = null
            val kind = if (failure is SSHException && failure.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE)
                SftpFailure.Kind.HOST_KEY else if (failure is TailscaleSignInRequired) SftpFailure.Kind.TAILSCALE else stage
            throw SftpFailure(kind)
        } finally {
            if (connection !== shared) connection?.close()
        }
    }
}
