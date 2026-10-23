package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class NavidromePlayQueueTest {
    @Test
    fun aSavedQueueComesBackFromTheRealServerWithItsCurrentSongPositionAndClient() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val songIds = client.songs().map { it.id }
            val saved = ServerPlayQueue(songIds.reversed(), songIds[1], 83_250)
            client.savePlayQueue(saved)
            assertEquals(saved.copy(changedBy = "Nevumi"), client.playQueue())
        } finally { http.close() }
    }
}
