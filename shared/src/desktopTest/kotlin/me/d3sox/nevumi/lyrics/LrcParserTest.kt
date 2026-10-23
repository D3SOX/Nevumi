package me.d3sox.nevumi.lyrics

import kotlin.test.*

class LrcParserTest {
    @Test fun readsTwoAndThreeDigitFractionsAndSortsLines() {
        val lyrics = assertNotNull(LrcParser.parse("[00:12.50]Second\n[00:01.123]First\n[01:02]Third", LyricsSource.SIDECAR))
        assertEquals(listOf(LyricLine(1_123, "First"), LyricLine(12_500, "Second"), LyricLine(62_000, "Third")), lyrics.lines)
        assertEquals(LyricsSource.SIDECAR, lyrics.source)
        assertTrue(lyrics.synced)
    }

    @Test fun repeatsALineForEachOfItsTimestamps() {
        val lyrics = assertNotNull(LrcParser.parse("[00:10.00][00:30.00] Chorus\n[00:20.00]Verse", LyricsSource.EMBEDDED))
        assertEquals(listOf(LyricLine(10_000, "Chorus"), LyricLine(20_000, "Verse"), LyricLine(30_000, "Chorus")), lyrics.lines)
    }

    @Test fun dropsMetadataTagsAndAppliesTheOffset() {
        val text = "[ar:Fixture Artist]\n[ti:Northern Light]\n[al:Fixture Album]\n[offset:+500]\n[00:01.00]Early\n[00:00.20]Clamped"
        val lyrics = assertNotNull(LrcParser.parse(text, LyricsSource.EMBEDDED))
        assertEquals(listOf(LyricLine(0, "Clamped"), LyricLine(500, "Early")), lyrics.lines)
        val later = assertNotNull(LrcParser.parse("[offset:-250]\n[00:01.00]Late", LyricsSource.EMBEDDED))
        assertEquals(1_250, later.lines.single().startMs)
    }

    @Test fun keepsTimedBlankLinesSkipsUntimedOnesAndStripsWordTimestamps() {
        val text = "\uFEFF[00:01.00]<00:01.00>One <00:01.50>two\n\n[00:05.00]\n   \n[00:09.00]Three"
        val lyrics = assertNotNull(LrcParser.parse(text, LyricsSource.LRCLIB))
        assertEquals(listOf(LyricLine(1_000, "One two"), LyricLine(5_000, ""), LyricLine(9_000, "Three")), lyrics.lines)
    }

    @Test fun fallsBackToPlainTextWithoutTimestamps() {
        val lyrics = assertNotNull(LrcParser.parse("[ar:Someone]\n\n[Chorus: Someone]\nFirst line\n\nSecond verse\n", LyricsSource.SERVER))
        assertFalse(lyrics.synced)
        assertEquals("[Chorus: Someone]\nFirst line\n\nSecond verse", lyrics.plain)
        assertNull(LrcParser.parse("  \n[ti:Only tags]\n", LyricsSource.SERVER))
        assertNull(LrcParser.parse("", LyricsSource.SERVER))
    }
}
