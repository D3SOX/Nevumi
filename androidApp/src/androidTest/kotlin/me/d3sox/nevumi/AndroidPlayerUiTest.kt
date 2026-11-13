package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import me.d3sox.nevumi.playback.RepeatMode
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class AndroidPlayerUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun theAndroidNowPlayingRepeatCountCountsDownAndContinuesWithoutAddingQueueEntries() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "repeat-ui-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                compose.waitUntil(5_000) { !controller.state.value.loading }
                val track = runBlocking {
                    controller.library.importTrack(TrackMetadata("Repeat count fixture"),
                        TrackSource("repeat-ui-${UUID.randomUUID()}", SourceKind.LOCAL, audio.path))
                }
                runBlocking {
                    controller.reload().join()
                    controller.playTrack(track.id, listOf(track.id, track.id)).join()
                    controller.pause().join()
                }
                compose.waitUntil(5_000) { controller.state.value.playback.durationMs == 3_000L }
                val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                val navigation = compose.onNodeWithTag("library-navigation").fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue(mini.bottom <= navigation.top + 1)
                val entry = controller.state.value.queue.currentEntryId
                compose.onNodeWithContentDescription("Mini-player position").performTouchInput {
                    swipe(center.copy(x = width * .15f), center.copy(x = width * .75f), 400)
                }
                compose.waitUntil(5_000) { controller.state.value.playback.positionMs in 1_900..2_600 }
                assertEquals(entry, controller.state.value.queue.currentEntryId)
                compose.onNodeWithContentDescription("Next track").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.currentIndex == 1 }
                compose.onNodeWithContentDescription("Previous track").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.currentIndex == 0 }
                val original = controller.state.value.queue
                compose.onNodeWithTag("mini-player").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("audio-waveform").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Playback position").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
                compose.onNodeWithContentDescription("Repeat").performClick()
                compose.onNodeWithText("Repeat count").performClick()
                compose.onNodeWithText("Total plays").performTextClearance()
                compose.onNodeWithText("Total plays").performTextInput("2")
                compose.onNodeWithText("Set repeat count").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.repeatPlays == 2 }
                assertEquals(original.entries, controller.state.value.queue.entries)
                runBlocking {
                    controller.seek(2_000).join()
                    controller.play().join()
                    // Observe the transition without waiting for Compose to become idle, then freeze it for UI assertions.
                    withTimeout(5_000) { controller.state.first { it.queue.repeatPlays == 1 } }
                    controller.pause().join()
                }
                compose.waitUntil(5_000) { !controller.state.value.playback.playing && controller.state.value.queue.repeatPlays == 1 }
                assertEquals(original.currentEntryId, controller.state.value.queue.currentEntryId)
                assertEquals(original.entries, controller.state.value.queue.entries)
                compose.onNodeWithContentDescription("Repeat").performClick()
                compose.onNodeWithText("1 play remaining").assertIsDisplayed()
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                val continued = runBlocking {
                    controller.seek(2_000).join()
                    controller.play().join()
                    withTimeout(5_000) {
                        controller.state.first { it.playback.entryId == original.entries[1].id && it.playback.playing }
                    }
                }
                assertEquals(RepeatMode.OFF to null, continued.queue.repeatMode to continued.queue.repeatPlays)
            }
        } finally {
            runBlocking { app.controller.setQueueRepeat(RepeatMode.OFF).join(); app.controller.pause().join() }
            audio.delete()
        }
    }

    @Test
    fun downwardDragsCanReverseAndArtworkSwipesKeepTheirAxisOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "axis-gesture-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForLibrary(app.controller)
                val tracks = runBlocking { (0..2).map { index ->
                    app.controller.library.importTrack(TrackMetadata("Axis fixture $index", durationMs = 3_000),
                        TrackSource("axis-$index-${UUID.randomUUID()}", SourceKind.LOCAL, audio.path))
                } }
                runBlocking {
                    app.controller.reload().join()
                    app.controller.playTrack(tracks[1].id, tracks.map { it.id }, 1).join()
                    app.controller.pause().join()
                }
                compose.waitUntil(5_000) { app.controller.state.value.playback.let {
                    it.durationMs == 3_000L && !it.playing && !it.playWhenReady
                } }
                val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                compose.onNodeWithTag("mini-player").performClick()
                compose.mainClock.advanceTimeBy(1_000)
                val playing = compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot
                val distance = mini.top - playing.top
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag("now-playing").performTouchInput {
                        down(androidx.compose.ui.geometry.Offset(width * .5f, 100f))
                        moveBy(androidx.compose.ui.geometry.Offset(0f, distance), 800)
                    }
                    compose.mainClock.advanceTimeBy(300)
                    compose.onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -distance), 800) }
                    compose.mainClock.advanceTimeBy(300)
                    compose.onNodeWithContentDescription("Favorite").assertIsDisplayed()
                    compose.onRoot().performTouchInput { advanceEventTime(500); up() }
                } finally { compose.mainClock.autoAdvance = true }
                compose.mainClock.advanceTimeBy(1_000)
                val artwork = compose.onNodeWithTag("player-artwork", useUnmergedTree = true)
                val original = artwork.fetchSemanticsNode().boundsInRoot
                val entry = app.controller.state.value.queue.currentEntryId
                compose.mainClock.autoAdvance = false
                try {
                    artwork.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(-160f, 70f), 250) }
                    compose.mainClock.advanceTimeByFrame()
                    compose.onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-160f, 250f), 250) }
                    compose.mainClock.advanceTimeByFrame()
                    val diagonal = artwork.fetchSemanticsNode().boundsInRoot
                    org.junit.Assert.assertTrue("Horizontal artwork swipes must ignore vertical movement: $original / $diagonal",
                        kotlin.math.abs(diagonal.top - original.top) < 2f)
                    compose.onNodeWithContentDescription("Next artwork: ${tracks[2].metadata.title}", useUnmergedTree = true).assertIsDisplayed()
                    compose.onNode(hasText(tracks[2].metadata.title) and hasAnyAncestor(hasTestTag("now-playing-details:next"))).assertIsDisplayed()
                    compose.onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(320f, -320f), 300); up() }
                } finally { compose.mainClock.autoAdvance = true }
                compose.mainClock.advanceTimeBy(1_000)
                assertEquals(entry, app.controller.state.value.queue.currentEntryId)
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }

    @Test
    fun aPanelDragCanReturnToTheMiniPlayerWithoutLiftingTheFingerOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "reversible-gesture-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForLibrary(app.controller)
                val track = runBlocking {
                    app.controller.library.importTrack(TrackMetadata("Reversible gesture fixture"),
                        TrackSource("reversible-gesture-${UUID.randomUUID()}", SourceKind.LOCAL, audio.path))
                }
                runBlocking {
                    app.controller.reload().join()
                    app.controller.playTrack(track.id).join()
                    app.controller.pause().join()
                }
                val original = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                val distance = original.center.y - root.top - 24f
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag("mini-player").performTouchInput {
                        down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -distance), 500)
                    }
                    compose.mainClock.advanceTimeByFrame()
                    compose.onNodeWithTag("now-playing").assertIsDisplayed()
                    compose.onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, distance), 500) }
                    compose.mainClock.advanceTimeByFrame()
                    val returned = compose.onNodeWithTag("mini-player").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    org.junit.Assert.assertTrue("The held finger must retrace the panel transition: $original / $returned",
                        kotlin.math.abs(returned.top - original.top) < 2f)
                    compose.onRoot().performTouchInput { up() }
                } finally { compose.mainClock.autoAdvance = true }
                compose.onNodeWithTag("mini-player").assertIsDisplayed()
            }
        } finally {
            runBlocking { app.controller.pause().join() }
            audio.delete()
        }
    }

    @Test
    fun swipesConnectTheMiniPlayerNowPlayingAndQueueOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = File(context.filesDir, "gesture-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input ->
            audio.outputStream().use { input.copyTo(it) }
        }
        val app = context.applicationContext as MusicApplication
        runBlocking {
            app.controller.library.importTrack(TrackMetadata("Gesture Fixture", "Fixture Artist"),
                TrackSource("ui-flac", SourceKind.LOCAL, audio.toURI().toString()))
            app.controller.reload().join()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("Gesture Fixture").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Gesture Fixture").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mini-player").performTouchInput {
                swipe(center, center.copy(y = center.y - 800), 250)
            }
            try {
                compose.waitUntil(5_000) {
                    compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot.top < compose.onRoot().fetchSemanticsNode().boundsInRoot.height * .1f
                }
            } catch (error: Throwable) {
                val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
                val destination = File(output, "gesture-failed.png").apply { parentFile!!.mkdirs() }
                val picture = instrumentation.uiAutomation.takeScreenshot()
                destination.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                picture.recycle()
                throw AssertionError("Root: ${compose.onRoot().fetchSemanticsNode().boundsInRoot}; player: ${compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot}; mini: ${compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().map { it.boundsInRoot }}; queue: ${compose.onNodeWithTag("queue-header", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot}", error)
            }
            compose.onNodeWithTag("now-playing").assertIsDisplayed().performTouchInput {
                swipe(center, center.copy(y = center.y - 800), 250)
            }
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("queue-mini-player").fetchSemanticsNode().boundsInRoot.top < compose.onRoot().fetchSemanticsNode().boundsInRoot.height * .1f
            }
            compose.onNodeWithTag("queue-header").assertIsDisplayed().performTouchInput {
                swipe(center, center.copy(y = center.y + 800), 250)
            }
            try {
                compose.waitUntil(5_000) {
                    compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot.top < compose.onRoot().fetchSemanticsNode().boundsInRoot.height * .1f
                }
            } catch (error: Throwable) {
                val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
                val destination = File(output, "gesture-failed.png").apply { parentFile!!.mkdirs() }
                val picture = instrumentation.uiAutomation.takeScreenshot()
                destination.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                picture.recycle()
                throw AssertionError("Root: ${compose.onRoot().fetchSemanticsNode().boundsInRoot}; player: ${compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot}; mini: ${compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().map { it.boundsInRoot }}; queue: ${compose.onNodeWithTag("queue-header", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot}", error)
            }
            compose.onNodeWithTag("now-playing").assertIsDisplayed().performTouchInput {
                swipe(center, center.copy(y = center.y + 800), 250)
            }
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
        }
    }
}
