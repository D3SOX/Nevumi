package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidQueueToolsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun snapshotsPreviewHistoryExportUpNextAndRestoreAsTemporaryQueuesOnAPhone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val audio = File(app.cacheDir, "queue-tools.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForLibrary(controller)
                val tracks = runBlocking { (1..3).map { controller.library.importTrack(TrackMetadata("Snapshot song $it", "Artist", durationMs = 3_000),
                    TrackSource("snapshot:$it", SourceKind.LOCAL, audio.path)) } }
                runBlocking {
                    controller.reload().join(); controller.playTrack(tracks[1].id, listOf(tracks[0].id, tracks[1].id, tracks[1].id), 2).join()
                    controller.pause().join(); controller.enqueue(listOf(tracks[2].id, tracks[2].id), QueueAction.APPEND).join(); controller.seek(1_000).join()
                }
                try { compose.waitUntil(5_000) { controller.state.value.playback.positionMs in 950..1_050 } }
                catch (failure: Throwable) { throw AssertionError("Queue=${controller.state.value.queue}; playback=${controller.state.value.playback}; error=${controller.state.value.error}", failure) }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Saved queues").performScrollTo().performClick()
                compose.onNodeWithText("Save current queue").performClick()
                compose.onNodeWithContentDescription("Queue name").performTextReplacement("Phone checkpoint")
                compose.onNodeWithContentDescription("Save as").performClick()
                compose.onNode(hasText("Fixed snapshot") and hasAnyAncestor(isPopup())).performClick()
                compose.onNodeWithText("Save queue").performClick()
                compose.waitUntil(5_000) { controller.state.value.savedQueues.any { it.name == "Phone checkpoint" } }
                val saved = controller.state.value.savedQueues.single()
                assertEquals(SavedQueueKind.SNAPSHOT, saved.kind)
                compose.onNode(hasText("Phone checkpoint") and hasClickAction()).performClick()
                compose.onNodeWithText("Previously played").assertIsDisplayed()
                compose.onNodeWithText("Now playing").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Export queue").performClick()
                compose.onNodeWithContentDescription("Include").performClick()
                compose.onNode(hasText("Up next") and hasAnyAncestor(isPopup())).performClick()
                compose.onNodeWithText("Save as playlist").performClick()
                compose.waitUntil(5_000) { controller.state.value.playlists.any { it.name == "Phone checkpoint" } }
                assertEquals(listOf(tracks[2].id, tracks[2].id), controller.state.value.playlists.single().trackIds)
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss))[0]
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Recovery settings").performClick()
                compose.onNodeWithContentDescription("Capture automatically").assertIsOff().performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.queueRecoveryEnabled }
                runBlocking { controller.playTrack(tracks[0].id).join(); controller.pause().join() }
                compose.waitUntil(5_000) { controller.state.value.savedQueues.any { it.kind == SavedQueueKind.RECOVERY } }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Snapshots").performClick()
                compose.onNode(hasText("Phone checkpoint") and hasClickAction()).performClick()
                compose.onNodeWithText("Restore snapshot").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.currentEntryId == saved.snapshot.currentEntryId && controller.state.value.playback.positionMs in 950..1_050 }
                assertNull(controller.state.value.queue.selectedSavedQueueId)
                assertEquals(saved.snapshot.entries, controller.state.value.queue.entries)
                scenario.recreate()
                assertEquals(saved.snapshot.entries, controller.state.value.queue.entries)
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.pause().join() }; audio.delete() }
    }
}
