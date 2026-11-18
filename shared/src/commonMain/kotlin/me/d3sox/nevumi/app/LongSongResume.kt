package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.isPodcastEpisode
import me.d3sox.nevumi.library.isRadio
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.playback.RepeatMode

/**
 * Continues long songs, such as DJ mixes, audiobooks and live recordings, where they were left, if Settings asks for it. Like podcast
 * bookmarks, a position is saved when the song pauses, every half minute while it plays, and when another song replaces it; one that
 * finishes, or nearly does, starts from the beginning next time. Podcast episodes keep using the server's bookmarks instead.
 */
internal class LongSongResume(private val context: ControllerContext, private val library: MusicLibrary,
    private val playback: PlaybackController) {
    init { context.scope.launch { follow() } }

    private suspend fun follow() {
        var previous: PlaybackStatus? = null
        var savedAt = 0L
        /** A song whose length was unknown when it started; it can still resume once the decoder reports one. */
        var awaitingDuration: String? = null
        val clock = kotlin.time.TimeSource.Monotonic.markNow()
        context.state.map { it.playback }.distinctUntilChanged().collect { current ->
            val last = previous
            previous = current
            // Read before the setting: turning it off saves the setting first and then forgets the positions under a new generation,
            // so a save that saw the setting on always carries a generation that forgetting replaces.
            val generation = library.songPositionGeneration
            val minimumMs = context.state.value.preferences.resumeLongSongsMinutes * 60_000L
            if (last == null || minimumMs <= 0) return@collect
            // One failed lookup or write must not end the saving for the rest of the session.
            try {
                val changed = current.entryId != last.entryId
                // Repeat-one starts the song over by itself, so the pass that ended counts as finished.
                val repeated = !changed && current.repeatMode == RepeatMode.ONE && last.playbackId != null &&
                    current.playbackId != null && current.playbackId != last.playbackId
                val paused = last.playing && !current.playing && !changed
                val now = clock.elapsedNow().inWholeMilliseconds
                val periodic = current.playing && !changed && now - savedAt >= 30_000
                if (changed || repeated || paused || periodic || current.ended) {
                    savedAt = now
                    // The song being left saves first: the same song queued twice must not resume from the position it just finished.
                    checkpoint(if (changed || repeated) last else current, repeated, minimumMs, generation)
                }
                if (changed) awaitingDuration = if (resume(current, minimumMs)) null else current.entryId
                else if (current.entryId == awaitingDuration && current.durationMs > 0) {
                    awaitingDuration = null
                    resume(current, minimumMs)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }

    private suspend fun checkpoint(status: PlaybackStatus, repeated: Boolean, minimumMs: Long, generation: Long) {
        val track = findTrack(status.trackId ?: return)?.takeIf { qualifies(it, status, minimumMs) } ?: return
        val duration = status.durationMs.takeIf { it > 0 } ?: track.metadata.durationMs
        val finished = repeated || status.ended || duration > 0 && status.positionMs >= duration - FINISHED_WITHIN_MS
        // A song left within its first seconds keeps its earlier position, as does one still seeking there after a resume.
        if (!finished && status.positionMs < MINIMUM_POSITION_MS) return
        // Turning the setting off forgets every position; a save read before that, even if it runs after the setting is back on,
        // is dropped, and the library checks in the same transaction that the song was not linked into another meanwhile.
        library.rememberSongPosition(track.id, status.positionMs.takeUnless { finished }, generation)
    }

    /**
     * Continues [status]'s song where it was left when it starts from the beginning; a recovered queue already starts where it stopped.
     * Returns false only while neither the library nor the decoder knows the song's length, so the caller can try again.
     */
    private suspend fun resume(status: PlaybackStatus, minimumMs: Long): Boolean {
        val entryId = status.entryId ?: return true
        if (status.positionMs >= MINIMUM_POSITION_MS) return true
        val track = findTrack(status.trackId ?: return true) ?: return true
        if (status.durationMs <= 0 && track.metadata.durationMs <= 0) return false
        if (!qualifies(track, status, minimumMs)) return true
        library.songPosition(track.id)?.let { playback.resumeCurrentEntry(entryId, it) }
        return true
    }

    private suspend fun findTrack(id: String): Track? = context.state.value.tracks.firstOrNull { it.id == id } ?: library.findTrack(id)

    private fun qualifies(track: Track, status: PlaybackStatus, minimumMs: Long) = !track.isPodcastEpisode && !track.isRadio &&
        (status.durationMs.takeIf { it > 0 } ?: track.metadata.durationMs) >= minimumMs

    private companion object {
        /** Longer than the longest crossfade, so a song that fades into the next one counts as finished. */
        const val FINISHED_WITHIN_MS = 15_000L
        const val MINIMUM_POSITION_MS = 5_000L
    }
}
