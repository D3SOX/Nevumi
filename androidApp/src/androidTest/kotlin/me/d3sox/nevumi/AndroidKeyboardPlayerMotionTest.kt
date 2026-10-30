package me.d3sox.nevumi

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class AndroidKeyboardPlayerMotionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun heldMiniPlayerDragStaysWithTheFingerWhileTheKeyboardCloses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "keyboard-held-drag.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                val track = runBlocking { app.controller.library.importTrack(TrackMetadata("Keyboard drag", durationMs = 3_000),
                    TrackSource("keyboard-held-drag", SourceKind.LOCAL, audio.path)) }
                runBlocking { app.controller.reload().join(); app.controller.playTrack(track.id).join(); app.controller.pause().join() }
                fun miniTop() = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot.top
                fun keyboardVisible(): Boolean {
                    var visible = false
                    scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    return visible
                }
                fun waitForStablePlayer() {
                    var last = miniTop()
                    var stableSince = android.os.SystemClock.uptimeMillis()
                    compose.waitUntil(5_000) {
                        val next = miniTop()
                        if (abs(next - last) > 1f) { last = next; stableSince = android.os.SystemClock.uptimeMillis() }
                        android.os.SystemClock.uptimeMillis() - stableSince >= 150
                    }
                }
                val closedStart = miniTop()
                compose.onNodeWithTag("mini-player").performTouchInput { down(center); moveBy(Offset(0f, -200f), 300) }
                val normalMovement = miniTop() - closedStart
                assertTrue("A held drag must move the player", normalMovement < -150f)
                compose.onRoot().performTouchInput { moveBy(Offset(0f, 200f), 300); advanceEventTime(500); up() }
                waitForStablePlayer()
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Keyboard")
                compose.waitUntil(5_000) { keyboardVisible() }
                waitForStablePlayer()
                val keyboardStart = miniTop()
                compose.onNodeWithTag("mini-player").performTouchInput { down(center); moveBy(Offset(0f, -200f), 300) }
                compose.waitUntil(5_000) { !keyboardVisible() }
                waitForStablePlayer()
                val keyboardMovement = miniTop() - keyboardStart
                assertTrue("Keyboard dismissal must preserve the held drag: without=$normalMovement with=$keyboardMovement",
                    abs(keyboardMovement - normalMovement) < 5f)
                compose.onRoot().performTouchInput { moveBy(Offset(0f, 200f), 300); advanceEventTime(500); up() }
                compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Search your library").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Search your library").assertIsNotFocused().assertTextEquals("Keyboard")
            }
        } finally {
            runCatching { compose.onRoot().performTouchInput { cancel() } }
            runBlocking { app.controller.pause().join() }
            audio.delete()
        }
    }

    @Test
    fun expandingThePlayerDismissesTheSearchKeyboardAndClearsItsCursorForTapsAndSwipes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "keyboard-expand.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                val track = runBlocking { app.controller.library.importTrack(TrackMetadata("Keyboard expansion", durationMs = 3_000),
                    TrackSource("keyboard-expand", SourceKind.LOCAL, audio.path)) }
                runBlocking { app.controller.reload().join(); app.controller.playTrack(track.id).join(); app.controller.pause().join() }
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Keyboard")
                fun keyboardIsVisible(): Boolean {
                    var visible = false
                    scenario.onActivity { visible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    return visible
                }
                for (swipe in listOf(true, false)) {
                    // A slow emulator can drop a show request that arrives while the last hide is still settling, so tap again like a person would.
                    val keyboardOpened = (1..3).any {
                        compose.onNodeWithContentDescription("Search your library").performClick()
                        runCatching { compose.waitUntil(5_000) { keyboardIsVisible() } }.isSuccess
                    }
                    assertTrue("Tapping the search field must open the keyboard", keyboardOpened)
                    if (swipe) compose.onNodeWithTag("mini-player").performTouchInput {
                        swipe(center, center.copy(y = center.y - 400f), 400)
                    } else compose.onNodeWithTag("mini-player").performClick()
                    compose.onNodeWithContentDescription("Collapse").assertIsDisplayed()
                    compose.waitUntil(5_000) { !keyboardIsVisible() }
                    compose.onNodeWithContentDescription("Collapse").performClick()
                    compose.onNodeWithContentDescription("Search your library").assertIsNotFocused().assertTextEquals("Keyboard")
                }
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }

    @Test
    fun hidingTheSearchKeyboardSlidesTheMiniPlayerWithoutResizingArtwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "keyboard-motion.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { !app.controller.state.value.loading }
                val song = runBlocking { app.controller.library.importTrack(TrackMetadata("Keyboard fixture", durationMs = 3_000),
                    TrackSource("keyboard-motion", SourceKind.LOCAL, audio.path)) }
                runBlocking { app.controller.reload().join(); app.controller.playTrack(song.id).join(); app.controller.pause().join() }
                val closed = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val navigationHeight = compose.onNodeWithTag("library-navigation").fetchSemanticsNode().boundsInRoot.height
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Keyboard")
                var keyboardVisible = false
                compose.waitUntil(5_000) {
                    scenario.onActivity { keyboardVisible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    keyboardVisible
                }
                val baseline = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val frames = mutableListOf<Rect>()
                var listener: ViewTreeObserver.OnPreDrawListener? = null
                scenario.onActivity { activity ->
                    val view = findComposeRoot(activity.window.decorView)
                    listener = ViewTreeObserver.OnPreDrawListener {
                        (view as ViewRootForTest).semanticsOwner.getAllSemanticsNodes(false).filter {
                            it.config.getOrNull(SemanticsProperties.TestTag) == "player-artwork"
                        }.forEach { frames += it.boundsInRoot }
                        true
                    }
                    view.viewTreeObserver.addOnPreDrawListener(listener)
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.ime())
                }
                compose.waitUntil(5_000) {
                    scenario.onActivity { keyboardVisible = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                    !keyboardVisible
                }
                compose.waitUntil(5_000) {
                    runCatching { abs(compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top - closed.top - navigationHeight) <= 2f }.getOrDefault(false)
                }
                compose.waitForIdle()
                scenario.onActivity { activity -> findComposeRoot(activity.window.decorView).viewTreeObserver.removeOnPreDrawListener(listener) }
                assertTrue("The keyboard dismiss animation was sampled: ${frames.size} frames", frames.size >= 3)
                assertTrue("Artwork must keep its mini-player dimensions during every keyboard frame: baseline=$baseline frames=$frames",
                    frames.all { abs(it.width - baseline.width) <= 2f && abs(it.height - baseline.height) <= 2f })
                assertTrue("The mini player moves down after the keyboard closes", frames.last().top > baseline.top + 100f)
                compose.onNodeWithTag("mini-player").assertIsDisplayed()
                compose.onNodeWithTag("library-navigation").assertDoesNotExist()
                compose.onNodeWithContentDescription("Close search").performClick()
                compose.onNodeWithTag("library-navigation").assertIsDisplayed()
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }

    private fun findComposeRoot(view: View): View {
        if (view is ViewRootForTest) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            runCatching { findComposeRoot(view.getChildAt(index)) }.getOrNull()?.let { return it }
        }
        error("The activity has no Compose root")
    }
}
