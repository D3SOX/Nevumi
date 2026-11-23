package me.d3sox.nevumi.playback

import kotlinx.serialization.Serializable
import me.d3sox.nevumi.library.MusicLibrary
import kotlin.math.max
import kotlin.math.sqrt

/** Where a song's sound starts and ends in its file, so a crossfade skips leading and trailing silence. */
@Serializable
data class SoundEdges(val startMs: Long, val endMs: Long)

/** What one decoding pass learns about a song: its waveform and where its sound starts and ends. */
@Serializable
data class AudioAnalysis(val peaks: List<Float>, val edges: SoundEdges? = null)

/** Collects the energy of short windows while a decoder reads a song, to find where its sound starts and ends. */
internal class SoundEnvelope {
    private var energy = DoubleArray(1_024)
    private var counts = LongArray(1_024)
    private var size = 0

    /** Adds the summed squares of [count] samples that all lie in [window], counted in [SOUND_WINDOW_MS] steps from the start. */
    fun add(window: Long, squares: Double, count: Long) {
        if (window < 0 || count <= 0) return
        // A broken timestamp must not make the decoder allocate gigabytes; nothing plays for more than a day.
        val index = window.coerceAtMost(MAX_WINDOWS.toLong()).toInt()
        if (index >= energy.size) {
            val grown = max(index + 1, energy.size * 2).coerceAtMost(MAX_WINDOWS + 1)
            energy = energy.copyOf(grown)
            counts = counts.copyOf(grown)
        }
        energy[index] += squares
        counts[index] += count
        size = max(size, index + 1)
    }

    /**
     * A window holds sound when it is no more than [RELATIVE_SILENCE] below the song's overall level and above an absolute
     * floor, so a near-silent fade-out tail counts as silence while quiet recordings keep their soft passages.
     */
    fun edges(): SoundEdges? {
        var total = 0.0
        var samples = 0L
        for (index in 0 until size) { total += energy[index]; samples += counts[index] }
        if (samples == 0L) return null
        val threshold = max(SILENCE_FLOOR, sqrt(total / samples) * RELATIVE_SILENCE)
        fun audible(index: Int) = counts[index] > 0 && sqrt(energy[index] / counts[index]) >= threshold
        val first = (0 until size).firstOrNull(::audible) ?: return null
        val last = (size - 1 downTo first).first(::audible)
        return SoundEdges(first * SOUND_WINDOW_MS, (last + 1) * SOUND_WINDOW_MS)
    }

    private companion object {
        /** −60 dBFS. */
        const val SILENCE_FLOOR = 0.001
        /** −40 dB below the song's overall level. */
        const val RELATIVE_SILENCE = 0.01
        const val MAX_WINDOWS = 24 * 60 * 60 * 1_000 / SOUND_WINDOW_MS.toInt()
    }
}

internal const val SOUND_WINDOW_MS = 50L

/** Reads the analyzed edges of the library source a player holds, without starting a decoder. */
fun soundEdgeLookup(library: MusicLibrary, waveforms: AudioWaveforms): suspend (PlayableTrack) -> SoundEdges? = lookup@{ track ->
    val sourceId = track.sourceId ?: return@lookup null
    val source = library.findTrack(track.id)?.sources?.firstOrNull { it.id == sourceId } ?: return@lookup null
    waveforms.soundEdges(source)
}
