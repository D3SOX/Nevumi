package me.d3sox.nevumi.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import me.d3sox.nevumi.library.TrackSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal actual fun waveformPreparationParallelism(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)

class AndroidAudioWaveforms(private val context: Context) : AudioWaveforms {
    private val cached = CachedAudioWaveforms(context.filesDir.resolve("waveforms").path, ::decode)
    override suspend fun load(source: TrackSource, durationMs: Long) = cached.load(source, durationMs)
    override suspend fun cached(source: TrackSource) = cached.cached(source)

    override suspend fun soundEdges(source: TrackSource) = cached.soundEdges(source)

    private suspend fun decode(location: String, durationMs: Long): AudioAnalysis {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(location).let { if (it.scheme == null) Uri.fromFile(java.io.File(location)) else it }, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            var format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val energy = DoubleArray(128)
            val counts = LongArray(128)
            val envelope = SoundEnvelope()
            var samples = 0L
            var pcmSamples = ShortArray(32_768)
            fun accumulate(buffer: ByteBuffer, timestampUs: Long) {
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val bytes = when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    AudioFormat.ENCODING_PCM_8BIT -> 1
                    else -> 2
                }
                if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
                    val pcm = buffer.asShortBuffer()
                    val sampleCount = pcm.remaining()
                    if (pcmSamples.size < sampleCount) pcmSamples = ShortArray(sampleCount)
                    pcm.get(pcmSamples, 0, sampleCount)
                    var cursor = 0
                    val totalFrames = (durationMs * rate / 1_000).coerceAtLeast(1)
                    var frame = timestampUs * rate / 1_000_000
                    while (sampleCount - cursor >= channels) {
                        val band = (frame * 128 / totalFrames).coerceIn(0, 127).toInt()
                        val boundary = (band + 1L) * totalFrames / 128
                        // Chunks also end at sound windows, the first frame at or after the next window's start.
                        val window = frame * 1_000 / SOUND_WINDOW_MS / rate
                        val windowEnd = ((window + 1) * SOUND_WINDOW_MS * rate + 999) / 1_000
                        val limit = (if (band == 127) windowEnd else minOf(boundary, windowEnd)) - frame
                        val count = minOf(((sampleCount - cursor) / channels).toLong(), limit.coerceAtLeast(1)).toInt()
                        var squares = 0.0
                        val end = cursor + count * channels
                        while (cursor < end) { val value = pcmSamples[cursor++].toInt(); squares += value.toDouble() * value }
                        energy[band] += squares / (32_768.0 * 32_768.0)
                        counts[band] += count * channels
                        envelope.add(window, squares / (32_768.0 * 32_768.0), count.toLong() * channels)
                        frame += count
                        samples += count * channels
                    }
                    return
                }
                var frame = 0L
                while (buffer.remaining() >= channels * bytes) {
                    val time = timestampUs + frame++ * 1_000_000 / rate
                    val band = (time * 128 / (durationMs * 1_000)).coerceIn(0, 127).toInt()
                    val window = time / (SOUND_WINDOW_MS * 1_000)
                    repeat(channels) {
                        val value = when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
                            AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                                val raw = (buffer.get().toInt() and 255) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() shl 16)
                                raw / 8_388_608f
                            }
                            AudioFormat.ENCODING_PCM_8BIT -> ((buffer.get().toInt() and 255) - 128) / 128f
                            else -> buffer.short / 32_768f
                        }
                        if (value.isFinite()) {
                            energy[band] += value.toDouble() * value; counts[band]++
                            envelope.add(window, value.toDouble() * value, 1)
                        }
                        samples++
                    }
                }
            }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            if (mime == "audio/raw") {
                val buffer = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    buffer.position(0); buffer.limit(size)
                    accumulate(buffer, extractor.sampleTime)
                    extractor.advance()
                }
            } else {
                val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
                decoder.configure(format, null, null, 0); decoder.start()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    var queued = 0
                    while (!inputEnded && queued < 8) {
                        currentCoroutineContext().ensureActive()
                        val index = decoder.dequeueInputBuffer(0)
                        if (index < 0) break
                        val buffer = requireNotNull(decoder.getInputBuffer(index))
                        buffer.clear()
                        val timestamp = extractor.sampleTime
                        var size = extractor.readSampleData(buffer, 0)
                        inputEnded = size < 0
                        if (!inputEnded) {
                            extractor.advance()
                            // MP3 permits consecutive complete frames in one input buffer.
                            // Batch them to reduce codec IPC while retaining the first timestamp.
                            var frames = 1
                            while (mime == "audio/mpeg" && frames < 16 && extractor.sampleSize > 0 &&
                                extractor.sampleSize <= buffer.capacity() - size) {
                                currentCoroutineContext().ensureActive()
                                buffer.clear()
                                val extra = extractor.readSampleData(buffer, size)
                                if (extra <= 0) break
                                size += extra
                                frames++
                                extractor.advance()
                            }
                        }
                        decoder.queueInputBuffer(index, 0, size.coerceAtLeast(0), if (inputEnded) 0 else timestamp,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        queued++
                    }
                    var index = decoder.dequeueOutputBuffer(info, 1_000)
                    while (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        currentCoroutineContext().ensureActive()
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = decoder.outputFormat
                        else if (index >= 0) {
                            try {
                                if (info.size > 0) {
                                    val buffer = requireNotNull(decoder.getOutputBuffer(index))
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    accumulate(buffer, info.presentationTimeUs)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { decoder.releaseOutputBuffer(index, false) }
                        }
                        if (outputEnded) break
                        index = decoder.dequeueOutputBuffer(info, 0)
                    }
                }
            }
            check(samples > 0)
            return AudioAnalysis(energy.indices.map { sqrt(energy[it] / counts[it].coerceAtLeast(1)).toFloat().coerceIn(0f, 1f) },
                envelope.edges())
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
