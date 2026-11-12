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

    @Test fun keepsTimedBlankLinesAndSkipsUntimedOnes() {
        val text = "\uFEFF[00:01.00]One two\n\n[00:05.00]\n   \n[00:09.00]Three"
        val lyrics = assertNotNull(LrcParser.parse(text, LyricsSource.LRCLIB))
        assertEquals(listOf(LyricLine(1_000, "One two"), LyricLine(5_000, ""), LyricLine(9_000, "Three")), lyrics.lines)
    }

    @Test fun readsEnhancedLrcWordTimestampsIntoWordsWithoutLeavingThemInTheText() {
        val text = "[00:12.00]<00:12.00>Hello <00:12.50>world<00:13.2>\n[00:14.00]Before <00:14.50>after\n[00:16.00]<00:16.00>Tight<00:16.40> <00:16.50>gap"
        val lyrics = assertNotNull(LrcParser.parse(text, LyricsSource.SIDECAR))
        assertEquals(listOf(
            // The closing timestamp only ends the last word.
            LyricLine(12_000, "Hello world", listOf(LyricWord(12_000, "Hello "), LyricWord(12_500, "world"))),
            // Text before the first word timestamp is sung from the start of the line.
            LyricLine(14_000, "Before after", listOf(LyricWord(14_000, "Before "), LyricWord(14_500, "after"))),
            // A timestamp that only times spacing keeps it with the word before.
            LyricLine(16_000, "Tight gap", listOf(LyricWord(16_000, "Tight "), LyricWord(16_500, "gap"))),
        ), lyrics.lines)
        lyrics.lines.forEach { line -> assertEquals(line.text, line.words.joinToString("") { it.text }) }
    }

    @Test fun shiftsWordsWithTheOffsetAndWithEachRepeatOfTheirLine() {
        val text = "[offset:500]\n[00:10.00][00:30.00]<00:10.00>Cho<00:10.40>rus\n[00:20.00]<00:20.00> <00:20.00>\n[00:00.20]<00:00.20>Clamped"
        val lyrics = assertNotNull(LrcParser.parse(text, LyricsSource.EMBEDDED))
        assertEquals(listOf(
            LyricLine(0, "Clamped", listOf(LyricWord(0, "Clamped"))),
            LyricLine(9_500, "Chorus", listOf(LyricWord(9_500, "Cho"), LyricWord(9_900, "rus"))),
            // A line with only timestamps is still a break.
            LyricLine(19_500, ""),
            LyricLine(29_500, "Chorus", listOf(LyricWord(29_500, "Cho"), LyricWord(29_900, "rus"))),
        ), lyrics.lines)
    }

    @Test fun plainTextAndLinesTimedOnlyAsAWholeHaveNoWords() {
        val lyrics = assertNotNull(LrcParser.parse("[00:01.00]Whole line\n[00:02.00]Another one", LyricsSource.EMBEDDED))
        assertTrue(lyrics.lines.all { it.words.isEmpty() })
        // Word timestamps without line timestamps can't be followed, and never show.
        val plain = assertNotNull(LrcParser.parse("<00:01.00>Loose <00:01.50>words\nSecond line", LyricsSource.EMBEDDED))
        assertFalse(plain.synced)
        assertEquals("Loose words\nSecond line", plain.plain)
    }

    @Test fun readsNetEaseYrcWordTiming() {
        val text = """
            {"t":0,"c":[{"tx":"作词: "},{"tx":"Someone"}]}
            [0,0](0,0,0) 作曲 : Someone
            [4890,1250](4890,270,0)Hel(5160,300,0)lo (5460,680,0)(world)
            [1000,2500](1000,400,0)First (1400,600,0)line
            [7000,1000]Untimed words
            [14000,1000](14000,1000,0)After a break
        """.trimIndent()
        val lyrics = assertNotNull(YrcParser.parse(text))
        assertEquals(LyricsSource.NETEASE, lyrics.source)
        assertEquals(listOf(
            LyricLine(0, "作曲 : Someone", listOf(LyricWord(0, "作曲 : Someone"))),
            LyricLine(1_000, "First line", listOf(LyricWord(1_000, "First "), LyricWord(1_400, "line"))),
            LyricLine(4_890, "Hello (world)", listOf(LyricWord(4_890, "Hel"), LyricWord(5_160, "lo "), LyricWord(5_460, "(world)"))),
            LyricLine(7_000, "Untimed words"),
            // Pauses of five seconds or more show as breaks, like the end of the song; shorter ones and untimed credits don't.
            LyricLine(8_000, ""),
            LyricLine(14_000, "After a break", listOf(LyricWord(14_000, "After a break"))),
            LyricLine(15_000, ""),
        ), lyrics.lines)
        assertNull(YrcParser.parse(""))
        assertNull(YrcParser.parse("""{"t":0,"c":[{"tx":"纯音乐，请欣赏"}]}"""))
    }

    @Test fun lyricsTimedByLineAreStoredWithoutWords() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val lyrics = Lyrics(LyricsSource.LRCLIB, listOf(LyricLine(1_000, "Line")))
        // Lyrics kept before words were read decode the same, and lines without words keep that form.
        assertEquals("""{"source":"LRCLIB","lines":[{"startMs":1000,"text":"Line"}],"plain":""}""", json.encodeToString(lyrics))
        assertEquals(lyrics, json.decodeFromString<Lyrics>("""{"source":"LRCLIB","lines":[{"startMs":1000,"text":"Line"}],"plain":""}"""))
        val timed = Lyrics(LyricsSource.SIDECAR, listOf(LyricLine(1_000, "A b", listOf(LyricWord(1_000, "A "), LyricWord(1_200, "b")))))
        assertEquals(timed, json.decodeFromString<Lyrics>(json.encodeToString(timed)))
    }

    @Test fun fallsBackToPlainTextWithoutTimestamps() {
        val lyrics = assertNotNull(LrcParser.parse("[ar:Someone]\n\n[Chorus: Someone]\nFirst line\n\nSecond verse\n", LyricsSource.SERVER))
        assertFalse(lyrics.synced)
        assertEquals("[Chorus: Someone]\nFirst line\n\nSecond verse", lyrics.plain)
        assertNull(LrcParser.parse("  \n[ti:Only tags]\n", LyricsSource.SERVER))
        assertNull(LrcParser.parse("", LyricsSource.SERVER))
    }
}
