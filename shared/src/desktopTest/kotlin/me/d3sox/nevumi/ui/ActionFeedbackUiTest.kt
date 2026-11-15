package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.material3.SnackbarDuration
import me.d3sox.nevumi.app.ActionDestination
import me.d3sox.nevumi.app.ActionNotice
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.NoticeDuration
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_soundcloud_check_tags
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ActionFeedbackUiTest {
    @Test
    fun noticesWaitingOnTheUserStayUntilDismissed() {
        val visuals = ActionSnackbarVisuals(ActionNotice(Res.string.notice_soundcloud_check_tags, ActionDestination.SOUNDCLOUD_DOWNLOADS,
            arguments = listOf("Song"), duration = NoticeDuration.UNTIL_DISMISSED), "Open downloads")
        assertEquals(SnackbarDuration.Indefinite, visuals.duration)
        assertTrue(visuals.withDismissAction)
        assertEquals(SnackbarDuration.Short, ActionSnackbarVisuals(ActionNotice(Res.string.notice_soundcloud_check_tags), null).duration)
        assertEquals(SnackbarDuration.Long, ActionSnackbarVisuals(ActionNotice(Res.string.notice_soundcloud_check_tags, play = { Job() }), "Play").duration)
    }

    @Test
    fun addingSongsOffersUndoAndAQueueShortcut() {
        val directory = Files.createTempDirectory("action-feedback-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitForLibrary(player)
                runBlocking { player.playTrack(songs[0].id, songs.map { it.id }).join(); player.pause().join() }
                val before = player.state.value.queue.entries
                runBlocking { player.enqueue(songs[2].id, QueueAction.APPEND).join() }
                onNodeWithText("Added to queue").assertIsDisplayed()
                onNodeWithText("Undo").assertIsDisplayed()
                onNodeWithContentDescription("Open queue").assertHasClickAction()
                onNodeWithText("Undo").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.entries == before }
                assertEquals(before, player.state.value.queue.entries)
                onNodeWithText("Undone").assertIsDisplayed()
                runBlocking { player.enqueue(songs[2].id, QueueAction.APPEND).join() }
                val notice = onNodeWithText("Added to queue")
                val beforeDrag = notice.fetchSemanticsNode().boundsInRoot
                notice.performTouchInput {
                    down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -100f), delayMillis = 100)
                }
                waitForIdle()
                assertEquals(beforeDrag.top, notice.fetchSemanticsNode().boundsInRoot.top, "Vertical gestures must not move the notice")
                notice.performTouchInput { up() }
                onNodeWithText("Added to queue").assertIsDisplayed().performTouchInput {
                    swipe(center, center.copy(x = center.x + 500f), 180)
                }
                waitUntil(timeoutMillis = 1_000) { onAllNodesWithText("Added to queue").fetchSemanticsNodes().isEmpty() }
                assertEquals(before.size + 1, player.state.value.queue.entries.size)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
