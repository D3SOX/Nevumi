package me.d3sox.nevumi

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidQueueBoundaryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun aFastDragStaysBelowUpNextAndCommitsTheFirstManualSlot() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "fast-queue.flac")
        InstrumentationRegistry.getInstrumentation().context.assets.open("waveform-long.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            val tracks = runBlocking { (0 until 8).map { index ->
                app.controller.library.importTrack(TrackMetadata("Fast Song %02d".format(index), durationMs = 600_000),
                    TrackSource("fast-queue:$index", SourceKind.LOCAL, audio.toURI().toString()))
            }.also {
                app.controller.updatePreferences { it.copy(precomputeWaveforms = false) }.join()
                app.controller.reload().join()
            } }
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.playTrack(tracks[0].id, tracks.map { it.id }).join() }
                compose.waitUntil(10_000) { app.controller.state.value.playback.playing }
                val current = app.controller.state.value.queue.currentEntryId
                val moved = app.controller.state.value.queue.context.first()
                compose.onNodeWithTag("mini-player").performClick(); compose.onNodeWithContentDescription("Queue").performClick()
                val heading = compose.onNodeWithTag("queue:manual").fetchSemanticsNode().boundsInRoot
                val handle = compose.onNodeWithContentDescription("Reorder: Fast Song 01", useUnmergedTree = true)
                val start = handle.fetchSemanticsNode().boundsInRoot.center.y
                val target = compose.onNodeWithTag("queue-mini-player").fetchSemanticsNode().boundsInRoot.center.y
                handle.performTouchInput { down(center); moveBy(Offset(0f, target - start), delayMillis = 50) }
                compose.waitForIdle()
                val moving = compose.onNodeWithTag("queue-entry:${moved.id}").fetchSemanticsNode().boundsInRoot
                assertTrue("Dragged songs must stay beneath Up next", moving.top >= heading.bottom - 1f)
                assertEquals(heading, compose.onNodeWithTag("queue:manual").fetchSemanticsNode().boundsInRoot)
                handle.performTouchInput { up() }
                compose.waitUntil(3_000) { app.controller.state.value.queue.upNext.singleOrNull()?.id == moved.id }
                assertEquals(current, app.controller.state.value.queue.currentEntryId)
                assertEquals(8, app.controller.state.value.queue.entries.size)
                assertTrue(app.controller.state.value.playback.playing)
                compose.onNodeWithContentDescription("Now playing").performClick()
                compose.onNodeWithContentDescription("Collapse").performClick()
                runBlocking {
                    app.controller.pause().join(); app.controller.clearQueue().join()
                    app.controller.enqueue(tracks.drop(3).take(3).map { it.id }, me.d3sox.nevumi.queue.QueueAction.APPEND).join()
                }
                val requested = app.controller.state.value.queue.upNext
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
                compose.onNodeWithTag("track-row:${tracks[5].id}").performTouchInput { click(center) }
                compose.waitUntil(3_000) { app.controller.state.value.queue.currentTrackId == tracks[5].id }
                assertEquals(requested, app.controller.state.value.queue.upNext)
                assertTrue(app.controller.state.value.queue.context.isEmpty())
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }

    @Test fun upcomingSongsStopBelowNowPlayingAndClearingAScrolledQueuePreservesPlayback() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "long-queue.flac")
        InstrumentationRegistry.getInstrumentation().context.assets.open("waveform-long.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            val tracks = runBlocking { (0 until 40).map { index ->
                app.controller.library.importTrack(TrackMetadata("Song %02d".format(index), durationMs = 600_000),
                    TrackSource("queue:$index", SourceKind.LOCAL, audio.toURI().toString()))
            }.also {
                app.controller.updatePreferences { it.copy(precomputeWaveforms = false) }.join()
                app.controller.reload().join()
            } }
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.playTrack(tracks[0].id, tracks.map { it.id }).join() }
                compose.waitUntil(10_000) { app.controller.state.value.playback.playing }
                val current = app.controller.state.value.queue.currentEntryId
                val moved = app.controller.state.value.queue.context.first()
                compose.onNodeWithTag("mini-player").performClick(); compose.onNodeWithContentDescription("Queue").performClick()
                val protected = compose.onNodeWithTag("queue-entry:$current").fetchSemanticsNode().boundsInRoot
                val target = protected.top - 100f
                fun handle() = compose.onNodeWithContentDescription("Reorder: Song 01", useUnmergedTree = true)
                val distance = target - handle().fetchSemanticsNode().boundsInRoot.center.y
                handle().performTouchInput { down(center); moveBy(Offset(0f, -20f), delayMillis = 50) }
                compose.waitForIdle()
                repeat(10) {
                    handle().performTouchInput { moveBy(Offset(0f, (distance + 20f) / 10), delayMillis = 50) }
                    compose.waitForIdle()
                    val row = compose.onNodeWithTag("queue-entry:${moved.id}").fetchSemanticsNode().boundsInRoot
                    assertTrue("Upcoming songs must stop below Now playing", row.top >= compose.onNodeWithTag("queue:manual").fetchSemanticsNode().boundsInRoot.bottom - 1f)
                }
                handle().performTouchInput { up() }
                compose.waitUntil(3_000) { app.controller.state.value.queue.upNext.singleOrNull()?.id == moved.id }
                compose.onNodeWithContentDescription("Scroll queue").performSemanticsAction(SemanticsActions.SetProgress) { it(25f) }
                compose.onNodeWithContentDescription("Clear queue").performClick(); compose.onNodeWithText("All").performClick()
                compose.onNodeWithText("Clear", substring = false).performClick()
                compose.waitUntil(3_000) { app.controller.state.value.queue.entries.size == 1 }
                compose.onNodeWithTag("queue-entry:$current").assertIsDisplayed()
                assertEquals(current, app.controller.state.value.queue.currentEntryId)
                assertTrue(app.controller.state.value.playback.playing)
                compose.onNodeWithText("Undo").performClick()
                compose.waitUntil(3_000) { app.controller.state.value.queue.entries.size == 40 }
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }
}
