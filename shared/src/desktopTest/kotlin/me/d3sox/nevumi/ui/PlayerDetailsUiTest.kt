package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap

@OptIn(ExperimentalTestApi::class)
class PlayerDetailsUiTest {
    @Test fun openingTheQueueKeepsTheSeekBarAndTimestampAtTheCurrentPosition() {
        val directory = Files.createTempDirectory("queue-position-transition")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("Position fixture", durationMs = 3_000), TrackSource("position", SourceKind.LOCAL, audio)) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join(); controller.seek(1_000).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs == 1_000L }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Elapsed time").assertTextEquals("0:01")
                mainClock.autoAdvance = false
                onNodeWithContentDescription("Queue").performClick()
                for (millis in listOf(16L, 48L, 96L)) {
                    mainClock.advanceTimeBy(millis)
                    onNodeWithContentDescription("Elapsed time").assertTextEquals("0:01")
                    val progress = onNodeWithContentDescription("Playback position").fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo].current
                    assertTrue(progress in 950f..1050f, "Queue transition must not move the seekbar to zero: $progress")
                }
                mainClock.autoAdvance = true
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun nowPlayingMovesItsLargeCoverToTheTrackMenuAndBack() {
        val directory = Files.createTempDirectory("playing-cover-menu")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("Cover menu", durationMs = 3_000), TrackSource("cover-menu", SourceKind.LOCAL, audio)) }
        val artwork = me.d3sox.nevumi.artwork.ArtworkLibrary(directory.resolve("artwork").toString())
        val red = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB).also { image ->
            for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0xE05040)
        }
        runBlocking { artwork.save(track.id, java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(red, "png", it) }.toByteArray()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = artwork, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                mainClock.advanceTimeBy(1_000)
                fun screenBounds(node: SemanticsNodeInteraction): androidx.compose.ui.geometry.Rect {
                    val semantics = node.fetchSemanticsNode()
                    return semantics.boundsInRoot.translate(semantics.positionOnScreen - semantics.positionInRoot)
                }
                val cover = onNodeWithTag("player-artwork", useUnmergedTree = true)
                val origin = screenBounds(cover)
                fun assertCoverInAppFrame() {
                    val pixels = onNodeWithTag("app-window").captureToImage().toPixelMap()
                    val visible = (0 until pixels.height step 2).sumOf { y ->
                        (0 until pixels.width step 2).count { x ->
                            val color = pixels[x, y]
                            kotlin.math.abs(color.red - 224f / 255) < .015f && kotlin.math.abs(color.green - 80f / 255) < .015f &&
                                kotlin.math.abs(color.blue - 64f / 255) < .015f
                        }
                    }
                    assertTrue(visible > 100, "The main window must keep rendering the cover through menu transitions")
                }
                assertCoverInAppFrame()
                mainClock.autoAdvance = false
                onNodeWithContentDescription("Track options: Cover menu").performClick()
                mainClock.advanceTimeByFrame()
                val moving = onNodeWithTag("track-action-artwork", useUnmergedTree = true)
                val start = screenBounds(moving)
                assertTrue((start.center - origin.center).getDistance() < 3f && kotlin.math.abs(start.width - origin.width) < 3f,
                    "The track menu must start with the large playing cover: $origin / $start")
                mainClock.advanceTimeBy(400)
                assertCoverInAppFrame()
                val opened = screenBounds(moving)
                assertTrue(opened.width < origin.width / 2)
                onNodeWithContentDescription("Close song actions").performClick()
                mainClock.advanceTimeBy(96)
                val returning = screenBounds(moving)
                assertCoverInAppFrame()
                assertTrue(returning.width > opened.width + 2f && returning.width < origin.width - 2f)
                mainClock.advanceTimeBy(400)
                onNodeWithTag("track-actions").assertDoesNotExist()
                assertTrue((screenBounds(cover).center - origin.center).getDistance() < 2f)
                assertEquals(track.id, controller.state.value.playback.trackId)
                assertFalse(controller.state.value.playback.playing)
                mainClock.autoAdvance = true
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun audioInformationIsCenteredAtTheBottomAndCanBeHiddenInPlaybackSettings() {
        val directory = Files.createTempDirectory("playing-audio-info")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "audio-info.wav").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("Audio info fixture", durationMs = 3_000),
            TrackSource("audio-info", SourceKind.LOCAL, audio)) }
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(360.dp, 660.dp)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.audioInfo != null }
                val label = onNodeWithText("WAV · 706 kb/s · 44.1 kHz").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val output = onNodeWithContentDescription("Audio output").fetchSemanticsNode().boundsInRoot
                val queue = onNodeWithContentDescription("Queue").fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(label.center.x - (output.center.x + queue.center.x) / 2) < 2f)
                onNodeWithContentDescription("App volume").performScrollTo().assertIsDisplayed()
                onNodeWithContentDescription("Collapse").performClick()
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithContentDescription("Show audio info").performScrollTo().performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("mini-player").performClick()
                onNodeWithText("WAV · 706 kb/s · 44.1 kHz").assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun playerToolbarCentersAudioInfoAndMovesItBelowForNarrowWidthsAndLargeText() {
        val directory = Files.createTempDirectory("player-toolbar")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val lyrics = object : me.d3sox.nevumi.lyrics.LyricsLibrary {
            override suspend fun lyrics(track: Track, online: Boolean): me.d3sox.nevumi.lyrics.Lyrics? = null
        }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), lyrics = lyrics)
        var width by mutableStateOf(540.dp)
        var fontScale by mutableFloatStateOf(1f)
        var effectsOpened = 0
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                        MaterialTheme {
                            PlayerToolbar(controller, "WAV · 706 kb/s · 44.1 kHz", onOutput = {}, onVisualizer = {},
                                onAudioEffects = { effectsOpened++ }, onPlaybackSettings = {}, onQueue = {}, modifier = Modifier.width(width).testTag("test-toolbar"))
                        }
                    }
                }
                fun bounds(label: String) = onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                fun assertInfo(inline: Boolean) {
                    val info = onNodeWithTag("playing-audio-info").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val output = bounds("Audio output")
                    val queue = bounds("Queue")
                    assertTrue(kotlin.math.abs(info.center.x - (output.center.x + queue.center.x) / 2) < 2f)
                    if (inline) {
                        assertTrue(info.left >= bounds("Audio effects").right && info.right <= bounds("Playback settings").left)
                        assertTrue(kotlin.math.abs(info.center.y - output.center.y) < 2f)
                    } else assertTrue(info.top >= maxOf(output.bottom, queue.bottom))
                }
                bounds("Lyrics")
                assertInfo(inline = true)
                onNodeWithContentDescription("Audio effects").performClick()
                runOnIdle { assertEquals(1, effectsOpened); width = 320.dp }
                assertInfo(inline = false)
                runOnIdle { width = 440.dp }
                assertInfo(inline = true)
                runOnIdle { fontScale = 2f }
                assertInfo(inline = false)
                runOnIdle { fontScale = 1f; width = 272.dp }
                val toolbar = onNodeWithTag("test-toolbar").fetchSemanticsNode().boundsInRoot
                for (label in listOf("Audio output", "MilkDrop visualizer", "Audio effects", "Playback settings", "Lyrics", "Queue")) {
                    val node = onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
                    val touch = node.touchBoundsInRoot
                    assertTrue(touch.width >= 48f && touch.height >= 48f, "$label keeps its touch target: $touch")
                    assertTrue(touch.left >= toolbar.left && touch.right <= toolbar.right,
                        "$label stays within the toolbar")
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun titlesArtistsAndTrackButtonsFollowTheArtworkAndReturnWhenASwipeIsCancelled() {
        val directory = Files.createTempDirectory("playing-details-swipe")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "waveform-long.flac").toString()
        val tracks = runBlocking { listOf("Previous", "Current", "Next").map { title ->
            library.importTrack(TrackMetadata("$title song", "$title artist", durationMs = 600_000),
                TrackSource(title, SourceKind.LOCAL, audio))
        } }
        val playlists = Playlists(store)
        runBlocking { library.setFavorite(tracks[2].id, true); playlists.create("Next playlist", listOf(tracks[2].id)) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(tracks[1].id, tracks.map { it.id }, 1).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                mainClock.advanceTimeBy(1_000)
                fun currentTitle() = onNode(hasText("Current song") and hasAnyAncestor(hasTestTag("now-playing")))
                fun currentArtist() = onNode(hasText("Current artist") and hasAnyAncestor(hasTestTag("now-playing")))
                val originalTitle = currentTitle().fetchSemanticsNode().boundsInRoot
                val originalArtist = currentArtist().fetchSemanticsNode().boundsInRoot
                val originalFavorite = onNodeWithContentDescription("Favorite").fetchSemanticsNode().boundsInRoot
                val originalPlaylist = onNodeWithContentDescription("Manage playlists").fetchSemanticsNode().boundsInRoot
                mainClock.autoAdvance = false
                onNodeWithTag("player-artwork", useUnmergedTree = true).performTouchInput {
                    down(center); moveBy(androidx.compose.ui.geometry.Offset(-160f, 0f), 300)
                }
                mainClock.advanceTimeByFrame()
                val movedTitle = currentTitle().fetchSemanticsNode().boundsInRoot
                val movedArtist = currentArtist().fetchSemanticsNode().boundsInRoot
                assertTrue(movedTitle.left < originalTitle.left - 20f || movedTitle.right < originalTitle.right - 20f)
                assertTrue(movedArtist.left < originalArtist.left - 20f || movedArtist.right < originalArtist.right - 20f)
                val currentDetails = hasAnyAncestor(hasTestTag("now-playing-details:current"))
                assertTrue(onNode(hasContentDescription("Favorite") and currentDetails).fetchSemanticsNode().boundsInRoot.left < originalFavorite.left - 20f)
                assertTrue(onNode(hasContentDescription("Manage playlists") and currentDetails).fetchSemanticsNode().boundsInRoot.left < originalPlaylist.left - 20f)
                onNode(hasText("Next song") and hasAnyAncestor(hasTestTag("now-playing-details:next"))).assertIsDisplayed()
                onNode(hasText("Next artist") and hasAnyAncestor(hasTestTag("now-playing-details:next"))).assertIsDisplayed()
                onNode(hasContentDescription("Remove favorite") and hasAnyAncestor(hasTestTag("now-playing-details:next"))).assertExists()
                onNode(hasContentDescription("Manage playlists") and hasAnyAncestor(hasTestTag("now-playing-details:next"))).assertIsSelected()
                onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(160f, 0f), 300); up() }
                mainClock.autoAdvance = true
                mainClock.advanceTimeBy(500)
                assertEquals(tracks[1].id, controller.state.value.playback.trackId)
                assertTrue(kotlin.math.abs(currentTitle().fetchSemanticsNode().boundsInRoot.left - originalTitle.left) < 2f)
                onNodeWithContentDescription("Favorite").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.first { it.id == tracks[1].id }.favorite }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
