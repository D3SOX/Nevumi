package me.d3sox.nevumi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The state of one search of another source, which [RecentSearches] keeps once it found results. */
internal open class CachedSearch {
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var loaded: TimeMark? = null
    /** Results found recently enough to show again without searching. */
    val fresh: Boolean get() = loaded?.let { it.elapsedNow() < 15.minutes } == true
    fun markLoaded() { loaded = TimeSource.Monotonic.markNow() }
}

/**
 * The latest searches that found results, kept while the search screen is open, so going back to one, such as from the library,
 * shows its results without searching again.
 */
internal class RecentSearches<K, S : CachedSearch>(private val create: (K) -> S) {
    private val found = mutableMapOf<K, S>()

    fun open(key: K): S = found[key] ?: create(key)

    /** Keeps [search] after it found results, dropping the oldest search beyond 20. */
    fun keep(key: K, search: S) {
        found.remove(key)
        found[key] = search
        if (found.size > 20) found.remove(found.keys.first())
    }
}
