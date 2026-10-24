package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PlayerStartupUiTest {
    @Test
    fun restoredMiniPlayerDoesNotBuildHiddenPanelsUntilExpanded() {
        val directory = Files.createTempDirectory("player-startup")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        runBlocking {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Restored song", "Artist"),
                TrackSource("source", SourceKind.LOCAL, "/music/song.flac"))
            PlaybackQueue(store).replace(listOf(track.id), 0)
        }
        store.close()
        store = openMusicStore(path)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                setContent { Box(Modifier.size(1000.dp, 740.dp)) { MusicPlayerApp(controller) } }
                onNodeWithTag("mini-player").assertIsDisplayed()
                onNodeWithTag("now-playing", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("queue-mini-player", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("mini-player").performClick()
                onNodeWithTag("now-playing").assertIsDisplayed()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithTag("queue-mini-player").assertIsDisplayed()
                onNodeWithContentDescription("Now playing").performClick()
                onNodeWithContentDescription("Collapse").performClick()
                onNodeWithTag("mini-player").assertIsDisplayed()
                onNodeWithTag("now-playing", useUnmergedTree = true).assertDoesNotExist()
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            store.close()
            directory.toFile().deleteRecursively()
        }
    }
}
