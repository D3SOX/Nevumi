package me.d3sox.nevumi.soundcloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundCloudDownloadsTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val track = SoundCloudTrack(7, "https://soundcloud.com/artist/night-drive", "Night Drive", "Artist", "artist",
        durationMs = 201_000, source = SoundCloudSource.GATE)
    private val audio = Files.readAllBytes(Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.mp3"))

    private fun status(stage: String, percent: Int = 0, outputFilename: String? = null, libraryFilename: String? = null) =
        """{"progress":{"stage":"$stage","message":"$stage","percent":$percent},"outputFilename":${outputFilename?.let { "\"$it\"" }},
            "libraryFilename":${libraryFilename?.let { "\"$it\"" }},"defaultMetadata":{"title":"Night Drive","artist":"Artist"},"error":null}"""

    private class Fixture(val directory: Path, val store: MusicStore, val library: MusicLibrary, val http: HttpClient,
        val downloads: SoundCloudDownloads, val requests: MutableList<String>)

    private fun fixture(handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?): Fixture {
        val directory = Files.createTempDirectory("soundcloud-downloads")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val http = HttpClient(MockEngine { request ->
            requests += "${request.method.value} ${request.url.encodedPath}"
            handle(request) ?: when (request.url.encodedPath) {
                "/api/capabilities" -> respond("""{"search":true,"libraryDestination":false}""", headers = json)
                else -> respond("""{"error":"Unexpected request"}""", HttpStatusCode.NotFound, json)
            }
        })
        val downloads = SoundCloudDownloads(store, http, files = DirectorySoundCloudFiles(directory.resolve("SoundCloud").toString()),
            localSources = LocalFiles(library), pollMillis = 10)
        return Fixture(directory, store, library, http, downloads, requests)
    }

    private fun Fixture.close() = runBlocking {
        downloads.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively()
    }

    @Test
    fun aDeviceDownloadIsSavedIndexedAndAnnounced() = runBlocking {
        var polls = 0
        val fixture = fixture { request ->
            when (request.url.encodedPath) {
                "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/j1" -> respond(if (polls++ == 0) status("downloading", 40) else status("ready", 100, outputFilename = "Artist - Night Drive.mp3"),
                    headers = json)
                "/api/job/j1/file" -> respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> null
            }
        }
        try {
            fixture.downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.DEVICE)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { fixture.downloads.completed.first() }
            fixture.downloads.download(track, ScGateDestination.CLIENT)
            val job = withTimeout(10_000) { completed.await() }
            assertEquals(SoundCloudJobState.COMPLETE, job.state)
            val saved = fixture.directory.resolve("SoundCloud/Artist - Night Drive.mp3")
            assertTrue(Files.exists(saved))
            assertEquals(audio.size.toLong(), Files.size(saved))
            val imported = fixture.library.tracks().single()
            assertEquals(imported.id, job.trackId)
            assertEquals(SourceKind.LOCAL, imported.sources.single().kind)
            assertTrue("POST /api/job" in fixture.requests && "GET /api/job/j1/file" in fixture.requests)
        } finally { fixture.close() }
    }

    @Test
    fun aMissingGateCanUseTheSoundCloudStreamInstead() = runBlocking {
        var linked: String? = null
        var started = false
        val fixture = fixture { request ->
            when (request.url.encodedPath) {
                "/api/job" -> respond("""{"jobId":"j1","started":false,"needsHypedditUrl":true}""", headers = json)
                "/api/job/j1/hypeddit" -> {
                    linked = request.body.toByteArray().decodeToString()
                    respond("""{"success":true}""", headers = json)
                }
                "/api/job/j1/start" -> { started = true; respond("""{"success":true}""", headers = json) }
                "/api/job/j1" -> respond(if (started) status("ready", 100, outputFilename = "Artist - Night Drive.mp3") else status("waiting_hypeddit"),
                    headers = json)
                "/api/job/j1/file" -> respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> null
            }
        }
        try {
            fixture.downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.ASK)
            val waiting = fixture.downloads.download(track, ScGateDestination.CLIENT)
            assertEquals(SoundCloudJobState.NEEDS_LINK, waiting.state)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { fixture.downloads.completed.first() }
            fixture.downloads.useStream(waiting.id)
            assertEquals(SoundCloudJobState.COMPLETE, withTimeout(10_000) { completed.await() }.state)
            assertTrue(linked.orEmpty().contains(track.url))
        } finally { fixture.close() }
    }

    @Test
    fun aJobForgottenByARestartedServerIsLostAndRetryStartsANewOne() = runBlocking {
        var created = 0
        val fixture = fixture { request ->
            when (request.url.encodedPath) {
                "/api/job" -> respond("""{"jobId":"j${++created}","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/j1" -> respond("""{"error":"Job not found"}""", HttpStatusCode.NotFound, json)
                "/api/job/j2" -> respond(status("ready", 100, outputFilename = "Artist - Night Drive.mp3"), headers = json)
                "/api/job/j2/file" -> respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> null
            }
        }
        try {
            fixture.downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.ASK)
            val job = fixture.downloads.download(track, ScGateDestination.CLIENT)
            val lost = withTimeout(10_000) { fixture.downloads.jobs.first { jobs -> jobs?.singleOrNull()?.state == SoundCloudJobState.LOST } }!!.single()
            assertEquals("j1", lost.remoteId)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { fixture.downloads.completed.first() }
            fixture.downloads.retry(job.id)
            val finished = withTimeout(10_000) { completed.await() }
            assertEquals("j2", finished.remoteId)
            assertEquals(2, created)
        } finally { fixture.close() }
    }

    @Test
    fun aSongOutsideTheIncludedFoldersJoinsTheLibraryOnceItsFolderIsAdded() = runBlocking {
        val fixture = fixture { request ->
            when (request.url.encodedPath) {
                "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/j1" -> respond(status("ready", 100, outputFilename = "Artist - Night Drive.mp3"), headers = json)
                "/api/job/j1/file" -> respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> null
            }
        }
        try {
            val preferences = MusicPreferences(fixture.store)
            preferences.update { it.copy(includeFolders = listOf(fixture.directory.resolve("elsewhere").toString())) }
            fixture.downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.DEVICE)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { fixture.downloads.completed.first() }
            fixture.downloads.download(track, ScGateDestination.CLIENT)
            val saved = withTimeout(10_000) { completed.await() }
            assertNull(saved.trackId)
            val folder = fixture.directory.resolve("SoundCloud").toString()
            assertEquals(folder, saved.excludedFolder)
            assertEquals(emptyList(), fixture.library.tracks())
            preferences.update { it.copy(includeFolders = it.includeFolders + folder) }
            val imported = async(start = CoroutineStart.UNDISPATCHED) { fixture.downloads.completed.first() }
            fixture.downloads.importSaved(saved.id)
            val added = withTimeout(10_000) { imported.await() }
            assertEquals(fixture.library.tracks().single().id, added.trackId)
            assertNull(added.excludedFolder)
        } finally { fixture.close() }
    }

    @Test
    fun aServerDownloadIsFoundByItsFileAfterTheMusicServerRescans() = runBlocking {
        val subsonic = { body: String -> """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome"$body}}""" }
        var scanned = false
        val directory = Files.createTempDirectory("soundcloud-server")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/capabilities" -> respond("""{"search":true,"libraryDestination":true}""", headers = json)
                path == "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                path == "/api/job/j1" -> respond(status("ready", 100, libraryFilename = "Artist - Night Drive.mp3"), headers = json)
                path == "/rest/startScan.view" -> { scanned = true; respond(subsonic(""), headers = json) }
                path == "/rest/getScanStatus.view" -> respond(subsonic(""","scanStatus":{"scanning":false}"""), headers = json)
                path == "/rest/ping.view" -> respond(subsonic(""), headers = json)
                path == "/rest/search3.view" -> respond(subsonic(if (scanned && request.url.parameters["songOffset"] == "0")
                    ""","searchResult3":{"song":[{"id":"old","title":"Old Song","artist":"Someone"},{"id":"new","title":"Night Drive","artist":"Artist"}]}"""
                    else ""","searchResult3":{}"""), headers = json)
                path == "/rest/getPlaylists.view" -> respond(subsonic(""","playlists":{}"""), headers = json)
                path == "/auth/login" -> respond("""{"token":"token"}""", headers = json)
                path == "/api/song" -> respond(if (request.url.parameters["_start"] == "0")
                    """[{"id":"old","path":"Spotify/Someone - Old Song.mp3","libraryId":1},{"id":"new","path":"SoundCloud/Artist - Night Drive.mp3","libraryId":1}]"""
                    else "[]", headers = json)
                else -> respond("""{"error":"Unexpected request $path"}""", HttpStatusCode.NotFound, json)
            }
        })
        val navidrome = NavidromeClient(http, "http://music.test", ServerCredentials("fixture", "fixture-password"))
        val downloads = SoundCloudDownloads(store, http, server = { navidrome }, serverChanges = NavidromeChanges(store, library, client = { navidrome }),
            pollMillis = 10)
        try {
            downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.SERVER)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { downloads.completed.first() }
            downloads.download(track, ScGateDestination.LIBRARY)
            val job = withTimeout(10_000) { completed.await() }
            assertTrue(scanned)
            val added = assertNotNull(library.findTrack(assertNotNull(job.trackId)))
            assertEquals("Night Drive", added.metadata.title)
            assertEquals("SoundCloud/Artist - Night Drive.mp3", added.sources.single().filePath)
        } finally { downloads.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
