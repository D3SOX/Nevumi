package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class SavedQueuesUiTest {
    @Test fun savedQueuesCanBeOverwrittenAndSwitchedFromTheQueueMenu() {
        val directory = Files.createTempDirectory("saved-queues-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val tracks = runBlocking { (1..2).map { library.importTrack(TrackMetadata("Song $it", durationMs = 3_000), TrackSource("source:$it", SourceKind.LOCAL, audio)) } }
        val queue = PlaybackQueue(store)
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForLibrary(controller)
                runBlocking { controller.playTrack(tracks[0].id, listOf(tracks[0].id)).join(); controller.pause().join() }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Saved queues").performScrollTo().performClick()
                onNodeWithText("Save current queue").performClick()
                onNodeWithContentDescription("Queue name").performTextReplacement("Evening")
                onNodeWithText("Save queue").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.savedQueues.size == 1 }
                val evening = controller.state.value.savedQueues.single()
                onNode(hasText("Evening") and hasClickAction()).assertIsSelected()
                runBlocking { controller.playTrack(tracks[1].id, listOf(tracks[1].id)).join(); controller.pause().join(); controller.saveQueue("Morning").join() }
                val morning = runBlocking { queue.saved().first { it.name == "Morning" } }
                onNodeWithContentDescription("Saved queue options: Evening").performClick()
                onNodeWithContentDescription("Overwrite saved queue: Evening").performClick()
                onNodeWithText("Cancel").performClick()
                assertEquals(evening.snapshot, runBlocking { queue.saved().first { it.id == evening.id }.snapshot })
                onNodeWithContentDescription("Saved queue options: Evening").performClick()
                onNodeWithContentDescription("Overwrite saved queue: Evening").performClick()
                onNodeWithText("Overwrite").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.selectedSavedQueueId == evening.id }
                assertEquals(listOf(tracks[1].id), runBlocking { queue.saved().first { it.id == evening.id }.snapshot.entries.map { it.trackId } })
                onNodeWithText("Use temporary queue").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.selectedSavedQueueId == null }
                onNodeWithContentDescription("Load queue: Morning").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.selectedSavedQueueId == morning.id }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithContentDescription("Saved queues").performClick()
                onNode(hasText("Evening") and hasAnyAncestor(isPopup())).performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.selectedSavedQueueId == evening.id }
                assertFalse(controller.state.value.playback.playWhenReady)
                onNode(hasText("Evening") and hasAnyAncestor(hasTestTag("queue-header"))).assertIsDisplayed()
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun reopeningSavedQueuesAfterSelectingFromADetailPageShowsTheList() {
        val directory = Files.createTempDirectory("saved-queues-reopen-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("Song", durationMs = 3_000), TrackSource("source:1", SourceKind.LOCAL, audio)) }
        val queue = PlaybackQueue(store)
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForLibrary(controller)
                runBlocking { controller.playTrack(track.id, listOf(track.id)).join(); controller.pause().join(); controller.saveQueue("Evening").join() }
                fun selectFromDetailPage() {
                    onNode(hasText("Evening") and hasClickAction()).performClick()
                    onNodeWithText("Select queue").performClick()
                }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Saved queues").performScrollTo().performClick()
                selectFromDetailPage()
                onNodeWithText("Saved queues").performScrollTo().performClick()
                onNodeWithText("Save current queue").assertIsDisplayed()
                selectFromDetailPage()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithContentDescription("Saved queues").performClick()
                onNodeWithText("Manage saved queues").performClick()
                onNodeWithText("Save current queue").assertIsDisplayed()
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun aNewEmptyQueueKeepsOnlyThePlayingSong() {
        val directory = Files.createTempDirectory("empty-queue-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val tracks = runBlocking { (1..3).map { library.importTrack(TrackMetadata("Song $it", durationMs = 3_000), TrackSource("source:$it", SourceKind.LOCAL, audio)) } }
        val queue = PlaybackQueue(store)
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForLibrary(controller)
                runBlocking { controller.playTrack(tracks[1].id, tracks.map { it.id }, 1, "Album").join(); controller.pause().join() }
                val playing = controller.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithContentDescription("Saved queues").performClick()
                onNodeWithText("New empty queue").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.entries.size == 1 }
                assertEquals(playing, controller.state.value.queue.currentEntryId)
                assertEquals(listOf(tracks[1].id), runBlocking { queue.snapshot() }.entries.map { it.trackId })
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
