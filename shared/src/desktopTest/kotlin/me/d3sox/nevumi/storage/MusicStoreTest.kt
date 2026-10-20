package me.d3sox.nevumi.storage

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

class MusicStoreTest {
    @Test
    fun rewritingAnUnchangedRecordDoesNotWakeObservers() = runBlocking {
        val directory = Files.createTempDirectory("unchanged-records")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            store.put("pins:song", "pins", listOf("manual"))
            val queries = MutableStateFlow(0)
            // Any write to the table makes every observer query again, whichever kind it watches.
            val observer = launch(Dispatchers.Default) { store.database.records().observe("tracks").collect { queries.value++ } }
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
