package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlin.test.*

class NavidromeStreamQualityTest {
    @Test
    fun originalStreamsAskForTheRawFileAndLimitedStreamsLetTheServerTranscode() {
        val http = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("user", "password"))
            val original = Url(client.streamUrl("song")).parameters
            assertEquals("raw", original["format"])
            assertEquals("0", original["maxBitRate"])
            assertNull(original["estimateContentLength"])

            val limited = Url(client.streamUrl("song", maxBitRate = 128)).parameters
            assertNull(limited["format"], "The server picks its own transcoding format")
            assertEquals("128", limited["maxBitRate"])
            assertEquals("true", limited["estimateContentLength"])
            assertEquals("song", limited["id"])
        } finally { http.close() }
    }
}
