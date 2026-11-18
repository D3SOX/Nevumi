package me.d3sox.nevumi

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.playback.AudioEffects
import me.d3sox.nevumi.playback.BitPerfectAudioOutputProvider
import me.d3sox.nevumi.playback.BitPerfectMixers
import me.d3sox.nevumi.playback.EqualizerBand
import me.d3sox.nevumi.playback.NevumiAudioRenderersFactory
import me.d3sox.nevumi.playback.ReplayGain
import me.d3sox.nevumi.playback.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Plays a 24-bit FLAC through the bit-perfect pipeline into a stand-in for a USB DAC's mixer, which takes only part of each
 * buffer per write like a full AudioTrack. The DAC must receive every decoded sample unchanged, even with effects set, after
 * the fade-in Media3 applies to the start of every output.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AndroidBitPerfectOutputTest {
    @Test
    fun aUsbDacReceives24BitSamplesUnchangedAfterTheFadeIn() = runBlocking {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 34)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(context.cacheDir, "bit-perfect-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("bit-perfect-24.flac").use { input -> source.outputStream().use(input::copyTo) }
        // The fixture's samples come from this generator, encoded losslessly as 24-bit stereo FLAC at 48 kHz.
        var state = 1L
        val expected = IntArray(12_000 * 2) {
            state = (state * 1_103_515_245 + 12_345) % (1L shl 31)
            (((state shr 7) and 0xFFFFFF).toInt() shl 8) shr 8
        }
        try {
            for (encoding in listOf(AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_24BIT_PACKED)) {
                val dac = FakeDac(context, encoding)
                val ended = CompletableDeferred<Unit>()
                lateinit var player: ExoPlayer
                instrumentation.runOnMainSync {
                    val renderers = NevumiAudioRenderersFactory(context, effects = { AudioEffects(enabled = true, preampDb = -6.0,
                        bands = listOf(EqualizerBand(1_000.0, 9.0, 1.0))) }, replayGain = { ReplayGain(ReplayGainMode.TRACK, 6.0) },
                        bitPerfect = true, bitPerfectOutput = { dac })
                    player = ExoPlayer.Builder(context, renderers).build()
                    player.playbackParameters = PlaybackParameters.DEFAULT
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) { if (playbackState == Player.STATE_ENDED) ended.complete(Unit) }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { ended.completeExceptionally(error) }
                    })
                    player.setMediaItem(MediaItem.fromUri(source.toURI().toString()))
                    player.prepare()
                    player.play()
                }
                try { withTimeout(15_000) { ended.await() } }
                finally { instrumentation.runOnMainSync { player.release() } }

                val routed = dac.routed.single()
                assertEquals(48_000, routed.sampleRate)
                assertEquals(AudioFormat.CHANNEL_OUT_STEREO, routed.channelMask)
                assertEquals(AudioFormat.ENCODING_PCM_FLOAT, routed.encoding)
                assertEquals(encoding, dac.opened.single().encoding)
                val bytes = ByteBuffer.wrap(dac.received.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
                val received = if (encoding == AudioFormat.ENCODING_PCM_32BIT) IntArray(bytes.remaining() / 4) { bytes.int shr 8 }
                    else IntArray(bytes.remaining() / 3) { (bytes.get().toInt() and 0xff) or ((bytes.get().toInt() and 0xff) shl 8) or (bytes.get().toInt() shl 16) }
                assertEquals(expected.size, received.size)
                // Media3 fades in the first 20 ms after it opens an output, so those samples may only get quieter.
                val ramp = 960 * 2
                assertTrue((0 until ramp).all { kotlin.math.abs(received[it]) <= kotlin.math.abs(expected[it]) })
                val first = (ramp until expected.size).firstOrNull { expected[it] != received[it] }
                assertEquals("Sample $first differs for encoding $encoding", null, first)
                assertEquals(1, dac.releases)
            }
        } finally { source.delete() }
    }
}

/** A USB DAC whose bit-perfect mixer takes [encoding]. Its outputs record what they receive, 3,000 bytes per write at most. */
@UnstableApi
private class FakeDac(context: android.content.Context, private val encoding: Int,
    private val real: AudioOutputProvider = AudioTrackAudioOutputProvider.Builder(context).build()) : AudioOutputProvider by real {
    val routed = mutableListOf<OutputConfig>()
    val opened = mutableListOf<OutputConfig>()
    val received = ByteArrayOutputStream()
    var releases = 0
    private val provider = BitPerfectAudioOutputProvider(object : AudioOutputProvider by real {
        override fun getAudioOutput(config: OutputConfig): AudioOutput = RecordingOutput(config).also { opened += config }
    }, object : BitPerfectMixers {
        override fun route(config: OutputConfig?) = config?.let { routed += it; encoding }
        override fun release() { releases++ }
    })
    override fun getAudioOutput(config: OutputConfig) = provider.getAudioOutput(config)
    override fun release() = provider.release()

    private inner class RecordingOutput(private val config: OutputConfig) : AudioOutput {
        private val frameBytes = if (config.encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) 6 else 8
        private var frames = 0L
        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            val bytes = minOf(buffer.remaining(), 3_000 / frameBytes * frameBytes)
            val chunk = ByteArray(bytes).also { buffer.get(it) }
            received.write(chunk)
            frames += bytes / frameBytes
            return !buffer.hasRemaining()
        }
        override fun getPositionUs() = frames * 1_000_000 / config.sampleRate
        override fun getSampleRate() = config.sampleRate
        override fun getBufferSizeInFrames() = (config.bufferSize / frameBytes).toLong()
        override fun getAudioSessionId() = config.audioSessionId
        override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
        override fun isOffloadedPlayback() = false
        override fun isStalled() = false
        override fun play() {}
        override fun pause() {}
        override fun flush() { frames = 0 }
        override fun stop() {}
        override fun release() {}
        override fun setVolume(volume: Float) {}
        override fun addListener(listener: AudioOutput.Listener) {}
        override fun removeListener(listener: AudioOutput.Listener) {}
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) {}
        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {}
        override fun setOffloadEndOfStream() {}
        override fun attachAuxEffect(effectId: Int) {}
        override fun setAuxEffectSendLevel(level: Float) {}
        override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {}
    }
}
