package me.d3sox.nevumi.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reads trimmed YouTube Music responses, recorded without an account. */
class YouTubeMusicPagesTest {
    private fun response(name: String): JsonObject =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/youtube-music/$name.json")).readText()).jsonObject

    @Test fun anArtistPageShowsEveryShelfInYouTubeMusicsOrder() {
        val page = YouTubeMusicPages.page(response("artist"))
        assertEquals("Taylor Swift", page.title)
        assertEquals("408m monthly audience", page.subtitle)
        assertTrue(page.banner)
        assertTrue(page.description.startsWith("And, baby, that’s show business for you."))
        assertEquals(listOf("Top songs", "Albums", "Singles and EPs", "Videos", "Live performances", "Featured on", "Playlists by Taylor Swift",
            "Fans might also like"), page.shelves.map { it.title })
        assertEquals(listOf(YouTubeShelfLayout.ROWS) + List(7) { YouTubeShelfLayout.CAROUSEL }, page.shelves.map { it.layout })

        val top = page.shelves[0]
        assertEquals(YouTubeTrack("7nVctvQVz0U", "The Fate of Ophelia", "Taylor Swift", 0,
            "https://yt3.googleusercontent.com/ZpWD37EEQE_LVuQ_O03cRmz5mZA7jG0gKGLt-49uHNDX6DycysijGGSOX9woU-OfQR5jyahz3aEdmBmm3A=w120-h120-l90-rj", song = true),
            top.items.first())
        assertEquals(YouTubeLink("VLOLAK5uy_kRl6HdICkQpZF7zuHu_Yx-RDVHw-hboxo", "ggMCCAI%3D"), top.more?.link)

        val album = page.shelves[1].items.first() as YouTubeCollection
        assertEquals(YouTubeCollectionKind.ALBUM, album.kind)
        assertEquals("The Life of a Showgirl: The Encore", album.title)
        assertEquals("2026", album.subtitle)
        assertEquals("MPREb_L64BnYDQpHZ", album.link.browseId)
        assertEquals(YouTubeCollectionKind.LIST, page.shelves[1].more?.kind)

        val video = page.shelves[3].items.first() as YouTubeTrack
        assertEquals("j8pR1tyoRFc", video.videoId)
        assertEquals("Taylor Swift", video.artist)
        assertEquals(false, video.song)
        assertEquals("VLOLAK5uy_loEjsMw6hRHXqBnM7tPvxZodwPc4BN3RY", page.shelves[3].more?.link?.browseId)
        // Signed out, an artist's own lists of live performances and playlists open empty, so they offer no See all.
        assertNull(page.shelves[4].more)
        assertNull(page.shelves[6].more)

        val similar = page.shelves[7].items.first() as YouTubeCollection
        assertEquals(YouTubeCollection(YouTubeLink("UC6ZjlLJhqP79nqGr3Ic6Adg"), YouTubeCollectionKind.ARTIST, "Shawn Mendes", "149m monthly audience",
            "https://yt3.googleusercontent.com/VVGOJpxGFBbbUJHurZq_U1F_BjIhaMBAY1sDhOT3a2kE2DnidfYT8L_hY7WoJOusysvzn1msCoinvtma=w544-h544-p-l90-rj"), similar)
    }

    @Test fun albumSongsTakeTheAlbumsArtistAndCover() {
        val page = YouTubeMusicPages.page(response("album"))
        assertEquals("The Life of a Showgirl", page.title)
        assertEquals("Album · 2025 · 12 songs · 41 minutes", page.subtitle)
        assertEquals(YouTubeCollection(YouTubeLink("UCPC0L1d253x-KuMNwa05TpA"), YouTubeCollectionKind.ARTIST, "Taylor Swift"), page.artist)
        val songs = page.shelves.first()
        assertEquals(YouTubeShelfLayout.ROWS, songs.layout)
        val first = songs.items.first() as YouTubeTrack
        assertEquals(YouTubeTrack("ko70cExuzZM", "The Fate of Ophelia", "Taylor Swift", 227_000, page.artworkUrl, song = true), first)
        assertEquals(listOf("Other versions", "Releases for you"), page.shelves.drop(1).map { it.title })
        assertNull(page.next)
    }

    @Test fun aLongPlaylistContinuesWhereItsFirstPageEnds() {
        val page = YouTubeMusicPages.page(response("playlist"))
        assertEquals("Top songs", page.title)
        assertEquals(2, page.shelves.single().items.size)
        assertNotNull(page.next)
        val more = YouTubeMusicPages.continued(response("continued"))
        assertEquals(2, more.results.size)
        assertTrue(more.results.all { it is YouTubeTrack && it.artist.isNotEmpty() && it.durationMs > 0 })
    }

    @Test fun aDiscographyListsReleasesInAGrid() {
        val page = YouTubeMusicPages.page(response("discography"))
        assertEquals("Taylor Swift", page.title)
        val grid = page.shelves.single()
        assertEquals(YouTubeShelfLayout.GRID, grid.layout)
        assertEquals(3, grid.items.size)
        assertTrue(grid.items.all { it is YouTubeCollection && it.kind == YouTubeCollectionKind.ALBUM })
    }

    @Test fun aLongDiscographyContinuesForTheVisitorItWasShownTo() {
        val page = YouTubeMusicPages.page(response("long-discography"))
        assertEquals(YouTubeShelfLayout.GRID, page.shelves.single().layout)
        // Signed out, YouTube Music only continues a discography for the visitor it answered first.
        assertEquals("CgtaZGZVZkFKUm9WSSjVr9zXBjIKCgJERRIEEgAgEQ%3D%3D", (page.next?.value as YouTubeMusicPages.Next).visitor)
        val more = YouTubeMusicPages.continued(response("discography-continued"))
        assertEquals(listOf("Geppetos Trunk", "Ydrapoej"), more.results.map { (it as YouTubeCollection).title })
        assertTrue(more.results.all { (it as YouTubeCollection).kind == YouTubeCollectionKind.ALBUM })
    }

    @Test fun anArtistSearchFindsArtistsAndContinues() {
        val page = YouTubeMusicPages.searchResults(response("artists"))
        val first = page.results.first() as YouTubeCollection
        assertEquals(YouTubeCollectionKind.ARTIST, first.kind)
        assertTrue(first.link.browseId.startsWith("UC"))
        assertTrue(first.subtitle.startsWith("Artist · "))
        assertNotNull(page.next)
    }

    @Test fun anAlbumSearchFindsAlbumsWithTheirArtistAndYear() {
        val page = YouTubeMusicPages.searchResults(response("albums"))
        assertEquals(YouTubeCollection(YouTubeLink("MPREb_yXhSI4FCUo6"), YouTubeCollectionKind.ALBUM, "OK Computer", "Album · Radiohead · 1997",
            (page.results.first() as YouTubeCollection).artworkUrl), page.results.first())
        assertNotNull(page.next)
    }

    @Test fun lengthsReadMinutesAndHours() {
        assertEquals(227_000, YouTubeMusicPages.duration("3:47"))
        assertEquals(3_723_000, YouTubeMusicPages.duration("1:02:03"))
        assertNull(YouTubeMusicPages.duration("883m plays"))
    }
}
