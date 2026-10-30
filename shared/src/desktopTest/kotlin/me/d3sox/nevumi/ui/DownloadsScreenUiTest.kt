package me.d3sox.nevumi.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.downloads.*
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.soundcloud.*
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DownloadsScreenUiTest {
    @Test fun theChosenGroupStaysPutAndSearchStaysWhileItFilters() {
        val directory = Files.createTempDirectory("tabs")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        val tracks = (0..20).map { Track("t$it", TrackMetadata("Song $it", "Artist"), emptyList()) }
        val downloads = tracks.associate { it.id to DownloadRecord(it.id, DownloadStatus.COMPLETE, sizeBytes = 2_000_000) }
        val playlist = PlaylistDownloadProgress(Playlist("p", "Mix", emptyList(), autoDownload = true), 1, 1, 1f)
        val job = SoundCloudJob("j", SoundCloudTrack(1, "", "Gate Song", "DJ"), ScGateDestination.CLIENT, ScGateFormat.MP3_320, "http://x",
            state = SoundCloudJobState.NEEDS_TAGS)
        var state by mutableStateOf(controller.state.value.copy(tracks = tracks, downloads = downloads, playlistDownloads = listOf(playlist), soundCloudJobs = listOf(job)))
        try {
            var arriving by mutableStateOf(state.copy(downloads = emptyMap(), playlistDownloads = emptyList(), soundCloudJobs = emptyList()))
            runComposeUiTest {
                setContent { MaterialTheme { DownloadsScreen(arriving, controller) {} } }
                waitForIdle()
                arriving = arriving.copy(downloads = downloads)
                waitForIdle()
                // A playlist that starts downloading while the page is open does not take it over.
                arriving = arriving.copy(playlistDownloads = listOf(playlist))
                waitForIdle()
                onNodeWithText("Mix").assertDoesNotExist()
                onNodeWithText("Song 0").assertExists()
            }
            runComposeUiTest {
                setContent { MaterialTheme { CompositionLocalProvider(LocalArtworkPicker provides ArtworkPicker { null }) { DownloadsScreen(state, controller) {} } } }
                onNodeWithText("Gate Song").assertExists()
                onNodeWithContentDescription("Check tags: Gate Song", substring = true).performClick()
                waitForIdle()
                onNodeWithText("Mix").assertDoesNotExist()
                // The tags were saved elsewhere, so the editor closes and the page comes back.
                state = state.copy(soundCloudJobs = listOf(job.copy(state = SoundCloudJobState.COMPLETE)))
                waitForIdle()
                onNodeWithText("Gate Song").assertExists()
                onNodeWithText("Mix").assertDoesNotExist()
                onNodeWithText("Songs · 21").performClick()
                onNodeWithText("Search songs").performTextInput("Song 1")
                waitForIdle()
                state = state.copy(downloads = downloads - "t20" - "t19")
                waitForIdle()
                onNodeWithText("Search songs").assertExists()
                onNodeWithText("Song 10").assertExists()
                onNodeWithText("Song 2").assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
