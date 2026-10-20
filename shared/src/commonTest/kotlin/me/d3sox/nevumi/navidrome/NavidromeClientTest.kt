package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel

class NavidromeClientTest {
    @Test
    fun theCatalogueIncludesEveryPageWhenTheServerCapsPageSize() = runBlocking {
        val engine = MockEngine { request ->
            val offset = request.url.parameters["songOffset"]?.toInt() ?: 0
            val songs = when (offset) {
                0 -> """[{"id":"a","title":"Northern Light"}]"""
                1 -> """[{"id":"b","title":"Long Way Home"}]"""
                2 -> """[{"id":"c","title":"Night Walk"}]"""
                else -> "[]"
            }
            respond("""{"subsonic-response":{"status":"ok","searchResult3":{"song":$songs}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(engine)
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))
            val progress = mutableListOf<ServerRefreshProgress>()
            assertEquals(setOf("Northern Light", "Long Way Home", "Night Walk"),
                client.songs(onProgress = progress::add).map { it.metadata.title }.toSet())
            assertEquals(listOf(0, 1, 2, 3), progress.map { it.processed })
            assertTrue(progress.all { it.stage == ServerRefreshStage.SONGS && it.total == null })
        } finally { http.close() }
    }

    @Test
    fun stalledMetadataAndArtworkBodiesHaveARequestDeadline() = runBlocking {
        val body = ByteChannel()
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("getCoverArt.view"))
                respond(body, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            else awaitCancellation()
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"), requestTimeoutMillis = 50)
            withTimeout(3_000) {
                val metadata = assertFailsWith<NavidromeRequestException> { client.songs() }
                assertTrue(metadata.connectionFailure)
                assertTrue(metadata.message.orEmpty().contains("too long"))
                val artwork = assertFailsWith<NavidromeRequestException> { client.coverArt("cover") }
                assertTrue(artwork.connectionFailure)
            }
        } finally { http.close(); body.cancel(null) }
    }

    @Test
    fun cancellingARequestRemainsCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { entered.complete(Unit); awaitCancellation() })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))
            val request = async { client.ping() }
            withTimeout(3_000) { entered.await(); request.cancelAndJoin() }
            assertTrue(request.isCancelled)
        } finally { http.close() }
    }

    @Test
    fun overloadedArtworkRequestsKeepTheirHttpStatus() = runBlocking {
        val http = HttpClient(MockEngine { respond("busy", HttpStatusCode.TooManyRequests) })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))
            val failure = assertFailsWith<NavidromeRequestException> { client.coverArt("cover") }
            assertEquals(429, failure.httpStatus)
            assertTrue(failure.serverUnavailable)
            assertTrue(failure.message.orEmpty().contains("busy"))
        } finally { http.close() }
    }
}
