package me.d3sox.nevumi.playback

import android.content.Context
import androidx.media3.common.Metadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.util.concurrent.atomic.AtomicReference

private val flatEffects = AudioEffects()
private val noReplayGain = ReplayGain()

@UnstableApi
class NevumiAudioRenderersFactory(context: Context, private val outputMonitor: AudioProcessor? = null,
    private val effects: () -> AudioEffects = { flatEffects }, private val energyMonitor: AudioProcessor? = null,
    private val replayGain: () -> ReplayGain = { noReplayGain }) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink {
        // The renderer configures the sink when a track's first decoded audio arrives, after the previous track's audio
        // has passed the effects processor, so the gain switches exactly at the track boundary.
        val tags = AtomicReference<ReplayGainTags?>()
        val effectsProcessor = EffectsAudioProcessor(effects) { replayGain().gainDb(tags.get()) }
        val processing = DefaultAudioSink.DefaultAudioProcessorChain(*listOfNotNull(effectsProcessor, energyMonitor).toTypedArray())
        val visualizer = VisualizerAudioProcessor()
        val chain = object : AudioProcessorChain by processing {
            override fun getAudioProcessors(): Array<AudioProcessor> = processing.audioProcessors + listOfNotNull(outputMonitor, visualizer)
        }
        val sink = DefaultAudioSink.Builder(context).setAudioProcessorChain(chain)
            .setEnableFloatOutput(false).setEnableAudioOutputPlaybackParameters(false).build()
        return object : ForwardingAudioSink(sink) {
            override fun configure(config: AudioSink.AudioSinkConfig) {
                tags.set(config.format.metadata?.replayGainTags())
                super.configure(config)
            }
        }
    }
}

private fun Metadata.replayGainTags(): ReplayGainTags? = replayGainTags((0 until length()).mapNotNull { index ->
    when (val entry = get(index)) {
        is VorbisComment -> entry.key to entry.value
        is TextInformationFrame -> entry.description?.let { it to entry.value }
        is InternalFrame -> entry.description to entry.text
        else -> null
    }
})
