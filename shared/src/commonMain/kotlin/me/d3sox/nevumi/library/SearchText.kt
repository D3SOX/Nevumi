package me.d3sox.nevumi.library

/** Strips characters' accents, so "Beyonce" finds "Beyoncé" and artist credits match however they are written. */
internal expect fun withoutMarks(text: String): String

private val punctuation = Regex("""[^\p{L}\p{N}]+""")

private fun fold(text: String) = punctuation.replace(withoutMarks(text).lowercase(), " ").trim()

/**
 * Text prepared for [SearchQuery]: lowercase, without accents and with punctuation as spaces, plus a copy without spaces, so
 * "acdc" finds "AC/DC" and "dont" finds "Don't". Each value stays apart, so a word cannot start in one and end in the next.
 */
class SearchText(vararg values: String) {
    internal val raw = values.joinToString("\u0000") { it.lowercase() }
    internal val words = values.joinToString("\u0000") { fold(it) }
    internal val compact = words.replace(" ", "")
}

/** Finds text that holds every word of the query, in any order, such as "beatles help" for "Help!" by The Beatles. */
class SearchQuery(text: String) {
    private val words = fold(text).split(' ').filter { it.isNotEmpty() }
    // A query of symbols only, such as "!!!", keeps them.
    private val symbols = text.trim().lowercase().takeIf { words.isEmpty() && it.isNotEmpty() }
    val isEmpty: Boolean get() = words.isEmpty() && symbols == null

    fun matches(text: SearchText): Boolean = if (symbols != null) symbols in text.raw
        else words.all { it in text.words || it in text.compact }
}
