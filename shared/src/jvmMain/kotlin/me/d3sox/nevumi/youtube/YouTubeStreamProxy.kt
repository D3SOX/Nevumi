package me.d3sox.nevumi.youtube

import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.Executors
import kotlin.uuid.Uuid

/**
 * Serves YouTube audio to the player from an address on this device. YouTube slows down a request for a whole stream to about
 * twice its playback speed, but answers requests for ranges of about a megabyte at full speed, so each request the player makes,
 * including the ones for seeking, is fetched as a series of such ranges. The stream's address is only looked up when the player
 * opens the song, and looked up again once YouTube rejects an expired one.
 */
internal class YouTubeStreamProxy(private val chunkBytes: Long = 1L shl 20,
    private val find: suspend (videoId: String, fresh: Boolean) -> YouTubeAudio) : AutoCloseable {
    // Other apps on this device can reach the port, but not guess the path.
    private val token = Uuid.random().toHexString()
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "youtube-stream").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    fun location(videoId: String): String = "http://127.0.0.1:${listening().localPort}/$token/$videoId"

    @Synchronized private fun listening(): ServerSocket = server ?: ServerSocket(0, 16, LOOPBACK).also { socket ->
        server = socket
        workers.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                workers.execute { client.use(::serve) }
            }
        }
    }

    override fun close() {
        synchronized(this) { server?.close(); server = null }
        workers.shutdownNow()
    }

    private class Range(val start: Long, val end: Long?)

    // Android's InetAddress.getLoopbackAddress() is ::1, which the IPv4 address in [location] does not reach.
    private companion object { val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)) }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 60_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = input.line() ?: return
            val headers = generateSequence { input.line()?.takeIf { it.isNotEmpty() } }
                .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
                .toMap()
            val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val output = client.getOutputStream()
            val videoId = path.removePrefix("/$token/").takeIf { path.startsWith("/$token/") && '/' !in it }
            if (videoId == null || method !in setOf("GET", "HEAD")) return output.status(404, "Not Found")
            val range = headers["range"]?.let(::range)
            var audio = try { runBlocking { find(videoId, false) } } catch (_: Exception) { return output.status(502, "Bad Gateway") }
            val total = audio.contentLength.takeIf { it > 0 } ?: try { length(audio.url) }
                catch (_: IOException) { audio = runBlocking { find(videoId, true) }; length(audio.url) }
            val start = range?.start ?: 0
            if (start >= total) return output.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nConnection: close\r\n\r\n")
            val end = (range?.end ?: (total - 1)).coerceAtMost(total - 1)
            output.write(buildString {
                append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: ${audio.mimeType}\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/$total\r\n")
                append("Connection: close\r\n\r\n")
            })
            if (method == "HEAD") return
            var position = start
            var refreshed = false
            while (position <= end) {
                val last = minOf(end, position + chunkBytes - 1)
                val connection = upstream(audio.url, position, last)
                try {
                    val code = connection.responseCode
                    // An expired or rejected address gets one fresh lookup; the bytes already sent stay valid.
                    if (code == 403 || code == 404 || code == 410) {
                        if (refreshed) return
                        refreshed = true
                        audio = runBlocking { find(videoId, true) }
                        continue
                    }
                    if (code != 206) return
                    position += connection.inputStream.use { it.copyTo(output) }
                } finally { connection.disconnect() }
            }
            output.flush()
        } catch (_: SocketException) { /* The player closed the connection, such as to seek elsewhere. */ }
        catch (_: IOException) { }
        catch (_: Exception) { }
    }

    private fun upstream(url: String, first: Long, last: Long) = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", YOUTUBE_USER_AGENT)
        setRequestProperty("Range", "bytes=$first-$last")
    }

    /** Asks for the first byte only, since the answer names the stream's whole length. */
    private fun length(url: String): Long {
        val connection = upstream(url, 0, 0)
        try {
            if (connection.responseCode != 206) throw IOException("YouTube rejected the stream")
            return connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: throw IOException("YouTube sent no length")
        } finally { connection.disconnect() }
    }

    private fun range(value: String): Range? {
        val bytes = value.removePrefix("bytes=").takeIf { value.startsWith("bytes=") && ',' !in it } ?: return null
        val start = bytes.substringBefore('-').toLongOrNull() ?: return null
        return Range(start, bytes.substringAfter('-').takeIf { it.isNotEmpty() }?.toLongOrNull())
    }

    private fun OutputStream.write(text: String) { write(text.encodeToByteArray()); flush() }
    private fun OutputStream.status(code: Int, reason: String) = write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

    /** One header line without its line break, or null at the end of the stream. */
    private fun InputStream.line(): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = read()
            if (next == -1) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (next == '\n'.code) return bytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
            if (bytes.size() > 8_192) throw IOException("Request header too long")
            bytes.write(next)
        }
    }
}
