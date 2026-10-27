package me.d3sox.nevumi.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.lyrics.LyricLine
import me.d3sox.nevumi.lyrics.Lyrics
import me.d3sox.nevumi.lyrics.LyricsLibrary
import me.d3sox.nevumi.lyrics.LyricsQuery
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
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Set<LyricsSource>>>())
        val queries = java.util.Collections.synchronizedList(mutableListOf<LyricsQuery?>())
        var release = CompletableDeferred<Unit>()
        val lyrics = object : LyricsLibrary {
            override suspend fun lyrics(track: Track, online: Set<LyricsSource>, query: LyricsQuery?): Lyrics? {
                queries += query
                calls += track.id to online
                release.await()
                if (query != null) return Lyrics(LyricsSource.LRCLIB, listOf(LyricLine(0, query.title)))
                return if (track.id == songs[0].id) Lyrics(LyricsSource.EMBEDDED, listOf(LyricLine(0, track.metadata.title))) else null
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), lyrics = lyrics)
        var watching = launch { player.lyricsController.lyrics.collect {} }
        suspend fun awaitCalls(count: Int) = withTimeout(3_000) { while (calls.size < count) delay(10) }
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
            assertEquals(listOf(songs[0].id to emptySet<LyricsSource>()), calls)

            player.updatePreferences { it.copy(fetchLyricsOnline = true) }.join()
            // A new lookup may leave the state equal to the old one, which a state flow does not emit again, so wait for the call.
            awaitCalls(2)
            withTimeout(3_000) { player.lyricsController.lyrics.first { it is LyricsState.Available } }
            assertEquals(songs[0].id to setOf(LyricsSource.LRCLIB), calls.last())

            player.skip(1).join()
            awaitCalls(3)
            withTimeout(3_000) { player.lyricsController.lyrics.first { it == LyricsState.None } }
            assertEquals(songs[1].id to setOf(LyricsSource.LRCLIB), calls.last())
            player.updatePreferences { it.copy(fetchLyricsFromNetEase = true) }.join()
            awaitCalls(4)
            assertEquals(songs[1].id to setOf(LyricsSource.LRCLIB, LyricsSource.NETEASE), calls.last())
            release = CompletableDeferred(Unit)
            player.lyricsController.refresh()
            awaitCalls(5)
            withTimeout(3_000) { player.lyricsController.lyrics.first { it == LyricsState.None } }

            // A search the listener wrote replaces the tags for the playing song only, and searching it again looks again.
            val playing = songs[1]
            assertEquals(LyricsQuery("Song 1", ""), player.lyricsController.query(playing))
            val written = LyricsQuery("Real title", "Real artist")
            player.lyricsController.search(playing, written)
            val found = withTimeout(3_000) { player.lyricsController.lyrics.first { it is LyricsState.Available } }
            assertEquals("Real title", (found as LyricsState.Available).lyrics.lines.single().text)
            assertEquals(written, queries.last())
            assertEquals(written, player.lyricsController.query(playing))
            assertEquals(LyricsQuery("Song 0", ""), player.lyricsController.query(songs[0]))
            val searched = calls.size
            player.lyricsController.search(playing, written)
            awaitCalls(searched + 1)
            assertEquals(written, queries.last())
            // Turning online lyrics off stops asking for the search, and leaving the song ends it, so it returns to the cache.
            player.updatePreferences { it.copy(fetchLyricsOnline = false) }.join()
            awaitCalls(searched + 2)
            assertEquals(null to emptySet<LyricsSource>(), queries.last() to calls.last().second)
            player.updatePreferences { it.copy(fetchLyricsOnline = true) }.join()
            awaitCalls(searched + 3)
            assertEquals(written, queries.last())
            // This holds even while nothing shows the lyrics.
            watching.cancel()
            player.skip(-1, restartPrevious = false).join()
            withTimeout(3_000) { while (player.lyricsController.query(songs[1]) != LyricsQuery("Song 1", "")) delay(10) }
            player.skip(1).join()
            watching = launch { player.lyricsController.lyrics.collect {} }
            awaitCalls(searched + 4)
            assertEquals(songs[1].id to null, calls.last().first to queries.last())
            Unit
        } finally { watching.cancel(); player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
