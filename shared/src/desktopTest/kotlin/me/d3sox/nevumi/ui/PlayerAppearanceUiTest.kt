package me.d3sox.nevumi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.settings.ColorMode
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.math.abs

@OptIn(ExperimentalTestApi::class)
class PlayerAppearanceUiTest {
    @Test
    fun artworkAccentColorsTheSelectedLibraryPageAndMoreIndicator() {
        val directory = Files.createTempDirectory("artwork-navigation-color")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val preferences = MusicPreferences(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac").toString()
        val track = runBlocking {
            preferences.update { it.copy(themeMode = ThemeMode.DARK, colors = ColorMode.ARTWORK) }
            library.importTrack(TrackMetadata("Red artwork"), TrackSource("red", SourceKind.LOCAL, audio))
        }
        val red = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).also { image ->
            for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0xE05040)
        }
        runBlocking { artwork.save(track.id, ByteArrayOutputStream().also { ImageIO.write(red, "png", it) }.toByteArray()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            artwork = artwork, preferences = preferences)
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(360.dp, 740.dp)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join() }
                // Wait for the visible playback accent to confirm the artwork palette has arrived.
                waitUntil(timeoutMillis = 3_000) {
                    val pixels = onNode(hasContentDescription("Play") and hasClickAction()).captureToImage().toPixelMap()
                    val color = pixels[pixels.width / 2, pixels.height / 4]
                    color.red > color.blue + .05f
                }
                fun assertWarmIndicator(label: String) {
                    val page = onNode(hasText(label) and hasAnyAncestor(hasTestTag("library-navigation"))).assertIsSelected()
                    val pixels = page.captureToImage().toPixelMap()
                    val color = pixels[pixels.width / 4, pixels.height * 3 / 10]
                    assertTrue(color.red > color.blue + .05f, "Selected $label uses the red artwork accent: $color")
                }
                assertWarmIndicator("Songs")
                onNodeWithText("More").performClick()
                onNodeWithText("Folders").performClick()
                assertWarmIndicator("More")
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun artworkGlowLightsTheBackgroundWithoutTintingTextOrControls() {
        val directory = Files.createTempDirectory("player-glow-layer")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val preferences = MusicPreferences(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val track = runBlocking {
            preferences.update { it.copy(themeMode = ThemeMode.DARK, amoledBlack = true) }
            library.importTrack(TrackMetadata("Glow track", "Glow artist"), TrackSource("glow", SourceKind.LOCAL, audio))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            artwork = artwork, preferences = preferences.withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(360.dp, 740.dp)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                val foreground = listOf(
                    onNode(hasText("Glow track") and hasAnyAncestor(hasTestTag("now-playing")), useUnmergedTree = true),
                    onNode(hasText("Glow artist") and hasAnyAncestor(hasTestTag("now-playing")), useUnmergedTree = true),
                    onNodeWithContentDescription("Collapse"),
                    onNodeWithContentDescription("Favorite"),
                )
                fun brightest(node: SemanticsNodeInteraction): Color {
                    val pixels = node.captureToImage().toPixelMap()
                    return (0 until pixels.width).flatMap { x -> (0 until pixels.height).map { y -> pixels[x, y] } }
                        .maxBy { it.green + it.blue }
                }
                val originalColors = foreground.map(::brightest)
                val red = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).also { image ->
                    for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0xE05040)
                }
                runBlocking { artwork.save(track.id, ByteArrayOutputStream().also { ImageIO.write(red, "png", it) }.toByteArray()) }
                // Observe the actual rendered background so the assertion cannot race the async glow.
                val titleY = foreground.first().fetchSemanticsNode().boundsInRoot.center.y.toInt()
                waitUntil(timeoutMillis = 3_000) {
                    val color = onRoot().captureToImage().toPixelMap()[8, titleY]
                    color.red > .1f && color.red > color.blue + .05f
                }
                foreground.forEachIndexed { index, node ->
                    val before = originalColors[index]
                    val after = brightest(node)
                    assertTrue(abs(after.red - before.red) < .01f && abs(after.green - before.green) < .01f &&
                        abs(after.blue - before.blue) < .01f, "The glow must stay behind foreground element $index: $before / $after")
                }
                val player = onNodeWithTag("now-playing")
                val glowY = titleY - player.fetchSemanticsNode().boundsInRoot.top.toInt()
                fun assertGlow() {
                    val color = player.captureToImage().toPixelMap()[8, glowY]
                    assertTrue(color.red > color.blue + .02f, "The artwork glow stays visible behind the track menu: $color")
                }
                mainClock.autoAdvance = false
                onNodeWithContentDescription("Track options: Glow track").performClick()
                mainClock.advanceTimeBy(400)
                onNodeWithTag("track-actions").assertIsDisplayed()
                assertGlow()
                onNodeWithContentDescription("Close song actions").performClick()
                mainClock.advanceTimeBy(96)
                assertGlow()
                mainClock.advanceTimeBy(400)
                onNodeWithTag("track-actions").assertDoesNotExist()
                assertGlow()
                mainClock.autoAdvance = true
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun coversFollowThePlayerAndOpeningALongQueueShowsTheCurrentOccurrence() {
        val directory = Files.createTempDirectory("player-appearance")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val tracks = runBlocking { (0..79).map { library.importTrack(TrackMetadata("Track $it"), TrackSource("local:$it", SourceKind.LOCAL, audio)) } }
        val red = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).also { image ->
            for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0xE05040)
        }
        runBlocking { artwork.save(tracks[70].id, ByteArrayOutputStream().also { ImageIO.write(red, "png", it) }.toByteArray()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = artwork, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { controller.playTrack(tracks[70].id, tracks.map { it.id }, 70).join(); controller.pause().join() }
                fun assertRed(description: String) {
                    waitUntil(timeoutMillis = 3_000) {
                        val cover = if (description.startsWith("Album artwork:"))
                            onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("player-artwork")), useUnmergedTree = true)
                            else onNodeWithContentDescription(description, useUnmergedTree = true)
                        cover.captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] } == Color(0xFFE05040)
                    }
                }
                assertRed("Mini-player artwork: Track 70")
                onNodeWithContentDescription("Album artwork: Track 70", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithContentDescription("Queue artwork: ${controller.state.value.queue.currentEntryId}", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("mini-player").performClick()
                onNodeWithTag("mini-player").assertDoesNotExist()
                assertRed("Album artwork: Track 70")
                onNodeWithContentDescription("Previous track").assertHasClickAction()
                onNodeWithText("Previous").assertDoesNotExist()
                onNodeWithContentDescription("Queue").performClick()
                val entry = controller.state.value.queue.currentEntryId!!
                assertRed("Queue mini-player artwork: Track 70")
                onNodeWithTag("queue-entry:$entry").assertIsDisplayed()
                assertRed("Queue artwork: $entry")
                onNode(hasContentDescription("Track options: Track 70") and hasAnyAncestor(hasTestTag("queue-entry:$entry"))).assertHasClickAction()
                onNodeWithText("Remove").assertDoesNotExist()
                onNodeWithContentDescription("Now playing").performClick()
                onNodeWithContentDescription("Collapse").performClick()
                onNodeWithTag("mini-player").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
