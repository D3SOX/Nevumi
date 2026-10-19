package me.d3sox.nevumi.tunnel

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Objects

/** JNI bridge to the bundled userspace Tailscale node (native/tailscale). */
internal object TailscaleNative {
    private val loadFailure = try {
        val resources = System.getProperty("compose.application.resources.dir")
        if (resources != null) System.load(File(resources, "native/libnevumi_tailscale.so").absolutePath)
        else System.loadLibrary("nevumi_tailscale")
        null
    } catch (failure: LinkageError) { failure }

    fun requireLoaded() { loadFailure?.let { throw IOException("Tailscale is unavailable in this build", it) } }
    external fun start(directory: String, hostname: String, controlUrl: String, authKey: String)
    external fun status(): String
    external fun signOut()
    external fun relay(origin: String): String
    external fun dial(host: String, port: Int, timeoutMs: Int, ioTimeoutMs: Int): Long
    external fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
    external fun write(handle: Long, buffer: ByteArray, offset: Int, length: Int)
    external fun close(handle: Long)
}

/** Thrown when a route uses Tailscale but this device has not joined a tailnet. */
class TailscaleSignInRequired : IOException("Sign in to Tailscale in Tunnel settings")

/** Joins the tailnet inside Nevumi, so its connections work without the Tailscale app or a system VPN. */
class Tailscale(private val directory: File, private val hostname: String = "nevumi") {
    private val controlUrlFile get() = File(directory, "nevumi-control-url")

    @Synchronized fun status(): TailscaleStatus {
        TailscaleNative.requireLoaded()
        resume()
        val (state, authUrl, name) = TailscaleNative.status().split('\n')
        return TailscaleStatus(state, authUrl.ifEmpty { null }, name.ifEmpty { null }, controlUrlFile.takeIf { it.isFile }?.readText().orEmpty())
    }

    @Synchronized fun signIn(controlUrl: String, authKey: String?): TailscaleStatus {
        require(controlUrl.isEmpty() || controlUrl.startsWith("https://") || controlUrl.startsWith("http://")) {
            "Enter the control server's full URL"
        }
        TailscaleNative.requireLoaded()
        directory.mkdirs()
        try { TailscaleNative.start(directory.path, hostname, controlUrl, authKey.orEmpty()) }
        catch (failure: IOException) { directory.deleteRecursively(); throw failure }
        controlUrlFile.writeText(controlUrl)
        return status()
    }

    /** Logs a running node out first. A saved node that can no longer start, such as one with corrupt state, is just deleted. */
    @Synchronized fun signOut() {
        TailscaleNative.requireLoaded()
        val resumed = try { resume(); true } catch (failure: IOException) { false }
        if (resumed) TailscaleNative.signOut()
        directory.deleteRecursively()
    }

    fun connect(host: String, port: Int, timeoutMs: Int, ioTimeoutMs: Int): TailscaleConnection {
        ready()
        return try { TailscaleConnection(TailscaleNative.dial(host, port, timeoutMs, ioTimeoutMs)) }
        catch (failure: IOException) {
            throw if (status().state != "Running") TailscaleSignInRequired() else failure
        }
    }

    /** Returns a loopback URL that forwards HTTP requests to [origin] over the tailnet. */
    fun relay(origin: String): String {
        ready()
        return TailscaleNative.relay(origin)
    }

    @Synchronized private fun ready() {
        TailscaleNative.requireLoaded()
        if (!controlUrlFile.isFile) throw TailscaleSignInRequired()
        resume()
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
