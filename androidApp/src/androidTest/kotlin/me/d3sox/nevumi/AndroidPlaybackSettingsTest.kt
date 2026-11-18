package me.d3sox.nevumi

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.playback.AndroidExoPlaybackEngine
import me.d3sox.nevumi.playback.AndroidPlaybackEngine
import me.d3sox.nevumi.playback.CrossfadePlaybackEngine
import me.d3sox.nevumi.playback.PlayableTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AndroidPlaybackSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** A 48 kHz mono WAV: a second of tone, five of silence and another second of tone. */
    private fun gapFile(): File {
        val file = File(context.cacheDir, "gap-${UUID.randomUUID()}.wav")
        val parts = listOf(440 to 1, 0 to 5, 660 to 1)
        val frames = parts.sumOf { it.second } * 48_000
        val wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        for ((frequency, seconds) in parts) repeat(seconds * 48_000) { wav.putShort((sin(2 * PI * frequency * it / 48_000) * 6_000).toInt().toShort()) }
        file.writeBytes(wav.array())
        return file
    }

    @Test
    fun skippingSilenceShortensTheOutputAndKeepsPositionsInTheFile() = runBlocking {
        val file = gapFile()
        val outputBytes = AtomicLong()
        val engine = CrossfadePlaybackEngine {
            AndroidExoPlaybackEngine(context, outputMonitor = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {}
                override fun handleBuffer(buffer: ByteBuffer) { outputBytes.addAndGet(buffer.remaining().toLong()) }
            }))
        }
        try {
            engine.setSkipSilence(true)
            val started = System.nanoTime()
            engine.loadQueue(listOf(PlayableTrack("gap", file.toURI().toString())))
            val positions = mutableListOf<Long>()
            val ended = withTimeout(10_000) { engine.status.onEach { if (it.playing) positions += it.positionMs }.first { it.ended } }
            val seconds = (System.nanoTime() - started) / 1e9
            assertEquals(7_000.0, ended.durationMs.toDouble(), 50.0)
            assertTrue("Seven seconds with five silent took $seconds seconds", seconds < 5.0)
            // The visualizer and the output monitor follow the shortened audio, which the effects processor ran on.
            val outputSeconds = outputBytes.get() / 2 / 48_000.0
            assertTrue("$outputSeconds seconds of audio left the processors", outputSeconds in 1.8..4.5)
            assertTrue("Positions must jump over the silence: $positions", positions.any { it in 6_100..7_000 })
            assertTrue("Positions went backwards: $positions", positions.zipWithNext().all { (a, b) -> b >= a - 30 })
        } finally { engine.close(); file.delete() }
    }

    @Test
    fun theSessionAppliesTheFadeSkipSilenceAndResumeCommands() = runBlocking {
        val file = gapFile()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
                try {
                    engine.setPlayPauseFade(true)
                    engine.setSkipSilence(false)
                    engine.loadQueue(listOf(PlayableTrack("gap", file.toURI().toString(), entryId = "entry")))
                    withTimeout(10_000) { engine.status.first { it.playing && it.positionMs >= 300 } }
                    engine.resume("entry", 3_000)
                    withTimeout(5_000) { engine.status.first { it.playing && it.positionMs in 3_000..4_500 } }
                    // The pause shows at once while the audio fades out, and playing continues from where it faded.
                    engine.pause()
                    assertFalse(engine.status.value.playWhenReady)
                    withTimeout(5_000) { engine.status.first { !it.playing } }
                    delay(600)
                    val paused = engine.status.value.positionMs
                    delay(400)
                    assertEquals(paused.toDouble(), engine.status.value.positionMs.toDouble(), 60.0)
                    engine.play()
                    withTimeout(5_000) { engine.status.first { it.playing && it.positionMs > paused + 200 } }
                    // Skip silence reaches the session's decoders: the remaining silence passes in well under its length.
                    engine.setSkipSilence(true)
                    val started = System.nanoTime()
                    withTimeout(6_000) { engine.status.first { it.ended } }
                    assertTrue((System.nanoTime() - started) / 1e9 < 3.5)
                } finally { engine.pause(); engine.setPlayPauseFade(false); engine.setSkipSilence(false); engine.close() }
            }
        } finally { file.delete() }
    }
}
