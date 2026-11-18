package me.d3sox.nevumi.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/** Little-endian linear PCM sample layouts. */
internal enum class PcmSampleFormat(val bytes: Int, private val bits: Int) {
    INT16(2, 16), INT24(3, 24), INT32(4, 32), FLOAT(4, 0);

    /**
     * Whether [source]'s samples fit this format unchanged. Floats count as 24 bits, which covers every float a decoder makes
     * from 24-bit or narrower integers. Floats stored natively can hold finer values that integer formats round.
     */
    internal fun holds(source: PcmSampleFormat) = precision >= source.precision
    private val precision get() = if (this == FLOAT) 24 else bits

    internal fun read(buffer: ByteBuffer): Double = when (this) {
        INT16 -> buffer.short / 32_768.0
        INT24 -> ((buffer.get().toInt() and 0xff) or ((buffer.get().toInt() and 0xff) shl 8) or (buffer.get().toInt() shl 16)) / 8_388_608.0
        INT32 -> buffer.int / 2_147_483_648.0
        FLOAT -> buffer.float.toDouble()
    }

    internal fun write(buffer: ByteBuffer, sample: Double) {
        if (this == FLOAT) { buffer.putFloat(sample.toFloat()); return }
        val scale = (1L shl (bits - 1)).toDouble()
        // A broken float sample, which no DAC can play, becomes silence.
        val value = if (sample.isNaN()) 0L else (sample * scale).roundToLong().coerceIn(-scale.toLong(), scale.toLong() - 1)
        when (this) {
            INT16 -> buffer.putShort(value.toInt().toShort())
            INT24 -> { buffer.put(value.toByte()); buffer.put((value shr 8).toByte()); buffer.put((value shr 16).toByte()) }
            else -> buffer.putInt(value.toInt())
        }
    }
}

/**
 * Converts the samples of [input] from [from] to [to] while [output] has room. At a gain of 1, a sample keeps its value:
 * integers widen by padding with zeros, and the floats a decoder produces from 16- or 24-bit audio become the same integers
 * again. Other gains scale the samples, ramping from [startGain] to [endGain] across frames of [channels] samples.
 */
internal fun convertPcm(input: ByteBuffer, from: PcmSampleFormat, output: ByteBuffer, to: PcmSampleFormat,
    startGain: Double = 1.0, endGain: Double = startGain, channels: Int = 1) {
    input.order(ByteOrder.LITTLE_ENDIAN)
    output.order(ByteOrder.LITTLE_ENDIAN)
    val samples = minOf(input.remaining() / from.bytes, output.remaining() / to.bytes)
    if (startGain == 1.0 && endGain == 1.0) { repeat(samples) { to.write(output, from.read(input)) }; return }
    // A volume change ramps from the start gain to the end gain across the frames, keeping the channels of a frame together.
    val frames = (samples / channels).coerceAtLeast(1)
    repeat(samples) { i -> to.write(output, from.read(input) * (startGain + (endGain - startGain) * (i / channels + 1).coerceAtMost(frames) / frames)) }
}
