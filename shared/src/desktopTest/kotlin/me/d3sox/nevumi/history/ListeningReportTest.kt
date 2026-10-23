package me.d3sox.nevumi.history

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ListeningReportTest {
    @Test
    fun recommendationsFavorUnheardSongsFromListenedArtistsAndGenresAndAvoidRecentReplays() = runBlocking {
        val directory = Files.createTempDirectory("listening-recommendations")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            suspend fun song(title: String, artist: String, genres: List<String>, available: Boolean = true) =
                library.importTrack(TrackMetadata(title, artist, durationMs = 20_000, genres = genres),
                    TrackSource(title, SourceKind.LOCAL, "/$title.flac", available = available))
            val seed = song("Heard favorite", "Favorite artist", listOf("Ambient", "ambient"))
            val other = song("Heard other", "Other artist", listOf("Rock"))
            val artist = song("New favorite artist", "Favorite artist", listOf("Ambient"))
            val genre = song("New ambient", "New artist", listOf("ambient"))
            val rock = song("New rock", "New artist", listOf("Rock"))
            song("Unrelated", "Unrelated artist", listOf("Pop"))
            song("Unavailable", "Favorite artist", listOf("Ambient"), available = false)
            var wall = 1_800_000_000_000L
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { wall }, elapsedClock = { elapsed })
            for ((index, track) in listOf(seed, seed, seed, other).withIndex()) {
                val playing = PlaybackStatus(track.id, playing = true, playbackId = "listen-$index", durationMs = 20_000)
                history.record(playing); elapsed += 10_000; history.record(playing.copy(playing = false, ended = true))
            }
            val tracks = library.tracks()
            assertEquals(listOf(artist.id, genre.id, rock.id), history.recommendations(tracks).map { it.track.id })
            assertEquals(ListeningRecommendation.Reason.ARTIST to "Favorite artist", history.recommendations(tracks).first().let { it.reason to it.name })
            assertEquals(ListeningRecommendation.Reason.GENRE to "Ambient", history.recommendations(tracks)[1].let { it.reason to it.name })
            assertEquals(listOf(artist.id, genre.id), history.recommendations(tracks, limit = 2).map { it.track.id })
            assertEquals(listOf(genre.id), history.recommendations(listOf(genre)).map { it.track.id })
            wall += 8 * 86_400_000L
            assertEquals(listOf(artist.id, genre.id, rock.id, seed.id, other.id), history.recommendations(tracks).map { it.track.id })
            history.clear()
            assertEquals(emptyList(), history.recommendations(tracks))
            assertFailsWith<IllegalArgumentException> { history.recommendations(tracks, limit = 0) }
            Unit
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun timeRangesAndGroupsCountActualListeningWithoutDoubleCountingMultiGenreSongs() = runBlocking {
        val directory = Files.createTempDirectory("listening-report")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val first = library.importTrack(TrackMetadata("First", "Artist", "Shared title", durationMs = 20_000,
                genres = listOf("Ambient", "Electronic", "ambient")), TrackSource("first", SourceKind.LOCAL, "/first.flac"))
            val second = library.importTrack(TrackMetadata("Second", "Other artist", "Shared title", durationMs = 20_000,
                genres = listOf("Ambient")), TrackSource("second", SourceKind.LOCAL, "/second.flac"))
            var wall = 1_800_000_000_000L
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { wall }, elapsedClock = { elapsed })
            suspend fun listen(track: Track, id: String, milliseconds: Long) {
                val playing = PlaybackStatus(track.id, playing = true, playbackId = id, durationMs = 20_000)
                history.record(playing); elapsed += milliseconds; history.record(playing.copy(playing = false, ended = true))
            }
            listen(second, "old", 10_000)
            wall += 8 * 86_400_000L
            listen(first, "recent", 12_000)
            listen(second, "partial", 2_000)
            val tracks = library.tracks()
            val report = history.report(tracks, ListeningPeriod.LAST_7_DAYS)
            assertEquals(1, report.playCount)
            assertEquals(14_000, report.listenedMs)
            assertEquals(listOf(first.id), report.songs.filter { it.playCount > 0 }.flatMap { it.trackIds })
            assertEquals(1, report.genres.single { it.name == "Ambient" }.playCount)
            assertEquals(14_000, report.genres.single { it.name == "Ambient" }.listenedMs)
            assertEquals(12_000, report.genres.single { it.name == "Electronic" }.listenedMs)
            assertEquals(2, report.albums.size) // Identically named albums by different artists remain distinct.
            assertEquals(listOf("Artist", "Other artist"), report.artists.map { it.name })
            val all = history.report(tracks)
            assertEquals(2, all.playCount)
            assertEquals(24_000, all.listenedMs)
            assertEquals(2, all.genres.single { it.name == "Ambient" }.playCount)
            assertEquals(1, history.report(listOf(first), ListeningPeriod.LAST_30_DAYS).playCount)
            wall += 366 * 86_400_000L
            assertEquals(0, history.report(tracks, ListeningPeriod.LAST_365_DAYS).playCount)
            assertEquals(2, history.report(tracks).playCount)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
