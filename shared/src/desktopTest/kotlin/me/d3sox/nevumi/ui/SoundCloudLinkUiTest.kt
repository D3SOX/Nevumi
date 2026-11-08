package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.soundcloud.ScGateDestination
import me.d3sox.nevumi.soundcloud.ScGateFormat
import me.d3sox.nevumi.soundcloud.SoundCloudJob
import me.d3sox.nevumi.soundcloud.SoundCloudJobState
import me.d3sox.nevumi.soundcloud.SoundCloudTrack
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SoundCloudLinkUiTest {
    @Test
    fun aPastedLinkWithLineBreaksStaysVisible() {
        val directory = Files.createTempDirectory("soundcloud-link")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        val job = SoundCloudJob("job", SoundCloudTrack(1, "https://soundcloud.com/a/b", "Gated song", "Artist"),
            ScGateDestination.LIBRARY, ScGateFormat.MP3_320, "http://127.0.0.1:1", state = SoundCloudJobState.NEEDS_LINK)
        try {
            runComposeUiTest {
                setContent { SoundCloudJobItem(job, controller, viewer = false) {} }
                onNodeWithContentDescription("Paste link", substring = true).performClick()
                onNode(hasSetTextAction()).performTextInput("\nhttps://hypeddit.com/x/gated-song \n")
                onNode(hasSetTextAction()).assert(hasText("https://hypeddit.com/x/gated-song"))
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
