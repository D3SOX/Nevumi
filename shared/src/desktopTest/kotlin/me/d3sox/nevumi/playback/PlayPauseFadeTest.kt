package me.d3sox.nevumi.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class PlayPauseFadeTest {
    /** Plays and pauses at once and records every volume, so the fade's steps can be checked. */
    private class RecordingEngine : PlaybackEngine {
        override val status = MutableStateFlow(PlaybackStatus())
        val volumes = CopyOnWriteArrayList<Double>()
        val transport = CopyOnWriteArrayList<String>()
        /** No volume yet, so waiting for one only ends once the engine set it. */
        @Volatile var level = -1.0
        override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)
        override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
            val track = tracks[startIndex]
            transport += "load:${track.entryId}:$play"
            status.update { it.copy(trackId = track.id, entryId = track.entryId, durationMs = 600_000, positionMs = positionMs,
                playing = play, playWhenReady = play, ended = false) }
        }
        override fun updateQueue(tracks: List<PlayableTrack>) {}
        override fun play() { transport += "play"; status.update { it.copy(playing = true, playWhenReady = true) } }
        override fun pause() { transport += "pause"; status.update { it.copy(playing = false, playWhenReady = false) } }
        override fun seek(positionMs: Long) { transport += "seek:$positionMs"; status.update { it.copy(positionMs = positionMs) } }
        override fun setVolume(volume: Double) { level = volume; volumes += volume }
        override fun setSpeed(speed: Double) {}
        override fun setPitch(pitch: Double) {}
        override suspend fun setAudioEffects(effects: AudioEffects) {}
        override fun setRepeat(mode: RepeatMode, playCount: Int?) { status.update { it.copy(repeatMode = mode, repeatPlays = playCount) } }
        @Volatile var rejectSkipSilence = false
        val skipSilence = CopyOnWriteArrayList<Boolean>()
        override fun setSkipSilence(enabled: Boolean) {
            if (enabled && rejectSkipSilence) error("The decoder rejected the filter chain")
            skipSilence += enabled
        }
        override fun close() {}
    }

    private val track = PlayableTrack("song", Path.of("song.flac").toString(), entryId = "entry")

    private suspend fun RecordingEngine.awaitVolume(expected: Double) =
        withTimeout(2_000) { while (kotlin.math.abs(level - expected) > 1e-9) delay(5) }

    @Test fun pausingFadesOutBeforeItPausesAndPlayingFadesBackIn() = runBlocking {
        val decoder = RecordingEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            engine.setPlayPauseFade(true)
            engine.setVolume(0.8)
            engine.loadQueue(listOf(track))
            decoder.awaitVolume(0.8)
            val fadeIn = decoder.volumes.dropWhile { it != 0.0 }
            assertTrue(fadeIn.zipWithNext().all { (a, b) -> b >= a } && fadeIn.count { it > 0.0 && it < 0.8 } >= 3,
                "Playing did not fade in from silence: $fadeIn")

            decoder.volumes.clear()
            val started = System.nanoTime()
            engine.pause()
            // The app shows the pause at once while the audio fades out.
            assertFalse(engine.status.value.playWhenReady)
            // The decoder pauses just before the engine silences it for the next fade-in.
            withTimeout(2_000) { while ("pause" !in decoder.transport || decoder.level != 0.0) delay(5) }
            val fadeMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(fadeMs in 150..1_000, "The fade-out took $fadeMs ms")
            assertEquals(0.0, decoder.level)
            val fadeOut = decoder.volumes.toList()
            assertTrue(fadeOut.zipWithNext().all { (a, b) -> b <= a } && fadeOut.count { it > 0.0 && it < 0.8 } >= 3,
                "Pausing did not fade out: $fadeOut")

            engine.play()
            decoder.awaitVolume(0.8)
            assertEquals(0.8, engine.status.value.volume)
        } finally { engine.close() }
    }

    @Test fun playingDuringTheFadeOutTurnsItAroundWithoutPausing() = runBlocking {
        val decoder = RecordingEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            engine.setPlayPauseFade(true)
            engine.loadQueue(listOf(track))
            decoder.awaitVolume(1.0)
            engine.pause()
            withTimeout(2_000) { while (decoder.level >= 0.9) delay(5) }
            engine.play()
            decoder.awaitVolume(1.0)
            delay(300)
            assertFalse("pause" in decoder.transport, "The decoder paused although playback continued: ${decoder.transport}")
        } finally { engine.close() }
    }

    @Test fun seekingOrSkippingDuringAFadeNeverLeavesTheVolumeLow() = runBlocking {
        val decoder = RecordingEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            engine.setPlayPauseFade(true)
            engine.loadQueue(listOf(track))
            decoder.awaitVolume(1.0)
            // A seek during the fade-out finishes the pause first, so the new position is not heard faintly.
            engine.pause()
            withTimeout(2_000) { while (decoder.level >= 0.9) delay(5) }
            engine.seek(30_000)
            withTimeout(2_000) { while ("seek:30000" !in decoder.transport) delay(5) }
            assertEquals(listOf("pause", "seek:30000"), decoder.transport.takeLast(2))
            engine.play()
            decoder.awaitVolume(1.0)
            // A seek and a skip during the fade-in keep fading in.
            engine.pause()
            withTimeout(2_000) { while ("pause" !in decoder.transport.takeLast(1)) delay(5) }
            engine.play()
            withTimeout(2_000) { while (decoder.level <= 0.0) delay(5) }
            engine.seek(60_000)
            engine.loadQueue(listOf(track.copy(entryId = "next")))
            decoder.awaitVolume(1.0)
            // Ducking for a notification lowers what the fade reaches, and ending it restores the full volume.
            engine.pause()
            withTimeout(2_000) { while ("pause" !in decoder.transport.takeLast(1)) delay(5) }
            engine.play()
            engine.setVolume(0.2)
            decoder.awaitVolume(0.2)
            engine.setVolume(1.0)
            decoder.awaitVolume(1.0)
        } finally { engine.close() }
    }

    @Test fun anImmediatePauseAndTurningTheFadeOffSkipTheFade() = runBlocking {
        val decoder = RecordingEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            engine.setPlayPauseFade(true)
            engine.loadQueue(listOf(track))
            decoder.awaitVolume(1.0)
            decoder.volumes.clear()
            engine.pause(fade = false)
            withTimeout(500) { while ("pause" !in decoder.transport || decoder.level != 0.0) delay(5) }
            assertTrue(decoder.volumes.none { it > 0.0 && it < 1.0 }, "An immediate pause faded: ${decoder.volumes}")
            // Paused with the fade on, the decoder waits silent; turning the fade off restores the volume.
            assertEquals(0.0, decoder.level)
            engine.setPlayPauseFade(false)
            decoder.awaitVolume(1.0)
            decoder.volumes.clear()
            engine.play()
            engine.pause()
            withTimeout(500) { while (decoder.transport.last() != "pause") delay(5) }
            delay(100)
            assertTrue(decoder.volumes.all { it == 1.0 }, "Playback faded with the fade off: ${decoder.volumes}")
        } finally { engine.close() }
    }

    @Test fun aLoadedPausedSongWaitsSilentAndFadesInWhenItPlays() = runBlocking {
        val decoder = RecordingEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            engine.setPlayPauseFade(true)
            engine.loadQueue(listOf(track), play = false)
            withTimeout(2_000) { engine.status.first { it.entryId == "entry" } }
            decoder.awaitVolume(0.0)
            decoder.volumes.clear()
            engine.play()
            decoder.awaitVolume(1.0)
            assertTrue(decoder.volumes.count { it > 0.0 && it < 1.0 } >= 3, "Playing did not fade in: ${decoder.volumes}")
        } finally { engine.close() }
    }

    @Test fun aSongStartedAfterTheQueueEndedFadesIn() = runBlocking {
        val decoder = RecordingEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            engine.setPlayPauseFade(true)
            engine.loadQueue(listOf(track))
            decoder.awaitVolume(1.0)
            // The last song ends by itself, so nothing paused it and the fade still stands at full volume.
            decoder.status.update { it.copy(playing = false, ended = true) }
            delay(100)
            decoder.volumes.clear()
            engine.loadQueue(listOf(track.copy(entryId = "again")))
            withTimeout(2_000) { while (0.0 !in decoder.volumes) delay(5) }
            decoder.awaitVolume(1.0)
            assertEquals(0.0, decoder.volumes.first(), "The song did not start silent: ${decoder.volumes}")
            assertTrue(decoder.volumes.count { it > 0.0 && it < 1.0 } >= 3, "Playing did not fade in: ${decoder.volumes}")
        } finally { engine.close() }
    }

    @Test fun aGaplessQueueThatMovesOnDuringTheFadeOutPausesAtOnce() = runBlocking {
        val decoder = RecordingEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            engine.setPlayPauseFade(true)
            engine.loadQueue(listOf(track))
            decoder.awaitVolume(1.0)
            engine.pause()
            withTimeout(2_000) { while (decoder.level >= 0.9) delay(5) }
            // The song ends during the fade and the decoder's own queue starts the next one.
            decoder.status.update { it.copy(entryId = "next", positionMs = 0) }
            withTimeout(100) { while ("pause" !in decoder.transport) delay(5) }
        } finally { engine.close() }
    }

    @Test fun skipSilenceThatTheDecoderRejectsIsNotPassedToTheNextSongsDecoder() = runBlocking {
        val decoders = CopyOnWriteArrayList<RecordingEngine>()
        val engine = CrossfadePlaybackEngine { RecordingEngine().also { it.rejectSkipSilence = true; decoders += it } }
        try {
            engine.setCrossfadeDuration(1_000)
            engine.setSkipSilence(true)
            engine.loadQueue((1..2).map { track.copy(entryId = "entry-$it") })
            // The worker prepares the next song's decoder once the current one reports its length.
            withTimeout(2_000) { while (decoders.size < 2 || decoders[1].skipSilence.isEmpty()) delay(5) }
            assertEquals(listOf(false), decoders[1].skipSilence.toList())
            assertTrue(decoders.none { true in it.skipSilence })
        } finally { engine.close() }
    }
}
