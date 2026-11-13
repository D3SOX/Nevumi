package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
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
class LibraryTabReselectUiTest {
    @Test fun tappingTheOpenTabAgainReturnsToItsTopLevel() {
        val directory = Files.createTempDirectory("library-tab-reselect")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val preferences = MusicPreferences(store)
        runBlocking {
            preferences.update { it.copy(libraryTabs = LibraryTab.entries, startupTab = LibraryTab.SONGS) }
            val songs = (1..2).map { index ->
                library.importTrack(TrackMetadata("Song $index", artist = "Night artist", album = "Night album"),
                    TrackSource("local:$index", SourceKind.LOCAL, "/music/$index.flac"))
            }
            playlists.create("Night mixes ▸ Nested ▸ Deep mix", songs.map { it.id })
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            playlists = playlists, preferences = preferences)
        try {
            runSkikoComposeUiTest(size = Size(390f, 844f)) {
                setContent { MusicPlayerApp(controller) }
                waitForLibrary(controller)
                fun header(description: String) = onNodeWithTag("library-header").assertContentDescriptionEquals(description)
                openLibraryPage("Playlists")
                onNodeWithText("Night mixes").performClick()
                onNodeWithText("Nested").performClick()
                onNodeWithText("Deep mix").performClick()
                header("Deep mix, 2 songs")
                openLibraryPage("Playlists")
                header("Playlists")
                onNodeWithContentDescription("Back").assertDoesNotExist()
                openLibraryPage("Playlists")
                header("Playlists")

                openLibraryPage("Albums")
                onNodeWithText("Night album").performClick()
                header("Night album, 2 songs")
                openLibraryPage("Albums")
                header("Albums, 2 songs")
                onNodeWithContentDescription("Back").assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
