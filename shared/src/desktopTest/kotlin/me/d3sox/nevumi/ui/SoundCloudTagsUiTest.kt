package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
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
class SoundCloudTagsUiTest {
    private fun cover(color: Int): ByteArray {
        val image = java.awt.image.BufferedImage(300, 300, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { paint = java.awt.Color(color); fillRect(0, 0, 300, 300); dispose() }
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun ComposeUiTest.snapshot(name: String) = System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
        val pixels = onNodeWithTag("app-window").captureToImage().toPixelMap()
        val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        javax.imageio.ImageIO.write(image, "png", Path.of(directory).also(Files::createDirectories).resolve("$name.png").toFile())
    }

    @Test fun aDownloadInTheChosenBrowserWaitsForItsTagsWhichAreCheckedLikeInTheWebInterface() {
        val directory = Files.createTempDirectory("soundcloud-tags-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.mp3")
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val tagged = java.util.concurrent.atomic.AtomicReference<String?>()
        val created = java.util.concurrent.atomic.AtomicReference<String?>()
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/capabilities" -> respond("""{"search":true,"libraryDestination":false,"browserModes":["headless","headed"],"browserViewUrl":null}""",
                    headers = json)
                "/api/job" -> {
                    created.set(request.body.toByteArray().decodeToString())
                    respond("""{"jobId":"j1","started":true,"needsHypedditUrl":false}""", headers = json)
                }
                "/api/job/j1" -> respond(if (tagged.get() == null) """{"progress":{"stage":"waiting_metadata","message":"Check the tags","percent":100},
                    "defaultMetadata":{"title":"Other - Night Swim [FREE DL]","artist":"Other","album":"","genre":"House"},
                    "existingMetadata":{"title":"Night Swim","artist":"Other","album":"Swim EP"},"hasArtwork":true,"hasExistingArtwork":true,
                    "downloadFilename":"Night Swim [2].mp3","outputFormat":"mp3-320"}"""
                    else """{"progress":{"stage":"ready","message":"File ready","percent":100},"outputFilename":"Other - Night Swim.mp3"}""", headers = json)
                "/api/job/j1/artwork" -> respond(cover(0xFF7043), headers = headersOf(HttpHeaders.ContentType, "image/png"))
                "/api/job/j1/existing-artwork" -> respond(cover(0x1E88E5), headers = headersOf(HttpHeaders.ContentType, "image/png"))
                "/api/job/j1/metadata" -> {
                    tagged.set(request.body.toByteArray().decodeToString())
                    respond("""{"success":true}""", headers = json)
                }
                "/api/job/j1/file" -> respond(Files.readAllBytes(audio), headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                else -> respond("""{"error":"Unexpected request"}""", HttpStatusCode.NotFound, json)
            }
        })
        val downloads = SoundCloudDownloads(store, http, files = DirectorySoundCloudFiles(directory.resolve("SoundCloud").toString()),
            localSources = LocalFiles(library), pollMillis = 10)
        runBlocking { downloads.connect("http://sc-gate.test", ScGateFormat.MP3_320, SoundCloudDestination.DEVICE, reviewTags = true) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = LocalFiles(library),
            preferences = MusicPreferences(store), soundCloud = downloads)
        val track = SoundCloudTrack(2, "https://soundcloud.com/other/night-swim", "Night Swim", "Other", "other", source = SoundCloudSource.GATE)
        val picked = cover(0x43A047)
        try {
            runSkikoComposeUiTest(size = Size(420f, 1400f)) {
                setContent { MusicPlayerApp(controller, artworkPicker = ArtworkPicker { picked }) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady && controller.state.value.soundCloud != null }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("SoundCloud").performScrollTo().performClick()
                onNodeWithText("Gate browser").performScrollTo().performClick()
                onNodeWithText("Visible window").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.soundCloud?.browser == ScGateBrowser.HEADED }
                // This server has no browser viewer.
                onNodeWithText("Set BROWSER_VIEW_URL on sc-gate-dl to watch its browser from Nevumi.").performScrollTo().assertIsDisplayed()
                snapshot("soundcloud-settings")
                controller.downloadFromSoundCloud(track, ScGateDestination.CLIENT)
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Open downloads").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Open downloads").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Check tags: Night Swim").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("Check tags: Night Swim").performClick()
                onNodeWithText("The file already has tags").assertIsDisplayed()
                onNodeWithText("Remove promo text").performScrollTo().performClick()
                onNodeWithText("Remove promo text").assertDoesNotExist()
                onNodeWithContentDescription("Copy album into the form").performClick()
                onNodeWithText("Change cover").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Reset cover").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("File name: Other - Night Swim.mp3").performScrollTo().assertIsDisplayed()
                snapshot("soundcloud-tags")
                onNodeWithText("Write tags").performClick()
                waitUntil(timeoutMillis = 10_000) { controller.state.value.soundCloudJobs.singleOrNull()?.state == SoundCloudJobState.COMPLETE }
            }
            val job = Json.parseToJsonElement(requireNotNull(created.get())).jsonObject
            assertEquals("headed", job.getValue("browserMode").jsonPrimitive.content)
            assertTrue(job.getValue("reviewTags").jsonPrimitive.boolean)
            val form = requireNotNull(tagged.get())
            // The cleaned title, the file's album and the picked cover go to sc-gate-dl.
            assertTrue("\r\n\r\nNight Swim\r\n" in form && "[FREE DL]" !in form, form)
            assertTrue("\r\n\r\nSwim EP\r\n" in form && "filename=\"cover.png\"" in form, form)
            assertTrue(Files.exists(directory.resolve("SoundCloud/Other - Night Swim.mp3")))
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
