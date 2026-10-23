package me.d3sox.nevumi.queue

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.*
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*
import org.jetbrains.compose.resources.getString

class QueueExportTest {
    @Test fun playlistAndM3uExportsPreserveOccurrenceOrderAndScopeWithoutChangingTheQueue() = runBlocking {
        val directory = Files.createTempDirectory("queue-export")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val queue = PlaybackQueue(store)
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"), playlists = playlists)
        try {
            val a = library.importTrack(TrackMetadata("Song A", "Artist", durationMs = 90_000), TrackSource("local:a", SourceKind.LOCAL, "/music/a.flac"))
            val b = library.importTrack(TrackMetadata("Song B", "Artist"), TrackSource("server:test:b", SourceKind.SERVER, "b"))
            val snapshot = QueueSnapshot(listOf(QueueEntry("past", a.id), QueueEntry("current", b.id),
                QueueEntry("manual1", a.id, QueueSection.MANUAL), QueueEntry("manual2", a.id, QueueSection.MANUAL), QueueEntry("last", b.id)),
                currentEntryId = "current", positionMs = 1_500)
            queue.restore(snapshot)
            val expected = mapOf(QueueExportScope.ALL to listOf(a.id, b.id, a.id, a.id, b.id),
                QueueExportScope.REMAINING to listOf(b.id, a.id, a.id, b.id), QueueExportScope.UP_NEXT to listOf(a.id, a.id))
            for ((selection, ids) in expected) {
                assertEquals(ids, selection.trackIds(snapshot))
                val playlist = controller.createQueuePlaylist(getString(selection.label), snapshot, selection)
                assertEquals(ids, playlist.trackIds)
                val text = controller.exportQueueM3u(snapshot, selection)
                val imported = M3uPlaylists(library, playlists).import("Imported ${getString(selection.label)}", text)
                assertEquals(ids, imported.playlist.trackIds)
                assertEquals(emptyList(), imported.unresolved)
                assertFalse("http" in text)
            }
            assertEquals(snapshot, queue.snapshot())
            val invalid = snapshot.copy(entries = snapshot.entries + QueueEntry("missing", "no-library-song"))
            val before = playlists.all()
            assertFailsWith<IllegalArgumentException> { controller.createQueuePlaylist("Missing", invalid, QueueExportScope.ALL) }
            assertFailsWith<IllegalArgumentException> { controller.exportQueueM3u(invalid, QueueExportScope.ALL) }
            assertEquals(before, playlists.all())
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
