package me.d3sox.nevumi.history

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals

class ScrobblingFailuresTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val vault = object : CredentialVault {
        private val secrets = ConcurrentHashMap<String, String>()
        override suspend fun save(key: String, secret: String) { secrets[key] = secret }
        override suspend fun read(key: String) = secrets[key]
        override suspend fun delete(key: String) { secrets.remove(key) }
    }

    private suspend fun recordQualifiedListen(store: MusicStore, library: MusicLibrary) {
        val song = library.importTrack(TrackMetadata("Song", "Artist", durationMs = 180_000),
            TrackSource("local", SourceKind.LOCAL, "/private/song.flac"))
        var elapsed = 0L
        val history = ListeningHistory(store, library, wallClock = { 10_000 }, elapsedClock = { elapsed })
        val playing = PlaybackStatus(song.id, playing = true, durationMs = 180_000, playbackId = "play")
        history.record(playing); elapsed = 100_000; history.record(playing.copy(playing = false))
    }

    @Test
    fun unreadableLastFmCredentialsNeedASignInAndDoNotStopOtherSubmissions() = runBlocking {
        val directory = Files.createTempDirectory("scrobbling-unreadable-credentials")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token")) respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else respond("""{"status":"ok"}""", headers = jsonHeaders)
        })
        try {
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, now = { 1_000 })
            val listenBrainz = scrobbling.connectListenBrainz("fixture-token")
            val lastFm = ScrobblingProfile("lastfm", ScrobblingService.LASTFM, "fixture", "https://ws.audioscrobbler.com/2.0/", 0)
            store.put("scrobbling-profile:${lastFm.id}", "scrobbling-profiles", lastFm)
            vault.save("scrobbling:${lastFm.id}", "not credentials")
            recordQualifiedListen(store, library)
            scrobbling.enqueueEligible()
            scrobbling.runPending()
            val states = scrobbling.submissions().associate { it.profileId to it.state }
            assertEquals(mapOf(listenBrainz.id to ScrobbleState.CONFIRMED, lastFm.id to ScrobbleState.AUTHORIZATION), states)
            scrobbling.closeAndJoin()
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun unreadableLastFmCredentialsDoNotStopTheWorkerWhileASongPlays() = runBlocking {
        val directory = Files.createTempDirectory("scrobbling-unreadable-now-playing")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token")) respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else respond("""{"status":"ok"}""", headers = jsonHeaders)
        })
        try {
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, now = { 1_000 })
            val listenBrainz = scrobbling.connectListenBrainz("fixture-token")
            val lastFm = ScrobblingProfile("lastfm", ScrobblingService.LASTFM, "fixture", "https://ws.audioscrobbler.com/2.0/", 0)
            store.put("scrobbling-profile:${lastFm.id}", "scrobbling-profiles", lastFm)
            vault.save("scrobbling:${lastFm.id}", "not credentials")
            recordQualifiedListen(store, library)
            // Now Playing reads the Last.fm credentials before the worker submits anything.
            scrobbling.onPlayback(PlaybackStatus(library.tracks().single().id, playing = true, playWhenReady = true, durationMs = 180_000,
                playbackId = "now"))
            scrobbling.start()
            val expected = mapOf(listenBrainz.id to ScrobbleState.CONFIRMED, lastFm.id to ScrobbleState.AUTHORIZATION)
            withTimeout(5_000) { while (scrobbling.submissions().associate { it.profileId to it.state } != expected) delay(20) }
            scrobbling.closeAndJoin()
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anUnavailableServiceKeepsTheListenPendingForARetry() = runBlocking {
        val directory = Files.createTempDirectory("scrobbling-unavailable-service")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var status = HttpStatusCode.ServiceUnavailable
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token")) respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else respond("""{"status":"ok"}""", status, jsonHeaders)
        })
        try {
            val library = MusicLibrary(store)
            var clock = 1_000L
            val scrobbling = Scrobbling(store, library, http, vault, now = { clock })
            scrobbling.connectListenBrainz("fixture-token")
            recordQualifiedListen(store, library)
            scrobbling.enqueueEligible()
            scrobbling.runPending()
            assertEquals(ScrobbleState.PENDING, scrobbling.submissions().single().state)
            status = HttpStatusCode.OK
            clock += 60_000
            scrobbling.runPending()
            assertEquals(ScrobbleState.CONFIRMED, scrobbling.submissions().single().state)
            scrobbling.closeAndJoin()
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
