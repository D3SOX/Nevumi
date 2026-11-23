package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.TrackSource

interface AudioWaveforms {
    suspend fun load(source: TrackSource, durationMs: Long): List<Float>
    /** Read a prepared result without starting a decoder. */
    suspend fun cached(source: TrackSource): List<Float>? = null
    /** Where the song's sound starts and ends, read from a prepared result without starting a decoder. */
    suspend fun soundEdges(source: TrackSource): SoundEdges? = null
}
