package me.d3sox.nevumi.history

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.downloads.DownloadNetwork
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.security.SecretServiceVault
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.*

class ScrobblingThresholdTest {
    @Test
    fun percentageAndTimeLimitUseTheEarlierThresholdAndTheLimitCanBeDisabled() {
        val listen = Listen("listen", "track", "play", 0, TrackMetadata("Song", "Artist"), durationMs = 180_000)
        val defaults = MusicSettings()
        assertFalse(listen.copy(playedMs = 89_999).qualifiesForScrobbling(defaults))
        assertTrue(listen.copy(playedMs = 90_000).qualifiesForScrobbling(defaults))
        assertFalse(listen.copy(playedMs = 134_999).qualifiesForScrobbling(defaults.copy(scrobblePercent = 75)))
        assertTrue(listen.copy(playedMs = 135_000).qualifiesForScrobbling(defaults.copy(scrobblePercent = 75)))
        assertTrue(listen.copy(playedMs = 45_000).qualifiesForScrobbling(defaults.copy(scrobblePercent = 25)))
        assertFalse(listen.copy(playedMs = 45_000).qualified)
        val long = listen.copy(durationMs = 600_000)
        assertFalse(long.copy(playedMs = 239_999).qualifiesForScrobbling(defaults))
        assertTrue(long.copy(playedMs = 240_000).qualifiesForScrobbling(defaults))
        assertTrue(long.copy(playedMs = 60_000).qualifiesForScrobbling(defaults.copy(scrobbleMaxSeconds = 60)))
        val percentageOnly = defaults.copy(scrobblePercent = 100, scrobbleMaxSeconds = 0)
        assertFalse(long.copy(playedMs = 599_999).qualifiesForScrobbling(percentageOnly))
        assertTrue(long.copy(playedMs = 600_000).qualifiesForScrobbling(percentageOnly))
        val unknownDuration = listen.copy(durationMs = 0, playedMs = 240_000)
        assertTrue(unknownDuration.qualifiesForScrobbling(defaults))
        assertFalse(unknownDuration.qualifiesForScrobbling(percentageOnly))
    }

    @Test
    fun savedThresholdChangesWakeAllServicesAndKeepLastFmShortTrackAndDeduplicationRules() = runBlocking {
        val directory = Files.createTempDirectory("scrobbling-threshold")
        val database = directory.resolve("music.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(MockEngine { error("Offline listens must remain queued") })
        var worker: Scrobbling? = null
        try {
            val preferences = MusicPreferences(store)
            assertEquals(50, preferences.load().scrobblePercent)
            assertEquals(240, preferences.load().scrobbleMaxSeconds)
            preferences.update { it.copy(scrobblePercent = 75, scrobbleMaxSeconds = 0) }
            assertFailsWith<IllegalArgumentException> { preferences.update { it.copy(scrobblePercent = 0) } }
            assertFailsWith<IllegalArgumentException> { preferences.update { it.copy(scrobblePercent = 101) } }
            assertFailsWith<IllegalArgumentException> { preferences.update { it.copy(scrobbleMaxSeconds = -1) } }
            assertFailsWith<IllegalArgumentException> { preferences.update { it.copy(scrobbleMaxSeconds = 601) } }
            store.close(); store = openMusicStore(database)
            assertEquals(75, MusicPreferences(store).load().scrobblePercent)
            assertEquals(0, MusicPreferences(store).load().scrobbleMaxSeconds)
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Song", "Artist", durationMs = 180_000),
                TrackSource("server:fixture:song", SourceKind.SERVER, "song"))
            for (service in ScrobblingService.entries) {
                val profile = ScrobblingProfile(service.name, service, "fixture", "https://example.test", 0, serverId = "fixture")
                store.put("scrobbling-profile:${profile.id}", "scrobbling-profiles", profile)
            }
            val listen = Listen("listen", track.id, "play", 1_000, track.metadata, playedMs = 100_000, durationMs = 180_000)
            store.put("listen:${listen.id}", "listens", listen)
            store.put("listen:short", "listens", listen.copy(id = "short", playbackId = "short", durationMs = 30_000, playedMs = 30_000))
            store.put("listen:imported", "listens", listen.copy(id = "imported", playbackId = "imported", originDeviceId = "other"))
            val scrobbling = Scrobbling(store, library, http, SecretServiceVault("me.d3sox.nevumi.tests.threshold"),
                network = MutableStateFlow(DownloadNetwork(connected = false)))
            worker = scrobbling
            scrobbling.enqueueEligible()
            assertEquals(2, scrobbling.submissions().size)
            assertTrue(scrobbling.submissions().all { it.playbackId == "short" && it.profileId != ScrobblingService.LASTFM.name })
            scrobbling.start()
            MusicPreferences(store).update { it.copy(scrobblePercent = 50) }
            withTimeout(3_000) { while (scrobbling.submissions().size != 5) delay(10) }
            assertEquals(ScrobblingService.entries.map { it.name }.toSet(),
                scrobbling.submissions().filter { it.playbackId == "play" }.map { it.profileId }.toSet())
            scrobbling.enqueueEligible()
            assertEquals(5, scrobbling.submissions().size)
        } finally {
            worker?.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }
}
