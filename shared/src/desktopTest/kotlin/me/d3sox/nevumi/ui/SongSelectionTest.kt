package me.d3sox.nevumi.ui

import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.queue.QueueOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class SongSelectionTest {
    @Test fun aSongFromAnotherPageStartsANewSelectionSoQueuedSongsKeepTheirOwnPage() {
        val selection = SongSelection()
        val search = QueueOrigin(LibraryTab.SONGS)
        val album = QueueOrigin(LibraryTab.ALBUMS, "album")
        selection.toggle("a", listOf("a", "b"), search)
        selection.toggle("b", listOf("a", "b"), search)
        assertEquals(listOf("a", "b"), selection.selectedIds)
        selection.toggle("c", listOf("c"), album)
        assertEquals(listOf("c"), selection.selectedIds)
        assertEquals(album, selection.origin)
    }
}
