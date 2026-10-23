package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NavidromeTimeoutTest {
    @Test
    fun aBusyServerGetsTheWholeRequestTimeoutInsteadOfTheEngineDefault() = runBlocking {
        val socketTimeouts = mutableListOf<Long?>()
        val http = HttpClient(MockEngine { request ->
            socketTimeouts += request.getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis
            respond("""{"subsonic-response":{"status":"ok","version":"1.16.1"}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            NavidromeClient(http, "https://music.example.test", ServerCredentials("user", "password"), requestTimeoutMillis = 45_000).ping()
            assertEquals(listOf<Long?>(45_000), socketTimeouts)
        } finally { http.close() }
    }

    @Test
    fun aSlowResponseIsReportedAsSlowRatherThanAsABadAddress() = runBlocking {
        val http = HttpClient(MockEngine { throw java.net.SocketTimeoutException("Read timed out") })
        try {
            val error = assertFailsWith<NavidromeRequestException> {
                NavidromeClient(http, "https://music.example.test", ServerCredentials("user", "password")).songs()
            }
            assertEquals("The music server took too long to respond. Try again later.", error.message)
            assertTrue(error.connectionFailure)
        } finally { http.close() }
    }
}
