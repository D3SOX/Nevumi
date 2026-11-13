package me.d3sox.nevumi

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.TrackAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidFavoriteToastMotionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun favoritingInSongsThenHoldingAndReversingAnUpwardPlayerSwipeMovesTheToastWithTheFinger() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val audio = File(app.cacheDir, "toast-motion.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForLibrary(controller)
                val track = runBlocking { controller.library.importTrack(TrackMetadata("Toast motion", durationMs = 3_000),
                    TrackSource("toast-motion", SourceKind.LOCAL, audio.path)) }
                runBlocking {
                    controller.reload().join(); controller.updatePreferences { it.copy(quickAction = TrackAction.FAVORITE) }.join()
                    controller.playTrack(track.id).join(); controller.pause().join()
                }
                compose.onNodeWithContentDescription("Quick action: Favorite: Toast motion").performClick()
                compose.onNodeWithText("Undo").assertIsDisplayed()
                val bottom = compose.onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("mini-player").performTouchInput { down(center); moveBy(Offset(0f, -200f), 300) }
                compose.mainClock.advanceTimeByFrame()
                val moved = compose.onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
                assertTrue("A partial player swipe must move the favorite toast: $bottom -> $moved", moved < bottom - 30f)
                compose.onRoot().performTouchInput { moveBy(Offset(0f, 100f), 300) }
                compose.mainClock.advanceTimeByFrame()
                val reversed = compose.onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
                assertTrue("The toast must follow the reversed swipe: $moved -> $reversed", reversed > moved + 20f && reversed < bottom)
                compose.onRoot().performTouchInput { cancel() }
                compose.mainClock.autoAdvance = true
            }
        } finally { compose.mainClock.autoAdvance = true; runBlocking { controller.pause().join() }; audio.delete() }
    }
}
