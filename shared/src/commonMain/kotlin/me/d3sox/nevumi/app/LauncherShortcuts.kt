package me.d3sox.nevumi.app

import kotlinx.coroutines.flow.first
import me.d3sox.nevumi.library.inLibrary
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.songs
import org.jetbrains.compose.resources.getString

/** Shuffles every library song once startup has loaded the library, for the launcher's Shuffle shortcut. */
suspend fun MusicController.shuffleLibrary() {
    val ids = state.first { it.started && it.libraryReady }.tracks.filter { it.inLibrary }.map { it.id }
    if (ids.isEmpty()) return
    val index = shuffleStart(ids)
    playTrack(ids[index], ids, index, getString(Res.string.songs), shuffleContext = true).join()
}

/** Continues the restored queue once startup has loaded it, for the launcher's Resume shortcut. */
suspend fun MusicController.resumeQueue() {
    if (state.first { it.started }.queue.currentEntryId != null) play()
}
