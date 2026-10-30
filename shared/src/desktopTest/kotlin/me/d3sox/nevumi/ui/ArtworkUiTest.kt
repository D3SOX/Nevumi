package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import me.d3sox.nevumi.settings.MusicPreferences

@OptIn(ExperimentalTestApi::class)
class ArtworkUiTest {
    private fun image(color: Int): ByteArray {
        val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (x in 0..31) for (y in 0..31) bitmap.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
    }

    @Test
    fun returningToACoverKeepsTheDecodedImageVisibleWithoutReadingTheFileAgain() {
        val directory = Files.createTempDirectory("warm-artwork")
        val file = directory.resolve("cover.png").toFile().apply { writeBytes(image(0xE05040)) }
        val visible = androidx.compose.runtime.mutableStateOf(true)
        try {
            runComposeUiTest {
                setContent {
                    if (visible.value) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(64.dp)) {
                        MusicArtwork(file.path, "Cover", "Cover")
                    }
                }
                waitUntil(timeoutMillis = 3_000) {
                    onNodeWithContentDescription("Cover").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] } == Color(0xFFE05040)
                }
                runOnIdle { visible.value = false }
                waitForIdle()
                // Simulate storage being unavailable while a recycled row returns to the viewport.
                file.delete()
                runOnIdle { visible.value = true }
                waitForIdle()
                kotlin.test.assertEquals(Color(0xFFE05040), onNodeWithContentDescription("Cover").captureToImage().toPixelMap().let {
                    it[it.width / 2, it.height / 2]
                })
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun replacingAndUndoingEmbeddedArtworkUpdatesTheVisibleCoverWithoutRestarting() {
        val directory = Files.createTempDirectory("edited-artwork-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val audio = directory.resolve("song.flac")
        Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
            "Fixture Album", "01 Northern Light.flac"), audio)
        val red = directory.resolve("red.png").toFile().apply { writeBytes(image(0xE05040)) }
        org.jaudiotagger.audio.AudioFileIO.read(audio.toFile()).also {
            it.tag.setField(org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(red)); it.commit()
        }
        val sources = LocalFiles(library, artwork)
        val track = runBlocking { sources.scan(audio.toString()).single() }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = artwork,
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString(), localSources = sources))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { controller.playTrack(track.id, listOf(track.id)).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                fun coverColor(): Color = onNodeWithContentDescription("Album artwork: Northern Light")
                    .captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFFE05040) }
                runBlocking { controller.replaceArtwork(track.id, image(0x3050E0)).join() }
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFF3050E0) }
                runBlocking { controller.undoFileEdit(controller.state.value.fileEdits.single().id).join() }
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFFE05040) }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun artworkColorsFollowTheSelectedTrack() {
        val directory = Files.createTempDirectory("artwork-colors")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val tracks = runBlocking {
            listOf("Red Cover" to 0x00E05040, "Blue Cover" to 0x003050E0).map { (title, color) ->
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
                for (x in 0..31) for (y in 0..31) image.setRGB(x, y, color)
                val output = ByteArrayOutputStream()
                ImageIO.write(image, "png", output)
                val track = library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL,
                    Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                        "Fixture Album", "01 Northern Light.flac").toString()))
                artwork.save(track.id, output.toByteArray())
                track
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = MusicPreferences(store), artwork = artwork)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Appearance").performScrollTo().performClick()
                onNodeWithContentDescription("Theme").performClick()
                onNodeWithText("Light").performClick()
                onNodeWithContentDescription("Colors").performClick()
                onNodeWithText("Artwork colors").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                runBlocking { controller.playTrack(tracks[0].id, listOf(tracks[0].id), 0).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join() }
                onNodeWithTag("mini-player").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithContentDescription("Album artwork: Red Cover").fetchSemanticsNodes().isNotEmpty()
                }
                fun queueColor(): Color = onNodeWithContentDescription("Queue").captureToImage().toPixelMap().let { it[it.width / 2, 5] }
                var red = Color.Unspecified
                waitUntil(timeoutMillis = 3_000) { val color = queueColor(); (color == red).also { red = color } }
                runBlocking { controller.playTrack(tracks[1].id, listOf(tracks[1].id), 0).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join() }
                waitUntil(timeoutMillis = 3_000) { queueColor() != red }
                assertNotEquals(red, queueColor())
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun nowPlayingDisplaysTheRetainedAlbumCover() {
        val directory = Files.createTempDirectory("artwork-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0x00E05040)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        runBlocking {
            val track = library.importTrack(TrackMetadata("Artwork Fixture"), TrackSource("artwork-fixture", SourceKind.LOCAL,
                Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                    "Fixture Album", "01 Northern Light.flac").toString()))
            artwork.save(track.id, output.toByteArray())
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = artwork)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                onNodeWithText("Artwork Fixture").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join() }
                onNodeWithTag("mini-player").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithContentDescription("Album artwork: Artwork Fixture").fetchSemanticsNodes().isNotEmpty()
                }
                val pixels = onNodeWithContentDescription("Album artwork: Artwork Fixture").captureToImage().toPixelMap()
                assertEquals(Color(0xFFE05040), pixels[pixels.width / 2, pixels.height / 2])
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
