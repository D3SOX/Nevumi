package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.TrackAction
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlayerTransitionUiTest {
    private fun withPlayer(test: ComposeUiTest.(MusicController, Track) -> Unit) {
        val directory = Files.createTempDirectory("nevumi-player-transition")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("Transition song", "Artist", durationMs = 3_000),
            TrackSource("transition", SourceKind.LOCAL, audio)) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(1000.dp, 740.dp)) { MusicPlayerApp(controller) } }
                waitForLibrary(controller)
                runBlocking {
                    controller.updatePreferences { it.copy(quickAction = TrackAction.FAVORITE) }.join()
                    controller.playTrack(track.id).join(); controller.pause().join()
                }
                test(controller, track)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun theFavoriteToastFollowsAHeldMiniPlayerDragAndItsReversal() = withPlayer { _, _ ->
        onNodeWithContentDescription("Quick action: Favorite: Transition song").performClick()
        onNodeWithText("Undo").assertIsDisplayed()
        val bottom = onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
        mainClock.autoAdvance = false
        onNodeWithTag("mini-player").performTouchInput { down(center); moveBy(Offset(0f, -80f), 300) }
        mainClock.advanceTimeByFrame()
        val dragged = onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
        assertTrue(dragged < bottom - 20f, "A partial swipe must move the toast before the player settles: $bottom -> $dragged")
        onRoot().performTouchInput { moveBy(Offset(0f, 40f), 300) }
        mainClock.advanceTimeByFrame()
        val reversed = onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
        assertTrue(reversed > dragged + 10f && reversed < bottom, "The toast must follow the reversed finger: $bottom / $dragged / $reversed")
        onRoot().performTouchInput { cancel() }
        mainClock.autoAdvance = true
    }

    @Test fun aHeldSearchPlayerDragStaysUnderTheFingerWhenThePageTransitionCompletes() = withPlayer { _, _ ->
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Search").performClick()
        mainClock.advanceTimeBy(32)
        onNodeWithTag("mini-player").performTouchInput { down(center); moveBy(Offset(0f, -80f), 100) }
        mainClock.advanceTimeByFrame()
        val held = onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot.top
        mainClock.advanceTimeBy(400)
        val afterNavigation = onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot.top
        assertTrue(abs(afterNavigation - held) < 2f, "Finishing Search navigation must not move a held player: $held -> $afterNavigation")
        onRoot().performTouchInput { moveBy(Offset(0f, -40f), 100) }
        mainClock.advanceTimeByFrame()
        val next = onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot.top
        assertTrue(abs(next - afterNavigation + 40f) < 2f, "The next pointer move must remain one-to-one: $afterNavigation -> $next")
        onRoot().performTouchInput { cancel() }
        mainClock.autoAdvance = true
    }
}
