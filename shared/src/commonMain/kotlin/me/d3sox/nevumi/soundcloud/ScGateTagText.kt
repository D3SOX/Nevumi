package me.d3sox.nevumi.soundcloud

// The same cleanup and file names as sc-gate-dl's web interface, so both offer the same tags for a download.

/** Promo text often glued onto SoundCloud and gate titles. */
private const val PROMO = """free\s*d(?:own)?l(?:oad)?s?|free[\s._-]*dl|freedl|out\s*now|premiere|exclusive"""
private val bracketedPromo = Regex("""\s*[\[({]\s*(?:$PROMO)\s*[\])}]\s*(?:[-–—|/:·•]+\s*)?""", RegexOption.IGNORE_CASE)
private val trailingPromo = Regex("""(?:\s*[-–—|/·•*]+\s*|\s+)(?:$PROMO)(?:\s*\*+)?\s*$""", RegexOption.IGNORE_CASE)
private val gluedPromo = Regex("""(?:$PROMO)\s*$""", RegexOption.IGNORE_CASE)
private val leadingPromo = Regex("""^\s*(?:$PROMO)(?:\s*[-–—|/·•]+\s*|\s+)""", RegexOption.IGNORE_CASE)
private val edgeSeparators = Regex("""^[\s\-–—|/·•]+|[\s\-–—|/·•]+$""")
private val spaces = Regex("""\s{2,}""")

/** Removes promo text such as [FREE DL] or PREMIERE. */
internal fun withoutPromoText(value: String): String {
    if (value.isEmpty()) return value
    var result = bracketedPromo.replace(value, " ")
    result = trailingPromo.replace(result, "")
    result = gluedPromo.replace(result, "")
    result = leadingPromo.replace(result, "")
    result = spaces.replace(result, " ")
    return edgeSeparators.replace(result, "").trim()
}

/** Strips characters' accents, so artist credits match however they are written. */
internal expect fun withoutMarks(text: String): String

private val creditJoins = Regex("""\b(?:featuring|feat|ft|with|versus|vs)\.?\b""")
private val creditSeparators = Regex("""\s*(?:,|&|\+|×)\s*|\s+and\s+""")
private val creditPunctuation = Regex("""[^\p{L}\p{N}]+""")

private fun artistCredits(value: String): List<String> = creditJoins.replace(withoutMarks(value).lowercase(), "&")
    .split(creditSeparators).map { creditPunctuation.replace(it, "") }.filter { it.isNotEmpty() }.sorted()

private fun sameArtists(left: String, right: String): Boolean {
    val credits = artistCredits(left)
    return credits.isNotEmpty() && credits == artistCredits(right)
}

private val titleSeparators = Regex("""\s+[-–—|:]\s+""")

/** Removes a leading or trailing artist credit from [title], even when its separators differ. */
internal fun withoutDuplicateArtist(title: String, artist: String): String {
    val trimmed = title.trim()
    if (artist.isBlank() || trimmed.isEmpty()) return trimmed
    for (separator in titleSeparators.findAll(trimmed)) {
        val left = trimmed.substring(0, separator.range.first).trim()
        val right = trimmed.substring(separator.range.last + 1).trim()
        if (sameArtists(left, artist.trim())) return right
        if (sameArtists(right, artist.trim())) return left
    }
    return trimmed
}

/** The tags without promo text and without the artist repeated in the title. */
internal fun ScGateTags.withoutPromo(): ScGateTags {
    val cleanArtist = withoutPromoText(artist)
    return ScGateTags(withoutDuplicateArtist(withoutPromoText(title), cleanArtist), cleanArtist, withoutPromoText(album), withoutPromoText(genre))
}

private val unsafeFileCharacters = Regex("""[<>:"/\\|?*]""")
private val whitespace = Regex("""\s+""")
private val convertedToMp3 = Regex("""\.(wav|aiff|aif|flac|m4a|aac|ogg|opus|webm)$""", RegexOption.IGNORE_CASE)

private fun fileNamePart(value: String) = whitespace.replace(unsafeFileCharacters.replace(value, ""), " ").trim()

/** The name sc-gate-dl gives the tagged file of [fileName]. */
internal fun taggedFileName(fileName: String?, format: ScGateFormat, artistTitle: Boolean, tags: ScGateTags): String {
    if (fileName.isNullOrEmpty()) return ""
    val extension = if (format == ScGateFormat.FLAC) ".flac" else ".mp3"
    return when {
        artistTitle -> "${fileNamePart(tags.artist).ifEmpty { "Unknown Artist" }} - ${fileNamePart(tags.title).ifEmpty { "Unknown Title" }}$extension"
        format == ScGateFormat.FLAC -> fileName.replace(Regex("""\.[^.]+$"""), ".flac")
        else -> convertedToMp3.replace(fileName, ".mp3")
    }
}
