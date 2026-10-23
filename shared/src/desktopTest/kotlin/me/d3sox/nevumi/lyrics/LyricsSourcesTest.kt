package me.d3sox.nevumi.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.*

class LyricsSourcesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private fun http(handler: MockRequestHandler) = HttpClient(MockEngine(handler))
    private fun subsonic(body: String) = """{"subsonic-response":{"status":"ok","version":"1.16.1"$body}}"""
    private val metadata = TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", durationMs = 184_400)

    @Test fun prefersSyncedOpenSubsonicLyricsAndAppliesTheirOffset() = runBlocking {
        val requests = mutableListOf<String>()
        val client = NavidromeClient(http { request ->
            requests += request.url.encodedPath.substringAfterLast('/') + "?id=" + request.url.parameters["id"]
            respond(subsonic(""","lyricsList":{"structuredLyrics":[
                {"lang":"xxx","synced":false,"line":[{"value":"Plain"}]},
                {"lang":"eng","synced":true,"offset":100,"line":[{"start":2100,"value":"Two"},{"start":1100,"value":"One"}]}]}"""),
                headers = json)
        }, "https://music.example", ServerCredentials("listener", "secret"))
        val lyrics = assertNotNull(client.lyrics("song-1", "Fixture Artist", "Northern Light"))
        assertEquals(Lyrics(LyricsSource.SERVER, listOf(LyricLine(1_000, "One"), LyricLine(2_000, "Two"))), lyrics)
        assertEquals(listOf("getLyricsBySongId.view?id=song-1"), requests)
    }

    @Test fun fallsBackToClassicLyricsWhenTheServerLacksOpenSubsonic() = runBlocking {
        val classic = mutableListOf<Pair<String?, String?>>()
        val client = NavidromeClient(http { request ->
            if (request.url.encodedPath.endsWith("getLyricsBySongId.view"))
                respond("""{"subsonic-response":{"status":"failed","error":{"code":0,"message":"Unknown endpoint"}}}""", headers = json)
            else {
                classic += request.url.parameters["artist"] to request.url.parameters["title"]
                respond(subsonic(""","lyrics":{"artist":"Fixture Artist","title":"Northern Light","value":"First\nSecond"}"""), headers = json)
            }
        }, "https://music.example", ServerCredentials("listener", "secret"))
        assertEquals(Lyrics(LyricsSource.SERVER, plain = "First\nSecond"), client.lyrics("song-1", "Fixture Artist", "Northern Light"))
        assertEquals(listOf<Pair<String?, String?>>("Fixture Artist" to "Northern Light"), classic)
    }

    @Test fun lrcLibPrefersSyncedLyricsAndTreatsNotFoundAsNoLyrics() = runBlocking {
        var status = HttpStatusCode.OK
        var body = """{"id":1,"plainLyrics":"Plain only","syncedLyrics":"[00:01.00]Synced"}"""
        val seen = mutableListOf<Map<String, String?>>()
        val lrcLib = LrcLibClient(http { request ->
            seen += mapOf("path" to request.url.encodedPath, "agent" to request.headers[HttpHeaders.UserAgent],
                "artist" to request.url.parameters["artist_name"], "track" to request.url.parameters["track_name"],
                "album" to request.url.parameters["album_name"], "duration" to request.url.parameters["duration"])
            respond(body, status, json)
        }, "1.2.3")
        assertEquals(Lyrics(LyricsSource.LRCLIB, listOf(LyricLine(1_000, "Synced"))), lrcLib.lyrics(metadata))
        assertEquals(mapOf("path" to "/api/get", "agent" to "Nevumi/1.2.3 (https://github.com/D3SOX/Nevumi)", "artist" to "Fixture Artist",
            "track" to "Northern Light", "album" to "Fixture Album", "duration" to "184"), seen.single())
        body = """{"id":1,"plainLyrics":"Plain only","syncedLyrics":null}"""
        assertEquals(Lyrics(LyricsSource.LRCLIB, plain = "Plain only"), lrcLib.lyrics(metadata))
        status = HttpStatusCode.NotFound
        body = """{"code":404,"name":"TrackNotFound","message":"Failed to find specified track"}"""
        assertNull(lrcLib.lyrics(metadata))
    }

    @Test fun searchesLrcLibOnlyWhenOnlineAndCachesWhatItFoundUnderAPortableKey() = runBlocking {
        val directory = Files.createTempDirectory("lyrics-cache")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var requests = 0
        val http = http { requests++; respond("""{"syncedLyrics":"[00:02.00]From LRCLIB"}""", headers = json) }
        try {
            val identified = metadata.copy(recordingId = "11111111-2222-3333-4444-555555555555",
                releaseId = "66666666-7777-8888-9999-000000000000", trackNumber = 1)
            val track = Track("phone-track", identified, listOf(TrackSource("local:x", SourceKind.LOCAL, directory.resolve("missing.flac").toString())))
            var sources = LyricsSources(store, FileLyrics(), lrcLib = LrcLibClient(http, "1.0"))
            assertNull(sources.lyrics(track, online = false))
            assertEquals(0, requests)
            val found = assertNotNull(sources.lyrics(track, online = true))
            assertEquals(LyricsSource.LRCLIB, found.source)
            assertEquals(1, requests)
            store.close(); store = openMusicStore(database)
            sources = LyricsSources(store, FileLyrics(), lrcLib = LrcLibClient(http, "1.0"))
            // The same recording on another device has another track id and title spelling, but the same MusicBrainz identity.
            val elsewhere = Track("desktop-track", identified.copy(title = "northern light"), emptyList())
            assertEquals(found, sources.lyrics(elsewhere, online = false))
            assertEquals(1, requests)
            assertEquals(found, store.read<Lyrics>("lyrics:" + LyricsSources.cacheKey(identified)))
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun localLyricsWinWithoutAskingTheServerOrLrcLib() = runBlocking {
        val directory = Files.createTempDirectory("lyrics-order")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var requests = 0
        val http = http { requests++; respond("{}", HttpStatusCode.InternalServerError, json) }
        try {
            val audio = directory.resolve("song.flac").toFile()
            audio.writeBytes(byteArrayOf())
            directory.resolve("song.lrc").toFile().writeText("[00:01.00]Sidecar")
            val track = Track("t", metadata, listOf(TrackSource("local:s", SourceKind.LOCAL, audio.path),
                TrackSource("server:abc:1", SourceKind.SERVER, "1")))
            val sources = LyricsSources(store, FileLyrics(), { error("The server must not be asked") }, LrcLibClient(http, "1.0"))
            assertEquals(LyricsSource.SIDECAR, sources.lyrics(track, online = true)?.source)
            assertEquals(0, requests)
            // Failing sources leave nothing behind and do not throw.
            assertNull(sources.lyrics(track.copy(sources = track.sources.drop(1)), online = true))
            assertEquals(1, requests)
            assertNull(store.read<Lyrics>("lyrics:" + LyricsSources.cacheKey(metadata)))
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
