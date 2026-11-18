package me.d3sox.nevumi.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.AudioEffects
import me.d3sox.nevumi.playback.PlayableTrack
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.playback.ReplayGain
import me.d3sox.nevumi.playback.ReplayGainMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BitPerfectOutputTest {
    @Test fun bitPerfectOutputPlaysWithNeutralSettingsAndKeepsTheListenersChoicesForLater() = runBlocking {
        val directory = Files.createTempDirectory("bit-perfect-output")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = MusicPreferences(store)
        val effects = AudioEffects(enabled = true, preampDb = -3.0)
        val gain = ReplayGain(ReplayGainMode.TRACK, 2.0)
        preferences.update { it.copy(speed = 1.5, pitch = 0.75, audioEffects = effects, crossfadeDurationMs = 4_000, replayGain = gain,
            fadePlayPause = true, skipSilence = true) }
        val engine = RecordingEngine(supportsBitPerfectOutput = true)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), engine, preferences = preferences)
        try {
            withTimeout(5_000) { engine.applied.first { it.speed == 1.5 && it.replayGain == gain && it.skipSilence } }
            controller.updatePreferences { it.copy(bitPerfectOutput = true) }.join()
            assertEquals(Applied(bitPerfect = true), engine.applied.value)
            controller.setSpeed(2.0).join()
            assertEquals(1.0, engine.applied.value.speed)
            controller.updatePreferences { it.copy(crossfadeDurationMs = 6_000) }.join()
            assertEquals(0, engine.applied.value.crossfadeMs)

            controller.updatePreferences { it.copy(bitPerfectOutput = false) }.join()
            assertEquals(Applied(false, 2.0, 0.75, effects, 6_000, gain, fade = true, skipSilence = true), engine.applied.value)
        } finally {
            controller.closeAndJoin()
            store.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun aRestoredBackupCannotSilenceEffectsWhereBitPerfectOutputIsMissing() = runBlocking {
        val directory = Files.createTempDirectory("bit-perfect-unsupported")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = MusicPreferences(store)
        val gain = ReplayGain(ReplayGainMode.TRACK, 2.0)
        // Settings restored from an Android backup that had bit-perfect output on.
        preferences.update { it.copy(speed = 1.5, crossfadeDurationMs = 4_000, replayGain = gain, bitPerfectOutput = true) }
        val engine = RecordingEngine(supportsBitPerfectOutput = false)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), engine, preferences = preferences)
        try {
            withTimeout(5_000) { engine.applied.first { it.speed == 1.5 && it.replayGain == gain } }
            assertEquals(Applied(false, 1.5, 1.0, AudioEffects(), 4_000, gain), engine.applied.value)
            controller.setSpeed(1.25).join()
            assertEquals(1.25, engine.applied.value.speed)
        } finally {
            controller.closeAndJoin()
            store.close()
            directory.toFile().deleteRecursively()
        }
    }
}

private data class Applied(val bitPerfect: Boolean = false, val speed: Double = 1.0, val pitch: Double = 1.0,
    val effects: AudioEffects = AudioEffects(), val crossfadeMs: Long = 0, val replayGain: ReplayGain = ReplayGain(),
    val fade: Boolean = false, val skipSilence: Boolean = false)

/** Records the playback values the controller applies. */
private class RecordingEngine(override val supportsBitPerfectOutput: Boolean) : PlaybackEngine {
    val applied = MutableStateFlow(Applied())
    private val mutableStatus = MutableStateFlow(PlaybackStatus())
    override val status = mutableStatus.asStateFlow()
    override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)
    override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {}
    override fun updateQueue(tracks: List<PlayableTrack>) {}
    override fun play() {}
    override fun pause() {}
    override fun seek(positionMs: Long) {}
    override fun setVolume(volume: Double) {}
    override fun setSpeed(speed: Double) = applied.update { it.copy(speed = speed) }
    override fun setPitch(pitch: Double) = applied.update { it.copy(pitch = pitch) }
    override suspend fun setAudioEffects(effects: AudioEffects) = applied.update { it.copy(effects = effects) }
    override fun setCrossfadeDuration(milliseconds: Long) = applied.update { it.copy(crossfadeMs = milliseconds) }
    override fun setReplayGain(replayGain: ReplayGain) = applied.update { it.copy(replayGain = replayGain) }
    override fun setBitPerfectOutput(enabled: Boolean) = applied.update { it.copy(bitPerfect = enabled) }
    override fun setPlayPauseFade(enabled: Boolean) = applied.update { it.copy(fade = enabled) }
    override fun setSkipSilence(enabled: Boolean) = applied.update { it.copy(skipSilence = enabled) }
    override fun setRepeat(mode: RepeatMode, playCount: Int?) {}
    override fun close() {}
}
