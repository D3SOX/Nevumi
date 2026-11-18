package me.d3sox.nevumi.library

/** Strips characters' accents, so "Beyonce" finds "Beyoncé" and artist credits match however they are written. */
internal expect fun withoutMarks(text: String): String

private val punctuation = Regex("""[^\p{L}\p{N}]+""")
private val whitespace = Regex("""\s+""")

// Upper- then lowercasing folds case more fully than lowercasing alone, so "STRASSE" finds "Straße"; the capital ẞ stays ß.
private fun words(text: String) = if (text.all { it < '\u0080' }) asciiWords(text)
    else withoutMarks(text).uppercase().lowercase().replace("ß", "ss").split(whitespace).map { punctuation.replace(it, "") }.filter { it.isNotEmpty() }

/** [words] of plain ASCII text without normalizing or regular expressions, since most tags are ASCII and phones index thousands. */
private fun asciiWords(text: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    for (char in text) when (char) {
        in 'a'..'z', in '0'..'9' -> word.append(char)
        in 'A'..'Z' -> word.append(char + ('a' - 'A'))
        ' ', '\t', '\n', '\u000B', '\u000C', '\r' -> if (word.isNotEmpty()) { words += word.toString(); word.clear() }
    }
    if (word.isNotEmpty()) words += word.toString()
    return words
}

/**
 * Text prepared for [SearchQuery]: case-folded, without accents and with punctuation removed from each word, so "acdc" finds
 * "AC/DC" and "dont" finds "Don't". Each value stays apart, so a word cannot start in one and end in the next.
 */
class SearchText(vararg values: String) {
    internal val raw = values.joinToString("\u0000") { it.lowercase() }
    internal val words = values.joinToString("\u0000") { words(it).joinToString(" ") }
}

/** Finds text that holds every word of the query, in any order, such as "beatles help" for "Help!" by The Beatles. */
class SearchQuery(text: String) {
    private val words = words(text)
    // A query of symbols only, such as "!!!", keeps them.
    private val symbols = text.trim().lowercase().takeIf { words.isEmpty() && it.isNotEmpty() }
    val isEmpty: Boolean get() = words.isEmpty() && symbols == null

    fun matches(text: SearchText): Boolean = if (symbols != null) symbols in text.raw else words.all { it in text.words }
}
