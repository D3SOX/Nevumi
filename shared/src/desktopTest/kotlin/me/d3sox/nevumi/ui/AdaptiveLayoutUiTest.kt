package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.discovery_playing_similar_songs
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AdaptiveLayoutUiTest {
    private var width by mutableStateOf(400.dp)

    private fun withApp(startWidth: Dp, test: ComposeUiTest.(MusicController, List<Track>) -> Unit) {
        width = startWidth
        val directory = Files.createTempDirectory("adaptive-layout-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val tracks = runBlocking { (0 until 12).map { library.importTrack(TrackMetadata("Track $it", "Artist $it", durationMs = 3_000), TrackSource("source:$it", SourceKind.LOCAL, audio)) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            // Large enough for the widest layout, so each test's box sets the space the app gets.
            runSkikoComposeUiTest(size = Size(1400f, 900f)) {
                setContent { Box(Modifier.size(width, 800.dp)) { MusicPlayerApp(controller) } }
                waitForLibrary(controller)
                test(controller, tracks)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    private fun ComposeUiTest.play(controller: MusicController, tracks: List<Track>) {
        runBlocking { controller.playTrack(tracks[1].id, tracks.map { it.id }, 1).join(); controller.pause().join() }
        waitForIdle()
    }

    private fun ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test fun compactWindowsKeepTheBottomBarAndMiniPlayer() = withApp(400.dp) { controller, tracks ->
        play(controller, tracks)
        val navigation = bounds("library-navigation")
        val pager = bounds("library-pager")
        assertTrue(navigation.top >= pager.bottom, "The bottom bar sits below the library")
        assertEquals(with(density) { 400.dp.toPx() }, navigation.width, 1f)
        onNodeWithTag("mini-player").assertIsDisplayed()
        onNodeWithTag("now-playing-pane").assertDoesNotExist()
    }

    @Test fun mediumWindowsUseARailBesideTheLibraryAboveTheMiniPlayer() = withApp(800.dp) { controller, tracks ->
        play(controller, tracks)
        val navigation = bounds("library-navigation")
        assertTrue(navigation.right <= bounds("library-pager").left + 1f, "The rail sits left of the library")
        assertTrue(navigation.height > navigation.width, "The rail runs down the side")
        assertTrue(bounds("mini-player").top >= navigation.bottom - 1f, "The mini player stays below the rail")
        onNodeWithTag("now-playing-pane").assertDoesNotExist()
        onNodeWithText("Albums").performClick()
        onNodeWithTag("library-tab:ALBUMS").assertExists()
        // The full-screen player still covers the window and collapses back.
        onNodeWithTag("mini-player").performClick()
        onNodeWithContentDescription("Collapse").assertIsDisplayed().performClick()
        onNodeWithTag("mini-player").assertIsDisplayed()
    }

    @Test fun expandedWindowsDockNowPlayingBesideTheLibrary() = withApp(1200.dp) { controller, tracks ->
        onNodeWithTag("now-playing-pane").assertDoesNotExist()
        val emptyPager = bounds("library-pager")
        play(controller, tracks)
        val pane = onNodeWithTag("now-playing-pane").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(pane.left >= bounds("library-pager").right - 1f, "The pane sits right of the library")
        assertTrue(bounds("library-pager").width < emptyPager.width, "The library makes room for the pane")
        onNodeWithTag("mini-player").assertDoesNotExist()
        onNodeWithContentDescription("Collapse").assertDoesNotExist()
        onNode(hasTestTag("now-playing-title") and hasText("Track 1"), useUnmergedTree = true).assertExists()
        // The queue opens in the pane and returns to Now Playing, while the library stays usable.
        onNodeWithContentDescription("Queue").performClick()
        onNodeWithTag("queue-header").assertIsDisplayed()
        assertTrue(bounds("queue-header").left >= pane.left - 1f, "The queue opens inside the pane")
        onNodeWithText("Albums").performClick()
        onNodeWithTag("library-tab:ALBUMS").assertExists()
        onNodeWithTag("queue-header").assertIsDisplayed()
        onNodeWithContentDescription("Now playing").performClick()
        onNodeWithTag("now-playing").assertIsDisplayed()
    }

    @Test fun wideningTheWindowMovesTheOpenPlayerIntoThePane() = withApp(400.dp) { controller, tracks ->
        play(controller, tracks)
        onNodeWithTag("mini-player").performClick()
        onNodeWithContentDescription("Queue").performClick()
        onNodeWithTag("queue-header").assertIsDisplayed()
        width = 1200.dp
        waitForIdle()
        onNodeWithTag("queue-header").assertIsDisplayed()
        assertTrue(bounds("queue-header").left >= bounds("now-playing-pane").left - 1f)
        // The library is no longer covered, so it stays reachable.
        onNodeWithText("Albums").performClick()
        onNodeWithTag("library-tab:ALBUMS").assertExists()
        width = 400.dp
        waitForIdle()
        onNodeWithTag("mini-player").assertIsDisplayed()
    }

    @Test fun aNoticeOpensTheQueueInThePaneAfterTheWindowWidens() = withApp(400.dp) { controller, tracks ->
        play(controller, tracks)
        width = 1200.dp
        waitForIdle()
        onNodeWithTag("now-playing-pane").assertIsDisplayed()
        // Such as "Playing 3 similar songs", whose action opens the queue; the notice collector started in the compact layout.
        val context = MusicController::class.java.getDeclaredField("context").apply { isAccessible = true }.get(controller) as me.d3sox.nevumi.app.ControllerContext
        context.mutableNotices.tryEmit(me.d3sox.nevumi.app.ActionNotice(me.d3sox.nevumi.resources.Res.string.discovery_playing_similar_songs,
            me.d3sox.nevumi.app.ActionDestination.QUEUE, arguments = listOf(3)))
        onNodeWithText("Open queue").performClick()
        onNodeWithTag("queue-header").assertIsDisplayed()
        assertTrue(bounds("queue-header").left >= bounds("now-playing-pane").left - 1f, "The queue opens inside the pane")
    }
}
