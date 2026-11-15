package me.d3sox.nevumi.youtube

import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals

class NewPipeYouTubeTest {
    @Test fun rememberingAVideoForgetsExpiredAddressesAndIdleLookups() {
        NewPipeYouTube().use { youTube ->
            val audio = YouTubeAudio("https://media.test/a", "audio/mp4", 1, 128)
            youTube.lookups["expired"] = Mutex()
            youTube.lookups["failed"] = Mutex()
            youTube.lookups["running"] = Mutex(locked = true)
            youTube.remember("expired", NewPipeYouTube.Found(audio, expiresAt = 1_000), now = 0)
            youTube.remember("current", NewPipeYouTube.Found(audio, expiresAt = 10_000), now = 2_000)

            assertEquals(setOf("current"), youTube.found.keys)
            assertEquals(setOf("running"), youTube.lookups.keys)
        }
    }
}
