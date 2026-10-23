package me.d3sox.nevumi.lyrics

import kotlinx.serialization.Serializable

/** Where a song's lyrics were found. */
@Serializable
enum class LyricsSource { EMBEDDED, SIDECAR, SERVER, LRCLIB }

/** One synced line that starts at [startMs]; an empty [text] marks an instrumental break. */
@Serializable
data class LyricLine(val startMs: Long, val text: String)

/** A song's lyrics: timed [lines] when they are synced, otherwise the [plain] text. */
@Serializable
data class Lyrics(val source: LyricsSource, val lines: List<LyricLine> = emptyList(), val plain: String = "") {
    val synced: Boolean get() = lines.isNotEmpty()
}

/** Reads LRC lyrics, falling back to plain text when no line carries a timestamp. */
object LrcParser {
    private val timestamp = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    // Only the standard ID tags, so section labels like "[Chorus: Someone]" stay part of plain lyrics.
    private val tag = Regex("""^\[(ar|al|ti|au|by|length|offset|re|tool|ve|la|id|#):(.*)]$""", RegexOption.IGNORE_CASE)
    private val wordTimestamp = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(text: String, source: LyricsSource): Lyrics? {
        var offset = 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()
        text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.forEach { line ->
            var rest = line
            val starts = mutableListOf<Long>()
            while (true) {
                val match = timestamp.find(rest) ?: break
                val (minutes, seconds, fraction) = match.destructured
                val fractionMs = when (fraction.length) { 0 -> 0; 1 -> fraction.toInt() * 100; 2 -> fraction.toInt() * 10; else -> fraction.toInt() }
                starts += (minutes.toLong() * 60 + seconds.toLong()) * 1_000 + fractionMs
                rest = rest.substring(match.range.last + 1).trimStart()
            }
            if (starts.isNotEmpty()) {
                val content = rest.replace(wordTimestamp, "").trim()
                starts.forEach { timed += LyricLine(it, content) }
                return@forEach
            }
            val metadata = tag.matchEntire(line)
            if (metadata == null) plain += line
            // A positive offset shows the lyrics sooner.
            else if (metadata.groupValues[1].equals("offset", ignoreCase = true)) offset = metadata.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0
        }
        if (timed.isNotEmpty()) return Lyrics(source, timed.map { it.copy(startMs = (it.startMs - offset).coerceAtLeast(0)) }.sortedBy { it.startMs })
        val content = plain.joinToString("\n").trim()
        return if (content.isEmpty()) null else Lyrics(source, plain = content)
    }
}
