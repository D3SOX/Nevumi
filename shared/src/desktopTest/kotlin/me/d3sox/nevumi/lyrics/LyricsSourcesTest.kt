package me.d3sox.nevumi.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.awaitCancellation
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
            var sources = LyricsSources(store, FileLyrics(), online = listOf(LrcLibClient(http, "1.0")))
            assertNull(sources.lyrics(track, online = emptySet()))
            assertEquals(0, requests)
            val found = assertNotNull(sources.lyrics(track, online = setOf(LyricsSource.LRCLIB)))
            assertEquals(LyricsSource.LRCLIB, found.source)
            assertEquals(1, requests)
            store.close(); store = openMusicStore(database)
            sources = LyricsSources(store, FileLyrics(), online = listOf(LrcLibClient(http, "1.0")))
            // The same recording on another device has another track id and title spelling, but the same MusicBrainz identity.
            val elsewhere = Track("desktop-track", identified.copy(title = "northern light"), emptyList())
            assertEquals(found, sources.lyrics(elsewhere, online = emptySet()))
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
            val sources = LyricsSources(store, FileLyrics(), { error("The server must not be asked") }, listOf(LrcLibClient(http, "1.0")))
            assertEquals(LyricsSource.SIDECAR, sources.lyrics(track, online = setOf(LyricsSource.LRCLIB))?.source)
            assertEquals(0, requests)
            // Failing sources leave nothing behind and do not throw.
            assertNull(sources.lyrics(track.copy(sources = track.sources.drop(1)), online = setOf(LyricsSource.LRCLIB)))
            assertEquals(1, requests)
            assertNull(store.read<Lyrics>("lyrics:" + LyricsSources.cacheKey(metadata)))
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun aWrittenQueryAsksOnlyTheOnlineServicesAndReplacesTheKeptLyrics() = runBlocking {
        val directory = Files.createTempDirectory("lyrics-query")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val seen = mutableListOf<TrackMetadata>()
        val service = object : OnlineLyrics {
            override val source = LyricsSource.LRCLIB
            override suspend fun lyrics(metadata: TrackMetadata): Lyrics? {
                seen += metadata
                return if (metadata.title == "Nothing") null else Lyrics(LyricsSource.LRCLIB, plain = metadata.title)
            }
        }
        try {
            val audio = directory.resolve("song.flac").toFile()
            audio.writeBytes(byteArrayOf())
            directory.resolve("song.lrc").toFile().writeText("[00:01.00]Sidecar")
            val track = Track("t", metadata, listOf(TrackSource("local:s", SourceKind.LOCAL, audio.path)))
            val sources = LyricsSources(store, FileLyrics(), online = listOf(service))
            store.put("lyrics:" + LyricsSources.cacheKey(metadata), LyricsSources.KIND, Lyrics(LyricsSource.LRCLIB, plain = "Wrong match"))
            // The sidecar and the kept lyrics are skipped, and the length still helps the service find the right version.
            val query = LyricsQuery(" Northern Lights ", "Fixture Artist", "")
            assertEquals(Lyrics(LyricsSource.LRCLIB, plain = "Northern Lights"), sources.lyrics(track, setOf(LyricsSource.LRCLIB), query))
            assertEquals(metadata.copy(title = "Northern Lights", album = ""), seen.single())
            assertEquals(Lyrics(LyricsSource.LRCLIB, plain = "Northern Lights"), store.read<Lyrics>("lyrics:" + LyricsSources.cacheKey(metadata)))
            // A search that finds nothing keeps what was there, and the services are not asked while they are off.
            assertNull(sources.lyrics(track, setOf(LyricsSource.LRCLIB), query.copy(title = "Nothing")))
            assertNull(sources.lyrics(track, emptySet(), query))
            assertEquals(2, seen.size)
            assertEquals(Lyrics(LyricsSource.LRCLIB, plain = "Northern Lights"), store.read<Lyrics>("lyrics:" + LyricsSources.cacheKey(metadata)))
            // An untagged file keeps what a search found under its own track.
            val untagged = Track("untagged", TrackMetadata("track01"), emptyList())
            assertNull(LyricsSources.cacheKey(untagged.metadata))
            val found = sources.lyrics(untagged, setOf(LyricsSource.LRCLIB), LyricsQuery("Real title", "Real artist"))
            assertEquals(found, sources.lyrics(untagged, emptySet()))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun netEaseTakesTheFirstCopyOfTheSameRecordingWithLyricsAndDropsItsCredits() = runBlocking {
        val searches = mutableListOf<String?>()
        val lyricIds = mutableListOf<String?>()
        var lyric = """{"code":200,"lrc":{"lyric":"[00:00.000] 作词 : Someone\n[00:01.000] 作曲 : Someone\n[00:02.50]你说：First line\n[00:04.00]Second"}}"""
        val netEase = NetEaseClient(http { request ->
            if (request.url.encodedPath == "/api/cloudsearch/pc") {
                searches += request.url.parameters["s"]
                respond("""{"code":200,"result":{"songs":[
                    {"id":1,"name":"Northern Light (Live)","ar":[{"name":"Fixture Artist"}],"dt":184000},
                    {"id":2,"name":"Northern Light","ar":[{"name":"Someone Else"}],"dt":184000},
                    {"id":6,"name":"Northern Light","ar":[{"name":"Fixture Artists"}],"dt":184000},
                    {"id":3,"name":"Northern Light","ar":[{"name":"Fixture Artist"}],"dt":240000},
                    {"id":4,"name":"NORTHERN LIGHT","ar":[{"name":"Fixture Artist (测试)"}],"dt":183900},
                    {"id":5,"name":"Northern-Light","ar":[{"name":"FIXTURE ARTIST"}],"dt":185000}]}}""", headers = json)
            } else {
                val id = request.url.parameters["id"]
                lyricIds += id
                respond(when (id) { "4" -> """{"code":200,"lrc":{"lyric":"Plain copy"}}"""; else -> lyric }, headers = json)
            }
        }, "1.0")
        assertEquals(Lyrics(LyricsSource.NETEASE, listOf(LyricLine(2_500, "你说：First line"), LyricLine(4_000, "Second"))), netEase.lyrics(metadata))
        assertEquals(listOf<String?>("Fixture Artist Northern Light"), searches)
        // Other versions, artists and lengths are never fetched, and a plain copy gives way to a synced one.
        assertEquals(listOf<String?>("4", "5"), lyricIds)
        for (opening in listOf("I : remember", "僕 : まだ", "2 : 1", "你说 : First line")) {
            lyric = """{"code":200,"lrc":{"lyric":"[00:00.000] 作词 : Someone\n[00:00.100]制作人 : Someone\n[00:00.200]录音师 : Someone\n[00:00.300]混音工程师 : Someone\n[00:01.00]$opening"}}"""
            assertEquals(Lyrics(LyricsSource.NETEASE, listOf(LyricLine(1_000, opening))), netEase.lyrics(metadata))
        }
        lyric = """{"code":200,"lrc":{"lyric":""}}"""
        assertEquals(Lyrics(LyricsSource.NETEASE, plain = "Plain copy"), netEase.lyrics(metadata))
        lyric = """{"code":200,"pureMusic":true,"lrc":{"lyric":"[00:00.00]纯音乐，请欣赏"}}"""
        assertNull(netEase.lyrics(metadata))
        assertNull(netEase.lyrics(metadata.copy(title = "Unknown song")))
    }

    @Test fun netEaseReportsARefusalSentWithHttpOk() = runBlocking {
        val netEase = NetEaseClient(http { respond("""{"code":-462,"message":"Verify your phone"}""", headers = json) }, "1.0")
        assertFailsWith<IllegalStateException> { netEase.lyrics(metadata) }
        Unit
    }

    @Test fun onlineServicesAreAskedInOrderUntilOneHasSyncedLyrics() = runBlocking {
        val asked = mutableListOf<LyricsSource>()
        fun service(source: LyricsSource, answer: suspend () -> Lyrics?) = object : OnlineLyrics {
            override val source = source
            override suspend fun lyrics(metadata: TrackMetadata): Lyrics? { asked += source; return answer() }
        }
        val track = Track("t", metadata, emptyList())
        val synced = Lyrics(LyricsSource.NETEASE, listOf(LyricLine(0, "Synced")))
        val plain = Lyrics(LyricsSource.LRCLIB, plain = "Plain")
        suspend fun find(lrcLib: suspend () -> Lyrics?, netEase: suspend () -> Lyrics?, online: Set<LyricsSource>): Lyrics? {
            asked.clear()
            return LyricsSources(null, null, online = listOf(service(LyricsSource.LRCLIB, lrcLib), service(LyricsSource.NETEASE, netEase)),
                onlineTimeoutMillis = 200).lyrics(track, online)
        }
        val both = setOf(LyricsSource.LRCLIB, LyricsSource.NETEASE)
        assertNull(find({ plain }, { synced }, emptySet()))
        assertEquals(emptyList<LyricsSource>(), asked)
        // Synced lyrics from a later service beat plain text from an earlier one, which stays as the fallback.
        assertEquals(synced, find({ plain }, { synced }, both))
        assertEquals(plain, find({ plain }, { null }, both))
        assertEquals(listOf(LyricsSource.LRCLIB, LyricsSource.NETEASE), asked)
        assertEquals(plain, find({ plain }, { synced }, setOf(LyricsSource.LRCLIB)))
        assertEquals(listOf(LyricsSource.LRCLIB), asked)
        assertEquals(synced.copy(source = LyricsSource.LRCLIB), find({ synced.copy(source = LyricsSource.LRCLIB) }, { error("unused") }, both))
        assertEquals(listOf(LyricsSource.LRCLIB), asked)
        // A failing or stalled service gives way to the next one.
        assertEquals(synced, find({ error("HTTP 503") }, { synced }, both))
        assertEquals(synced, find({ awaitCancellation() }, { synced }, both))
        assertEquals(plain, find({ plain }, { awaitCancellation() }, both))
    }
}
