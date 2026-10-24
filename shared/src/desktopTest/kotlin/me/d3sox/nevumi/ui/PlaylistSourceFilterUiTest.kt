package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PlaylistSourceFilterUiTest {
    @Test fun sourceFiltersHidePlaylistsAndFoldersWithoutMatchingSongsAndCanBeCleared() {
        val directory = Files.createTempDirectory("playlist-source-filter")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        runBlocking {
            val remote = library.importTrack(TrackMetadata("Remote song"), TrackSource("server", SourceKind.SERVER, "remote"))
            val downloaded = library.importTrack(TrackMetadata("Downloaded song"), TrackSource("download-server", SourceKind.SERVER, "downloaded"))
            library.importTrack(downloaded.metadata, TrackSource("download", SourceKind.DOWNLOAD, "/music/download.flac"), linkTo = downloaded.id)
            val device = library.importTrack(TrackMetadata("Device song"), TrackSource("device", SourceKind.LOCAL, "/music/device.flac"))
            playlists.create("Mixed playlist", listOf(remote.id, downloaded.id, downloaded.id, device.id))
            playlists.create("Remote playlist", listOf(remote.id))
            playlists.create("Device playlist", listOf(device.id))
            playlists.create("Empty playlist", emptyList())
            playlists.create("Offline ▸ Saved mix", listOf(downloaded.id))
            playlists.create("Offline ▸ Remote mix", listOf(remote.id))
            playlists.create("Offline ▸ Online only ▸ Hidden mix", listOf(remote.id))
            playlists.create("Online ▸ Remote mix", listOf(remote.id))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            playlists = playlists, preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady && controller.state.value.playlists.size == 8 }
                openLibraryPage("Playlists")
                onNodeWithText("Remote playlist").assertIsDisplayed()
                onNodeWithText("Empty playlist").assertIsDisplayed()
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Downloads").performClick()
                onNodeWithText("Remote playlist").assertDoesNotExist()
                onNodeWithText("Device playlist").assertDoesNotExist()
                onNodeWithText("Empty playlist").assertDoesNotExist()
                onNodeWithText("Online").assertDoesNotExist()
                onNodeWithText("Mixed playlist").assertIsDisplayed()
                onNodeWithText("2 playlists").assertIsDisplayed()
                onNodeWithText("Offline").performClick()
                onNodeWithTag("library-header").assertContentDescriptionEquals("Offline, 1 song")
                onNodeWithText("Saved mix").assertIsDisplayed()
                onNodeWithText("Remote mix").assertDoesNotExist()
                onNodeWithText("Online only").assertDoesNotExist()
                onNodeWithText("1 playlist").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Device").performClick()
                onNodeWithText("Device playlist").assertIsDisplayed()
                onNodeWithText("Mixed playlist").assertIsDisplayed()
                onNodeWithText("Remote playlist").assertDoesNotExist()
                onNodeWithText("Offline").assertDoesNotExist()
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Server").performClick()
                onNodeWithText("Remote playlist").assertIsDisplayed()
                onNodeWithText("Mixed playlist").assertIsDisplayed()
                onNodeWithText("Device playlist").assertDoesNotExist()
                onNodeWithText("Empty playlist").assertDoesNotExist()
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("All sources").performClick()
                onNodeWithText("Remote playlist").assertIsDisplayed()
                onNodeWithText("Device playlist").assertIsDisplayed()
                onNodeWithText("Empty playlist").assertIsDisplayed()
                onNodeWithText("Online").assertIsDisplayed()
                onNodeWithText("8 playlists").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun downloadsFilterHidesOtherCollectionsWithoutDownloadedSongs() {
        val directory = Files.createTempDirectory("collection-source-filter")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking {
            for (kind in listOf(SourceKind.SERVER, SourceKind.DOWNLOAD)) {
                val label = if (kind == SourceKind.DOWNLOAD) "Saved" else "Remote"
                library.importTrack(TrackMetadata("$label song", artist = "$label artist", album = "$label album",
                    albumArtist = "$label album artist", genres = listOf("$label genre"), folder = "$label folder"),
                    TrackSource(label, kind, "/music/$label.flac"))
            }
        }
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(libraryTabs = LibraryTab.entries) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Downloads").performClick()
                for ((tab, label) in listOf("Albums" to "album", "Artists" to "artist", "Album artists" to "album artist",
                    "Genres" to "genre", "Folders" to "folder")) {
                    openLibraryPage(tab)
                    onNodeWithText("Saved $label").assertIsDisplayed()
                    onNodeWithText("Remote $label").assertDoesNotExist()
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
