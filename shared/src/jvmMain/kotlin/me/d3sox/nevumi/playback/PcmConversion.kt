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
        val value = (sample * scale).roundToLong().coerceIn(-scale.toLong(), scale.toLong() - 1)
        when (this) {
            INT16 -> buffer.putShort(value.toInt().toShort())
            INT24 -> { buffer.put(value.toByte()); buffer.put((value shr 8).toByte()); buffer.put((value shr 16).toByte()) }
            else -> buffer.putInt(value.toInt())
        }
    }
}

/**
 * Converts the samples of [input] from [from] to [to] while [output] has room. A sample keeps its value: integers widen by
 * padding with zeros, and the floats a decoder produces from 16- or 24-bit audio become the same integers again.
 */
internal fun convertPcm(input: ByteBuffer, from: PcmSampleFormat, output: ByteBuffer, to: PcmSampleFormat) {
    input.order(ByteOrder.LITTLE_ENDIAN)
    output.order(ByteOrder.LITTLE_ENDIAN)
    while (input.remaining() >= from.bytes && output.remaining() >= to.bytes) to.write(output, from.read(input))
}
