package me.d3sox.nevumi.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.lyrics.LyricLine
import me.d3sox.nevumi.lyrics.Lyrics
import me.d3sox.nevumi.lyrics.LyricsLibrary
import me.d3sox.nevumi.lyrics.LyricsSource
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LyricsControllerTest {
    @Test fun followsThePlayingSongWithoutHoldingUpPlaybackAndPassesTheOnlineSetting() = runBlocking {
        val directory = Files.createTempDirectory("lyrics-controller")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val songs = (0..1).map { library.importTrack(TrackMetadata("Song $it"), TrackSource("local:$it", SourceKind.LOCAL, audio)) }
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        var release = CompletableDeferred<Unit>()
        val lyrics = object : LyricsLibrary {
            override suspend fun lyrics(track: Track, online: Boolean): Lyrics? {
                calls += track.id to online
                release.await()
                return if (track.id == songs[0].id) Lyrics(LyricsSource.EMBEDDED, listOf(LyricLine(0, track.metadata.title))) else null
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), lyrics = lyrics)
        val watching = launch { player.lyricsController.lyrics.collect {} }
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            assertTrue(player.lyricsController.available)
            assertEquals(LyricsState.None, withTimeout(3_000) { player.lyricsController.lyrics.first { it == LyricsState.None } })
            player.playTrack(songs[0].id).join()
            withTimeout(3_000) { player.lyricsController.lyrics.first { it == LyricsState.Loading } }
            // A slow lyrics source must not hold the operation lock that playback actions wait for.
            withTimeout(3_000) { player.pause().join() }
            release.complete(Unit)
            val shown = withTimeout(3_000) { player.lyricsController.lyrics.first { it is LyricsState.Available } }
            assertEquals("Song 0", (shown as LyricsState.Available).lyrics.lines.single().text)
            assertEquals(listOf(songs[0].id to false), calls)

            player.updatePreferences { it.copy(fetchLyricsOnline = true) }.join()
            withTimeout(3_000) { player.lyricsController.lyrics.first { it is LyricsState.Available && calls.size == 2 } }
            assertEquals(songs[0].id to true, calls.last())

            player.skip(1).join()
            withTimeout(3_000) { player.lyricsController.lyrics.first { it == LyricsState.None && calls.size == 3 } }
            assertEquals(songs[1].id to true, calls.last())
            release = CompletableDeferred(Unit)
            player.lyricsController.refresh()
            withTimeout(3_000) { player.lyricsController.lyrics.first { it == LyricsState.None && calls.size == 4 } }
            Unit
        } finally { watching.cancel(); player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
