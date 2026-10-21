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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
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
import kotlin.test.assertFalse
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

    private fun fixture(route: suspend (String) -> String = { it }, handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData?): Fixture {
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
        val downloads = SoundCloudDownloads(store, http, route, files = DirectorySoundCloudFiles(directory.resolve("SoundCloud").toString()),
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
    fun aDownloadInAVisibleBrowserWaitsForItsTagsAndIsSavedWithTheCheckedOnes() = runBlocking {
        val stage = java.util.concurrent.atomic.AtomicReference("gate")
        var created: kotlinx.serialization.json.JsonObject? = null
        var tagged: kotlinx.serialization.json.JsonObject? = null
        val fixture = fixture { request ->
            when (request.url.encodedPath) {
                "/api/capabilities" -> respond("""{"search":true,"libraryDestination":false,"browserModes":["headless","headed"],
                    "browserViewUrl":"http://{host}:6080/vnc.html?autoconnect=true"}""", headers = json)
                "/api/job" -> {
                    created = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                }
                "/api/job/j1" -> respond(when (stage.get()) {
                    "gate" -> """{"progress":{"stage":"handling_gates","message":"Opening the gate","percent":30,"browserActive":true},"browserMode":"headed"}"""
                    // Not every progress report repeats that the browser is open.
                    "comment" -> """{"progress":{"stage":"handling_gates","message":"Commenting","percent":40},"browserMode":"headed"}"""
                    "downloading" -> """{"progress":{"stage":"downloading","message":"Downloading","percent":57.5},"browserMode":"headed"}"""
                    "tags" -> """{"progress":{"stage":"waiting_metadata","message":"Check the tags","percent":100},"browserMode":"headed",
                        "defaultMetadata":{"title":"Night Drive [FREE DL]","artist":"Artist","album":"","genre":"House"},
                        "existingMetadata":{"title":"","artist":"","album":"","genre":""},"hasArtwork":true,"hasExistingArtwork":false,
                        "downloadFilename":"Night Drive [7].mp3"}"""
                    else -> status("ready", 100, outputFilename = "Artist - Night Drive (Edit).mp3")
                }, headers = json)
                "/api/job/j1/metadata" -> {
                    tagged = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    stage.set("ready")
                    respond("""{"success":true}""", headers = json)
                }
                "/api/job/j1/file" -> respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> null
            }
        }
        try {
            fixture.downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.DEVICE, ScGateBrowser.HEADED, reviewTags = true)
            val job = fixture.downloads.download(track, ScGateDestination.CLIENT)
            assertEquals("headed", created?.getValue("browserMode")?.jsonPrimitive?.content)
            assertEquals(true, created?.getValue("reviewTags")?.jsonPrimitive?.boolean)
            suspend fun waitFor(check: (SoundCloudJob) -> Boolean) = withTimeout(10_000) {
                fixture.downloads.jobs.first { jobs -> jobs?.singleOrNull()?.let(check) == true }
            }!!.single()
            assertTrue(waitFor { it.browser }.browser)
            assertEquals("http://sc-gate.test:6080/vnc.html?autoconnect=true", fixture.downloads.browserView(job.id))
            stage.set("comment")
            assertTrue(waitFor { it.message == "Commenting" }.browser)
            stage.set("downloading")
            val downloading = waitFor { it.message == "Downloading" }
            assertFalse(downloading.browser)
            assertEquals(58, downloading.percent)
            stage.set("tags")
            val waiting = waitFor { it.state == SoundCloudJobState.NEEDS_TAGS }
            val review = assertNotNull(waiting.review)
            assertEquals(ScGateTags("Night Drive [FREE DL]", "Artist", "", "House"), review.tags)
            // A file without tags of its own offers nothing to keep.
            assertNull(review.fileTags)
            assertEquals("Night Drive [7].mp3", review.fileName)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { fixture.downloads.completed.first() }
            fixture.downloads.finishTags(job.id, ScGateTagChoice(ScGateTags("Night Drive (Edit)", "Artist", "Night EP", "House")))
            val finished = withTimeout(10_000) { completed.await() }
            assertEquals(SoundCloudJobState.COMPLETE, finished.state)
            assertEquals(ScGateTags("Night Drive (Edit)", "Artist", "Night EP", "House"), finished.tags)
            assertEquals("Night Drive (Edit)", tagged?.getValue("title")?.jsonPrimitive?.content)
            assertEquals(true, tagged?.getValue("nameAsArtistTitle")?.jsonPrimitive?.boolean)
            assertTrue(Files.exists(fixture.directory.resolve("SoundCloud/Artist - Night Drive (Edit).mp3")))
        } finally { fixture.close() }
    }

    @Test
    fun theBrowserViewerGoesThroughTheTunnelWhenSoundCloudDownloadsUseIt() = runBlocking {
        val fixture = fixture(route = { if (it == "http://sc-gate.test:6080") "http://127.0.0.1:9/secret" else it }) { request ->
            when (request.url.encodedPath) {
                "/api/capabilities" -> respond("""{"search":true,"libraryDestination":false,"browserModes":["headless","headed"],
                    "browserViewUrl":"http://{host}:6080/vnc.html?autoconnect=true&resize=scale"}""", headers = json)
                "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/j1" -> respond(status("downloading", 40), headers = json)
                else -> null
            }
        }
        try {
            fixture.downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.DEVICE)
            assertEquals("http://{host}:6080/vnc.html?autoconnect=true&resize=scale", fixture.downloads.profile.value?.browserViewUrl)
            val job = fixture.downloads.download(track, ScGateDestination.CLIENT)
            assertEquals("http://127.0.0.1:9/secret/vnc.html?autoconnect=true&resize=scale", fixture.downloads.browserView(job.id))
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
    fun aJobKeepsUsingTheServerThatStartedItAfterSettingsSwitchServers() = runBlocking {
        val hosts = Collections.synchronizedSet(mutableSetOf<String>())
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val fixture = fixture { request ->
            if (request.url.encodedPath.startsWith("/api/job")) hosts += request.url.host
            when (request.url.encodedPath) {
                "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/j1" -> respond(if (ready.get()) status("ready", 100, outputFilename = "Artist - Night Drive.mp3") else status("downloading", 40),
                    headers = json)
                "/api/job/j1/file" -> respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> null
            }
        }
        try {
            fixture.downloads.connect("http://first.test", ScGateFormat.MP3_320, SoundCloudDestination.ASK)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { fixture.downloads.completed.first() }
            fixture.downloads.download(track, ScGateDestination.CLIENT)
            fixture.downloads.connect("http://second.test", ScGateFormat.MP3_320, SoundCloudDestination.ASK)
            ready.set(true)
            assertEquals(SoundCloudJobState.COMPLETE, withTimeout(10_000) { completed.await() }.state)
            assertEquals(setOf("first.test"), hosts)
        } finally { fixture.close() }
    }

    @Test
    fun retryingACanceledJobStartsANewOne() = runBlocking {
        var created = 0
        var resumed = false
        val fixture = fixture { request ->
            when (request.url.encodedPath) {
                "/api/job" -> respond("""{"jobId":"j${++created}","started":${created > 1},"needsHypedditUrl":${created == 1}}""", headers = json)
                "/api/job/j1/cancel" -> respond("""{"success":true}""", headers = json)
                // sc-gate-dl cannot resume a job that was canceled before it had a download link.
                "/api/job/j1/start" -> { resumed = true; respond("""{"error":"Gate URL not set"}""", HttpStatusCode.BadRequest, json) }
                "/api/job/j2" -> respond(status("ready", 100, outputFilename = "Artist - Night Drive.mp3"), headers = json)
                "/api/job/j2/file" -> respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> null
            }
        }
        try {
            fixture.downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.ASK)
            val job = fixture.downloads.download(track, ScGateDestination.CLIENT)
            assertEquals(SoundCloudJobState.NEEDS_LINK, job.state)
            fixture.downloads.cancel(job.id)
            val completed = async(start = CoroutineStart.UNDISPATCHED) { fixture.downloads.completed.first() }
            fixture.downloads.retry(job.id)
            val finished = withTimeout(10_000) { completed.await() }
            assertEquals("j2", finished.remoteId)
            assertEquals(false, resumed)
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
    fun aServerDownloadWaitsForTheMusicServerItWasSavedTo() = runBlocking {
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        val directory = Files.createTempDirectory("soundcloud-music-server")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/capabilities" -> respond("""{"search":true,"libraryDestination":true}""", headers = json)
                "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/j1" -> respond(if (ready.get()) status("ready", 100, libraryFilename = "Artist - Night Drive.mp3") else status("downloading", 40),
                    headers = json)
                else -> respond("""{"error":"Unexpected request"}""", HttpStatusCode.NotFound, json)
            }
        })
        val credentials = ServerCredentials("fixture", "fixture-password")
        var current = NavidromeClient(http, "http://first.test", credentials)
        val downloads = SoundCloudDownloads(store, http, server = { current },
            serverChanges = NavidromeChanges(store, MusicLibrary(store), client = { current }), pollMillis = 10)
        try {
            downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.SERVER)
            downloads.download(track, ScGateDestination.LIBRARY)
            current = NavidromeClient(http, "http://second.test", credentials)
            ready.set(true)
            val failed = withTimeout(10_000) { downloads.jobs.first { it?.singleOrNull()?.state == SoundCloudJobState.FAILED } }!!.single()
            assertEquals("Connect to the music server this song was saved to, then retry", failed.message)
            assertEquals("Artist - Night Drive.mp3", failed.fileName)
        } finally { downloads.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aRestartedServerDownloadGoesToTheServersConnectedNowAndKeepsAnOfflineRequest() = runBlocking {
        val directory = Files.createTempDirectory("soundcloud-restart")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/capabilities" -> respond("""{"search":true,"libraryDestination":true}""", headers = json)
                "/api/job" -> respond("""{"jobId":"${request.url.host}","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/first.test/cancel" -> respond("""{"success":true}""", headers = json)
                else -> if (request.url.encodedPath.startsWith("/api/job/")) respond(status("downloading", 40), headers = json)
                    else respond("""{"error":"Unexpected request"}""", HttpStatusCode.NotFound, json)
            }
        })
        val credentials = ServerCredentials("fixture", "fixture-password")
        var music = NavidromeClient(http, "http://music-one.test", credentials)
        val downloads = SoundCloudDownloads(store, http, server = { music }, pollMillis = 10)
        try {
            downloads.connect("http://first.test", ScGateFormat.MP3_320, SoundCloudDestination.SERVER)
            val job = downloads.download(track, ScGateDestination.LIBRARY)
            assertTrue(downloads.download(track, ScGateDestination.LIBRARY, keepOffline = true).keepOffline)
            downloads.cancel(job.id)
            downloads.connect("http://second.test", ScGateFormat.MP3_320, SoundCloudDestination.SERVER)
            music = NavidromeClient(http, "http://music-two.test", credentials)
            downloads.retry(job.id)
            val restarted = assertNotNull(downloads.jobs.first { it?.singleOrNull()?.remoteId == "second.test" }).single()
            assertEquals("http://second.test", restarted.baseUrl)
            assertEquals(music.serverId, restarted.musicServerId)
            assertTrue(restarted.keepOffline)
        } finally { downloads.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anOlderSongWithTheSameTagsDoesNotCompleteAServerDownload() = runBlocking {
        val subsonic = { body: String -> """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"gonic"$body}}""" }
        val directory = Files.createTempDirectory("soundcloud-unindexed")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        // The scan finishes without the new file, as when sc-gate-dl's folder is outside the music server's library.
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/capabilities" -> respond("""{"search":true,"libraryDestination":true}""", headers = json)
                "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/j1" -> respond(status("ready", 100, libraryFilename = "Artist - Night Drive.mp3"), headers = json)
                "/rest/startScan.view", "/rest/ping.view" -> respond(subsonic(""), headers = json)
                "/rest/getScanStatus.view" -> respond(subsonic(""","scanStatus":{"scanning":false}"""), headers = json)
                "/rest/search3.view" -> respond(subsonic(if (request.url.parameters["songOffset"] == "0")
                    ""","searchResult3":{"song":[{"id":"old","title":"Night Drive","artist":"Artist"}]}""" else ""","searchResult3":{}"""), headers = json)
                "/rest/getPlaylists.view" -> respond(subsonic(""","playlists":{}"""), headers = json)
                else -> respond("""{"error":"Unexpected request"}""", HttpStatusCode.NotFound, json)
            }
        })
        val navidrome = NavidromeClient(http, "http://music.test", ServerCredentials("fixture", "fixture-password"))
        val changes = NavidromeChanges(store, library, client = { navidrome })
        val downloads = SoundCloudDownloads(store, http, server = { navidrome }, serverChanges = changes, pollMillis = 10)
        try {
            changes.refreshLibrary(navidrome)
            downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.SERVER)
            downloads.download(track, ScGateDestination.LIBRARY)
            val failed = withTimeout(10_000) { downloads.jobs.first { it?.singleOrNull()?.state == SoundCloudJobState.FAILED } }!!.single()
            assertEquals("The music server has not indexed the song yet. Retry in a moment.", failed.message)
            assertNull(failed.trackId)
        } finally { downloads.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    /**
     * Saves [track] to a mocked music server of [type], with [title] written after checking the tags when it is given;
     * returns the imported song and whether Navidrome's own API was used.
     */
    private suspend fun importFromServer(type: String, title: String? = null): Pair<Track, Boolean> = coroutineScope {
        val subsonic = { body: String -> """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"$type"$body}}""" }
        var scanned = false
        var native = false
        var tagged = title == null
        val directory = Files.createTempDirectory("soundcloud-server")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/api/capabilities" -> respond("""{"search":true,"libraryDestination":true}""", headers = json)
                path == "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                path == "/api/job/j1" -> respond(if (tagged) status("ready", 100, libraryFilename = "Artist - ${title ?: "Night Drive"}.mp3")
                    else status("waiting_metadata", 100), headers = json)
                path == "/api/job/j1/metadata" -> { tagged = true; respond("""{"success":true}""", headers = json) }
                path == "/rest/startScan.view" -> { scanned = true; respond(subsonic(""), headers = json) }
                path == "/rest/getScanStatus.view" -> respond(subsonic(""","scanStatus":{"scanning":false}"""), headers = json)
                path == "/rest/ping.view" -> respond(subsonic(""), headers = json)
                path == "/rest/search3.view" -> respond(subsonic(if (scanned && request.url.parameters["songOffset"] == "0")
                    ""","searchResult3":{"song":[{"id":"old","title":"Old Song","artist":"Someone"},{"id":"new","title":"${title ?: "Night Drive"}","artist":"Artist"}]}"""
                    else ""","searchResult3":{}"""), headers = json)
                path == "/rest/getPlaylists.view" -> respond(subsonic(""","playlists":{}"""), headers = json)
                path == "/auth/login" -> { native = true; respond("""{"token":"token"}""", headers = json) }
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
            val started = downloads.download(track, ScGateDestination.LIBRARY, reviewTags = title != null)
            if (title != null) {
                withTimeout(10_000) { downloads.jobs.first { it?.singleOrNull()?.state == SoundCloudJobState.NEEDS_TAGS } }
                downloads.finishTags(started.id, ScGateTagChoice(ScGateTags(title, "Artist")))
            }
            val job = withTimeout(10_000) { completed.await() }
            assertTrue(scanned)
            assertNotNull(library.findTrack(assertNotNull(job.trackId))) to native
        } finally { downloads.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aServerDownloadIsFoundByItsFileAfterTheMusicServerRescans() = runBlocking {
        val (added, native) = importFromServer("navidrome")
        assertTrue(native)
        assertEquals("Night Drive", added.metadata.title)
        assertEquals("SoundCloud/Artist - Night Drive.mp3", added.sources.single().filePath)
    }

    @Test
    fun otherSubsonicServersFindTheServerDownloadByItsTags() = runBlocking {
        val (added, native) = importFromServer("gonic")
        assertFalse(native)
        assertEquals("Night Drive", added.metadata.title)
    }

    @Test
    fun otherSubsonicServersFindAServerDownloadByItsCheckedTags() = runBlocking {
        val (added, native) = importFromServer("gonic", title = "Night Drive (Edit)")
        assertFalse(native)
        assertEquals("Night Drive (Edit)", added.metadata.title)
    }
}
