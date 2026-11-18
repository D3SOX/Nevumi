package me.d3sox.nevumi.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.playback.CrossfadePlaybackEngine
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.storage.openMusicStore
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LongSongResumeTest {
    /** Opens a store in a temporary directory and removes both afterwards, also when the player cannot be created. */
    private fun withStore(prefix: String, block: suspend (Path, MusicStore) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory(prefix)
        val store = openMusicStore(directory.resolve("library.db").toString())
        try { block(directory, store) } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    private fun elevenMinutes(directory: Path): Path = silence(directory, "mix.wav", 11 * 60)

    /** [seconds] of 8 kHz mono silence; seeking skips the listening. */
    private fun silence(directory: Path, name: String, seconds: Int): Path {
        val mix = directory.resolve(name)
        val frames = seconds * 8_000
        RandomAccessFile(mix.toFile(), "rw").use { file ->
            file.write(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + frames * 2)
                .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(frames * 2).array())
            file.setLength(44L + frames * 2)
        }
        return mix
    }

    @Test fun aPositionIsNotSavedForARemovedSongOrAfterThePositionsWereForgotten() = runBlocking {
        val directory = Files.createTempDirectory("long-song-stale-saves")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        try {
            val kept = library.importTrack(TrackMetadata("Kept"), TrackSource("source:kept", SourceKind.LOCAL, "/kept.flac"))
            val merged = library.importTrack(TrackMetadata("Merged"), TrackSource("source:merged", SourceKind.LOCAL, "/merged.flac"))
            // A checkpoint that read the merged song before it was linked into another song writes nothing afterwards.
            library.linkTracks(kept.id, merged.id)
            library.rememberSongPosition(merged.id, 120_000)
            assertNull(library.songPosition(merged.id))
            // A checkpoint read before the setting was turned off is dropped, even when it is saved after it was turned on again.
            val generation = library.songPositionGeneration
            library.forgetSongPositions()
            library.rememberSongPosition(kept.id, 120_000, generation)
            assertNull(library.songPosition(kept.id))
            library.rememberSongPosition(kept.id, 120_000)
            assertEquals(120_000, library.songPosition(kept.id))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun aSongWithoutAKnownLengthResumesOnceTheDecoderReportsIt() = withStore("long-song-unknown-length") { directory, store ->
        val library = MusicLibrary(store)
        val mix = library.importTrack(TrackMetadata("Mix"), TrackSource("source:mix", SourceKind.LOCAL, elevenMinutes(directory).toString()))
        assertEquals(0, mix.metadata.durationMs)
        library.rememberSongPosition(mix.id, 240_000)
        val player = MusicController(library, PlaybackQueue(store), CrossfadePlaybackEngine { MpvPlaybackEngine("null") },
            preferences = MusicPreferences(store))
        try {
            withTimeout(5_000) { player.state.first { it.libraryReady } }
            player.updatePreferences { it.copy(resumeLongSongsMinutes = 10) }.join()
            player.playTrack(mix.id, listOf(mix.id), 0).join()
            withTimeout(5_000) { player.state.first { it.playback.trackId == mix.id && it.playback.playing && it.playback.positionMs >= 240_000 } }
            assertNull(player.state.value.error)
        } finally { player.closeAndJoin() }
    }

    @Test fun theSameSongQueuedTwiceStartsItsSecondPlayFromTheBeginning() = withStore("long-song-twice") { directory, store ->
        val library = MusicLibrary(store)
        val mix = library.importTrack(TrackMetadata("Mix", durationMs = 660_000), TrackSource("source:mix", SourceKind.LOCAL, elevenMinutes(directory).toString()))
        library.rememberSongPosition(mix.id, 240_000)
        val player = MusicController(library, PlaybackQueue(store), CrossfadePlaybackEngine { MpvPlaybackEngine("null") },
            preferences = MusicPreferences(store))
        try {
            withTimeout(5_000) { player.state.first { it.libraryReady } }
            player.updatePreferences { it.copy(resumeLongSongsMinutes = 10) }.join()
            player.playTrack(mix.id, listOf(mix.id, mix.id), 0).join()
            val first = withTimeout(5_000) { player.state.first { it.playback.playing && it.playback.positionMs >= 240_000 } }.playback.entryId
            // The first play finishes and the gapless queue moves on to the second one at its start.
            player.seek(658_500).join()
            withTimeout(8_000) { player.state.first { it.playback.entryId != first && it.playback.playing } }
            delay(1_500)
            assertTrue(player.state.value.playback.positionMs < 5_000, "The second play resumed at ${player.state.value.playback.positionMs}")
            assertNull(library.songPosition(mix.id))
        } finally { player.closeAndJoin() }
    }

    @Test fun closingTheAppSavesTheLatestPositionAndASingleSongRepeatClearsIt() = withStore("long-song-close") { directory, store ->
        val library = MusicLibrary(store)
        val mix = library.importTrack(TrackMetadata("Mix", durationMs = 660_000), TrackSource("source:mix", SourceKind.LOCAL, elevenMinutes(directory).toString()))
        fun player() = MusicController(library, PlaybackQueue(store), CrossfadePlaybackEngine { MpvPlaybackEngine("null") },
            preferences = MusicPreferences(store))
        val first = player()
        try {
            withTimeout(5_000) { first.state.first { it.libraryReady } }
            first.updatePreferences { it.copy(resumeLongSongsMinutes = 10) }.join()
            first.playTrack(mix.id, listOf(mix.id), 0).join()
            withTimeout(5_000) { first.state.first { it.playback.playing } }
            first.seek(200_000).join()
            withTimeout(5_000) { first.state.first { it.playback.playing && it.playback.positionMs >= 200_500 } }
            // Closing while it plays, before any checkpoint, still saves where it was.
            first.closeAndJoin()
            assertTrue(library.songPosition(mix.id)!! in 200_000..205_000)
        } finally { first.closeAndJoin() }
        val second = player()
        try {
            withTimeout(5_000) { second.state.first { it.libraryReady } }
            // A queue of only this song that repeats it forgets the finished pass, like repeating the song itself.
            second.playTrack(mix.id, listOf(mix.id), 0).join()
            withTimeout(5_000) { second.state.first { it.playback.playing && it.playback.positionMs >= 200_000 } }
            second.setQueueRepeat(RepeatMode.ALL).join()
            second.seek(658_500).join()
            withTimeout(8_000) { second.state.first { it.playback.playing && it.playback.positionMs in 1..5_000 } }
            withTimeout(5_000) { while (library.songPosition(mix.id) != null) delay(25) }
        } finally { second.closeAndJoin() }
    }

    @Test fun longSongsContinueWhereTheyWereLeftUntilTheyFinish() = withStore("long-song-resume") { directory, store ->
        val library = MusicLibrary(store)
        val mix = elevenMinutes(directory)
        val long = library.importTrack(TrackMetadata("Mix", durationMs = 660_000), TrackSource("source:mix", SourceKind.LOCAL, mix.toString()))
        val short = library.importTrack(TrackMetadata("Short", durationMs = 60_000),
            TrackSource("source:short", SourceKind.LOCAL, silence(directory, "short.wav", 60).toString()))
        val player = MusicController(library, PlaybackQueue(store), CrossfadePlaybackEngine { MpvPlaybackEngine("null") },
            preferences = MusicPreferences(store))
        suspend fun storedPosition(expected: (Long?) -> Boolean): Long? = withTimeout(5_000) {
            while (!expected(library.songPosition(long.id))) delay(25)
            library.songPosition(long.id)
        }
        try {
            withTimeout(5_000) { player.state.first { it.libraryReady } }
            player.updatePreferences { it.copy(resumeLongSongsMinutes = 10) }.join()
            player.playTrack(long.id, listOf(long.id, short.id), 0).join()
            withTimeout(5_000) { player.state.first { it.playback.trackId == long.id && it.playback.playing } }
            player.seek(120_000).join()
            withTimeout(5_000) { player.state.first { it.playback.playing && it.playback.positionMs >= 120_500 } }
            // Leaving the song before its end remembers where.
            player.skip(1).join()
            withTimeout(5_000) { player.state.first { it.playback.trackId == short.id } }
            assertTrue(storedPosition { it != null }!! in 120_000..125_000)
            // Shorter songs are not remembered, even when they are paused well past their first seconds.
            player.seek(20_000).join()
            withTimeout(5_000) { player.state.first { it.playback.playing && it.playback.positionMs >= 20_500 } }
            player.pause().join()
            delay(500)
            assertNull(library.songPosition(short.id))

            // Playing it again from the start continues there.
            player.playTrack(long.id, listOf(long.id), 0).join()
            withTimeout(5_000) { player.state.first { it.playback.trackId == long.id && it.playback.playing && it.playback.positionMs >= 120_000 } }

            // A repeat-one pass that finishes starts the song over and forgets the position.
            player.setQueueRepeat(RepeatMode.ONE).join()
            player.seek(658_500).join()
            withTimeout(8_000) { player.state.first { it.playback.trackId == long.id && it.playback.playing && it.playback.positionMs in 1..5_000 } }
            storedPosition { it == null }
            player.setQueueRepeat(RepeatMode.OFF).join()

            // Pausing remembers too, and turning the setting off forgets every position.
            player.seek(200_000).join()
            withTimeout(5_000) { player.state.first { it.playback.playing && it.playback.positionMs >= 200_500 } }
            player.pause().join()
            assertTrue(storedPosition { it != null && it >= 200_000 }!! < 205_000)
            player.updatePreferences { it.copy(resumeLongSongsMinutes = 0) }.join()
            storedPosition { it == null }

            // A song that plays to its end starts from the beginning next time.
            player.updatePreferences { it.copy(resumeLongSongsMinutes = 10) }.join()
            player.seek(300_000).join()
            player.play().join()
            withTimeout(5_000) { player.state.first { it.playback.playing && it.playback.positionMs >= 300_500 } }
            player.pause().join()
            storedPosition { it != null }
            player.seek(657_000).join()
            player.play().join()
            withTimeout(8_000) { player.state.first { it.playback.ended } }
            storedPosition { it == null }
            assertNull(player.state.value.error)
        } finally { player.closeAndJoin() }
    }
}
