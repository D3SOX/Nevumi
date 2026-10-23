package me.d3sox.nevumi.history

import me.d3sox.nevumi.library.Track
import kotlin.time.Clock

/** Recommended because the listener plays the artist or genre [name]; the UI phrases the reason. */
data class ListeningRecommendation(val track: Track, val reason: Reason, val name: String) {
    enum class Reason { ARTIST, GENRE }
}

fun listeningRecommendations(listens: List<Listen>, tracks: List<Track>, limit: Int = 20,
    now: Long = Clock.System.now().toEpochMilliseconds()): List<ListeningRecommendation> {
    require(limit > 0) { "Recommendation limit must be positive" }
    data class Preference(val name: String, var plays: Int = 0)
    val artists = linkedMapOf<String, Preference>()
    val genres = linkedMapOf<String, Preference>()
    val qualified = listens.filter { it.qualified && it.startedAt <= now }
    for (event in qualified) {
        val artist = event.metadata.artist.trim()
        if (artist.isNotEmpty()) artists.getOrPut(artist.lowercase()) { Preference(artist) }.plays++
        for (genre in event.metadata.genres.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() })
            genres.getOrPut(genre.lowercase()) { Preference(genre) }.plays++
    }
    val statistics = listeningStatistics(qualified)
    data class Candidate(val recommendation: ListeningRecommendation, val affinity: Int, val plays: Int)
    return tracks.asSequence().filter { it.sources.any { source -> source.available } }.mapNotNull { track ->
        val previous = statistics[track.id]
        if (previous != null && previous.lastPlayedAt >= now - 7 * 86_400_000L) return@mapNotNull null
        val artist = artists[track.metadata.artist.trim().lowercase()]
        val matchingGenres = track.metadata.genres.map(String::trim).distinctBy { it.lowercase() }.mapNotNull { genres[it.lowercase()] }
        val affinity = (artist?.plays ?: 0) * 3 + matchingGenres.sumOf { it.plays }
        if (affinity == 0) return@mapNotNull null
        val recommendation = artist?.let { ListeningRecommendation(track, ListeningRecommendation.Reason.ARTIST, it.name) }
            ?: ListeningRecommendation(track, ListeningRecommendation.Reason.GENRE, matchingGenres.maxBy { it.plays }.name)
        Candidate(recommendation, affinity, previous?.playCount ?: 0)
    }.sortedWith(compareByDescending<Candidate> { it.plays == 0 }.thenByDescending { it.affinity }.thenBy { it.plays }
        .thenBy { it.recommendation.track.metadata.title.lowercase() }.thenBy { it.recommendation.track.id })
        .take(limit).map { it.recommendation }.toList()
}
