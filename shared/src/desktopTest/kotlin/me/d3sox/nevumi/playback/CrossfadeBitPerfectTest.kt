package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossfadeBitPerfectTest {
    @Test fun turningOnBitPerfectOutputClosesTheDecoderWaitingForTheNextSong() = runBlocking {
        val decoders = mutableListOf<FakeDecoder>()
        val engine = CrossfadePlaybackEngine { FakeDecoder().also { synchronized(decoders) { decoders += it } } }
        try {
            engine.setCrossfadeDuration(4_000)
            engine.loadQueue(listOf(PlayableTrack("a", "a.flac"), PlayableTrack("b", "b.flac")), play = true)
            // With crossfade on, a second decoder loads the next song while the first one plays.
            withTimeout(5_000) { while (synchronized(decoders) { decoders.size < 2 || decoders[1].loaded.value != "b" }) kotlinx.coroutines.delay(10) }
            val (playing, waiting) = synchronized(decoders) { decoders[0] to decoders[1] }

            engine.setBitPerfectOutput(true)
            withTimeout(5_000) { playing.bitPerfect.first { it } }
            assertTrue(waiting.closed.value, "The waiting decoder must close instead of claiming the bit-perfect mixer")
            assertEquals(false, waiting.bitPerfect.value)
            // The chosen crossfade stays set, yet later ticks must not prepare another decoder for the next song.
            withTimeout(5_000) { engine.status.first { it.crossfadeDurationMs == 0L } }
            playing.seek(1_000)
            withTimeout(5_000) { engine.status.first { it.positionMs == 1_000L } }
            assertEquals(2, synchronized(decoders) { decoders.size })
        } finally { engine.close() }
    }
}

/** Reports what it was asked to load and play without decoding audio. */
private class FakeDecoder : PlaybackEngine {
    val loaded = MutableStateFlow<String?>(null)
    val bitPerfect = MutableStateFlow(false)
    val closed = MutableStateFlow(false)
    private val mutableStatus = MutableStateFlow(PlaybackStatus())
    override val status = mutableStatus.asStateFlow()
    override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)
    override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
        val track = tracks[startIndex]
        loaded.value = track.entryId
        mutableStatus.value = PlaybackStatus(track.id, play, positionMs, 180_000, entryId = track.entryId,
            playWhenReady = play, playbackId = UUID.randomUUID().toString())
    }
    override fun updateQueue(tracks: List<PlayableTrack>) {}
    override fun play() = mutableStatus.update { it.copy(playing = true, playWhenReady = true) }
    override fun pause() = mutableStatus.update { it.copy(playing = false, playWhenReady = false) }
    override fun seek(positionMs: Long) = mutableStatus.update { it.copy(positionMs = positionMs) }
    override fun setVolume(volume: Double) {}
    override fun setSpeed(speed: Double) {}
    override fun setPitch(pitch: Double) {}
    override suspend fun setAudioEffects(effects: AudioEffects) {}
    override fun setCrossfadeDuration(milliseconds: Long) {}
    override fun setBitPerfectOutput(enabled: Boolean) { bitPerfect.value = enabled }
    override fun setRepeat(mode: RepeatMode, playCount: Int?) = mutableStatus.update { it.copy(repeatMode = mode, repeatPlays = playCount) }
    override fun close() { closed.value = true }
}
