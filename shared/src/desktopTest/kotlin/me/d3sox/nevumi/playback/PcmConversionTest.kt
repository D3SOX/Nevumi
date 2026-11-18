package me.d3sox.nevumi.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class PcmConversionTest {
    private fun convert(from: PcmSampleFormat, to: PcmSampleFormat, samples: Int, fill: (ByteBuffer) -> Unit): ByteBuffer {
        val input = ByteBuffer.allocate(samples * from.bytes).order(ByteOrder.LITTLE_ENDIAN).also(fill).flip()
        val output = ByteBuffer.allocate(samples * to.bytes)
        convertPcm(input, from, output, to)
        assertFalse(input.hasRemaining())
        return output.flip().order(ByteOrder.LITTLE_ENDIAN)
    }

    @Test fun floatsDecodedFrom24BitAudioBecomeTheSameIntegers() {
        val values = listOf(-8_388_608, -1, 0, 1, 1_234_567, 8_388_607)
        val wide = convert(PcmSampleFormat.FLOAT, PcmSampleFormat.INT32, values.size) { buffer -> values.forEach { buffer.putFloat(it / 8_388_608f) } }
        assertEquals(values.map { it shl 8 }, values.map { wide.int })
        val packed = convert(PcmSampleFormat.FLOAT, PcmSampleFormat.INT24, values.size) { buffer -> values.forEach { buffer.putFloat(it / 8_388_608f) } }
        assertEquals(values, values.map { PcmSampleFormat.INT24.read(packed).times(8_388_608).toInt() })
    }

    @Test fun sixteenBitSamplesWidenWithoutChangingTheirValue() {
        val values = listOf<Short>(Short.MIN_VALUE, -2, 0, 3, Short.MAX_VALUE)
        val wide = convert(PcmSampleFormat.INT16, PcmSampleFormat.INT32, values.size) { buffer -> values.forEach { buffer.putShort(it) } }
        assertEquals(values.map { it.toInt() shl 16 }, values.map { wide.int })
        val narrow = convert(PcmSampleFormat.FLOAT, PcmSampleFormat.INT16, values.size) { buffer -> values.forEach { buffer.putFloat(it / 32_768f) } }
        assertEquals(values, values.map { narrow.short })
    }

    @Test fun lossyOvershootClipsInsteadOfWrappingAround() {
        val clipped = convert(PcmSampleFormat.FLOAT, PcmSampleFormat.INT32, 2) { it.putFloat(1.5f); it.putFloat(-1.5f) }
        assertEquals(listOf(Int.MAX_VALUE, Int.MIN_VALUE), listOf(clipped.int, clipped.int))
    }

    @Test fun conversionStopsAtAFullOutputBuffer() {
        val input = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4) { putFloat(0.5f) } }.flip()
        val output = ByteBuffer.allocate(2 * 3 + 1)
        convertPcm(input, PcmSampleFormat.FLOAT, output, PcmSampleFormat.INT24)
        assertEquals(2 * 4, input.position())
        assertEquals(2 * 3, output.position())
    }
}
