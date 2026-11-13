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
}
