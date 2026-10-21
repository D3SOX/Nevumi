package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.soundcloud.*
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SoundCloudSearchUiTest {
    private fun cover(from: Int, to: Int): ByteArray {
        val image = java.awt.image.BufferedImage(300, 300, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(from), 300f, 300f, java.awt.Color(to)); fillRect(0, 0, 300, 300); dispose()
        }
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun ComposeUiTest.snapshot(name: String) = System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
        val pixels = onNodeWithTag("app-window").captureToImage().toPixelMap()
        val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        javax.imageio.ImageIO.write(image, "png", Path.of(directory).also(Files::createDirectories).resolve("$name.png").toFile())
    }

    @Test fun searchingSoundCloudMarksOwnedSongsAndSavesAPickedSongToThisDevice() {
        val directory = Files.createTempDirectory("soundcloud-search-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.mp3")
        runBlocking { library.importTrack(TrackMetadata("Night Drive", "Artist"), TrackSource("owned", SourceKind.LOCAL, audio.toString())) }
        val searches = mutableListOf<String>()
        val covers = mutableListOf<String>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/capabilities" -> respond("""{"search":true,"libraryDestination":false}""", headers = json)
                "/api/search" -> {
                    searches += request.url.parameters["q"].orEmpty()
                    respond("""{"results":[
                        {"id":1,"url":"https://soundcloud.com/artist/night-drive","title":"Night Drive","artist":"Artist","uploader":"artist","durationMs":201000,
                         "artworkUrl":"https://i1.sndcdn.com/artworks-1-t500x500.jpg","source":"download"},
                        {"id":2,"url":"https://soundcloud.com/other/night-swim","title":"Night Swim","artist":"Other","uploader":"other","durationMs":180000,
                         "artworkUrl":"https://i1.sndcdn.com/artworks-2-t500x500.jpg","source":"gate"}
                    ],"nextOffset":null}""", headers = json)
                }
                "/api/artwork" -> {
                    val url = request.url.parameters["url"].orEmpty()
                    covers += url
                    respond(if ("artworks-1" in url) cover(0x3A2A80, 0x1E88E5) else cover(0xFF7043, 0xAD1457), headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
                "/api/job" -> respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                "/api/job/j1" -> respond("""{"progress":{"stage":"ready","message":"Ready","percent":100},"outputFilename":"Other - Night Swim.mp3"}""", headers = json)
                "/api/job/j1/file" -> respond(Files.readAllBytes(audio), headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> respond("""{"error":"Unexpected request"}""", HttpStatusCode.NotFound, json)
            }
        })
        val downloads = SoundCloudDownloads(store, http, files = DirectorySoundCloudFiles(directory.resolve("SoundCloud").toString()),
            localSources = LocalFiles(library), pollMillis = 10)
        runBlocking { downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.ASK) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = LocalFiles(library),
            preferences = MusicPreferences(store), soundCloud = downloads)
        try {
            runSkikoComposeUiTest(size = Size(420f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady && controller.state.value.soundCloud != null }
                onNodeWithContentDescription("Search").performClick()
                onNodeWithContentDescription("Search your library").performTextInput("Night")
                onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
                    .performScrollToNode(hasTestTag("search-type:SOUNDCLOUD"))
                onNodeWithTag("search-type:SOUNDCLOUD").assertIsDisplayed()
                onNodeWithTag("search-soundcloud").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("SoundCloud song: Night Swim by Other").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(listOf("Night"), searches)
                // Covers come through sc-gate-dl at the size a row needs.
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("soundcloud-cover", useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
                assertEquals(setOf("https://i1.sndcdn.com/artworks-1-t300x300.jpg", "https://i1.sndcdn.com/artworks-2-t300x300.jpg"), covers.toSet())
                snapshot("soundcloud-search")
                onNode(hasText("In library") and hasAnyAncestor(hasContentDescription("SoundCloud song: Night Drive by Artist")), useUnmergedTree = true)
                    .assertExists()
                onAllNodesWithText("In library", useUnmergedTree = true).assertCountEquals(1)
                onNodeWithContentDescription("SoundCloud song: Night Swim by Other").performClick()
                onNodeWithText("Save to music server").assertDoesNotExist()
                snapshot("soundcloud-download-sheet")
                onNodeWithText("Save to this device").performClick()
                // The library publishes imported songs shortly after the job records its track.
                waitUntil(timeoutMillis = 10_000) {
                    val job = controller.state.value.soundCloudJobs.singleOrNull()
                    job?.state == SoundCloudJobState.COMPLETE && controller.state.value.tracks.any { it.id == job.trackId }
                }
                assertTrue(Files.exists(directory.resolve("SoundCloud/Other - Night Swim.mp3")))
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Added “Night Swim”").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Play").assertExists()
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
