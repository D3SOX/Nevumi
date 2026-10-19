package me.d3sox.nevumi.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.click
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

@OptIn(ExperimentalTestApi::class)
class PlayerUiTest {
    @Test
    fun playerOptionLabelsRemainCompleteAtTheLargestFontScale() {
        val directory = Files.createTempDirectory("player-large-font")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val song = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }.first()
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        Box(Modifier.width(360.dp).height(740.dp)) { MusicPlayerApp(controller) }
                    }
                }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { controller.playTrack(song.id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Favorite").assertIsDisplayed()
                onNodeWithContentDescription("Repeat").performClick()
                listOf("Off", "Song", "Queue", "Repeat count").forEach { label ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                    assertTrue(results.isNotEmpty() && results.all { layout ->
                        !layout.didOverflowHeight && layout.getLineEnd(layout.lineCount - 1) == label.length &&
                            (0 until layout.lineCount).all { line ->
                                !layout.isLineEllipsized(line) && layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f
                            }
                    },
                        "$label must be readable in full: ${results.map { "${it.size}, lines=${it.lineCount}, width=${it.didOverflowWidth}, height=${it.didOverflowHeight}" }}")
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playerOptionsRemainReachableInAShortLandscapeWindow() {
        val directory = Files.createTempDirectory("player-landscape")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val song = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }.first()
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { Box(Modifier.width(800.dp).height(360.dp)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { controller.playTrack(song.id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Favorite").performScrollTo().assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.first { it.id == song.id }.favorite }
                onNodeWithContentDescription("Shuffle songs").assertDoesNotExist()
                onNodeWithContentDescription("Repeat").assertIsDisplayed()
                onNodeWithContentDescription("Audio output").performScrollTo().assertIsDisplayed()
                onNodeWithContentDescription("Queue").assertIsDisplayed()
                onNodeWithContentDescription("Collapse").assertIsDisplayed()
                assertEquals(false, controller.state.value.playback.playing)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun longPlayerTitlesStayOnOneLineWhileCollapsing() {
        val directory = Files.createTempDirectory("player-long-title")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val title = "A very long song title that should scroll smoothly instead of wrapping or changing into ellipsis during player motion"
        val artist = "An artist with a very long name and several featured artists"
        val song = runBlocking { library.importTrack(me.d3sox.nevumi.library.TrackMetadata(title, artist, durationMs = 3_000),
            me.d3sox.nevumi.library.TrackSource("long-title", me.d3sox.nevumi.library.SourceKind.LOCAL,
                Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString())) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { Box(Modifier.width(360.dp).height(700.dp)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { controller.playTrack(song.id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                assertTrue(onNodeWithText(title).fetchSemanticsNode().boundsInRoot.height <= 36f, "The expanded title uses one line")
                onNodeWithTag("player-artwork", useUnmergedTree = true).performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 180f)) }
                assertTrue(onNodeWithText(title).fetchSemanticsNode().boundsInRoot.height <= 36f, "Dragging keeps the same single-line title")
                onNodeWithTag("player-artwork", useUnmergedTree = true).performTouchInput { up() }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun tappingTheTimestampSwitchesElapsedAndRemainingTimeAndSavesTheChoice() {
        val directory = Files.createTempDirectory("playing-time-mode")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val song = controller.state.value.tracks.first { it.metadata.title == "Northern Light" }
                runBlocking { controller.playTrack(song.id).join(); controller.pause().join(); controller.seek(1_000).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.durationMs == 3_000L && controller.state.value.playback.positionMs == 1_000L }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Elapsed time").assertTextEquals("0:01").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.showRemainingTime }
                onNodeWithContentDescription("Remaining time").assertTextEquals("−0:02").performClick()
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.preferences.showRemainingTime }
                onNodeWithContentDescription("Elapsed time").assertTextEquals("0:01").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.showRemainingTime }
                assertEquals(true, runBlocking { preferences.load() }.showRemainingTime)
                assertEquals("−0:02", onNodeWithContentDescription("Remaining time").fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
                assertEquals(false, controller.state.value.playback.playing)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun thePlayButtonCircleFillsItsControlAtTheMiniPlayerEndOfTheTransition() {
        val directory = Files.createTempDirectory("mini-play-circle-motion")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { Box(Modifier.width(360.dp).height(740.dp)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(controller.state.value.tracks.first().id).join(); controller.pause().join() }
                fun circleAndControlWidths(): Pair<Int, Int> {
                    val pixels = onNode(hasContentDescription("Play") and hasClickAction()).captureToImage().toPixelMap()
                    val accent = pixels[pixels.width / 2, pixels.height / 4]
                    val painted = (0 until pixels.width).filter { x ->
                        val color = pixels[x, pixels.height / 2]
                        kotlin.math.abs(color.red - accent.red) + kotlin.math.abs(color.green - accent.green) +
                            kotlin.math.abs(color.blue - accent.blue) < .04f
                    }
                    return (painted.last() - painted.first() + 1) to pixels.width
                }
                val (restingWidth, restingControl) = circleAndControlWidths()
                val mini = onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                onNodeWithTag("mini-player").performClick()
                mainClock.advanceTimeBy(1_000)
                mainClock.autoAdvance = false
                onNodeWithTag("now-playing").performTouchInput {
                    down(androidx.compose.ui.geometry.Offset(width * .5f, 80f))
                    moveBy(androidx.compose.ui.geometry.Offset(0f, mini.top - 4f), 500)
                }
                mainClock.advanceTimeByFrame()
                val (transitioningWidth, transitioningControl) = circleAndControlWidths()
                assertTrue(transitioningWidth >= restingWidth - 2 &&
                    kotlin.math.abs((restingControl - restingWidth) - (transitioningControl - transitioningWidth)) <= 2,
                    "The play circle must fill its animated control without shrinking: " +
                        "$restingWidth/$restingControl at rest, $transitioningWidth/$transitioningControl during the drag")
                onRoot().performTouchInput { up() }
                mainClock.autoAdvance = true
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aHorizontalArtworkDragDoesNotBecomeAVerticalPanelDrag() {
        val directory = Files.createTempDirectory("player-drag-axis")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                val songs = controller.state.value.tracks.sortedBy { it.metadata.title }
                runBlocking { controller.playTrack(songs[1].id, songs.map { it.id }, 1).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                mainClock.advanceTimeBy(1_000)
                val artwork = onNodeWithTag("player-artwork", useUnmergedTree = true)
                val original = artwork.fetchSemanticsNode().boundsInRoot
                mainClock.autoAdvance = false
                artwork.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-80f, 35f), 250) }
                mainClock.advanceTimeByFrame()
                onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-80f, 220f), 250) }
                mainClock.advanceTimeByFrame()
                val diagonal = artwork.fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(diagonal.top - original.top) < 2f,
                    "A horizontal swipe must keep the artwork's panel in place even if the finger moves vertically: $original / $diagonal")
                onNodeWithContentDescription("Next artwork: ${songs[2].metadata.title}", useUnmergedTree = true).assertIsDisplayed()
                onRoot().performTouchInput { up() }
                mainClock.autoAdvance = true
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentIndex == 2 }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun draggingNowPlayingDownToTheMiniPlayerCanReverseBeforeRelease() {
        val directory = Files.createTempDirectory("reversible-playing-collapse")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(controller.state.value.tracks.first().id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                mainClock.advanceTimeBy(1_000)
                val original = onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot
                mainClock.autoAdvance = false
                onNodeWithTag("now-playing").performTouchInput {
                    down(androidx.compose.ui.geometry.Offset(width * .5f, 90f))
                    repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, 50f), 50) }
                }
                mainClock.advanceTimeBy(300)
                onRoot().performTouchInput { repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, -50f), 50) } }
                mainClock.advanceTimeBy(300)
                val returned = onNodeWithTag("now-playing").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(returned.top - original.top) < 2f,
                    "Reversing a held downward drag must reopen Now Playing before release: $original / $returned")
                onRoot().performTouchInput { up() }
                mainClock.autoAdvance = true
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aPanelDragCanCrossNowPlayingAndRetraceItsPathBeforeTheFingerIsReleased() {
        val directory = Files.createTempDirectory("reversible-player-drag")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                val track = controller.state.value.tracks.first()
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join() }
                val mini = onNodeWithTag("mini-player")
                val original = mini.fetchSemanticsNode().boundsInRoot
                mainClock.autoAdvance = false
                mini.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -750f), 500) }
                mainClock.advanceTimeByFrame()
                // Hold the same pointer and go all the way back, crossing both panel boundaries.
                onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, 750f), 500) }
                mainClock.advanceTimeByFrame()
                val returned = onNodeWithTag("mini-player").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(returned.top - original.top) < 2f,
                    "Returning the finger must retrace the panel path before release: $original / $returned")
                onRoot().performTouchInput { up() }
                mainClock.autoAdvance = true
                onNodeWithTag("mini-player").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun draggingNowPlayingArtworkPreviewsTheNeighborAndCanBeCancelledBeforeSkipping() {
        val directory = Files.createTempDirectory("playing-artwork-preview")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                val songs = controller.state.value.tracks.sortedBy { it.metadata.title }
                runBlocking { controller.playTrack(songs[1].id, songs.map { it.id }, 1).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                mainClock.advanceTimeBy(1_200)
                val artwork = onNodeWithTag("player-artwork", useUnmergedTree = true)
                val current = onNode(hasContentDescription("Album artwork: ${songs[1].metadata.title}") and hasAnyAncestor(hasTestTag("player-artwork")), useUnmergedTree = true)
                val original = current.fetchSemanticsNode().boundsInRoot
                artwork.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-240f, 0f), 300) }
                val moved = current.fetchSemanticsNode().boundsInRoot
                assertTrue(moved.left < original.left - 20f || moved.right < original.right - 20f,
                    "The artwork must follow the finger before releasing the drag")
                onNodeWithContentDescription("Next artwork: ${songs[2].metadata.title}", useUnmergedTree = true).assertIsDisplayed()
                assertEquals(1, controller.state.value.queue.currentIndex)
                artwork.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(200f, 0f), 150); up() }
                mainClock.advanceTimeBy(250)
                waitForIdle()
                val returned = current.fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(returned.left - original.left) < 1f, "Cancelled artwork: original=$original, dragged=$moved, returned=$returned")
                assertEquals(1, controller.state.value.queue.currentIndex)
                artwork.performTouchInput { swipe(center, center.copy(x = center.x - 200f), 300) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentIndex == 2 }
                assertEquals(false, controller.state.value.playback.playing)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun horizontalNowPlayingSwipesSkipTracksWithoutChangingThePausedState() {
        val directory = Files.createTempDirectory("playing-track-swipe")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val songs = controller.state.value.tracks.sortedBy { it.metadata.title }
                runBlocking { controller.playTrack(songs[1].id, songs.map { it.id }, 1).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithTag("player-artwork", useUnmergedTree = true).performTouchInput {
                    swipe(center, center.copy(x = center.x - 200f), 300)
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentIndex == 2 }
                onNodeWithTag("player-artwork", useUnmergedTree = true).performTouchInput {
                    swipe(center, center.copy(x = center.x + 200f), 300)
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentIndex == 1 }
                assertEquals(false, controller.state.value.playback.playing)
                onNodeWithContentDescription("Collapse").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pullingDownOnTheTopQueueEntryReturnsToNowPlaying() {
        val directory = Files.createTempDirectory("queue-pull-down")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val songs = controller.state.value.tracks.sortedBy { it.metadata.title }
                runBlocking { controller.playTrack(songs[0].id, songs.map { it.id }).join(); controller.pause().join() }
                val entry = controller.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithTag("queue-entry:$entry").performTouchInput {
                    swipe(center, center.copy(y = center.y + 300f), 400)
                }
                onNodeWithContentDescription("Collapse").assertIsDisplayed()
                onNodeWithTag("queue-header").assertDoesNotExist()
                assertEquals(entry, controller.state.value.queue.currentEntryId)
                assertEquals(false, controller.state.value.playback.playing)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun swipingAQueueEntryLeftRemovesOnlyThatOccurrence() {
        val directory = Files.createTempDirectory("queue-swipe-remove")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val songs = controller.state.value.tracks.sortedBy { it.metadata.title }
                runBlocking { controller.playTrack(songs[0].id, listOf(songs[0].id, songs[1].id, songs[1].id)).join(); controller.pause().join() }
                val entries = controller.state.value.queue.entries
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNode(hasContentDescription("Track options: ${songs[1].metadata.title}") and hasAnyAncestor(androidx.compose.ui.test.hasTestTag("queue-entry:${entries[1].id}"))).performClick()
                onNodeWithText("Rate song").performClick()
                onNodeWithContentDescription("4 stars").performClick()
                onNodeWithText("Confirm").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.first { it.id == songs[1].id }.rating == 4 }
                onNodeWithTag("queue-entry:${entries[1].id}").performTouchInput {
                    swipe(center, center.copy(x = center.x - 200f), 250)
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == 2 }
                assertEquals(listOf(entries[0], entries[2]), controller.state.value.queue.entries)
                assertEquals(entries[0].id, controller.state.value.queue.currentEntryId)
                assertEquals(false, controller.state.value.playback.playing)
                onNodeWithText("Removed from queue").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun nowPlayingHasAccessibleRepeatAndFavoriteControls() {
        val directory = Files.createTempDirectory("player-option-labels")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val song = controller.state.value.tracks.first { it.metadata.title == "Northern Light" }
                runBlocking { controller.playTrack(song.id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithText("Shuffle songs").assertDoesNotExist()
                onNodeWithText("Shuffle albums").assertDoesNotExist()
                onNodeWithContentDescription("Favorite").assertIsDisplayed()
                onNodeWithContentDescription("Favorite").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.first { it.id == song.id }.favorite }
                onNodeWithContentDescription("Remove favorite").assertIsDisplayed()
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Rate song").performClick()
                onNodeWithText("Cancel").performClick()
                onNodeWithContentDescription("Repeat").performClick()
                onNode(hasText("Song") and hasAnyAncestor(androidx.compose.ui.test.isPopup())).performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.repeatMode == me.d3sox.nevumi.playback.RepeatMode.ONE }
                assertEquals(true, onNodeWithContentDescription("Repeat").fetchSemanticsNode().config[SemanticsProperties.Selected])
                assertEquals(false, controller.state.value.playback.playing)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun tappingThePlayingSeekBarMovesImmediatelyWhileTheDecoderHandlesTheSeek() {
        val directory = Files.createTempDirectory("seek-feedback-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val decoder = MpvPlaybackEngine("null")
        val decoderScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val seekRequested = kotlinx.coroutines.CompletableDeferred<Unit>()
        val allowDecoderSeek = kotlinx.coroutines.CompletableDeferred<Unit>()
        val asynchronousDecoder = object : me.d3sox.nevumi.playback.PlaybackEngine by decoder {
            override fun seek(positionMs: Long) {
                seekRequested.complete(Unit)
                decoderScope.launch { allowDecoderSeek.await(); decoder.seek(positionMs) }
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), asynchronousDecoder)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val song = controller.state.value.tracks.first { it.metadata.title == "Northern Light" }
                runBlocking { controller.playTrack(song.id).join(); controller.pause().join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.durationMs == 3_000L }
                val occurrence = controller.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick()
                val position = onNodeWithContentDescription("Playback position")
                position.performTouchInput { click(center.copy(x = width * .75f)) }
                waitUntil(timeoutMillis = 3_000) { seekRequested.isCompleted }
                assertTrue(position.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current in 1_900f..2_600f,
                    "The tapped position is displayed before the decoder reports it")
                allowDecoderSeek.complete(Unit)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs in 1_900..2_600 }
                assertTrue(position.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current in 1_900f..2_600f)
                assertEquals(occurrence, controller.state.value.queue.currentEntryId)
                assertTrue(!controller.state.value.playback.playing)
            }
        } finally {
            allowDecoderSeek.complete(Unit)
            runBlocking { controller.closeAndJoin() }
            decoderScope.cancel()
            store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun theFloatingMiniPlayerCanSeekWithoutSkippingAndHasAPreviousButton() {
        val directory = Files.createTempDirectory("mini-seek-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val song = controller.state.value.tracks.first { it.metadata.title == "Northern Light" }
                runBlocking { controller.playTrack(song.id, listOf(song.id, song.id), 1).join(); controller.pause().join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.durationMs == 3_000L }
                val occurrence = controller.state.value.queue.currentEntryId
                val mini = onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                val navigation = onNodeWithTag("library-navigation").fetchSemanticsNode().boundsInRoot
                assertTrue(mini.bottom <= navigation.top + 1)
                runBlocking { controller.seek(1_500).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs == 1_500L }
                val position = onNodeWithContentDescription("Mini-player position")
                fun paintedColumn(xFraction: Float): Pair<List<Int>, List<Int>> {
                    val pixels = position.captureToImage().toPixelMap()
                    val rows = (0 until pixels.height).filter { y ->
                        pixels[pixels.width / 4, y] != pixels[pixels.width * 9 / 10, y] &&
                            pixels[pixels.width / 4, y] == pixels[pixels.width * 2 / 5, y]
                    }
                    assertTrue(rows.isNotEmpty(), "The playback progress line is visible")
                    val color = pixels[pixels.width / 4, rows[rows.size / 2]]
                    val column = ((pixels.width * (xFraction - .1f)).toInt()..(pixels.width * (xFraction + .1f)).toInt())
                        .map { x -> (0 until pixels.height).filter { y -> pixels[x, y] == color } }.maxBy { it.size }
                    return rows to column
                }
                assertTrue(paintedColumn(.5f).second.size <= 4, "The idle seek bar has no thumb")
                position.performTouchInput {
                    down(center.copy(x = width * .5f))
                    moveTo(center.copy(x = width * .65f), 200)
                    moveTo(center.copy(x = width * .75f), 200)
                }
                val (line, thumb) = paintedColumn(.75f)
                assertTrue(thumb.size > line.size, "Dragging reveals the seek thumb: line=$line, thumb=$thumb")
                assertTrue(kotlin.math.abs(thumb.average() - line.average()) <= 1, "The thumb is centered on the progress line")
                position.performTouchInput { up() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs in 1_900..2_600 }
                assertTrue(paintedColumn(.75f).second.size <= 4, "Releasing hides the seek thumb")
                onNodeWithContentDescription("Mini-player position").performTouchInput {
                    swipe(center.copy(x = width * .15f), center.copy(x = width * .75f), 400)
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs in 1_900..2_600 }
                assertEquals(occurrence, controller.state.value.queue.currentEntryId)
                assertTrue(!controller.state.value.playback.playing)
                onNodeWithTag("mini-player").assertIsDisplayed()
                onNodeWithContentDescription("Previous track").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentIndex == 0 }
                openLibraryPage("Folders")
                onNodeWithContentDescription("Collection view").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSongRepeatCountCanBeSetFromNowPlayingAndCountsDownBeforeContinuing() {
        val directory = Files.createTempDirectory("finite-repeat-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val song = controller.state.value.tracks.first { it.metadata.title == "Northern Light" }
                runBlocking { controller.playTrack(song.id, listOf(song.id, song.id)).join(); controller.pause().join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.durationMs == 3_000L }
                val original = controller.state.value.queue
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Repeat").performClick()
                onNodeWithText("Repeat count").performClick()
                onNodeWithText("Total plays").performTextClearance()
                onNodeWithText("Total plays").performTextInput("0")
                onNodeWithText("Set repeat count").assertIsNotEnabled()
                onNodeWithText("Total plays").performTextClearance()
                onNodeWithText("Total plays").performTextInput("2")
                onNodeWithText("Set repeat count").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.repeatPlays == 2 }
                onNodeWithContentDescription("Repeat").performClick()
                onNodeWithText("2 plays remaining").assertIsDisplayed()
                onNodeWithTag("now-playing").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
                assertEquals(original.entries, controller.state.value.queue.entries)
                assertEquals(false, controller.state.value.playback.playing)
                runBlocking { controller.seek(2_000).join(); controller.play().join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing && controller.state.value.playback.positionMs >= 1_950 }
                runBlocking {
                    withTimeout(4_000) { controller.state.first { it.queue.repeatPlays == 1 } }
                    controller.pause().join()
                }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.playback.playing && controller.state.value.queue.repeatPlays == 1 }
                onNodeWithContentDescription("Repeat").performClick()
                onNodeWithText("1 play remaining").assertIsDisplayed()
                onNodeWithTag("now-playing").performTouchInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
                runBlocking { controller.seek(2_000).join(); controller.play().join() }
                waitUntil(timeoutMillis = 4_000) { controller.state.value.playback.entryId == original.entries[1].id && controller.state.value.playback.playing }
                assertEquals(me.d3sox.nevumi.playback.RepeatMode.OFF, controller.state.value.queue.repeatMode)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun repeatSongFromNowPlayingLoopsTheCurrentOccurrenceAndCanBeTurnedOff() {
        val directory = Files.createTempDirectory("repeat-queue-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.pause().join() }
                val entry = controller.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Repeat").performClick()
                onNodeWithText("Song").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    controller.state.value.queue.repeatMode == me.d3sox.nevumi.playback.RepeatMode.ONE
                }
                runBlocking { controller.seek(2_000).join(); controller.play().join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing && controller.state.value.playback.positionMs >= 1_950 }
                waitUntil(timeoutMillis = 4_000) { controller.state.value.playback.playing && controller.state.value.playback.positionMs in 50..500 }
                assertEquals(entry, controller.state.value.playback.entryId)
                onNodeWithContentDescription("Repeat").performClick()
                onNodeWithText("Off").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    controller.state.value.queue.repeatMode == me.d3sox.nevumi.playback.RepeatMode.OFF
                }
                runBlocking { controller.seek(2_700).join() }
                waitUntil(timeoutMillis = 4_000) { controller.state.value.playback.ended }
                assertEquals(entry, controller.state.value.playback.entryId)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun shuffleCanBeTurnedOnAndOffWithoutMovingThePausedPlayhead() {
        val directory = Files.createTempDirectory("shuffle-queue-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Long Way Home").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.pause().join(); controller.seek(1_000).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs in 950..1_050 }
                val original = controller.state.value.queue
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Shuffle").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.unshuffledEntryIds != null }
                assertEquals(original.currentEntryId, controller.state.value.playback.entryId)
                assertEquals(false, controller.state.value.playback.playWhenReady)
                onNodeWithContentDescription("Shuffle").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.unshuffledEntryIds == null }
                assertEquals(original.entries, controller.state.value.queue.entries)
                assertEquals(original.currentEntryId, controller.state.value.playback.entryId)
                assertEquals(false, controller.state.value.playback.playWhenReady)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs in 950..1_050 }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSelectedQueueKeepsEditsAndCanBeLoadedPausedAndRemovedThroughThePlayer() {
        val directory = Files.createTempDirectory("saved-queue-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val queue = PlaybackQueue(store)
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Long Way Home").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.pause().join(); controller.seek(1_000).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs >= 950 }
                val original = controller.state.value.queue
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithContentDescription("Saved queues").performClick()
                onNodeWithText("Manage saved queues").performClick()
                onNodeWithText("Save current queue").performClick()
                onNodeWithContentDescription("Queue name").performTextInput("Evening")
                onNodeWithText("Save queue").performClick()
                waitUntil(timeoutMillis = 3_000) { runBlocking { queue.saved().size == 1 } }
                onNodeWithText("Done").performClick()
                runBlocking { controller.enqueue(original.entries.first().trackId,
                    me.d3sox.nevumi.queue.QueueAction.APPEND).join() }
                assertEquals(4, controller.state.value.queue.entries.size)
                val updated = controller.state.value.queue

                onNodeWithContentDescription("Saved queues").performClick()
                onNodeWithText("Manage saved queues").performClick()
                onNodeWithContentDescription("Load queue: Evening").performClick()
                assertEquals(updated.entries, controller.state.value.queue.entries)
                assertEquals(original.currentEntryId, controller.state.value.queue.currentEntryId)
                assertEquals(false, controller.state.value.playback.playWhenReady)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs in 950..1_050 }

                onNodeWithContentDescription("Saved queues").performClick()
                onNodeWithText("Manage saved queues").performClick()
                onNodeWithContentDescription("Saved queue options: Evening").performClick()
                onNodeWithContentDescription("Remove saved queue: Evening").performClick()
                waitUntil(timeoutMillis = 3_000) { runBlocking { queue.saved().isEmpty() } }
                assertEquals(updated.entries, controller.state.value.queue.entries)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun resizingTheWindowKeepsTheMiniPlayerCollapsed() {
        val directory = Files.createTempDirectory("player-resize-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                val height = mutableStateOf(700.dp)
                setContent { Box(Modifier.height(height.value)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Long Way Home").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join() }
                val baseline = onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot.top
                runOnIdle { height.value = 300.dp }
                waitForIdle()
                runOnIdle { height.value = 700.dp }
                waitForIdle()
                assertEquals(baseline, onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot.top, .5f)
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun miniPlayerSwipesPreviewNeighborArtworkAndTitlesBeforeCommittingOrCancelling() {
        val directory = Files.createTempDirectory("mini-preview-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val songs = controller.state.value.tracks.sortedBy { it.metadata.title }
                runBlocking { controller.playTrack(songs[1].id, songs.map { it.id }, 1).join(); controller.pause().join() }
                val original = controller.state.value.queue.currentEntryId
                val mini = onNodeWithTag("mini-player")
                mini.performTouchInput { down(center); moveTo(center.copy(x = width * .15f), 150) }
                onNodeWithContentDescription("Next track preview: ${songs[2].metadata.title}").assertIsDisplayed()
                onNodeWithTag("mini-preview-title", useUnmergedTree = true).assertTextEquals(songs[2].metadata.title)
                assertEquals(original, controller.state.value.queue.currentEntryId)
                mini.performTouchInput { cancel() }
                onNodeWithContentDescription("Mini-player artwork: ${songs[1].metadata.title}").assertIsDisplayed()
                assertEquals(original, controller.state.value.queue.currentEntryId)
                mini.performTouchInput { down(center); moveTo(center.copy(x = width * .85f), 150) }
                onNodeWithContentDescription("Previous track preview: ${songs[0].metadata.title}").assertIsDisplayed()
                onNodeWithTag("mini-preview-title", useUnmergedTree = true).assertTextEquals(songs[0].metadata.title)
                mini.performTouchInput { up() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentIndex == 0 }
                assertTrue(!controller.state.value.playback.playing)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun horizontalMiniPlayerSwipesMoveToTheNextAndPreviousQueueTrack() {
        val directory = Files.createTempDirectory("mini-swipe-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Long Way Home").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                onNodeWithTag("mini-player").performTouchInput { swipe(center, center.copy(x = center.x - 200), 200) }
                try { waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentIndex == 1 } }
                catch (failure: Throwable) { throw AssertionError("After mini swipe: ${controller.state.value.queue}; playback: ${controller.state.value.playback}; error: ${controller.state.value.error}", failure) }
                onNodeWithTag("mini-player").performTouchInput { swipe(center, center.copy(x = center.x + 200), 200) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentIndex == 0 }
                assertEquals("Long Way Home", controller.state.value.tracks.first {
                    it.id == controller.state.value.queue.currentTrackId }.metadata.title)
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun draggingAQueueHandleChangesOrderAndKeepsThePlayingOccurrence() {
        val directory = Files.createTempDirectory("queue-drag-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Long Way Home").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                val before = controller.state.value.queue
                val last = before.entries.last()
                val firstRow = onNodeWithTag("queue-entry:${before.context.first().id}")
                val originalTop = firstRow.fetchSemanticsNode().boundsInRoot.top
                val handle = onNodeWithContentDescription("Reorder: Northern Light", useUnmergedTree = true)
                val distance = firstRow.fetchSemanticsNode().boundsInRoot.center.y - handle.fetchSemanticsNode().boundsInRoot.center.y
                handle.performTouchInput {
                    down(center)
                    advanceEventTime(600)
                    moveTo(center.copy(y = center.y - 20), delayMillis = 16)
                    moveTo(center.copy(y = center.y + distance - 8), delayMillis = 300)
                }
                // Neighbours move out of the way while the finger is still down.
                assertTrue(firstRow.fetchSemanticsNode().boundsInRoot.top > originalTop + 20,
                    "Queue rows must reorder live before the drag is released")
                onNodeWithContentDescription("Reorder: Northern Light", useUnmergedTree = true).performTouchInput { up() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.context.first().id == last.id }
                assertEquals(before.currentEntryId, controller.state.value.queue.currentEntryId)
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun theQueueCanSelectAndRemoveThePlayingOccurrence() {
        val directory = Files.createTempDirectory("queue-controls-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Long Way Home").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                val entry = controller.state.value.queue.entries[1]
                onNodeWithTag("queue-entry:${entry.id}").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.entryId == entry.id }
                onNode(hasContentDescription("Track options: ${controller.state.value.tracks.first { it.id == entry.trackId }.metadata.title}") and
                    hasAnyAncestor(androidx.compose.ui.test.hasTestTag("queue-entry:${entry.id}"))).performClick()
                onNodeWithText("Remove from queue").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == 2 }
                val snapshot = controller.state.value.queue
                assertEquals("Northern Light", controller.state.value.tracks.first { it.id == snapshot.currentTrackId }.metadata.title)
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun trackSwipesInsertPlayNextAndAppendWithoutChangingThePlayingSong() {
        val directory = Files.createTempDirectory("track-swipe-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }.associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithText("Northern Light").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == 3 }
                onNodeWithTag("track-row:${tracks.getValue("Night Walk").id}").performTouchInput {
                    swipe(center, center.copy(x = center.x + 200), 200)
                }
                onNodeWithTag("track-row:${tracks.getValue("Long Way Home").id}").performTouchInput {
                    swipe(center, center.copy(x = center.x - 200), 200)
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == 5 }
                val snapshot = controller.state.value.queue
                val titles = tracks.values.associate { it.id to it.metadata.title }
                assertEquals(listOf("Long Way Home", "Night Walk", "Northern Light", "Night Walk", "Long Way Home")
                    to "Northern Light", snapshot.entries.map { titles[it.trackId] } to titles[snapshot.currentTrackId])
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun artworkAndTransportControlsMoveContinuouslyBetweenMiniPlayingAndTheCurrentQueueRow() {
        val directory = Files.createTempDirectory("player-motion-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val songs = controller.state.value.tracks.sortedBy { it.metadata.title }
                runBlocking { controller.playTrack(songs[1].id, songs.map { it.id }, 1).join(); controller.pause().join() }
                fun artwork() = onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val miniCover = artwork()
                val miniPlay = onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
                onNodeWithTag("mini-player").performTouchInput { down(center); moveTo(center.copy(y = center.y - 250), 250) }
                val halfwayCover = artwork()
                val halfwayPlay = onNodeWithContentDescription("Play").fetchSemanticsNode().boundsInRoot
                assertTrue(halfwayCover.width > miniCover.width * 1.5f)
                assertTrue(halfwayCover.top < miniCover.top - 100)
                assertTrue(halfwayPlay.left < miniPlay.left - 40)
                onNodeWithTag("mini-player").performTouchInput { moveTo(center.copy(y = center.y - 250), 150); up() }
                onNodeWithText("Playing from").assertIsDisplayed()
                val playingCover = artwork()
                assertTrue(playingCover.width > halfwayCover.width)
                onNodeWithTag("now-playing").performTouchInput { down(center); moveTo(center.copy(y = center.y - 260), 250) }
                val queueTransition = artwork()
                assertTrue(queueTransition.width < playingCover.width && queueTransition.width > miniCover.width)
                onNodeWithTag("now-playing").performTouchInput { moveTo(center.copy(y = -80f), 150); up() }
                onNodeWithText("Queue").assertIsDisplayed()
                assertTrue(artwork().width > miniCover.width && artwork().width < playingCover.width)
                val occurrence = controller.state.value.queue.currentEntryId
                val header = onNodeWithTag("queue-mini-player").fetchSemanticsNode().boundsInRoot
                assertTrue(artwork().center.y in header.top..header.bottom)
                onNodeWithContentDescription("Play").assertDoesNotExist()
                onNodeWithContentDescription("Now playing").performClick()
                onNodeWithContentDescription("Collapse").performClick()
                assertEquals(miniCover, artwork())
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun swipesConnectTheMiniPlayerNowPlayingAndQueueInBothDirections() {
        val directory = Files.createTempDirectory("player-ui-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithText("Northern Light").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.engine.status.value.playing }
                onNodeWithTag("mini-player").performTouchInput {
                    swipe(center, center.copy(y = center.y - 500), 250)
                }
                onNodeWithTag("now-playing").assertIsDisplayed()
                onNodeWithTag("now-playing").performTouchInput {
                    swipe(center, center.copy(y = center.y - 500), 250)
                }
                onNodeWithTag("queue-header").assertIsDisplayed().performTouchInput {
                    swipe(center, center.copy(y = center.y + 500), 250)
                }
                onNodeWithTag("now-playing").assertIsDisplayed().performTouchInput {
                    swipe(center, center.copy(y = center.y + 500), 250)
                }
                onNodeWithTag("mini-player").assertIsDisplayed()
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun clickingASongStartsPlaybackAndShowsThePauseControl() {
        val directory = Files.createTempDirectory("player-ui-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithText("Northern Light").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.engine.status.value.playing }
                onNodeWithContentDescription("Pause").assertExists()
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
