package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
}
