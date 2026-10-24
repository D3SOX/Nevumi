package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class RepeatMode { OFF, ONE, ALL }

@Serializable
data class PlaybackStatus(
    val trackId: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val ended: Boolean = false,
    val error: String? = null,
    val entryId: String? = null,
    val volume: Double = 1.0,
    val speed: Double = 1.0,
    val sourceId: String? = null,
    val playWhenReady: Boolean = playing,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val repeatPlays: Int? = null,
    val playbackId: String? = null,
    val pitch: Double = 1.0,
    val audioEffects: AudioEffects = AudioEffects(),
    val fadingOut: PlaybackStatus? = null,
    val crossfadeDurationMs: Long = 0,
    val audioInfo: AudioInfo? = null,
)

data class PlayableTrack(val id: String, val location: String, val title: String = "", val artist: String = "",
    val entryId: String = id, val sourceId: String? = null)

private val silentAudioLevel = MutableStateFlow(0f).asStateFlow()

interface PlaybackEngine : AutoCloseable {
    val status: StateFlow<PlaybackStatus>
    /** RMS energy of decoded audio, separate from library and transport updates. */
    val audioLevel: StateFlow<Float> get() = silentAudioLevel
    fun setAudioLevelEnabled(enabled: Boolean) {}
    fun load(track: PlayableTrack, positionMs: Long = 0, play: Boolean = true)
    fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int = 0, positionMs: Long = 0, play: Boolean = true)
    fun updateQueue(tracks: List<PlayableTrack>)
    fun play()
    fun pause()
    fun seek(positionMs: Long)
    fun setVolume(volume: Double)
    fun setSpeed(speed: Double)
    fun setPitch(pitch: Double)
    suspend fun setAudioEffects(effects: AudioEffects)
    fun setCrossfadeDuration(milliseconds: Long) { require(milliseconds == 0L) { "This decoder does not support crossfade" } }
    fun setGaplessPlayback(enabled: Boolean) {}
    fun setReplayGain(replayGain: ReplayGain) {}
    fun setRepeat(mode: RepeatMode, playCount: Int? = null)
}
