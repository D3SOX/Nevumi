package me.d3sox.nevumi.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

@UnstableApi
internal class EffectsAudioProcessor(private val effects: () -> AudioEffects, private val replayGainDb: () -> Double = { 0.0 }) :
    BaseAudioProcessor() {
    private var applied: Pair<AudioEffects, Double>? = null
    private var processor: AudioFrameProcessor? = null
    private var frame = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..32) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        applied = null
        processor = null
        frame = DoubleArray(inputAudioFormat.channelCount.coerceAtLeast(0))
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val settings = effects() to replayGainDb()
        if (settings != applied) {
            processor = AudioFrameProcessor(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, settings.first, settings.second)
            applied = settings
        }
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        while (inputBuffer.hasRemaining()) {
            for (channel in frame.indices) frame[channel] = inputBuffer.short / 32768.0
            requireNotNull(processor).process(frame)
            for (sample in frame) output.putShort((sample * 32768).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }
}
