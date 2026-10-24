package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LibraryHeaderUiTest {
    @Test fun portraitHeaderFollowsNavigationAndPlaylistEdits() = checkHeader(Size(390f, 844f), "portrait")
    @Test fun desktopHeaderFollowsNavigationAndPlaylistEdits() = checkHeader(Size(1100f, 900f), "desktop")

    private fun checkHeader(size: Size, name: String) {
        val directory = Files.createTempDirectory("library-header")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val preferences = MusicPreferences(store)
        val title = "An evening playlist with a very long name for quiet weekends"
        val (songs, playlist) = runBlocking {
            preferences.update { it.copy(libraryTabs = LibraryTab.entries, startupTab = LibraryTab.SONGS) }
            val songs = (1..3).map { index ->
                library.importTrack(TrackMetadata("Song $index", artist = "Evening artist", album = "Evening album"),
                    TrackSource("local:$index", SourceKind.LOCAL, "/music/$index.flac"))
            }
            library.setFavorite(songs.first().id, true)
            playlists.create("Evening mixes ▸ Shared songs", listOf(songs[0].id, songs[1].id, songs[0].id))
            playlists.create("Evening mixes ▸ Nested ▸ Another mix", listOf(songs[1].id))
            playlists.create("Empty mixes ▸ Empty playlist", emptyList())
            songs to playlists.create(title, listOf(songs.first().id, songs.first().id))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            playlists = playlists, preferences = preferences)
        try {
            runSkikoComposeUiTest(size = size) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                fun header(description: String) = onNodeWithTag("library-header").assertContentDescriptionEquals(description)
                header("Songs, 3 songs")
                openLibraryPage("Favorites")
                header("Favorites, 1 song")
                runBlocking { controller.updatePreferences { it.copy(sourceFilter = SourceKind.LOCAL) }.join() }
                header("Favorites, 1 of 1 song").assertHasClickAction().performClick()
                onNodeWithText("Cancel").performClick()
                runBlocking { controller.updatePreferences { it.copy(sourceFilter = null) }.join() }
                openLibraryPage("Albums")
                onNodeWithText("Evening album").performClick()
                header("Evening album, 3 songs")
                onNodeWithContentDescription("Back").performClick()
                header("Albums, 3 songs")
                openLibraryPage("Playlists")
                header("Playlists")
                onNodeWithText(title).performClick()
                header("$title, 2 songs")
                onNodeWithContentDescription("Settings").assertIsDisplayed()
                capture("playlist-header-$name")
                runBlocking { controller.removePlaylistTrack(playlist.id, 0).join() }
                header("$title, 1 song")
                openLibraryPage("Songs")
                header("Songs, 3 songs")
                openLibraryPage("Playlists")
                header("$title, 1 song")
                runBlocking { controller.removePlaylistTrack(playlist.id, 0).join() }
                header("$title, 0 songs")
                onNodeWithContentDescription("Back").performClick()
                header("Playlists")
                onNodeWithText("Evening mixes").performClick()
                header("Evening mixes, 2 songs")
                capture("playlist-folder-header-$name")
                onNodeWithText("Nested").performClick()
                header("Evening mixes ▸ Nested, 1 song")
                onNodeWithContentDescription("Back").performClick()
                header("Evening mixes, 2 songs")
                onNodeWithContentDescription("Back").performClick()
                header("Playlists")
                onNodeWithText("Empty mixes").performClick()
                header("Empty mixes, 0 songs")
                openLibraryPage("History")
                header("History, 0 songs")
                openLibraryPage("Favorites")
                runBlocking { controller.setFavorite(songs.first().id, false).join() }
                header("Favorites, 0 songs")
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    private fun ComposeUiTest.capture(name: String) {
        System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { output ->
            val pixels = onRoot().captureToImage().toPixelMap()
            val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
            ImageIO.write(image, "png", Path.of(output).also(Files::createDirectories).resolve("$name.png").toFile())
        }
    }
}
