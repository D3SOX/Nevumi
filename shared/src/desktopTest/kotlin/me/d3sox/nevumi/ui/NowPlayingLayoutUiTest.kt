package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class NowPlayingLayoutUiTest {
    @Test
    fun waveformSeekingKeepsThePlaybackPositionControlAccessible() {
        val directory = Files.createTempDirectory("waveform-player-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitForLibrary(player)
                runBlocking { player.playTrack(songs[0].id).join(); player.pause().join() }
                onNodeWithTag("mini-player").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("audio-waveform").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("Playback position").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1_500f) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.playback.positionMs in 1_450..1_550 }
                onNodeWithContentDescription("Audio output").performClick()
                onNodeWithText("This device").assertIsDisplayed()
                onNodeWithContentDescription("Close audio output").performClick()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun queueHasAFixedMiniPlayerAndScopedClearOptionsWithUndo() {
        val directory = Files.createTempDirectory("queue-header-clear")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitForLibrary(player)
                runBlocking {
                    player.playTrack(songs[0].id, songs.map { it.id }).join(); player.pause().join()
                    player.enqueue(listOf(songs[0].id, songs[1].id), me.d3sox.nevumi.queue.QueueAction.APPEND).join()
                }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithTag("queue-mini-player").assertIsDisplayed()
                onNodeWithContentDescription("Queue play/pause").assertHasClickAction()
                onNodeWithContentDescription("Clear queue").performClick()
                onNodeWithText("Duplicated tracks").assertIsDisplayed().performClick()
                onNodeWithText("Clear").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.entries.size == 3 }
                val before = player.state.value.queue.entries
                onNodeWithContentDescription("Clear queue").performClick()
                onNodeWithText("Next").performClick()
                onNodeWithText("Clear").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.entries.size == 1 }
                onNodeWithText("Undo").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.entries == before }
                kotlin.test.assertEquals(songs[0].id, player.state.value.queue.currentTrackId)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun contextAndTrackActionsSitAboveTheTransportRowAndRepeatCountIsInItsMenu() {
        val directory = Files.createTempDirectory("now-playing-layout")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitForLibrary(player)
                runBlocking { player.playTrack(songs[0].id, songs.map { it.id }, contextName = "My album").join(); player.pause().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithText("Playing from").assertIsDisplayed()
                onNodeWithText("My album").assertIsDisplayed()
                onNodeWithContentDescription("Shuffle").assertIsDisplayed()
                onNodeWithContentDescription("Audio output").assertIsDisplayed()
                fun center(label: String) = onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
                assertTrue(center("Favorite").y < center("Next track").y)
                assertTrue(center("Manage playlists").y < center("Next track").y)
                assertTrue(center("Queue").y > center("Next track").y)
                assertTrue(kotlin.math.abs(center("Shuffle").y - center("Repeat").y) < 2)
                onNodeWithContentDescription("Repeat count").assertDoesNotExist()
                onNodeWithContentDescription("Repeat").performClick()
                onNodeWithText("Repeat count").performClick()
                onNodeWithText("Total plays").performTextReplacement("3")
                onNodeWithText("Set repeat count").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.repeatMode == RepeatMode.ONE && player.state.value.queue.repeatPlays == 3 }
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
