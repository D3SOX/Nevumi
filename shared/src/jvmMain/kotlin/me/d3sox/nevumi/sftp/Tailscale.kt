package me.d3sox.nevumi.sftp

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Objects

/** JNI bridge to the bundled userspace Tailscale node (native/tailscale). */
internal object TailscaleNative {
    init {
        val resources = System.getProperty("compose.application.resources.dir")
        if (resources != null) System.load(File(resources, "native/libnevumi_tailscale.so").absolutePath)
        else System.loadLibrary("nevumi_tailscale")
    }
    external fun start(directory: String, hostname: String, controlUrl: String, authKey: String)
    external fun status(): String
    external fun signOut()
    external fun dial(host: String, port: Int, timeoutMs: Int): Long
    external fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external fun write(handle: Long, buffer: ByteArray, offset: Int, length: Int)
    external fun close(handle: Long)
}

/** Joins the tailnet inside Nevumi, so SFTP works without the Tailscale app or a system VPN. */
class Tailscale(private val directory: File, private val hostname: String = "nevumi") {
    private val controlUrlFile get() = File(directory, "nevumi-control-url")

    @Synchronized fun status(): TailscaleStatus {
        resume()
        val (state, authUrl, name) = TailscaleNative.status().split('\n')
        return TailscaleStatus(state, authUrl.ifEmpty { null }, name.ifEmpty { null }, controlUrlFile.takeIf { it.isFile }?.readText().orEmpty())
    }

    @Synchronized fun signIn(controlUrl: String, authKey: String?): TailscaleStatus {
        require(controlUrl.isEmpty() || controlUrl.startsWith("https://") || controlUrl.startsWith("http://")) {
            "Enter the control server's full URL"
        }
        directory.mkdirs()
        try { TailscaleNative.start(directory.path, hostname, controlUrl, authKey.orEmpty()) }
        catch (failure: IOException) { directory.deleteRecursively(); throw failure }
        controlUrlFile.writeText(controlUrl)
        return status()
    }

    @Synchronized fun signOut() {
        resume()
        TailscaleNative.signOut()
        directory.deleteRecursively()
    }

    fun connect(host: String, port: Int, timeoutMs: Int): TailscaleConnection {
        synchronized(this) {
            if (!controlUrlFile.isFile) throw SftpFailure(SftpFailure.Kind.TAILSCALE)
            resume()
        }
        return try { TailscaleConnection(TailscaleNative.dial(host, port, timeoutMs)) }
        catch (failure: IOException) {
            throw if (status().state != "Running") SftpFailure(SftpFailure.Kind.TAILSCALE) else failure
        }
    }

    /** Restarts a previously signed-in node after the app restarts. */
    private fun resume() {
        if (controlUrlFile.isFile && TailscaleNative.status().startsWith("Stopped"))
            TailscaleNative.start(directory.path, hostname, controlUrlFile.readText(), "")
    }
}

class TailscaleConnection(private val handle: Long) : Closeable {
    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            Objects.checkFromIndexSize(offset, length, buffer.size)
            return if (length == 0) 0 else TailscaleNative.read(handle, buffer, offset, length)
        }
        override fun close() = this@TailscaleConnection.close()
    }
    val output: OutputStream = object : OutputStream() {
        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            Objects.checkFromIndexSize(offset, length, buffer.size)
            TailscaleNative.write(handle, buffer, offset, length)
        }
        override fun close() = this@TailscaleConnection.close()
    }
    override fun close() = TailscaleNative.close(handle)
}
