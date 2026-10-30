package me.d3sox.nevumi.ui

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playlists.Playlist
import kotlin.test.*

class LibrarySearchResultsTest {
    @Test fun resultsIncludeEveryTypeAndCollectionsRetainAllTheirTracks() {
        val tracks = listOf(
            Track("one", TrackMetadata("First", "Pulse", album = "Arc", genres = listOf("Ambient"), folder = "Library/Ambient", trackNumber = 2), emptyList()),
            Track("two", TrackMetadata("Second", "Other", album = "Arc", albumArtist = "Pulse", trackNumber = 1), emptyList()),
        )
        val playlist = Playlist("mix", "Ambient mix", listOf("two", "one", "two"))
        val index = LibrarySearchIndex(tracks, listOf(playlist))
        val album = index.search("arc").collections[SearchType.ALBUMS]!!.single()
        assertEquals(listOf("two", "one"), album.tracks.map { it.id })
        assertEquals("Pulse", album.subtitle)
        assertEquals(listOf("one"), index.search("first").songs.map { it.id })
        assertEquals("Pulse", index.search("pulse").collections[SearchType.ARTISTS]!!.single().title)
        val ambient = index.search("ambient")
        assertEquals(listOf("two", "one", "two"), ambient.collections[SearchType.PLAYLISTS]!!.single().tracks.map { it.id })
        assertEquals("Ambient", ambient.collections[SearchType.GENRES]!!.single().title)
        assertEquals("Library/Ambient", ambient.collections[SearchType.FOLDERS]!!.single().title)
        assertEquals(0, index.search("  ").count)
        assertEquals(0, index.search("missing").count)
    }
}
