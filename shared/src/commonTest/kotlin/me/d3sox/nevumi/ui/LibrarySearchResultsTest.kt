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

    @Test fun wordsMatchInAnyOrderIgnoringAccentsAndPunctuation() {
        val tracks = listOf(
            Track("help", TrackMetadata("Help!", "The Beatles", album = "Help!"), emptyList()),
            Track("halo", TrackMetadata("Halo", "Beyoncé"), emptyList()),
            Track("back", TrackMetadata("Back in Black", "AC/DC"), emptyList()),
            Track("dont", TrackMetadata("Don't Stop Me Now", "Queen"), emptyList()),
            Track("duet", TrackMetadata("Duet", "Pulse feat. Echo"), emptyList()),
            Track("bang", TrackMetadata("Bang", "!!!"), emptyList()),
        )
        val index = LibrarySearchIndex(tracks, emptyList())
        assertEquals(listOf("help"), index.search("beatles help").songs.map { it.id })
        assertEquals(listOf("help"), index.search("HELP beatles!").songs.map { it.id })
        assertEquals(listOf("halo"), index.search("beyonce").songs.map { it.id })
        assertEquals(listOf("halo"), index.search("Beyoncé halo").songs.map { it.id })
        assertEquals(listOf("back"), index.search("acdc").songs.map { it.id })
        assertEquals(listOf("back"), index.search("ac dc black").songs.map { it.id })
        assertEquals(listOf("dont"), index.search("dont stop").songs.map { it.id })
        assertEquals(listOf("bang"), index.search("!!!").songs.map { it.id })
        assertEquals(0, index.search("beatles halo").count)
        // Each credited artist is an artist result of its own.
        assertEquals(listOf("Echo"), index.search("echo").collections[SearchType.ARTISTS]!!.map { it.title })
        assertEquals(listOf("duet"), index.search("echo").collections[SearchType.ARTISTS]!!.single().tracks.map { it.id })
    }
}
