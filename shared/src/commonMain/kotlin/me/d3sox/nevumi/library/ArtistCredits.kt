package me.d3sox.nevumi.library

import me.d3sox.nevumi.settings.MusicSettings

/**
 * What separates artists in an artist tag by default. Spotify and SoundCloud downloads list artists with commas. `&`, `x` and
 * `/` are opt-in, because they appear inside names such as "Above & Beyond", "X CLUB." and "AC/DC".
 */
val DEFAULT_ARTIST_SEPARATORS = listOf(";", ",", "feat.", "feat", "ft.", "ft", "featuring", "vs.", "vs")

/**
 * Finds the artists a song credits: those that tags or the server list separately, or else the artist tag. [separators]
 * split each of them, except inside [exceptions] such as "Tyler, The Creator", which "Keep as one artist" adds. Separators made of symbols split wherever they occur,
 * and words such as "feat." only where they stand alone, so "ft." does not split "Daft." With [featuredFromTitle], artists a
 * title names, as in "Song (feat. B)", are credited too.
 */
data class ArtistSplitting(val separators: List<String> = DEFAULT_ARTIST_SEPARATORS, val exceptions: List<String> = emptyList(),
    val featuredFromTitle: Boolean = true) {
    private val protectedNames = exceptions.map { it.trim() }.filter { it.isNotEmpty() }
    private val separator = pattern(separators)
    // Featured artists are nearly always listed with commas and ampersands, whatever splits the artist tag.
    private val featuredSeparator = pattern(separators + listOf("&", ","))

    // Servers split with their own rules, so a listed name can still hold several artists, as in "Macloud, makko".
    private fun credits(metadata: TrackMetadata) = metadata.artists.takeIf { it.size > 1 } ?: listOf(metadata.artist)

    fun names(metadata: TrackMetadata): List<String> {
        val credited = credits(metadata).flatMap { split(it, separator) }
        val featured = featured(metadata.title)
        return (credited + featured).distinctBy { it.lowercase() }
    }

    /**
     * Names the separators split apart in the artist credits and featured titles, which an exception can keep together: each
     * two neighbors, as in "Tyler, The Creator" from "Tyler, The Creator, A", and a whole credit of three or more, as in
     * "Crosby, Stills, Nash & Young".
     */
    fun joinedNames(metadata: TrackMetadata): List<String> =
        (credits(metadata).map { it to separator } + featuredCredits(metadata.title).map { it to featuredSeparator }).flatMap { (text, pattern) ->
        // Pieces that hold no name, such as a lone ")", are no artists to join.
        val pieces = pieces(text, pattern).filter { tidy(text.substring(it)) != null }
        if (pieces.size < 2) return@flatMap emptyList()
        val neighbors = pieces.zipWithNext { first, second -> text.substring(first.first, second.last + 1) }
        (neighbors + if (pieces.size > 2) listOf(text.substring(pieces.first().first, pieces.last().last + 1)) else emptyList())
            .mapNotNull { it.trim().ifEmpty { null } }
    }.distinctBy { it.lowercase() }

    private fun featuredCredits(title: String): List<String> = if (!featuredFromTitle) emptyList()
        else featuring.findAll(title).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()

    private fun featured(title: String): List<String> = featuredCredits(title).flatMap { split(it, featuredSeparator) }

    private fun split(text: String, pattern: Regex?): List<String> {
        val pieces = pieces(text, pattern)
        // A name nothing split keeps its punctuation, as in "Sunn O)))".
        if (pieces.singleOrNull() == text.indices) return listOfNotNull(text.trim().ifEmpty { null })
        return pieces.mapNotNull { tidy(text.substring(it)) }
    }

    /**
     * Where the names between [pattern]'s separators are in [text]. Separators inside an exception are skipped when the
     * exception is a whole credit, so "Earth, Wind & Fire" doesn't also keep "Earth, Wind & Fireflies" together.
     */
    private fun pieces(text: String, pattern: Regex?): List<IntRange> {
        if (pattern == null || text.isBlank()) return listOf(text.indices)
        val matches = pattern.findAll(text).toList()
        // Brackets around a credit, as in "A (feat. B)", are as much a boundary as spaces.
        fun gap(between: String) = between.all { it.isWhitespace() || it in "()[]" }
        fun creditStart(index: Int) = gap(text.substring(0, index)) ||
            matches.any { it.range.last < index && gap(text.substring(it.range.last + 1, index)) }
        fun creditEnd(index: Int) = gap(text.substring(index)) ||
            matches.any { it.range.first >= index && gap(text.substring(index, it.range.first)) }
        val protected = protectedNames.flatMap { name ->
            generateSequence(text.indexOf(name, ignoreCase = true).takeIf { it >= 0 }) { start ->
                text.indexOf(name, start + 1, ignoreCase = true).takeIf { it >= 0 }
            }.filter { creditStart(it) && creditEnd(it + name.length) }.map { it until it + name.length }
        }
        val pieces = mutableListOf<IntRange>()
        var start = 0
        for (match in matches) {
            if (protected.any { match.range.first in it }) continue
            if (text.substring(start, match.range.first).isNotBlank()) pieces += start until match.range.first
            start = match.range.last + 1
        }
        if (text.substring(start).isNotBlank()) pieces += start until text.length
        return pieces
    }

    companion object {
        private val featuring = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^)\]]+)[)\]]|\s(?:feat\.?|ft\.?|featuring)\s+([^(\[]+)$""",
            RegexOption.IGNORE_CASE)
        private val wordCharacter = Regex("""[\p{L}\p{N}]""")

        private fun pattern(separators: List<String>): Regex? = separators.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            // Longer separators first, so "featuring" wins over a shorter one it contains.
            .sortedByDescending { it.length }
            .map { if (wordCharacter.containsMatchIn(it)) """(?<![\p{L}\p{N}])${Regex.escape(it)}(?![\p{L}\p{N}])""" else Regex.escape(it) }
            .takeIf { it.isNotEmpty() }?.let { Regex(it.joinToString("|"), RegexOption.IGNORE_CASE) }

        /** Trims a name, and the bracket a split left at its edge, as in "A (feat. B)". */
        private fun tidy(name: String): String? {
            var result = name.trim()
            if (result.endsWith('(') || result.endsWith('[')) result = result.dropLast(1).trim()
            if (result.endsWith(')') && result.count { it == '(' } < result.count { it == ')' }) result = result.dropLast(1).trim()
            if (result.endsWith(']') && result.count { it == '[' } < result.count { it == ']' }) result = result.dropLast(1).trim()
            return result.ifEmpty { null }
        }
    }
}

fun MusicSettings.artistSplitting() = ArtistSplitting(artistSeparators, artistSeparatorExceptions, featuredArtistsFromTitle)
