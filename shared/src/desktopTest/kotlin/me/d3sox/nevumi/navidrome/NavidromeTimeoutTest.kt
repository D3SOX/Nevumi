package me.d3sox.nevumi.navidrome

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

class NavidromeTimeoutTest {
    @Test
    fun aBusyServerGetsTheWholeIdleTimeoutInsteadOfTheEngineDefault() = runBlocking {
        val socketTimeouts = mutableListOf<Long?>()
        val http = HttpClient(MockEngine { request ->
            socketTimeouts += request.getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis
            respond("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            NavidromeClient(http, "https://music.example.test", ServerCredentials("user", "password"), idleTimeoutMillis = 45_000).ping()
            assertEquals(listOf<Long?>(45_000), socketTimeouts)
        } finally { http.close() }
    }

    @Test
    fun aSlowResponseIsRetriedAndThenReportedAsSlowRatherThanAsABadAddress() = runBlocking {
        val attempts = AtomicInteger()
        val http = HttpClient(MockEngine { attempts.incrementAndGet(); throw java.net.SocketTimeoutException("Read timed out") })
        try {
            val error = assertFailsWith<NavidromeRequestException> {
                NavidromeClient(http, "https://music.example.test", ServerCredentials("user", "password"), retryDelaysMillis = listOf(1, 1)).songs()
            }
            assertEquals("The music server took too long to respond. Try again later.", error.message)
            assertTrue(error.connectionFailure)
            assertEquals(3, attempts.get(), "A timed-out catalogue read is tried three times in all")
        } finally { http.close() }
    }

    @Test
    fun aLargeResponseThatKeepsArrivingIsNotCutOffByTheIdleTimeout() = runBlocking {
        val entries = (1..3_000).joinToString(",") { """{"id":"song-$it","title":"Song $it"}""" }
        val body = """{"subsonic-response":{"status":"ok","playlist":{"id":"favs","name":"Current favs","owner":"user","entry":[$entries]}}}"""
        SlowServer().use { server ->
            server.handle("getPlaylist") { exchange ->
                exchange.json()
                // About three times the idle timeout in all, but never more than a fraction of it without data.
                val bytes = body.encodeToByteArray()
                val chunk = bytes.size / 20 + 1
                for (start in bytes.indices step chunk) {
                    exchange.responseBody.write(bytes, start, minOf(chunk, bytes.size - start)); exchange.responseBody.flush()
                    Thread.sleep(150)
                }
            }
            val http = HttpClient(OkHttp)
            try {
                val client = NavidromeClient(http, server.address, ServerCredentials("user", "password"), idleTimeoutMillis = 1_000,
                    retryDelaysMillis = emptyList())
                val (playlist, elapsed) = measureTimedValue { client.playlist("favs") }
                assertEquals(3_000, playlist.songIds.size)
                assertTrue(elapsed > 2.seconds, "The response must outlast the idle timeout to prove anything, took $elapsed")
                assertEquals(1, server.requests("getPlaylist"))
            } finally { http.close() }
        }
    }

    @Test
    fun aServerThatStopsSendingFailsAfterTheIdleTimeoutBeforeOrDuringTheResponse() = runBlocking {
        SlowServer().use { server ->
            server.handle("getPlaylist") { Thread.sleep(30_000) }
            server.handle("search3") { exchange ->
                exchange.json()
                exchange.responseBody.write("""{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"a","ti""".encodeToByteArray())
                exchange.responseBody.flush()
                Thread.sleep(30_000)
            }
            val http = HttpClient(OkHttp)
            try {
                val client = NavidromeClient(http, server.address, ServerCredentials("user", "password"), idleTimeoutMillis = 300,
                    retryDelaysMillis = emptyList())
                for (request in listOf<suspend () -> Unit>({ client.playlist("favs") }, { client.songs() })) {
                    val (error, elapsed) = measureTimedValue { assertFailsWith<NavidromeRequestException> { request() } }
                    assertEquals("The music server took too long to respond. Try again later.", error.message)
                    assertTrue(error.connectionFailure)
                    assertTrue(elapsed in 250.milliseconds..10.seconds, "Gave up after $elapsed")
                }
            } finally { http.close() }
        }
    }

    @Test
    fun aStalledCatalogueReadIsRetriedUntilTheServerAnswersAndThenGivesUp() = runBlocking {
        SlowServer().use { server ->
            server.handle("getPlaylist") { exchange ->
                if (server.requests("getPlaylist") == 1) Thread.sleep(30_000)
                exchange.json()
                exchange.responseBody.write("""{"subsonic-response":{"status":"ok","playlist":{"id":"favs","name":"Favs","entry":[{"id":"a"}]}}}""".encodeToByteArray())
            }
            server.handle("search3") { Thread.sleep(30_000) }
            val http = HttpClient(OkHttp)
            try {
                val client = NavidromeClient(http, server.address, ServerCredentials("user", "password"), idleTimeoutMillis = 300,
                    retryDelaysMillis = listOf(10, 10))
                assertEquals(listOf("a"), client.playlist("favs").songIds)
                assertEquals(2, server.requests("getPlaylist"))
                assertFailsWith<NavidromeRequestException> { client.songs() }
                assertEquals(3, server.requests("search3"), "A read that keeps stalling is tried three times in all")
                // A connection dropped before the whole body arrived is retried too.
                server.handle("getPlaylists") { exchange ->
                    if (server.requests("getPlaylists") == 1) {
                        exchange.responseHeaders.add("Content-Type", "application/json")
                        exchange.sendResponseHeaders(200, 1_000)
                        exchange.responseBody.write("""{"subsonic-response":""".encodeToByteArray())
                        exchange.responseBody.flush()
                        return@handle
                    }
                    exchange.json()
                    exchange.responseBody.write("""{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"favs","changed":"t1"}]}}}""".encodeToByteArray())
                }
                assertEquals(mapOf("favs" to "t1"), client.playlistVersions())
                assertEquals(2, server.requests("getPlaylists"))
            } finally { http.close() }
        }
    }

    @Test
    fun onlyTransientFailuresOfReadsAreRetried() = runBlocking {
        val attempts = mutableMapOf<String, Int>()
        var failure: () -> Nothing = { throw java.net.SocketTimeoutException("Read timed out") }
        var status = HttpStatusCode.OK
        val http = HttpClient(MockEngine { request ->
            val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            attempts[endpoint] = (attempts[endpoint] ?: 0) + 1
            if (status == HttpStatusCode.OK) failure() else respond("unavailable", status)
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("user", "password"), retryDelaysMillis = listOf(1, 1))
            // Repeating a write could apply it twice, such as a scrobble.
            assertFailsWith<NavidromeRequestException> { client.scrobble("a", 1, submission = true) }
            assertFailsWith<NavidromeRequestException> { client.setFavorite("a", true) }
            assertEquals(mapOf("scrobble" to 1, "star" to 1), attempts)
            attempts.clear()
            status = HttpStatusCode.BadGateway
            assertFailsWith<NavidromeRequestException> { client.coverArt("cover") }
            status = HttpStatusCode.NotFound
            assertFailsWith<NavidromeRequestException> { client.playlist("gone") }
            status = HttpStatusCode.OK
            failure = { throw java.net.ConnectException("Connection refused") }
            assertFailsWith<NavidromeRequestException> { client.playlistVersions() }
            assertEquals(mapOf("getCoverArt" to 3, "getPlaylist" to 1, "getPlaylists" to 1), attempts,
                "Gateway errors are retried; a missing item and an unreachable server are not")
        } finally { http.close() }
    }

    private fun HttpExchange.json() {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, 0)
    }

    /** A real HTTP server whose handlers can pause, so OkHttp's own socket timeouts apply. */
    private class SlowServer : AutoCloseable {
        private val threads = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply { executor = threads; start() }
        private val counts = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val address get() = "http://127.0.0.1:${server.address.port}"

        fun requests(endpoint: String) = counts[endpoint]?.get() ?: 0

        fun handle(endpoint: String, handler: (HttpExchange) -> Unit) {
            runCatching { server.removeContext("/rest/$endpoint.view") }
            server.createContext("/rest/$endpoint.view") { exchange ->
                counts.getOrPut(endpoint) { AtomicInteger() }.incrementAndGet()
                try { handler(exchange) }
                catch (_: IOException) { /* The client gave up. */ }
                catch (_: InterruptedException) { /* The test finished. */ }
                finally { exchange.close() }
            }
        }

        override fun close() { server.stop(0); threads.shutdownNow() }
    }
}
