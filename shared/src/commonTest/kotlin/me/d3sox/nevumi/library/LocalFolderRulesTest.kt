package me.d3sox.nevumi.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalFolderRulesTest {
    private val downloads = "/storage/emulated/0/Music/Nevumi/SoundCloud"

    @Test
    fun shortLocalSongsStayOutWhileSongsOfUnknownLengthStayIn() {
        fun song(durationMs: Long, folder: String = "Music") = Track(durationMs.toString(), TrackMetadata("Song", durationMs = durationMs, folder = folder),
            listOf(TrackSource("local:$durationMs", SourceKind.LOCAL, "/$folder/song.mp3", filePath = "/storage/emulated/0/$folder/song.mp3")))
        val rules = LocalFolderRules(excluded = listOf("Ringtones"), minimumDurationMs = 30_000)
        assertTrue(rules.allows(song(30_000)))
        assertTrue(rules.allows(song(0)))
        assertEquals(false, rules.allows(song(29_999)))
        assertEquals(false, rules.allows(song(60_000, "Ringtones")))
        assertTrue(LocalFolderRules().allows(song(1_000)))
        assertTrue(rules.allows(Track("server", TrackMetadata("Intro", durationMs = 5_000),
            listOf(TrackSource("server:a:1", SourceKind.SERVER, "1")))))
        assertEquals(30_000, me.d3sox.nevumi.settings.MusicSettings(minimumSongSeconds = 30).localFolderRules().minimumDurationMs)
    }

    @Test
    fun allowingAFolderLiftsItsOwnExclusionsHoweverTheyAreWritten() {
        val rules = LocalFolderRules(excluded = listOf("Music/Nevumi/SoundCloud/", "$downloads/Old", "Podcasts"))
        assertNull(rules.excludedParent(downloads))
        val allowed = rules.allowing(downloads)
        assertEquals(LocalFolderRules(excluded = listOf("Podcasts")), allowed)
        assertTrue(allowed.allows(downloads))
    }

    @Test
    fun allowingAFolderAddsItOnlyWhenIncludeFoldersLeaveItOut() {
        assertEquals(listOf("Music/Albums", downloads), LocalFolderRules(listOf("Music/Albums")).allowing(downloads).included)
        assertEquals(listOf("Music"), LocalFolderRules(listOf("Music")).allowing(downloads).included)
        assertEquals(emptyList(), LocalFolderRules().allowing(downloads).included)
    }

    @Test
    fun anExcludedParentFolderIsReportedInsteadOfLifted() {
        val rules = LocalFolderRules(excluded = listOf("Music/"))
        assertEquals("Music/", rules.excludedParent(downloads))
        assertEquals(listOf("Music/"), rules.allowing(downloads).excluded)
    }
}
