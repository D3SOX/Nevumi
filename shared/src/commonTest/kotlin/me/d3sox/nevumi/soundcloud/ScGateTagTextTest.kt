package me.d3sox.nevumi.soundcloud

import kotlin.test.Test
import kotlin.test.assertEquals

class ScGateTagTextTest {
    @Test
    fun promoTextIsRemovedLikeInTheWebInterface() {
        assertEquals("Artist - Track", withoutPromoText("[PREMIERE] Artist - Track"))
        assertEquals("Artist - Track", withoutPromoText("[PREMIERE]: Artist - Track"))
        assertEquals("Night Drive", withoutPromoText("Night Drive [FREE DL]"))
        assertEquals("Night Drive", withoutPromoText("Night Drive - Free Download"))
        assertEquals("Night Drive", withoutPromoText("Night DriveFreeDL"))
        assertEquals("Shout Now", withoutPromoText("Shout Now"))
        assertEquals("Nonexclusive", withoutPromoText("Nonexclusive"))
        assertEquals("Night Drive (Edit)", withoutPromoText("Night Drive (Edit)"))
    }

    @Test
    fun aRepeatedArtistIsRemovedFromTheTitle() {
        assertEquals("Nite Sky (edit)", withoutDuplicateArtist("Nite Sky (edit) - OSKAMAXX & ADEMES", "OSKAMAXX, ADEMES"))
        assertEquals("Nite Sky", withoutDuplicateArtist("ademes + oskamaxx — Nite Sky", "OSKAMAXX, ADEMES"))
        assertEquals("Nite Sky", withoutDuplicateArtist("Beyonce feat. Ádemes - Nite Sky", "Beyoncé, Ademes"))
        val guest = ScGateTags("Nite Sky - Guest Remix", "OSKAMAXX, ADEMES")
        assertEquals(guest, guest.withoutPromo())
        assertEquals(ScGateTags("Night Drive", "Artist", "Night EP"), ScGateTags("Artist - Night Drive [FREE DL]", "Artist", "Night EP (OUT NOW)").withoutPromo())
    }

    @Test
    fun theFileNameFollowsTheFormatAndTheNamingChoice() {
        val tags = ScGateTags("Night: Drive?", "Artist/A")
        assertEquals("ArtistA - Night Drive.flac", taggedFileName("Night [1].m4a", ScGateFormat.FLAC, artistTitle = true, tags))
        assertEquals("Unknown Artist - Unknown Title.mp3", taggedFileName("x.wav", ScGateFormat.MP3_320, artistTitle = true, ScGateTags()))
        assertEquals("Night [1].mp3", taggedFileName("Night [1].m4a", ScGateFormat.MP3_320, artistTitle = false, tags))
        assertEquals("Night [1].flac", taggedFileName("Night [1].m4a", ScGateFormat.FLAC, artistTitle = false, tags))
        assertEquals("Night [1].mp3", taggedFileName("Night [1].mp3", ScGateFormat.MP3_320, artistTitle = false, tags))
        assertEquals("", taggedFileName(null, ScGateFormat.MP3_320, artistTitle = true, tags))
    }
}
