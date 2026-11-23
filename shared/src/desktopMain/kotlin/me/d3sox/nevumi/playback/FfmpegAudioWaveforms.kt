package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.TrackSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal actual fun waveformPreparationParallelism(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

/** Waveforms and sound edges cached in the user's cache directory, shared by the waveform view and crossfades. */
class FfmpegAudioWaveforms(directory: String = defaultDirectory()) : AudioWaveforms {
    private val cached = CachedAudioWaveforms(directory, ::decode)
    override suspend fun load(source: TrackSource, durationMs: Long) = cached.load(source, durationMs)
    override suspend fun cached(source: TrackSource) = cached.cached(source)
    override suspend fun soundEdges(source: TrackSource) = cached.soundEdges(source)
    private suspend fun decode(location: String, durationMs: Long): AudioAnalysis {
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-i", location, "-ac", "2", "-ar", "8000", "-f", "f32le", "pipe:1")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val energy = DoubleArray(128)
            val counts = LongArray(128)
            val envelope = SoundEnvelope()
            val total = (durationMs * 16).coerceAtLeast(1)
            var sample = 0L
            val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            process.inputStream.use { pcm ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = pcm.read(buffer.array(), buffer.position(), buffer.remaining())
                    if (read < 0) break
                    buffer.limit(buffer.position() + read).position(0)
                    val floats = buffer.asFloatBuffer()
                    while (floats.hasRemaining()) {
                        val band = (sample * 128 / total).coerceIn(0, 127).toInt()
                        val end = if (band == 127) Long.MAX_VALUE else ((band + 1) * total + 127) / 128
                        val window = sample / SAMPLES_PER_WINDOW
                        val count = minOf(floats.remaining().toLong(), end - sample, (window + 1) * SAMPLES_PER_WINDOW - sample).toInt()
                        var squares = 0.0
                        var finite = 0L
                        repeat(count) {
                            val value = floats.get()
                            if (value.isFinite()) { squares += value.toDouble() * value; finite++ }
                        }
                        energy[band] += squares
                        counts[band] += finite
                        envelope.add(window, squares, finite)
                        sample += count
                    }
                    // Pipe reads may end between the four bytes of a PCM float.
                    buffer.position(floats.position() * 4).compact()
                }
            }
            check(process.waitFor() == 0 && sample > 0)
            return AudioAnalysis(energy.indices.map { sqrt(energy[it] / counts[it].coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
                envelope.edges())
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}

private fun defaultDirectory(): String {
    val root = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), ".cache").path
    return File(root, "nevumi/waveforms").path
}

/** Two channels at 8 kHz. */
private const val SAMPLES_PER_WINDOW = 2 * 8_000 * SOUND_WINDOW_MS / 1_000
