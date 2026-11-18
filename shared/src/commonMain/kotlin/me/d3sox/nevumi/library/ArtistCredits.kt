package me.d3sox.nevumi.library

import me.d3sox.nevumi.settings.MusicSettings

/**
 * What separates artists in an artist tag by default. Spotify and SoundCloud downloads list artists with commas. `&`, `x` and
 * `/` are opt-in, because they appear inside names such as "Above & Beyond", "X CLUB." and "AC/DC".
 */
val DEFAULT_ARTIST_SEPARATORS = listOf(";", ",", "feat.", "feat", "ft.", "ft", "featuring", "vs.", "vs")

/** Well-known artists whose names contain a comma, so the default separators keep them whole. */
val DEFAULT_ARTIST_EXCEPTIONS = listOf("Tyler, The Creator", "Earth, Wind & Fire", "Crosby, Stills, Nash & Young", "Crosby, Stills & Nash",
    "Emerson, Lake & Palmer", "Blood, Sweat & Tears", "Peter, Paul and Mary")

/**
 * Finds the artists a song credits: those that tags or the server list separately, or else the artist tag. [separators]
 * split each of them, except inside [exceptions] such as "Tyler, The Creator". Separators made of symbols split wherever they occur,
 * and words such as "feat." only where they stand alone, so "ft." does not split "Daft." With [featuredFromTitle], artists a
 * title names, as in "Song (feat. B)", are credited too.
 */
data class ArtistSplitting(val separators: List<String> = DEFAULT_ARTIST_SEPARATORS, val exceptions: List<String> = DEFAULT_ARTIST_EXCEPTIONS,
    val featuredFromTitle: Boolean = true) {
    private val protectedNames = exceptions.map { it.trim() }.filter { it.isNotEmpty() }
    private val separator = pattern(separators)
    // Featured artists are nearly always listed with commas and ampersands, whatever splits the artist tag.
    private val featuredSeparator = pattern(separators + listOf("&", ","))

    fun names(metadata: TrackMetadata): List<String> {
        // Servers split with their own rules, so a listed name can still hold several artists, as in "Macloud, makko".
        val credited = (metadata.artists.takeIf { it.size > 1 } ?: listOf(metadata.artist)).flatMap { split(it, separator) }
        val featured = if (featuredFromTitle) featured(metadata.title) else emptyList()
        return (credited + featured).distinctBy { it.lowercase() }
    }

    private fun featured(title: String): List<String> =
        featuring.findAll(title).flatMap { split(it.groupValues[1].ifEmpty { it.groupValues[2] }, featuredSeparator) }.toList()

    private fun split(text: String, pattern: Regex?): List<String> {
        if (pattern == null || text.isBlank()) return listOfNotNull(tidy(text))
        // An exception keeps its separators, so splits inside one are skipped.
        val protected = protectedNames.flatMap { name ->
            generateSequence(text.indexOf(name, ignoreCase = true).takeIf { it >= 0 }) { start ->
                text.indexOf(name, start + 1, ignoreCase = true).takeIf { it >= 0 }
            }.map { it until it + name.length }
        }
        val names = mutableListOf<String>()
        var start = 0
        for (match in pattern.findAll(text)) {
            if (protected.any { match.range.first in it }) continue
            tidy(text.substring(start, match.range.first))?.let(names::add)
            start = match.range.last + 1
        }
        tidy(text.substring(start))?.let(names::add)
        return names
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
