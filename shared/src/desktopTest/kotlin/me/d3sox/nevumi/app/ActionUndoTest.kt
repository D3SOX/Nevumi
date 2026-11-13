package me.d3sox.nevumi.app

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*

class ActionUndoTest {
    @Test fun confirmationsAlternateUndoAndRedoWithoutChangingAnotherSelectedQueue() = runBlocking {
        val directory = Files.createTempDirectory("action-redo")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val audio = java.nio.file.Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val songs = (0..2).map { library.importTrack(TrackMetadata("Song $it"), TrackSource("local:$it", SourceKind.LOCAL, audio)) }
        val queue = PlaybackQueue(store)
        val player = MusicController(library, queue, MpvPlaybackEngine("null"), playlists = playlists)
        suspend fun notice(action: () -> Job): ActionNotice = coroutineScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { player.notices.first() }
            action().join()
            withTimeout(3_000) { pending.await() }
        }
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            val favorite = notice { player.setFavorite(songs[0].id, true) }
            val redoFavorite = notice { assertNotNull(favorite.undo)() }
            assertTrue(redoFavorite.isRedo)
            assertFalse(library.findTrack(songs[0].id)!!.favorite)
            val undoFavorite = notice { assertNotNull(redoFavorite.undo)() }
            assertFalse(undoFavorite.isRedo)
            assertTrue(library.findTrack(songs[0].id)!!.favorite)
            notice { assertNotNull(undoFavorite.undo)() }
            assertFalse(library.findTrack(songs[0].id)!!.favorite)
            val created = notice { player.createPlaylist("Mix", songs.map { it.id }) }
            val playlistId = playlists.all().single().id
            val redoCreation = notice { assertNotNull(created.undo)() }
            assertTrue(playlists.all().isEmpty())
            notice { assertNotNull(redoCreation.undo)() }
            assertEquals(playlistId, playlists.all().single().id)
            player.playTrack(songs[0].id, listOf(songs[0].id, songs[1].id, songs[0].id), 2).join()
            assertNull(player.state.value.error)
            player.pause().join(); player.seek(700).join()
            withTimeout(3_000) { player.state.first { it.playback.positionMs in 650..750 } }
            player.saveQueue("Evening").join()
            val evening = queue.saved().single()
            val original = queue.snapshot()
            val added = notice { player.enqueue(songs[2].id, me.d3sox.nevumi.queue.QueueAction.APPEND) }
            player.playTrack(songs[2].id).join(); player.pause().join(); player.saveQueue("Morning").join()
            val morning = queue.snapshot()
            val redoQueue = notice { assertNotNull(added.undo)() }
            assertEquals(morning.entries, queue.snapshot().entries)
            assertEquals(morning.selectedSavedQueueId, queue.snapshot().selectedSavedQueueId)
            assertEquals(original.entries, queue.saved().first { it.id == evening.id }.snapshot.entries)
            notice { assertNotNull(redoQueue.undo)() }
            assertEquals(morning.entries, queue.snapshot().entries)
            assertEquals(4, queue.saved().first { it.id == evening.id }.snapshot.entries.size)
            assertEquals(original.currentEntryId, queue.saved().first { it.id == evening.id }.snapshot.currentEntryId)
            assertEquals(700L, queue.saved().first { it.id == evening.id }.snapshot.positionMs)
            assertNull(player.state.value.error)
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun undoingANewDownloadCancelsItButUndoingARepeatedRequestKeepsTheOriginal() = runBlocking {
        val directory = Files.createTempDirectory("download-undo")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val collections = Playlists(store)
        val song = library.importTrack(TrackMetadata("Server song"), TrackSource("server:test:1", SourceKind.SERVER, "1"))
        val offline = me.d3sox.nevumi.downloads.OfflineMusic(store, library, { null }, directory.resolve("audio").toString(),
            collections, kotlinx.coroutines.flow.MutableStateFlow(me.d3sox.nevumi.downloads.DownloadNetwork(connected = false)))
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = collections, offline = offline)
        suspend fun request(): ActionNotice = coroutineScope {
            val notice = async(start = CoroutineStart.UNDISPATCHED) { player.notices.first() }
            player.downloadTracks(listOf(song.id)).join()
            withTimeout(3_000) { notice.await() }
        }
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            assertNotNull(request().undo)().join()
            assertFalse(song.id in offline.state.value, "Undo must cancel a newly requested download")
            assertNull(store.read<me.d3sox.nevumi.downloads.OfflineRequest>("excluded:${song.id}"),
                "Undo must not keep the song from downloading with a playlist later")
            request()
            assertNotNull(request().undo)().join()
            assertTrue(song.id in offline.state.value, "Undo must keep a download that was already requested")
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun favoriteRatingAndPlaylistConfirmationsRestoreTheirPreviousValues() = runBlocking {
        val directory = Files.createTempDirectory("action-undo")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val collections = Playlists(store)
        val songs = (0..1).map { library.importTrack(TrackMetadata("Song $it"), TrackSource("local:$it", SourceKind.LOCAL, "/music/$it.flac")) }
        val playlist = collections.create("Keep", listOf(songs[0].id))
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = collections)
        suspend fun undo(action: () -> Job) = coroutineScope {
            val notice = async(start = CoroutineStart.UNDISPATCHED) { player.notices.first() }
            action().join()
            assertNotNull(withTimeout(3_000) { notice.await() }.undo, "Every action confirmation must offer Undo")().join()
        }
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            undo { player.setFavorite(songs[0].id, true) }
            assertFalse(requireNotNull(library.findTrack(songs[0].id)).favorite)
            undo { player.setFavorite(songs.map { it.id }, true) }
            assertTrue(library.tracks().none { it.favorite })
            undo { player.setRating(songs[0].id, 4) }
            assertEquals(0, requireNotNull(library.findTrack(songs[0].id)).rating)
            undo { player.createPlaylist("Temporary", songs.map { it.id }) }
            assertEquals(listOf("Keep"), collections.all().map { it.name })
            undo { player.addToPlaylist(playlist.id, songs[1].id) }
            assertEquals(listOf(songs[0].id), collections.get(playlist.id)?.trackIds)
            undo { player.addToPlaylist(playlist.id, songs.map { it.id }) }
            assertEquals(listOf(songs[0].id), collections.get(playlist.id)?.trackIds)
            undo { player.managePlaylistMembership(songs.map { it.id }, mapOf(playlist.id to false)) }
            assertEquals(listOf(songs[0].id), collections.get(playlist.id)?.trackIds)
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
