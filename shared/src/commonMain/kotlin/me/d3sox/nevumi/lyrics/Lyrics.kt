package me.d3sox.nevumi.lyrics

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Where a song's lyrics were found. */
@Serializable
enum class LyricsSource { EMBEDDED, SIDECAR, SERVER, LRCLIB, NETEASE }

/** A word or syllable sung from [startMs]; its [text] keeps the spacing that follows it within the line. */
@Serializable
data class LyricWord(val startMs: Long, val text: String)

/**
 * One synced line that starts at [startMs]; an empty [text] marks an instrumental break. Lyrics timed word by word also have
 * the [words] that make up the [text].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LyricLine(val startMs: Long, val text: String,
    // Omitted when absent, so lyrics timed by line are kept in the same form as before.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val words: List<LyricWord> = emptyList()) {
    internal fun shifted(byMs: Long) = LyricLine((startMs + byMs).coerceAtLeast(0), text,
        words.map { it.copy(startMs = (it.startMs + byMs).coerceAtLeast(0)) })
}

/** A song's lyrics: timed [lines] when they are synced, otherwise the [plain] text. */
@Serializable
data class Lyrics(val source: LyricsSource, val lines: List<LyricLine> = emptyList(), val plain: String = "") {
    val synced: Boolean get() = lines.isNotEmpty()
    val timedByWord: Boolean get() = lines.any { it.words.isNotEmpty() }
}

/** Reads LRC lyrics, including the word timestamps of enhanced LRC, falling back to plain text when no line carries a timestamp. */
object LrcParser {
    private val timestamp = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    // Only the standard ID tags, so section labels like "[Chorus: Someone]" stay part of plain lyrics.
    private val tag = Regex("""^\[(ar|al|ti|au|by|length|offset|re|tool|ve|la|id|#):(.*)]$""", RegexOption.IGNORE_CASE)
    private val wordTimestamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    fun parse(text: String, source: LyricsSource): Lyrics? {
        var offset = 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()
        text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.forEach { line ->
            var rest = line
            val starts = mutableListOf<Long>()
            while (true) {
                val match = timestamp.find(rest) ?: break
                starts += millis(match)
                rest = rest.substring(match.range.last + 1).trimStart()
            }
            if (starts.isNotEmpty()) {
                // Word timestamps belong to the line's first time, so a line repeated later keeps its rhythm there.
                val content = line(starts.first(), rest)
                starts.forEach { timed += content.shifted(it - starts.first()) }
                return@forEach
            }
            val metadata = tag.matchEntire(line)
            if (metadata == null) plain += line.replace(wordTimestamp, "")
            // A positive offset shows the lyrics sooner.
            else if (metadata.groupValues[1].equals("offset", ignoreCase = true)) offset = metadata.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0
        }
        if (timed.isNotEmpty()) return Lyrics(source, timed.map { it.shifted(-offset) }.sortedBy { it.startMs })
        val content = plain.joinToString("\n").trim()
        return if (content.isEmpty()) null else Lyrics(source, plain = content)
    }

    /** The line starting at [startMs] with [content], timed word by word when it holds enhanced LRC word timestamps like `<00:12.50>`. */
    internal fun line(startMs: Long, content: String) = timedLine(startMs, content, wordTimestamp.findAll(content).toList(), ::millis)

    private fun millis(match: MatchResult): Long {
        val (minutes, seconds, fraction) = match.destructured
        val fractionMs = when (fraction.length) { 0 -> 0; 1 -> fraction.toInt() * 100; 2 -> fraction.toInt() * 10; else -> fraction.toInt() }
        return (minutes.toLong() * 60 + seconds.toLong()) * 1_000 + fractionMs
    }
}

/** Reads the `yrc` lyrics of NetEase Cloud Music, which time each word with lines like `[1000,2500](1000,400,0)Hel(1400,300,0)lo`. */
object YrcParser {
    private val lineTime = Regex("""^\[(\d+),(\d+)]""")
    private val wordTime = Regex("""\((\d+),(\d+),(\d+)\)""")
    /** A pause in the singing at least this long shows as an instrumental break. */
    private const val BREAK_MS = 5_000L

    fun parse(text: String): Lyrics? {
        // Credits may come as JSON objects in place of timed lines, which leaves them out.
        val timed = text.lineSequence().map { it.trim() }.mapNotNull { line ->
            val time = lineTime.find(line) ?: return@mapNotNull null
            val rest = line.substring(time.range.last + 1)
            val start = time.groupValues[1].toLong()
            timedLine(start, rest, wordTime.findAll(rest).toList()) { it.groupValues[1].toLong() } to start + time.groupValues[2].toLong()
        }.sortedBy { it.first.startMs }.toList()
        // Unlike LRC, yrc has no empty lines for instrumental breaks, but it gives each line's length, so a long pause after a line,
        // like the end of the song after the last one, gets the empty line LRC would have.
        val lines = timed.flatMapIndexed { index, (line, end) ->
            val next = timed.getOrNull(index + 1)?.first?.startMs ?: Long.MAX_VALUE
            if (end > line.startMs && next - end >= BREAK_MS) listOf(line, LyricLine(end, "")) else listOf(line)
        }
        return if (lines.isEmpty()) null else Lyrics(LyricsSource.NETEASE, lines)
    }
}

/**
 * The line starting at [startMs] with [content], split into words at the [tags] that each give the [time] the text after them is
 * sung from. Blank pieces only carry spacing, or mark when the last word ends, so they join the word before them, and the line keeps
 * no spacing around its words.
 */
private fun timedLine(startMs: Long, content: String, tags: List<MatchResult>, time: (MatchResult) -> Long): LyricLine {
    if (tags.isEmpty()) return LyricLine(startMs, content.trim())
    // Text before the first tag is sung from the start of the line.
    val pieces = listOf(startMs to content.substring(0, tags.first().range.first)) + tags.mapIndexed { index, match ->
        time(match) to content.substring(match.range.last + 1, tags.getOrNull(index + 1)?.range?.first ?: content.length)
    }
    val words = mutableListOf<LyricWord>()
    for ((start, text) in pieces) {
        val last = words.lastOrNull()
        when {
            text.isBlank() -> if (last != null) words[words.lastIndex] = last.copy(text = last.text + text)
            last == null -> words += LyricWord(start, text.trimStart())
            else -> words += LyricWord(start, text)
        }
    }
    words.lastOrNull()?.let { words[words.lastIndex] = it.copy(text = it.text.trimEnd()) }
    return if (words.isEmpty()) LyricLine(startMs, "") else LyricLine(startMs, words.joinToString("") { it.text }, words)
}
