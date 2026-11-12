package me.d3sox.nevumi.queue

import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.library.Track
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/** How long until a played song gets back half of the weight smart shuffle took from it. */
internal const val SMART_SHUFFLE_RECENT_HALF_LIFE_MS = 2 * 86_400_000L

/**
 * How often smart shuffle picks a song compared to an unrated song that was never played, which weighs 1. Each star above
 * three multiplies the weight by 1.5 and each star below divides it by 1.5; an unrated song counts as three stars, so one
 * or two stars come up less often than no rating. A favorite counts twice. A song played just now keeps a tenth of its
 * weight and gets half of what it lost back every [SMART_SHUFFLE_RECENT_HALF_LIFE_MS]. Every weight stays above zero, so
 * no song is left out.
 */
internal fun smartShuffleWeight(rating: Int, favorite: Boolean, lastPlayedAt: Long?, now: Long): Double {
    val stars = rating.takeIf { it in 1..5 } ?: 3
    val preference = 1.5.pow(stars - 3) * (if (favorite) 2.0 else 1.0)
    if (lastPlayedAt == null) return preference
    val age = (now - lastPlayedAt).coerceAtLeast(0).toDouble()
    return preference * (1 - 0.9 * 0.5.pow(age / SMART_SHUFFLE_RECENT_HALF_LIFE_MS))
}

/**
 * Smart shuffle's weight for each song ID, from the library's ratings and favorites and the newest listen of each song.
 * Built once per shuffle from what the app already holds. Linked local and server copies are one song with one ID, so
 * they share their rating, favorite and history. Songs missing from [tracks] weigh as unrated non-favorites.
 */
internal fun smartShuffleWeights(tracks: List<Track>, listens: List<Listen>, now: Long): (String) -> Double {
    val lastPlayed = HashMap<String, Long>()
    for (listen in listens) {
        val previous = lastPlayed[listen.trackId]
        if (previous == null || listen.startedAt > previous) lastPlayed[listen.trackId] = listen.startedAt
    }
    val library = tracks.associateBy { it.id }
    return { id -> library[id].let { smartShuffleWeight(it?.rating ?: 0, it?.favorite == true, lastPlayed[id], now) } }
}

/** The Efraimidis–Spirakis key u^(1 / weight), compared as ln(u) / weight; the largest keys win. */
private fun Random.sampleKey(weight: Double): Double = ln(1 - nextDouble()) / weight

/**
 * Weighted random sampling without replacement (Efraimidis–Spirakis): heavier items tend to come earlier, and every
 * item with a positive weight can still come anywhere. Equal weights give a uniform shuffle.
 */
internal fun <T> List<T>.weightedShuffled(random: Random, weight: (T) -> Double): List<T> {
    val keys = DoubleArray(size) { random.sampleKey(weight(this[it])) }
    return indices.sortedByDescending { keys[it] }.map { this[it] }
}

/** The index of one item picked by weight, the first item [weightedShuffled] would return; -1 when empty. */
internal fun <T> List<T>.weightedIndex(random: Random, weight: (T) -> Double): Int =
    indices.maxByOrNull { random.sampleKey(weight(this[it])) } ?: -1
