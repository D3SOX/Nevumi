package me.d3sox.nevumi

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.playback.NevumiAudioRenderersFactory
import me.d3sox.nevumi.playback.ReplayGain
import me.d3sox.nevumi.playback.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

@UnstableApi
@RunWith(AndroidJUnit4::class)
class AndroidReplayGainOutputTest {
    @Test
    fun eachSongInAGaplessQueuePlaysAtItsOwnTaggedGain() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Vorbis comments say -6 dB track and -9 dB album; the MP3's ID3 TXXX frame says -12 dB track only.
        val files = listOf("replaygain.flac", "replaygain.mp3").map { name ->
            File(context.cacheDir, name).also { file ->
                instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
        try {
            suspend fun render(replayGain: ReplayGain): ShortArray {
                val captured = ByteArrayOutputStream()
                val ended = CompletableDeferred<Unit>()
                val capture = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
                    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {}
                    override fun handleBuffer(buffer: ByteBuffer) {
                        val copy = ByteArray(buffer.remaining())
                        buffer.duplicate().get(copy)
                        captured.write(copy)
                    }
                })
                lateinit var player: ExoPlayer
                instrumentation.runOnMainSync {
                    player = ExoPlayer.Builder(context, NevumiAudioRenderersFactory(context, capture, replayGain = { replayGain })).build()
                    player.volume = 0f
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) ended.complete(Unit)
                        }
                        override fun onPlayerError(error: PlaybackException) { ended.completeExceptionally(error) }
                    })
                    player.setMediaItems(files.map { MediaItem.fromUri(it.toURI().toString()) })
                    player.prepare()
                    player.play()
                }
                try { withTimeout(15_000) { ended.await() } }
                finally { instrumentation.runOnMainSync { player.release() } }
                val samples = ByteBuffer.wrap(captured.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                return ShortArray(samples.remaining() / 2) { samples.short }
            }
            val off = render(ReplayGain())
            val track = render(ReplayGain(ReplayGainMode.TRACK))
            val album = render(ReplayGain(ReplayGainMode.ALBUM, preampDb = 2.0))
            assertEquals(10.0.pow(-6.0 / 20), amplitude(track, 0) / amplitude(off, 0), 0.02)
            assertEquals(10.0.pow(-12.0 / 20), amplitude(track, 1) / amplitude(off, 1), 0.02)
            assertEquals(10.0.pow(-7.0 / 20), amplitude(album, 0) / amplitude(off, 0), 0.02)
            assertEquals(10.0.pow(-10.0 / 20), amplitude(album, 1) / amplitude(off, 1), 0.02)
        } finally { files.forEach(File::delete) }
    }

    /** The 440 Hz level in the middle second of the [song]th two-second mono song. */
    private fun amplitude(samples: ShortArray, song: Int): Double {
        assertTrue("Not enough rendered audio", samples.size >= 192_000)
        var real = 0.0
        var imaginary = 0.0
        for (frame in 24_000 until 72_000) {
            val sample = samples[song * 96_000 + frame].toDouble()
            val phase = 2 * PI * 440 * frame / 48_000
            real += sample * cos(phase)
            imaginary += sample * sin(phase)
        }
        return 2 * hypot(real, imaginary) / 48_000
    }
}
