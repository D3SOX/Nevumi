package me.d3sox.nevumi.playback

import kotlin.math.*

internal class AudioFrameProcessor(sampleRateHz: Int, private val channels: Int, private val effects: AudioEffects,
    replayGainDb: Double = 0.0) {
    private val gain = 10.0.pow((effects.effectivePreampDb + replayGainDb) / 20)
    private val filters: List<Biquad>

    init {
        effects.validate()
        require(sampleRateHz > 0 && channels in 1..32)
        filters = if (effects.enabled) effects.bands.filter { it.gainDb != 0.0 && it.frequencyHz < sampleRateHz / 2.0 }
            .map { Biquad(it, sampleRateHz, channels) } else emptyList()
    }

    fun process(frame: DoubleArray) {
        require(frame.size == channels)
        if (effects.mono && channels > 1) frame.fill(frame.sum() / channels)
        for (channel in frame.indices) {
            var sample = frame[channel] * gain
            for (filter in filters) sample = filter.process(sample, channel)
            frame[channel] = sample
        }
    }

    // RBJ biquad equations: https://www.w3.org/TR/audio-eq-cookbook/
    private class Biquad(band: EqualizerBand, sampleRateHz: Int, channels: Int) {
        private val z1 = DoubleArray(channels)
        private val z2 = DoubleArray(channels)
        private val b0: Double
        private val b1: Double
        private val b2: Double
        private val a1: Double
        private val a2: Double

        init {
            val amplitude = 10.0.pow(band.gainDb / 40)
            val angle = 2 * PI * band.frequencyHz / sampleRateHz
            val cosine = cos(angle)
            val alpha = sin(angle) / (2 * band.q)
            val shelf = 2 * sqrt(amplitude) * alpha
            val coefficients = when (band.kind) {
                EqualizerKind.PEAK -> doubleArrayOf(1 + alpha * amplitude, -2 * cosine, 1 - alpha * amplitude,
                    1 + alpha / amplitude, -2 * cosine, 1 - alpha / amplitude)
                EqualizerKind.LOW_SHELF -> doubleArrayOf(
                    amplitude * ((amplitude + 1) - (amplitude - 1) * cosine + shelf),
                    2 * amplitude * ((amplitude - 1) - (amplitude + 1) * cosine),
                    amplitude * ((amplitude + 1) - (amplitude - 1) * cosine - shelf),
                    (amplitude + 1) + (amplitude - 1) * cosine + shelf,
                    -2 * ((amplitude - 1) + (amplitude + 1) * cosine),
                    (amplitude + 1) + (amplitude - 1) * cosine - shelf)
                EqualizerKind.HIGH_SHELF -> doubleArrayOf(
                    amplitude * ((amplitude + 1) + (amplitude - 1) * cosine + shelf),
                    -2 * amplitude * ((amplitude - 1) + (amplitude + 1) * cosine),
                    amplitude * ((amplitude + 1) + (amplitude - 1) * cosine - shelf),
                    (amplitude + 1) - (amplitude - 1) * cosine + shelf,
                    2 * ((amplitude - 1) - (amplitude + 1) * cosine),
                    (amplitude + 1) - (amplitude - 1) * cosine - shelf)
            }
            b0 = coefficients[0] / coefficients[3]
            b1 = coefficients[1] / coefficients[3]
            b2 = coefficients[2] / coefficients[3]
            a1 = coefficients[4] / coefficients[3]
            a2 = coefficients[5] / coefficients[3]
        }

        fun process(input: Double, channel: Int): Double {
            val output = b0 * input + z1[channel]
            z1[channel] = b1 * input - a1 * output + z2[channel]
            z2[channel] = b2 * input - a2 * output
            return output
        }
    }
}
