package me.d3sox.nevumi

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

class AndroidSavedQueuesUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savingOverwritingAndSwitchingQueuesKeepsThePositionAndSurvivesRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val file = File(app.cacheDir, "saved-queue-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use(input::copyTo) }
        val tracks = runBlocking { (1..2).map { controller.library.importTrack(TrackMetadata("Queue song $it", durationMs = 3_000),
            TrackSource("source:$it", SourceKind.LOCAL, file.path)) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForLibrary(controller)
                runBlocking { controller.playTrack(tracks[0].id, listOf(tracks[0].id)).join(); controller.pause().join(); controller.seek(600).join() }
                compose.waitUntil(5_000) { controller.state.value.playback.positionMs in 550..650 }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Saved queues").performScrollTo().performClick()
                compose.onNodeWithText("Save current queue").performClick()
                compose.onNodeWithContentDescription("Queue name").performTextReplacement("Evening")
                compose.onNodeWithText("Save queue").performClick()
                compose.waitUntil(5_000) { controller.state.value.savedQueues.size == 1 }
                val evening = controller.state.value.savedQueues.single()
                runBlocking { controller.playTrack(tracks[1].id, listOf(tracks[1].id)).join(); controller.pause().join(); controller.saveQueue("Morning").join() }
                val morning = controller.state.value.savedQueues.first { it.name == "Morning" }
                compose.onNodeWithContentDescription("Load queue: Evening").performClick()
                try { compose.waitUntil(5_000) { controller.state.value.queue.selectedSavedQueueId == evening.id && controller.state.value.playback.positionMs in 550..650 } }
                catch (failure: Throwable) { throw AssertionError("Queue: ${controller.state.value.queue}; playback: ${controller.state.value.playback}; saved: ${controller.state.value.savedQueues}; error: ${controller.state.value.error}", failure) }
                assertFalse(controller.state.value.playback.playWhenReady)
                scenario.recreate()
                compose.waitUntil(5_000) { controller.state.value.queue.selectedSavedQueueId == evening.id }
                compose.onNodeWithText("Saved queues").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Saved queue options: Morning").performClick()
                compose.onNodeWithContentDescription("Overwrite saved queue: Morning").performClick()
                compose.onNodeWithText("Overwrite").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.selectedSavedQueueId == morning.id }
                assertEquals(listOf(tracks[0].id), controller.state.value.savedQueues.first { it.id == morning.id }.snapshot.entries.map { it.trackId })
                compose.onNodeWithText("Use temporary queue").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.selectedSavedQueueId == null }
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.pause().join() }; file.delete() }
    }
}
