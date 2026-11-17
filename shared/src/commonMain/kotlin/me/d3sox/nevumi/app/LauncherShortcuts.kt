package me.d3sox.nevumi.app

import kotlinx.coroutines.flow.first
import me.d3sox.nevumi.library.inLibrary
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.songs
import org.jetbrains.compose.resources.getString

/**
 * Waits until startup has loaded the library, then returns the call that shuffles every library song, or null when there is none.
 * The waiting can be repeated, such as by a recreated activity; the returned call hands the shuffle to the controller once.
 */
suspend fun MusicController.libraryShuffle(): (() -> Unit)? {
    val ids = state.first { it.started && it.libraryReady }.tracks.filter { it.inLibrary }.map { it.id }
    if (ids.isEmpty()) return null
    val name = getString(Res.string.songs)
    return {
        val index = shuffleStart(ids)
        playTrack(ids[index], ids, index, name, shuffleContext = true)
    }
}

/** Waits until startup has restored the queue, then returns the call that continues it, or null without a queue. */
suspend fun MusicController.queueResume(): (() -> Unit)? =
    if (state.first { it.started }.queue.currentEntryId != null) { { play() } } else null
