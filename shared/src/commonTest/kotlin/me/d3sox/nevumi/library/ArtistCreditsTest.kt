package me.d3sox.nevumi.library

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtistCreditsTest {
    private fun names(artist: String, title: String = "Song", splitting: ArtistSplitting = ArtistSplitting(), artists: List<String> = emptyList()) =
        splitting.names(TrackMetadata(title, artist, artists = artists))

    @Test fun defaultSeparatorsSplitCreditsButKeepBandNames() {
        assertEquals(listOf("A", "B"), names("A feat. B"))
        assertEquals(listOf("A", "B"), names("A Ft. B"))
        assertEquals(listOf("A", "B"), names("A feat B"))
        assertEquals(listOf("A", "B", "C"), names("A; B;C"))
        assertEquals(listOf("Simon & Garfunkel"), names("Simon & Garfunkel"))
        // A word separator only splits where it stands alone.
        assertEquals(listOf("Daft Punk"), names("Daft Punk"))
        assertEquals(listOf("Leftfield"), names("Leftfield"))
        assertEquals(emptyList(), names(""))
        assertEquals(listOf("J Wax", "RUA DJ"), names("J Wax, RUA DJ"))
        assertEquals(listOf("Elvin (UK)", "AMEENAH"), names("Elvin (UK), AMEENAH"))
        assertEquals(listOf("A"), names("A feat."))
        assertEquals(listOf("Calvin Harris", "Rufus Du Sol"), names("Calvin Harris vs Rufus Du Sol"))
        assertEquals(listOf("A", "B"), names("A Vs. B"))
        assertEquals(listOf("Above & Beyond"), names("Above & Beyond"))
        assertEquals(listOf("AC/DC"), names("AC/DC"))
        assertEquals(listOf("Sunn O)))"), names("Sunn O)))"))
    }

    @Test fun exceptionsKeepNamesThatContainASeparator() {
        val splitting = ArtistSplitting(listOf("&", ",", "/"), listOf("Simon & Garfunkel", "AC/DC"))
        assertEquals(listOf("Simon & Garfunkel", "Paul Simon"), names("Simon & Garfunkel, Paul Simon", splitting = splitting))
        assertEquals(listOf("ac/dc", "B"), names("ac/dc & B", splitting = splitting))
        assertEquals(listOf("A", "B"), names("A & B", splitting = splitting))
        assertEquals(listOf("A & B"), names("A & B", splitting = ArtistSplitting(emptyList(), featuredFromTitle = false)))
    }

    @Test fun titlesCreditFeaturedArtists() {
        assertEquals(listOf("A", "B", "C"), names("A", "Song (feat. B & C)"))
        assertEquals(listOf("A", "B"), names("A", "Song [ft. B] (Remix)"))
        assertEquals(listOf("A", "B"), names("A", "Song feat. B"))
        assertEquals(listOf("A", "B"), names("A feat. B", "Song (feat. B)"))
        assertEquals(listOf("A", "the Orchestra"), names("A", "Song (with the Orchestra)"))
        assertEquals(listOf("A"), names("A", "Song (feat. B)", ArtistSplitting(featuredFromTitle = false)))
        assertEquals(listOf("A"), names("A", "Featuring Nobody"))
    }

    @Test fun keepingNamesTogetherOffersNeighborsAndWholeCredits() {
        fun joined(artist: String, artists: List<String> = emptyList(), splitting: ArtistSplitting = ArtistSplitting()) =
            splitting.joinedNames(TrackMetadata("Song (feat. Nova)", artist, artists = artists))
        assertEquals(listOf("Earth", "Wind & Fire"), names("Earth, Wind & Fire"))
        assertEquals(listOf("Earth, Wind & Fire"), joined("Earth, Wind & Fire"))
        assertEquals(listOf("Tyler, The Creator", "The Creator, A", "Tyler, The Creator, A"), joined("Tyler, The Creator, A"))
        val kept = ArtistSplitting(exceptions = listOf("Tyler, The Creator"))
        assertEquals(listOf("Tyler, The Creator", "A"), names("Tyler, The Creator, A", splitting = kept))
        assertEquals(listOf("Tyler, The Creator, A"), joined("Tyler, The Creator, A", splitting = kept))
        assertEquals(listOf("Macloud, makko"), joined("Miksu / Macloud, makko", listOf("Miksu", "Macloud, makko")))
        // Featured artists in titles and unsplit credits have nothing to keep together.
        assertEquals(emptyList(), joined("Pulse"))
    }

    @Test fun artistsAServerListsAreSplitAgainWithTheSeparators() {
        assertEquals(listOf("Miksu", "Macloud", "makko"), names("Miksu / Macloud, makko", artists = listOf("Miksu", "Macloud, makko")))
    }

    @Test fun artistsListedSeparatelyWin() {
        // No separator splits "and", but the listed names still credit both.
        assertEquals(listOf("Pulse", "Echo"), names("Pulse and Echo", artists = listOf("Pulse", "Echo")))
    }
}
