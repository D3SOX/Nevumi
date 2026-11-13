package me.d3sox.nevumi.youtube

import java.net.Socket
import java.net.SocketException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YouTubeStreamProxyTest {
    @Test
    fun aWrongPathIsAnsweredBeforeHeadersAndEndlessHeadersAreRefusedWithoutALookup() {
        var lookups = 0
        YouTubeStreamProxy { _, _ -> lookups++; error("No lookup expected") }.use { proxy ->
            val location = URI(proxy.location("video"))
            // Neither request ends its headers, so only answering early or refusing them finishes it.
            fun send(path: String, headers: Int): String = Socket("127.0.0.1", location.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(buildString {
                    append("GET $path HTTP/1.1\r\n")
                    repeat(headers) { append("X-Filler-$it: 1\r\n") }
                }.toByteArray())
                try { socket.getInputStream().readBytes().decodeToString() } catch (_: SocketException) { "" }
            }
            assertTrue(send("/wrong/video", 0).startsWith("HTTP/1.1 404"))
            assertEquals("", send(location.path, 200))
            assertEquals(0, lookups)
        }
    }

    @Test
    fun suffixAndInvertedRangesAreResolvedAgainstTheStreamLength() {
        YouTubeStreamProxy { _, _ -> YouTubeAudio("http://127.0.0.1:1/unused", "audio/webm", 1_000, 128) }.use { proxy ->
            val location = URI(proxy.location("video"))
            fun head(range: String): String = Socket("127.0.0.1", location.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write("HEAD ${location.path} HTTP/1.1\r\nRange: $range\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes().decodeToString()
            }
            val suffix = head("bytes=-100")
            assertTrue(suffix.startsWith("HTTP/1.1 206"), suffix)
            assertTrue("Content-Range: bytes 900-999/1000\r\n" in suffix, suffix)
            assertTrue("Content-Length: 100\r\n" in suffix, suffix)
            assertTrue("Content-Range: bytes 0-999/1000\r\n" in head("bytes=-5000"))
            assertTrue(head("bytes=500-100").startsWith("HTTP/1.1 416"))
            assertTrue(head("bytes=-0").startsWith("HTTP/1.1 416"))
        }
    }

    @Test
    fun aClosedProxyDoesNotListenAgain() {
        val proxy = YouTubeStreamProxy { _, _ -> error("No lookup expected") }
        proxy.location("video")
        proxy.close()
        kotlin.test.assertFailsWith<java.io.IOException> { proxy.location("video") }
    }
}
