package me.d3sox.nevumi.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.TimeSource

class BulkFavoritesPerformanceTest {
    @Test
    fun addingClearingAndUndoingThousandsOfFavoritesBatchesDatabaseNotifications() = runBlocking {
        val directory = Files.createTempDirectory("bulk-favorites")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val ids = store.transaction {
            (0 until 5_500).map { index -> library.importTrack(TrackMetadata("Song $index"),
                TrackSource("server:test:$index", SourceKind.SERVER, "$index")).id }
        }
        val changes = NavidromeChanges(store, library) { null }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), serverChanges = changes)
        val notifications = AtomicInteger()
        val observer = launch(Dispatchers.Default) {
            store.database.records().observeRevision("tracks").collect { notifications.incrementAndGet() }
        }
        try {
            withTimeout(10_000) { controller.state.first { it.libraryReady } }
            suspend fun change(favorite: Boolean): Long {
                val start = TimeSource.Monotonic.markNow()
                controller.setFavorite(ids, favorite).join()
                assertNull(controller.state.value.error)
                assertTrue(controller.state.value.tracks.all { it.favorite == favorite })
                assertTrue(library.tracks().all { it.favorite == favorite })
                return start.elapsedNow().inWholeMilliseconds
            }
            val addMs = change(true)
            val notice = async(start = CoroutineStart.UNDISPATCHED) { controller.notices.first() }
            val clearMs = change(false)
            val undo = assertNotNull(withTimeout(5_000) { notice.await() }.undo)
            println("5500 server favorites: add=${addMs}ms, clear=${clearMs}ms, notifications=${notifications.get()}")
            assertTrue(notifications.get() <= 120, "Bulk changes must not notify for each song: ${notifications.get()}")
            assertEquals(5_500, changes.pendingCount())
            undo().join()
            assertTrue(library.tracks().all { it.favorite })
            assertEquals(5_500, changes.pendingCount())
        } finally {
            observer.cancelAndJoin(); controller.closeAndJoin(); changes.closeAndJoin()
            store.close(); directory.toFile().deleteRecursively()
        }
    }
}
