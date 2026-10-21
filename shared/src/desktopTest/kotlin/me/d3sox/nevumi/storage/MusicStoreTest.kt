package me.d3sox.nevumi.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class MusicStoreTest {
    @Test
    fun revisionsFollowCommittedInsertsUpdatesMovesAndDeletes() = runBlocking {
        val directory = Files.createTempDirectory("record-revisions")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val dao = store.database.records()
        suspend fun revision(kind: String) = dao.observeRevision(kind).first()
        try {
            assertEquals(0L, revision("tracks"))
            dao.put("song", "tracks", "original")
            assertEquals(1L, revision("tracks"))
            dao.put("song", "tracks", "original")
            assertEquals(1L, revision("tracks"))
            dao.put("song", "tracks", "edited")
            assertEquals(2L, revision("tracks"))
            assertFailsWith<IllegalStateException> { store.transaction {
                dao.put("song", "tracks", "rolled back")
                error("rollback")
            } }
            assertEquals(2L, revision("tracks"))
            dao.put("song", "favorites", "edited")
            assertEquals(3L, revision("tracks"))
            assertEquals(1L, revision("favorites"))
            dao.delete("song")
            assertEquals(2L, revision("favorites"))
            dao.put("one", "tracks", "one"); dao.put("two", "tracks", "two")
            dao.deleteKind("tracks")
            assertEquals(7L, revision("tracks"))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun versionOneMigratesWithoutLosingRecordsAndTracksFutureChanges() = runBlocking {
        val directory = Files.createTempDirectory("record-migration")
        val path = directory.resolve("library.db").toString()
        BundledSQLiteDriver().open(path).use { connection ->
            connection.execSQL("CREATE TABLE records (`key` TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, value TEXT NOT NULL)")
            connection.execSQL("CREATE INDEX index_records_kind ON records (kind)")
            connection.execSQL("INSERT INTO records VALUES ('song', 'tracks', 'preserved')")
            connection.execSQL("PRAGMA user_version = 1")
        }
        try {
            openMusicStore(path).let { store -> try {
                assertEquals("preserved", store.database.records().read("song"))
                store.database.records().put("song", "tracks", "edited")
                assertEquals(1L, store.database.records().observeRevision("tracks").first())
            } finally { store.close() } }
            openMusicStore(path).let { store -> try {
                store.database.records().delete("song")
                assertEquals(2L, store.database.records().observeRevision("tracks").first())
            } finally { store.close() } }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun syncingFavoritesDoesNotReloadUnchangedLibraryRecords() = runBlocking {
        val directory = Files.createTempDirectory("favorite-invalidation")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val queries = MutableStateFlow(0)
        val observer = launch(Dispatchers.Default) {
            store.database.records().observe("tracks").collect { queries.value++ }
        }
        try {
            withTimeout(5_000) { queries.first { it == 1 } }
            store.put("favorite-change:song", "favorite-changes", true)
            assertNull(withTimeoutOrNull(500) { queries.first { it > 1 } },
                "Sync journal writes must not reload the whole track library")
            store.put("track:song", "tracks", "changed")
            withTimeout(5_000) { queries.first { it == 2 } }
            Unit
        } finally { observer.cancel(); observer.join(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun rewritingAnUnchangedRecordDoesNotWakeObservers() = runBlocking {
        val directory = Files.createTempDirectory("unchanged-records")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            store.put("pins:song", "pins", listOf("manual"))
            val queries = MutableStateFlow(0)
            val observer = launch(Dispatchers.Default) { store.database.records().observe("pins").collect { queries.value++ } }
            withTimeout(5_000) { queries.first { it == 1 } }
            store.transaction { store.put("pins:song", "pins", listOf("manual")) }
            assertNull(withTimeoutOrNull(500) { queries.first { it > 1 } }, "An unchanged record must not wake observers")
            store.put("pins:song", "pins", listOf("manual", "playlist:mix"))
            withTimeout(5_000) { queries.first { it == 2 } }
            assertEquals(listOf("manual", "playlist:mix"), store.read<List<String>>("pins:song"))
            observer.cancel()
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
