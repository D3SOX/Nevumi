package me.d3sox.nevumi.library

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.ui.droppedMusic
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DroppedMusicTest {
    private val fixtures = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets")

    @Test fun droppedFoldersJoinTheLibraryAndDroppedSongsPlayFromOutsideIt() = runBlocking {
        val directory = Files.createTempDirectory("dropped-music").toRealPath()
        val store = openMusicStore(directory.resolve("library.db").toString())
        val music = Files.createDirectories(directory.resolve("Music"))
        val album = Files.createDirectories(directory.resolve("Elsewhere/Album"))
        val downloads = Files.createDirectories(directory.resolve("Downloads"))
        Files.copy(fixtures.resolve("fixture.flac"), album.resolve("song.flac"))
        val loose = Files.copy(fixtures.resolve("fixture.mp3"), downloads.resolve("loose.mp3"))
        Files.writeString(downloads.resolve("notes.txt"), "not music")
        val preferences = MusicPreferences(store)
        preferences.update { it.copy(includeFolders = listOf(music.toString()), excludeFolders = listOf(album.resolve("Hidden").toString())) }
        val library = MusicLibrary(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = WatchingLocalFiles(library),
            preferences = preferences)
        try {
            withTimeout(3_000) { controller.state.first { it.libraryReady } }
            val dropped = listOf(album, loose, downloads.resolve("notes.txt"), directory.resolve("missing.flac"))
            val (folders, songs) = droppedMusic(dropped.map { it.toUri().toString() })
            assertEquals(listOf(album.toString()), folders)
            assertEquals(listOf(loose.toString()), songs)

            controller.addMusic(folders, emptyList()).join()
            val settings = controller.state.value.preferences
            assertEquals(listOf(music.toString(), album.toString()), settings.includeFolders)
            // Adding the whole folder lifts the exclusions inside it.
            assertEquals(emptyList(), settings.excludeFolders)
            assertTrue(controller.state.value.tracks.any { track -> track.sources.any { it.location == album.resolve("song.flac").toString() } })

            // A loose song plays even though Downloads is not a library folder.
            controller.addMusic(emptyList(), songs).join()
            val state = withTimeout(3_000) { controller.state.first { it.queue.entries.size == 1 } }
            val playing = state.tracks.single { it.id == state.queue.currentTrackId }
            assertEquals(loose.toString(), playing.sources.single().location)
            assertEquals("Dropped songs", state.queue.contextName)
            assertEquals(listOf(music.toString(), album.toString()), state.preferences.includeFolders)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun droppingAnAlreadyIncludedFolderIndexesNewSongsImmediately() = runBlocking {
        for (explicitInclude in listOf(false, true)) {
            val directory = Files.createTempDirectory("dropped-included").toRealPath()
            val store = openMusicStore(directory.resolve("library.db").toString())
            val music = Files.createDirectories(directory.resolve("Music"))
            val album = Files.createDirectories(music.resolve("Album"))
            val preferences = MusicPreferences(store)
            val included = if (explicitInclude) listOf(music.toString()) else emptyList()
            preferences.update { it.copy(includeFolders = included) }
            val library = MusicLibrary(store)
            // No filesystem watcher: the drop itself must discover the newly added song.
            val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = LocalFiles(library),
                preferences = preferences)
            try {
                withTimeout(3_000) { controller.state.first { it.libraryReady } }
                controller.importSource(music.toString()).join()
                val song = Files.copy(fixtures.resolve("fixture.flac"), album.resolve("new.flac"))
                controller.addMusic(listOf(album.toString()), emptyList()).join()
                assertTrue(controller.state.value.tracks.any { track -> track.sources.any { it.location == song.toString() && it.available } })
                assertEquals(included, controller.state.value.preferences.includeFolders)
            } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
        }
    }

    @Test fun droppingIntoAnExcludedFolderOrNothingPlayableExplainsWhy() = runBlocking {
        val directory = Files.createTempDirectory("dropped-excluded").toRealPath()
        val store = openMusicStore(directory.resolve("library.db").toString())
        val excluded = Files.createDirectories(directory.resolve("Excluded/Album"))
        val preferences = MusicPreferences(store)
        preferences.update { it.copy(excludeFolders = listOf(directory.resolve("Excluded").toString())) }
        val library = MusicLibrary(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = WatchingLocalFiles(library),
            preferences = preferences)
        try {
            withTimeout(3_000) { controller.state.first { it.libraryReady } }
            controller.addMusic(listOf(excluded.toString()), emptyList()).join()
            assertEquals("Your excluded folder ${directory.resolve("Excluded")} also hides $excluded. " +
                "Remove that exclusion in Settings › Local music to add it.", controller.state.value.error)
            assertEquals(emptyList(), controller.state.value.preferences.includeFolders)
            controller.addMusic(emptyList(), emptyList()).join()
            assertEquals("Drop music folders or MP3, FLAC and WAV files", controller.state.value.error)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
