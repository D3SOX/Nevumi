package me.d3sox.nevumi.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import java.nio.ByteBuffer

private fun pcmFormat(encoding: Int) = when (encoding) {
    AudioFormat.ENCODING_PCM_16BIT -> PcmSampleFormat.INT16
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmSampleFormat.INT24
    AudioFormat.ENCODING_PCM_32BIT -> PcmSampleFormat.INT32
    AudioFormat.ENCODING_PCM_FLOAT -> PcmSampleFormat.FLOAT
    else -> null
}

/** Finds the bit-perfect mixer for each audio output. */
@UnstableApi
interface BitPerfectMixers {
    /** Routes PCM in [config] to a bit-perfect mixer and returns the encoding it takes, or returns null and lets Android mix it. */
    fun route(config: OutputConfig?): Int?
    /** The media volume for the routed DAC as a linear gain, which Nevumi applies because the bit-perfect mixer doesn't. */
    fun gain(): Float = 1f
    fun release()
}

/**
 * The bit-perfect mixers Android 14 added for USB DACs. Android only routes a track there when its sample rate, encoding and
 * channel mask match the mixer. Without a USB DAC that offers one, songs play through Android's normal mixer.
 */
@UnstableApi
@RequiresApi(34)
class UsbBitPerfectMixers(context: Context) : BitPerfectMixers {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val media = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
    private var preferred: AudioDeviceInfo? = null

    override fun route(config: OutputConfig?): Int? {
        val source = config?.let { pcmFormat(it.encoding) }
        val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        // Only the DAC music plays on counts; if Android names another output, such as the speaker, songs play through its mixer.
        val playingOn = runCatching { audio.getAudioDevicesForAttributes(media) }.getOrDefault(emptyList())
        val device = if (playingOn.isEmpty()) outputs.firstOrNull() else outputs.firstOrNull { output -> playingOn.any { it.id == output.id } }
        val mixer = if (config == null || source == null || device == null) null else
            bitPerfectMixer(audio.getSupportedMixerAttributes(device), config.sampleRate, config.channelMask, source)
        if (preferred != null && (mixer == null || preferred?.id != device?.id)) release()
        val routed = device != null && mixer != null && audio.setPreferredMixerAttributes(media, device, mixer)
        if (device != null && config != null) Log.i("BitPerfect", "${device.productName}: ${config.sampleRate} Hz encoding ${config.encoding} " +
            if (routed) "goes to the bit-perfect mixer with encoding ${mixer?.format?.encoding}" else "plays through Android's mixer")
        if (!routed) return null
        preferred = device
        return requireNotNull(mixer).format.encoding
    }

    private var gain = 1f
    private var gainReadAt: Long? = null

    /**
     * Android passes the media volume of a bit-perfect track to the audio driver, and some drivers, such as the Pixel 8 Pro's
     * for USB, ignore it, so the DAC would play at full volume. Nevumi lowers the samples instead; at full volume they stay
     * unchanged. A driver that applies the volume as well makes songs quieter than set, which is safer than too loud.
     */
    override fun gain(): Float {
        val device = preferred ?: return 1f
        val now = android.os.SystemClock.elapsedRealtime()
        gainReadAt?.let { if (now - it < 100) return gain }
        gainReadAt = now
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val db = if (index == 0 || audio.isStreamMute(AudioManager.STREAM_MUSIC)) Float.NEGATIVE_INFINITY
            else audio.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, device.type)
        gain = if (db.isFinite()) Math.pow(10.0, db / 20.0).toFloat().coerceIn(0f, 1f) else 0f
        return gain
    }

    override fun release() {
        preferred?.let { runCatching { audio.clearPreferredMixerAttributes(media, it) } }
        preferred = null
    }
}

private val widths = listOf(PcmSampleFormat.INT16, PcmSampleFormat.FLOAT, PcmSampleFormat.INT24, PcmSampleFormat.INT32)

/**
 * Keeps the song's encoding when the DAC takes it, and otherwise the widest integers. A mixer too narrow for the samples, such as
 * 16 bits for the floats a 24-bit song decodes to, would round them, so the song plays through Android's mixer instead.
 */
@RequiresApi(34)
internal fun bitPerfectMixer(mixers: List<AudioMixerAttributes>, sampleRate: Int, channelMask: Int, source: PcmSampleFormat) = mixers
    .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT && it.format.sampleRate == sampleRate &&
        it.format.channelMask == channelMask && pcmFormat(it.format.encoding)?.holds(source) == true }
    .maxByOrNull { mixer -> pcmFormat(mixer.format.encoding).let { if (it == source) widths.size else widths.indexOf(it) } }

/** Writes PCM through [mixers]' bit-perfect mixers, converting samples to the mixer's encoding without changing their values. */
@UnstableApi
class BitPerfectAudioOutputProvider(private val delegate: AudioOutputProvider, private val mixers: BitPerfectMixers) :
    AudioOutputProvider by delegate {
    override fun getAudioOutput(config: OutputConfig): AudioOutput {
        val source = pcmFormat(config.encoding)?.takeUnless { config.isOffload || config.isTunneling }
        val encoding = mixers.route(config.takeIf { source != null })
        val target = encoding?.let(::pcmFormat)
        if (source == null || encoding == null || target == null) return delegate.getAudioOutput(config)
        val output = try {
            delegate.getAudioOutput(config.buildUpon().setEncoding(encoding).setBufferSize(config.bufferSize / source.bytes * target.bytes).build())
        } catch (_: AudioOutputProvider.InitializationException) {
            // Android took the mixer but can't open a track for it, so the song plays through its normal mixer.
            mixers.release()
            return delegate.getAudioOutput(config)
        }
        return ConvertingAudioOutput(output, source, target, mixers::gain)
    }

    override fun release() {
        mixers.release()
        delegate.release()
    }
}

/**
 * Writes the sink's samples to [output] in its encoding at the media and player volume, keeping the sink's byte positions in the
 * source encoding. At full volume in the source encoding, the sink's buffers pass through untouched.
 */
@UnstableApi
private class ConvertingAudioOutput(private val output: AudioOutput, private val from: PcmSampleFormat,
    private val to: PcmSampleFormat, private val mediaGain: () -> Float) : AudioOutput by output {
    private var converted = ByteBuffer.allocateDirect(0)
    private var source: ByteBuffer? = null
    private var sourcePosition = -1
    private var playerVolume = 1f
    /** The gain the last buffer ended at, so a volume change ramps across the next buffer instead of clicking. */
    private var applied = 1.0

    init { output.setVolume(1f) }

    // The bit-perfect mixer doesn't apply a track's volume either, so it joins the media volume here.
    override fun setVolume(volume: Float) { playerVolume = volume }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        // A full track gets the same buffer again until it takes the rest, which then needs no second conversion.
        if (buffer !== source || buffer.position() != sourcePosition) {
            val gain = (mediaGain() * playerVolume).toDouble()
            if (from == to && gain == 1.0 && applied == 1.0) {
                source = null
                return output.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            }
            val bytes = buffer.remaining() / from.bytes * to.bytes
            if (converted.capacity() < bytes) converted = ByteBuffer.allocateDirect(bytes)
            converted.clear().limit(bytes)
            convertPcm(buffer.duplicate(), from, converted, to, applied, gain)
            applied = gain
            converted.flip()
            source = buffer
        }
        val written = output.write(converted, encodedAccessUnitCount, presentationTimeUs)
        buffer.position(buffer.limit() - converted.remaining() / to.bytes * from.bytes)
        sourcePosition = buffer.position()
        if (written) source = null
        return written
    }

    override fun flush() {
        source = null
        output.flush()
    }
}
