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

    @Test fun aBrokenFloatSampleBecomesSilence() {
        val converted = convert(PcmSampleFormat.FLOAT, PcmSampleFormat.INT24, 2) { it.putFloat(Float.NaN); it.putFloat(0.5f) }
        assertEquals(listOf(0.0, 0.5), listOf(PcmSampleFormat.INT24.read(converted), PcmSampleFormat.INT24.read(converted)))
    }

    @Test fun onlyFormatsAtLeastAsPreciseHoldTheSamples() {
        assertFalse(PcmSampleFormat.INT16.holds(PcmSampleFormat.FLOAT))
        assertTrue(PcmSampleFormat.INT24.holds(PcmSampleFormat.FLOAT))
        assertTrue(PcmSampleFormat.INT32.holds(PcmSampleFormat.FLOAT))
        assertTrue(PcmSampleFormat.FLOAT.holds(PcmSampleFormat.INT24))
        assertFalse(PcmSampleFormat.FLOAT.holds(PcmSampleFormat.INT32))
        assertTrue(PcmSampleFormat.INT16.holds(PcmSampleFormat.INT16))
    }

    @Test fun aLowerVolumeScalesTheSamplesAndAChangeRampsAcrossTheBuffer() {
        val half = convert(PcmSampleFormat.INT16, PcmSampleFormat.INT16, 2) { it.putShort(1_000); it.putShort(-1_000) }
        val input = ByteBuffer.allocate(2 * 2).order(ByteOrder.LITTLE_ENDIAN).putShort(1_000).putShort(-1_000).flip()
        val output = ByteBuffer.allocate(2 * 2)
        convertPcm(input, PcmSampleFormat.INT16, output, PcmSampleFormat.INT16, 0.5)
        output.flip().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(500, -500), listOf(output.short, output.short))
        assertEquals(listOf<Short>(1_000, -1_000), listOf(half.short, half.short))

        val ramp = ByteBuffer.allocate(4 * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4) { putShort(1_000) } }.flip()
        val faded = ByteBuffer.allocate(4 * 2)
        convertPcm(ramp, PcmSampleFormat.INT16, faded, PcmSampleFormat.INT16, 1.0, 0.0)
        faded.flip().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf<Short>(750, 500, 250, 0), List(4) { faded.short })
    }

    @Test fun conversionStopsAtAFullOutputBuffer() {
        val input = ByteBuffer.allocate(4 * 4).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(4) { putFloat(0.5f) } }.flip()
        val output = ByteBuffer.allocate(2 * 3 + 1)
        convertPcm(input, PcmSampleFormat.FLOAT, output, PcmSampleFormat.INT24)
        assertEquals(2 * 4, input.position())
        assertEquals(2 * 3, output.position())
    }
}
