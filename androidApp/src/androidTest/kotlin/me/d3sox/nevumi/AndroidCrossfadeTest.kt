package me.d3sox.nevumi

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.playback.AndroidExoPlaybackEngine
import me.d3sox.nevumi.playback.CrossfadePlaybackEngine
import me.d3sox.nevumi.playback.PlayableTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.hypot

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AndroidCrossfadeTest {
    @Test
    fun crossfadePlaysBothRealDecodersAndPausesAndResumesThemTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = listOf(440, 660).mapIndexed { index, frequency ->
            val source = File(context.cacheDir, "crossfade-$index-${UUID.randomUUID()}.wav")
            val frames = 48_000 * 3
            val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(frames * 2)
            repeat(frames) { wav.putShort((sin(2 * PI * frequency * it / 48_000) * 6_000).toInt().toShort()) }
            source.writeBytes(wav.array())
            source
        }
        val captures = mutableListOf<ByteArrayOutputStream>()
        val engine = CrossfadePlaybackEngine {
            val captured = ByteArrayOutputStream().also { captures += it }
            AndroidExoPlaybackEngine(context, outputMonitor = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
                    if (sampleRateHz > 0) {
                        assertEquals(48_000, sampleRateHz)
                        assertEquals(1, channelCount)
                        assertEquals(C.ENCODING_PCM_16BIT, encoding)
                    }
                }
                override fun handleBuffer(buffer: ByteBuffer) {
                    val copy = ByteArray(buffer.remaining())
                    buffer.duplicate().get(copy)
                    synchronized(captured) { captured.write(copy) }
                }
            }))
        }
        try {
            engine.setCrossfadeDuration(1_000)
            engine.loadQueue(sources.mapIndexed { index, file -> PlayableTrack("tone-$index", file.toURI().toString()) })
            withTimeout(8_000) { engine.status.first { it.playing && it.entryId == "tone-0" } }
            val middle = withTimeout(5_000) { engine.status.first {
                it.entryId == "tone-1" && it.playing && it.positionMs >= 250 && it.fadingOut?.playing == true
            } }
            val outgoingPosition = requireNotNull(middle.fadingOut).positionMs
            assertTrue("The real decoders must overlap during the last second of the outgoing song: $middle",
                outgoingPosition in 2_000 until 3_000)
            assertNotEquals(middle.playbackId, middle.fadingOut?.playbackId)
            assertEquals(1.0, middle.volume, 0.001)
            engine.pause()
            withTimeout(2_000) { engine.status.first { !it.playing && it.fadingOut?.playing == false } }
            delay(150)
            val paused = engine.status.value
            delay(250)
            assertEquals(paused.positionMs.toDouble(), engine.status.value.positionMs.toDouble(), 60.0)
            assertEquals(requireNotNull(paused.fadingOut).positionMs.toDouble(), requireNotNull(engine.status.value.fadingOut).positionMs.toDouble(), 60.0)
            engine.play()
            withTimeout(6_000) { engine.status.first { it.entryId == "tone-1" && it.ended } }
            assertEquals(2, captures.size)
            captures.forEachIndexed { index, capture ->
                val samples = ByteBuffer.wrap(synchronized(capture) { capture.toByteArray() }).order(ByteOrder.LITTLE_ENDIAN)
                val pcm = ShortArray(samples.remaining() / 2) { samples.short }
                assertTrue("The real decoder did not render the track", pcm.size >= 140_000)
                assertEquals(6_000.0, amplitude(pcm, if (index == 0) 440 else 660), 20.0)
            }
        } finally {
            engine.close()
            sources.forEach { it.delete() }
        }
    }

    @Test
    fun crossfadeEndsWhereTheOutgoingSongFallsSilentAndStartsTheNextAtItsFirstSound() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The first song falls silent halfway through and the second starts after a second of silence.
        val files = listOf<(Int) -> Boolean>({ it < 1_500 }, { it >= 1_000 }).mapIndexed { index, audible ->
            val file = File(context.cacheDir, "edges-$index-${UUID.randomUUID()}.wav")
            val frames = 48_000 * 3
            val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
            wav.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(frames * 2)
            repeat(frames) { wav.putShort(if (audible(it / 48)) (sin(2 * PI * 440 * it / 48_000) * 6_000).toInt().toShort() else 0) }
            file.writeBytes(wav.array())
            file
        }
        val waveforms = me.d3sox.nevumi.playback.AndroidAudioWaveforms(context)
        fun source(track: PlayableTrack) = me.d3sox.nevumi.library.TrackSource(requireNotNull(track.sourceId),
            me.d3sox.nevumi.library.SourceKind.LOCAL, track.location)
        val tracks = files.mapIndexed { index, file ->
            PlayableTrack("song-$index", file.toURI().toString(), entryId = "entry-$index", sourceId = "local:${file.name}")
        }
        tracks.forEach { assertEquals(128, waveforms.load(source(it), 3_000).size) }
        val engine = CrossfadePlaybackEngine({ track -> waveforms.soundEdges(source(track)) }) { AndroidExoPlaybackEngine(context) }
        try {
            engine.setCrossfadeDuration(1_000)
            engine.loadQueue(tracks)
            val switched = withTimeout(8_000) { engine.status.first { it.entryId == "entry-1" } }
            assertEquals("The incoming song must start at its first sound: $switched", 1_000.0, switched.positionMs.toDouble(), 200.0)
            assertEquals("The fade must end where the outgoing song falls silent: $switched", 500.0,
                requireNotNull(switched.fadingOut).positionMs.toDouble(), 200.0)
            withTimeout(6_000) { engine.status.first { it.entryId == "entry-1" && it.ended } }
            Unit
        } finally {
            engine.close()
            files.forEach { it.delete() }
        }
    }

    private fun amplitude(samples: ShortArray, frequency: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (frame in 24_000 until 72_000) {
            val phase = 2 * PI * frequency * frame / 48_000
            real += samples[frame] * cos(phase)
            imaginary += samples[frame] * sin(phase)
        }
        return 2 * hypot(real, imaginary) / 48_000
    }
}
