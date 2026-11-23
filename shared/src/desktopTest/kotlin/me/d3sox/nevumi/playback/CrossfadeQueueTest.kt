package me.d3sox.nevumi.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class CrossfadeQueueTest {
    private val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
        "Fixture Album", "01 Northern Light.flac").toString()

    @Test fun disablingGaplessKeepsTheCurrentPositionAndAdvancesRepeatedQueueEntries() = runBlocking {
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null") }
        try {
            val tracks = (1..3).map { PlayableTrack("same-song", audio, entryId = "entry-$it") }
            engine.loadQueue(tracks, positionMs = 2_400, play = false)
            val initial = withTimeout(3_000) { engine.status.first { it.positionMs >= 2_350 && it.entryId == "entry-1" && it.durationMs > 0 } }
            engine.setGaplessPlayback(false)
            kotlinx.coroutines.delay(200)
            assertEquals(initial.playbackId, engine.status.value.playbackId)
            assertEquals(initial.positionMs, engine.status.value.positionMs)
            engine.play()
            withTimeout(4_000) { engine.status.first { it.entryId == "entry-2" && it.playing } }
            engine.setGaplessPlayback(true)
            withTimeout(5_000) { engine.status.first { it.entryId == "entry-3" && it.playing } }
            Unit
        } finally { engine.close() }
    }

    @Test fun disablingGaplessAfterNativeAdvancementStillPlaysTheNextQueueEntry() = runBlocking {
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null") }
        try {
            val tracks = (1..3).map { PlayableTrack("same-song", audio, entryId = "entry-$it") }
            engine.loadQueue(tracks, positionMs = 2_400)
            withTimeout(4_000) { engine.status.first { it.entryId == "entry-2" && it.playing } }
            engine.pause()
            withTimeout(3_000) { engine.status.first { it.entryId == "entry-2" && !it.playing } }
            engine.seek(2_400)
            val paused = withTimeout(3_000) { engine.status.first { it.positionMs in 2_350..2_450 && !it.playing } }
            engine.setGaplessPlayback(false)
            engine.setAudioEffects(AudioEffects())
            assertEquals(paused.playbackId, engine.status.value.playbackId)
            assertEquals("entry-2", engine.status.value.entryId)
            assertEquals(paused.positionMs, engine.status.value.positionMs)
            engine.play()
            withTimeout(3_000) { engine.status.first { it.entryId == "entry-3" && it.playing } }
            Unit
        } finally { engine.close() }
    }

    @Test
    fun aLongCrossfadeWithAShortIncomingSongDoesNotLeaveAnEndedSongOverAnAudibleOutgoingTrack() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-short-crossfade")
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null") }
        try {
            val tracks = listOf(3, 1, 3).mapIndexed { index, seconds ->
                val frames = 8_000 * seconds
                val wave = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
                wave.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(frames * 2)
                val file = directory.resolve("short-$index.wav")
                Files.write(file, wave.array())
                PlayableTrack("song-$index", file.toString())
            }
            engine.setCrossfadeDuration(12_000)
            engine.loadQueue(tracks)
            withTimeout(8_000) { engine.status.first { status ->
                assertFalse(status.ended && status.fadingOut?.playing == true,
                    "The incoming song ended while the outgoing song kept playing")
                status.entryId == tracks.last().entryId && status.ended
            } }
            Unit
        } finally { engine.close(); directory.toFile().deleteRecursively() }
    }

    /** Two silent three-second songs; the tests decide through injected edges where their sound is. */
    private fun threeSecondSongs(directory: java.nio.file.Path) = (0..1).map { index ->
        val frames = 8_000 * 3
        val wave = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        val file = directory.resolve("song-$index.wav")
        Files.write(file, wave.array())
        PlayableTrack("song-$index", file.toString())
    }

    @Test
    fun anIncomingSongWithALongLeadingSilenceGetsAFadeThatFitsItsSound() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-silent-intro")
        val tracks = threeSecondSongs(directory)
        // Only the last half second of the incoming song has sound, far less than the crossfade.
        val engine = CrossfadePlaybackEngine({ track -> if (track == tracks[1]) SoundEdges(2_500, 3_000) else null }) {
            MpvPlaybackEngine("null")
        }
        try {
            engine.setCrossfadeDuration(2_000)
            engine.loadQueue(tracks)
            val switched = withTimeout(5_000) { engine.status.first { it.entryId == tracks[1].entryId } }
            assertTrue(switched.positionMs >= 2_400, "The incoming song must start at its sound: $switched")
            assertTrue(requireNotNull(switched.fadingOut).positionMs >= 2_600,
                "The fade must fit the incoming song's half second of sound: ${switched.fadingOut}")
            withTimeout(5_000) { engine.status.first { status ->
                assertFalse(status.ended && status.fadingOut?.playing == true,
                    "The incoming song ended while the outgoing song kept playing")
                status.entryId == tracks[1].entryId && status.ended
            } }
            Unit
        } finally { engine.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anOutgoingSongWithLittleSoundGetsAFadeThatFitsItsSound() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-short-sound")
        val tracks = threeSecondSongs(directory)
        // Only half a second of the outgoing song has sound, far less than the crossfade or half its file.
        val engine = CrossfadePlaybackEngine({ track -> if (track == tracks[0]) SoundEdges(1_500, 2_000) else null }) {
            MpvPlaybackEngine("null")
        }
        try {
            engine.setCrossfadeDuration(2_000)
            engine.loadQueue(tracks)
            val switched = withTimeout(5_000) { engine.status.first { it.entryId == tracks[1].entryId } }
            assertTrue(requireNotNull(switched.fadingOut).positionMs >= 1_650,
                "The fade must fit the outgoing song's half second of sound: ${switched.fadingOut}")
        } finally { engine.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun singleSongQueueRepeatAndFiniteSongRepeatContinueToWorkWithCrossfadeEnabled() = runBlocking {
        val nativePlayers = mutableListOf<MpvPlaybackEngine>()
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null").also { nativePlayers += it } }
        try {
            val song = PlayableTrack("song", audio, entryId = "first")
            val next = song.copy(entryId = "second")
            engine.setCrossfadeDuration(1_000)
            engine.setRepeat(RepeatMode.ALL)
            engine.load(song, 2_600)
            val first = try { withTimeout(3_000) { engine.status.first { it.playing && it.positionMs >= 2_550 } } }
            catch (failure: Throwable) { throw AssertionError("Initial repeat playback failed: ${engine.status.value}; native: ${nativePlayers.map { it.status.value }}", failure) }
            val repeated = withTimeout(3_000) { engine.status.first { it.playing && it.positionMs < 250 && it.playbackId != first.playbackId } }
            assertEquals(RepeatMode.ALL, repeated.repeatMode)
            assertNull(repeated.fadingOut)
            engine.setRepeat(RepeatMode.ONE, 2)
            engine.loadQueue(listOf(song, next), positionMs = 2_600)
            try { withTimeout(3_000) { engine.status.first { it.playing && it.repeatPlays == 2 && it.positionMs >= 2_550 } } }
            catch (failure: Throwable) { throw AssertionError("Starting finite repeat failed: ${engine.status.value.let { listOf(it.entryId, it.positionMs, it.playing, it.repeatMode, it.repeatPlays) }}; native: ${nativePlayers.map { it.status.value.let { s -> listOf(s.entryId, s.positionMs, s.playing, s.repeatMode, s.repeatPlays) } }}", failure) }
            val remaining = try { withTimeout(3_000) { engine.status.first { it.playing && it.repeatPlays == 1 && it.positionMs < 250 } } }
            catch (failure: Throwable) { throw AssertionError("Finite repeat failed: ${engine.status.value.let { listOf(it.entryId, it.positionMs, it.playing, it.repeatMode, it.repeatPlays) }}; native: ${nativePlayers.map { it.status.value.let { s -> listOf(s.entryId, s.positionMs, s.playing, s.repeatMode, s.repeatPlays) } }}", failure) }
            assertNull(remaining.fadingOut)
            val advanced = withTimeout(5_000) { engine.status.first { it.entryId == "second" && it.playing } }
            assertEquals(RepeatMode.OFF, advanced.repeatMode)
            assertNull(advanced.fadingOut)
        } finally { engine.close() }
    }

    @Test
    fun seekingAndSelectingAnotherEntryStopTheOutgoingTrackAndRestoreTheMasterVolume() = runBlocking {
        val players = mutableListOf<MpvPlaybackEngine>()
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null").also { players += it } }
        try {
            val tracks = (1..3).map { PlayableTrack("song-$it", audio, entryId = "entry-$it") }
            engine.setCrossfadeDuration(1_000)
            engine.setVolume(0.4)
            engine.loadQueue(tracks, positionMs = 1_900)
            withTimeout(3_000) { engine.status.first { it.entryId == "entry-2" && it.playing && it.positionMs >= 100 && it.fadingOut != null } }
            engine.seek(500)
            withTimeout(2_000) { engine.status.first { it.fadingOut == null && it.positionMs in 450..700 } }
            engine.loadQueue(tracks, startIndex = 2, positionMs = 1_000, play = false)
            val selected = withTimeout(2_000) { engine.status.first { it.entryId == "entry-3" && !it.playing && it.positionMs >= 950 } }
            assertNull(selected.fadingOut)
            assertEquals(0.4, selected.volume)
            withTimeout(2_000) {
                while (players.any { it.status.value.playing } || players.none { it.status.value.entryId == "entry-3" && it.status.value.volume == 0.4 }) {
                    kotlinx.coroutines.delay(25)
                }
            }
        } finally { engine.close() }
    }

    @Test fun loadingAnotherSongReportsItImmediatelyWithoutRevertingToThePreviousSong() = runBlocking {
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null") }
        try {
            val tracks = (1..2).map { PlayableTrack("same-song", audio, entryId = "entry-$it") }
            engine.loadQueue(tracks, 0, play = false)
            withTimeout(3_000) { engine.status.first { it.entryId == "entry-1" && it.durationMs > 0 } }
            engine.loadQueue(tracks, 1, positionMs = 1_000, play = false)
            assertEquals("entry-2", engine.status.value.entryId)
            val seen = mutableListOf<String?>()
            val loaded = withTimeout(3_000) { engine.status.onEach { seen += it.entryId }.first { it.entryId == "entry-2" && it.durationMs > 0 } }
            assertTrue(seen.none { it == "entry-1" }, "The previous song was reported again: $seen")
            assertTrue(loaded.positionMs in 900..1_200, "Position ${loaded.positionMs}")
        } finally { engine.close() }
    }

    @Test fun aFailedLoadStopsReportingTheRequestedSongAsLoading() = runBlocking {
        val engine = CrossfadePlaybackEngine {
            val decoder = MpvPlaybackEngine("null")
            object : PlaybackEngine by decoder {
                override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
                    // A real decoder takes a moment before it rejects a song; the requested song is reported meanwhile.
                    if (tracks[startIndex].entryId == "broken") { Thread.sleep(100); error("The decoder rejected this song") }
                    decoder.loadQueue(tracks, startIndex, positionMs, play)
                }
            }
        }
        try {
            val tracks = listOf(PlayableTrack("same-song", audio, entryId = "entry-1"), PlayableTrack("same-song", audio, entryId = "broken"))
            engine.loadQueue(tracks, 0, play = false)
            withTimeout(3_000) { engine.status.first { it.entryId == "entry-1" && it.durationMs > 0 } }
            engine.loadQueue(tracks, 1, play = false)
            assertEquals("broken", engine.status.value.entryId)
            val settled = withTimeout(3_000) { engine.status.first { it.entryId == "entry-1" } }
            assertTrue(settled.durationMs > 0, "The decoder's real state is reported again after the failed load")
        } finally { engine.close() }
    }

    @Test fun aDecoderThatNeverConfirmsRepeatDoesNotStopThePlayer() = runBlocking {
        val engine = CrossfadePlaybackEngine {
            val decoder = MpvPlaybackEngine("null")
            object : PlaybackEngine by decoder { override fun setRepeat(mode: RepeatMode, playCount: Int?) {} }
        }
        try {
            engine.setRepeat(RepeatMode.ONE)
            // Completes only once the worker has outlived the repeat confirmation timeout.
            withTimeout(8_000) { engine.setAudioEffects(AudioEffects()) }
            engine.setRepeat(RepeatMode.OFF)
            engine.loadQueue(listOf(PlayableTrack("song", audio)), play = false)
            withTimeout(3_000) { engine.status.first { it.entryId == "song" && it.durationMs > 0 } }
            Unit
        } finally { engine.close() }
    }

    /** Reports a load only on [flush], as Android's decoder does once its main thread runs the command. */
    private class PostedEngine : PlaybackEngine {
        private val posted = ConcurrentLinkedQueue<() -> Unit>()
        val loads = CopyOnWriteArrayList<String>()
        val updates = CopyOnWriteArrayList<List<String>>()
        @Volatile var effectsFailure: Exception? = null
        @Volatile var loadFailure: Exception? = null
        override val status = MutableStateFlow(PlaybackStatus())
        fun flush() { while (true) (posted.poll() ?: return)() }
        override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)
        override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
            loadFailure?.let { throw it }
            val track = tracks[startIndex]
            loads += track.entryId
            posted += { status.update { it.copy(trackId = track.id, entryId = track.entryId, durationMs = 10_000, positionMs = positionMs,
                playing = play, ended = false) } }
        }
        override fun updateQueue(tracks: List<PlayableTrack>) { updates += tracks.map { it.entryId } }
        override fun play() {}
        override fun pause() {}
        override fun seek(positionMs: Long) {}
        override fun setVolume(volume: Double) {}
        override fun setSpeed(speed: Double) {}
        override fun setPitch(pitch: Double) {}
        override suspend fun setAudioEffects(effects: AudioEffects) { effectsFailure?.let { throw it } }
        override fun setRepeat(mode: RepeatMode, playCount: Int?) { status.update { it.copy(repeatMode = mode, repeatPlays = playCount) } }
        override fun close() {}
    }

    @Test fun aRejectedAudioEffectFailsOnlyThatChange() = runBlocking {
        val decoder = PostedEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            decoder.effectsFailure = IllegalStateException("The decoder rejected the filter chain")
            assertFailsWith<IllegalStateException> { withTimeout(2_000) { engine.setAudioEffects(AudioEffects()) } }
            decoder.effectsFailure = null
            engine.loadQueue(listOf(PlayableTrack("song", audio)), play = false)
            // Completes only after the worker ran the load.
            withTimeout(2_000) { engine.setAudioEffects(AudioEffects()) }
            assertEquals(listOf("song"), decoder.loads)
        } finally { engine.close() }
    }

    @Test fun aQueueUpdateBeforeTheDecoderReportsALoadKeepsTheLoadedSong() = runBlocking {
        val decoder = PostedEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            val first = (1..2).map { PlayableTrack("song", audio, entryId = "first-$it") }
            val second = (1..2).map { PlayableTrack("song", audio, entryId = "second-$it") }
            engine.loadQueue(first, play = false)
            engine.setAudioEffects(AudioEffects())
            decoder.flush()
            engine.loadQueue(second, play = false)
            engine.updateQueue(second)
            engine.setAudioEffects(AudioEffects())
            assertEquals(second.map { it.entryId }, decoder.updates.last())
            // Without gapless playback the decoder holds only the current song, which stays the newly loaded one.
            engine.setGaplessPlayback(false)
            engine.setAudioEffects(AudioEffects())
            decoder.flush()
            engine.loadQueue(second, 1, play = false)
            engine.updateQueue(second)
            engine.setAudioEffects(AudioEffects())
            assertEquals(listOf("second-2"), decoder.updates.last())
        } finally { engine.close() }
    }

    @Test fun anEndedSongStartsTheNextSongOnceWhileTheDecoderLoadsIt() = runBlocking {
        val decoders = CopyOnWriteArrayList<PostedEngine>()
        val engine = CrossfadePlaybackEngine { PostedEngine().also { decoders += it } }
        try {
            val tracks = (1..3).map { PlayableTrack("song", audio, entryId = "entry-$it") }
            engine.setCrossfadeDuration(1_000)
            engine.loadQueue(tracks)
            engine.setAudioEffects(AudioEffects())
            val decoder = decoders.first()
            decoder.flush()
            // The standby player never reports its song either, so the ended song falls back to loading the next one.
            decoder.status.update { it.copy(positionMs = 10_000, playing = false, ended = true) }
            withTimeout(2_000) { while (decoder.loads.size < 2) delay(10) }
            delay(300)
            assertEquals(listOf("entry-1", "entry-2"), decoder.loads)
        } finally { engine.close() }
    }

    @Test fun removingTheSongThatIsStillLoadingKeepsTheSongTheDecoderPlays() = runBlocking {
        val decoder = PostedEngine()
        val engine = CrossfadePlaybackEngine { decoder }
        try {
            val tracks = (1..3).map { PlayableTrack("song", audio, entryId = "entry-$it") }
            engine.setGaplessPlayback(false)
            engine.loadQueue(tracks)
            engine.setAudioEffects(AudioEffects())
            decoder.flush()
            decoder.status.update { it.copy(positionMs = 10_000, playing = false, ended = true) }
            withTimeout(2_000) { while (decoder.loads.size < 2) delay(10) }
            // The decoder has not reported the next song yet when the user removes it.
            engine.updateQueue(tracks - tracks[1])
            engine.setAudioEffects(AudioEffects())
            assertEquals(listOf("entry-1"), decoder.updates.last())
            // The song that still plays ends, so playback continues with the song after the removed one.
            withTimeout(2_000) { while (decoder.loads.size < 3) delay(10) }
            assertEquals(listOf("entry-1", "entry-2", "entry-3"), decoder.loads)
        } finally { engine.close() }
    }

    @Test fun aFailedLoadOfTheNextSongIsTriedAgain() = runBlocking {
        val decoders = CopyOnWriteArrayList<PostedEngine>()
        val engine = CrossfadePlaybackEngine { PostedEngine().also { decoders += it } }
        try {
            val tracks = (1..2).map { PlayableTrack("song", audio, entryId = "entry-$it") }
            engine.setCrossfadeDuration(1_000)
            engine.loadQueue(tracks)
            engine.setAudioEffects(AudioEffects())
            val decoder = decoders.first()
            decoder.flush()
            decoder.loadFailure = IllegalStateException("The decoder is unavailable")
            decoder.status.update { it.copy(positionMs = 10_000, playing = false, ended = true) }
            delay(300)
            decoder.loadFailure = null
            withTimeout(2_000) { while (decoder.loads.size < 2) delay(10) }
            assertEquals(listOf("entry-1", "entry-2"), decoder.loads)
        } finally { engine.close() }
    }
}
