package me.d3sox.nevumi.library

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class SourceImportTest {
    @Test
    fun folderChangesAppearWhileTheAppIsOpenWithoutMovingThePausedPlayhead() = runBlocking {
        val directory = Files.createTempDirectory("live-folder-watch")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var controller: MusicController? = null
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album")
            Files.copy(fixtures.resolve("01 Northern Light.flac"), music.resolve("first.flac"))
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(music.toString()).single()
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = WatchingLocalFiles(library))
            controller = player
            player.playTrack(track.id, listOf(track.id)).join()
            player.pause().join()
            player.seek(1_000).join()
            withTimeout(3_000) { player.state.first { !it.playback.playing && it.playback.positionMs in 990..1100 } }
            val queue = player.queue.snapshot()
            Files.copy(fixtures.resolve("02 Long Way Home.mp3"), music.resolve("second.mp3"))
            val updated = withTimeout(5_000) { player.state.first { it.tracks.any { track -> track.metadata.title == "Long Way Home" } } }
            assertEquals(queue.entries, updated.queue.entries)
            assertEquals(queue.currentEntryId, updated.playback.entryId)
            assertEquals(false, updated.playback.playing)
            assertEquals(true, updated.playback.positionMs in 990..1100)
        } finally { controller?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aFavoriteChangedInTheLibraryAppearsInThePlayerWithoutReloading() = runBlocking {
        val directory = Files.createTempDirectory("live-library-change")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Favorite from another screen"),
            TrackSource("local-favorite", SourceKind.LOCAL, "/music/favorite.flac"))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            library.setFavorite(track.id, true)
            val updated = withTimeout(3_000) { controller.state.first { it.tracks.single().favorite } }
            assertEquals(track.id, updated.tracks.single().id)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun reopeningTheAppRescansRememberedFoldersAndFindsChangesMadeWhileItWasClosed() = runBlocking {
        val directory = Files.createTempDirectory("restart-local-folder")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var controller: MusicController? = null
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album")
            val removed = music.resolve("first.flac")
            Files.copy(fixtures.resolve("01 Northern Light.flac"), removed)
            val library = MusicLibrary(store)
            val before = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = LocalFiles(library))
            controller = before
            before.importSource(music.toString()).join()
            val previous = before.state.value.tracks.single()
            before.closeAndJoin(); store.close()
            Files.delete(removed)
            Files.copy(fixtures.resolve("02 Long Way Home.mp3"), music.resolve("second.mp3"))
            store = openMusicStore(database)
            val restoredLibrary = MusicLibrary(store)
            val restored = MusicController(restoredLibrary, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = LocalFiles(restoredLibrary))
            controller = restored
            val updated = withTimeout(3_000) { restored.state.first { state ->
                state.tracks.any { it.metadata.title == "Long Way Home" } &&
                    state.tracks.firstOrNull { it.id == previous.id }?.sources?.none { it.available } == true
            } }
            assertEquals(null, SourceResolver.resolve(updated.tracks.single { it.id == previous.id }.sources, online = false))
            assertEquals(listOf("Long Way Home"), updated.tracks.filter { SourceResolver.resolve(it.sources, online = false) != null }.map { it.metadata.title })
        } finally { controller?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun addingALocalFolderKeepsExistingServerMusicAndTheQueue() = runBlocking {
        val directory = Files.createTempDirectory("source-import-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val queue = PlaybackQueue(store)
        val remote = library.importTrack(TrackMetadata("Server original"), TrackSource("server-original", SourceKind.SERVER, "opaque"))
        queue.replace(listOf(remote.id))
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"), localSources = LocalFiles(library))
        try {
            controller.importSource(Path.of(System.getProperty("nevumi.testRoot"),
                ".test-environment", "music").toString()).join()

            assertEquals(setOf("Long Way Home", "Northern Light", "Night Walk", "Server original") to remote.id,
                controller.state.value.tracks.map { it.metadata.title }.toSet() to queue.snapshot().currentTrackId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
