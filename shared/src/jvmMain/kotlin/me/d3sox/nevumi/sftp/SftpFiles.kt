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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.FileMode
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

    /** Runs [block] with one SSH connection for all of its file steps; connecting through a tunnel takes seconds each time. */
    suspend fun <T> session(block: suspend () -> T): T {
        if (sessionOpen) return block()
        sessionOpen = true
        try { return block() }
        finally {
            sessionOpen = false
            // Callers such as the tag editor run on Android's main thread, which must not touch the network.
            withContext(NonCancellable + Dispatchers.IO) { shared?.close() }
            shared = null
        }
    }

    fun verifyDirectory() = connected { client ->
        val directory = client.canonicalize(profile.musicDirectory)
        check(client.stat(directory).type == FileMode.Type.DIRECTORY) { "Choose the server's music directory" }
    }

    fun upload(relativePath: String, source: File, transferId: String = UUID.randomUUID().toString()) = connected { client ->
        val target = path(client, relativePath, mustExist = false, createParents = true)
        if (client.statExistence(target) != null) throw SftpFailure(SftpFailure.Kind.CONFLICT)
        publish(client, target, source, originalHash = null, transferId = transferId)
    }

    // The original is compared with originalHash just before the staged upload replaces it.
    fun replace(relativePath: String, source: File, originalHash: String) = connected { client ->
        publish(client, path(client, relativePath), source, originalHash)
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

    private fun publish(client: SFTPClient, target: String, source: File, originalHash: String?,
        transferId: String = UUID.randomUUID().toString()) {
        require(source.isFile) { "Choose an existing music file to upload" }
        require(UUID.fromString(transferId).toString() == transferId) { "Invalid upload identifier" }
        val staged = target.substringBeforeLast('/') + "/.nevumi-$transferId.part"
        val expected = source.inputStream().use(::sha256)
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
            check(hash(client, staged) == expected) { "The upload changed before it could be verified" }
            if (originalHash == null) client.rename(staged, target)
            else {
                if (hash(client, path(client, target.removePrefix(client.canonicalize(profile.musicDirectory).trimEnd('/') + "/"))) != originalHash)
                    throw SftpFailure(SftpFailure.Kind.CONFLICT)
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
        var connection = shared
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
