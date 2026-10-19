package me.d3sox.nevumi.history

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class ListeningHistoryTest {
    @Test
    fun bothAudibleTracksDuringACrossfadeKeepTheirOwnContinuousListenAndPauseTogether() = runBlocking {
        val directory = Files.createTempDirectory("crossfade-listening-history")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val tracks = (1..2).map { library.importTrack(TrackMetadata("Song $it", durationMs = 20_000),
                TrackSource("local-$it", SourceKind.LOCAL, "/music/song-$it.flac")) }
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { elapsed }, elapsedClock = { elapsed })
            val first = PlaybackStatus(tracks[0].id, playing = true, durationMs = 20_000, playbackId = "first-play")
            val second = PlaybackStatus(tracks[1].id, playing = true, durationMs = 20_000, playbackId = "second-play")
            history.record(first)
            elapsed = 10_000
            history.record(second.copy(fadingOut = first))
            elapsed = 12_000
            history.record(second.copy(playing = false, fadingOut = first.copy(playing = false)))
            elapsed = 22_000
            history.record(second.copy(fadingOut = first))
            elapsed = 25_000
            history.record(second)
            elapsed = 33_000
            history.record(second.copy(playing = false, ended = true))
            val events = history.all().associateBy { it.playbackId }
            assertEquals(2, events.size)
            assertEquals(15_000, events.getValue("first-play").listenedMs)
            assertEquals(13_000, events.getValue("second-play").listenedMs)
            assertEquals(true, events.values.all { it.qualified && it.ended })
            assertEquals(2, history.statistics().values.sumOf { it.playCount })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun clearingHistoryResetsStatisticsAndStartsCountingAgainFromTheClearTime() = runBlocking {
        val directory = Files.createTempDirectory("cleared-listening-history")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val song = library.importTrack(TrackMetadata("Song", durationMs = 20_000),
                TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { elapsed }, elapsedClock = { elapsed })
            val playing = PlaybackStatus(song.id, playing = true, durationMs = 20_000, playbackId = "same-play")
            history.record(playing)
            elapsed = 10_000
            history.record(playing)
            assertEquals(1, history.statistics().getValue(song.id).playCount)
            history.clear()
            assertEquals(emptyList(), history.all())
            assertEquals(emptyMap(), history.statistics())
            history.record(playing)
            elapsed = 12_000
            history.record(playing.copy(playing = false))
            assertEquals(2_000, history.all().single().listenedMs)
            assertEquals(10_000, history.all().single().startedAt)
            assertEquals(0, history.statistics().getValue(song.id).playCount)
            assertEquals("same-play", history.all().single().playbackId)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun disablingAndResumingRecordingExcludesTheDisabledIntervalWithoutDuplicatingThePlay() = runBlocking {
        val directory = Files.createTempDirectory("disabled-listening-history")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val song = library.importTrack(TrackMetadata("Song", durationMs = 20_000),
                TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { elapsed }, elapsedClock = { elapsed })
            val playing = PlaybackStatus(song.id, playing = true, durationMs = 20_000, entryId = "entry", playbackId = "same-play")
            history.record(playing)
            elapsed = 2_000
            history.record(playing, enabled = false)
            elapsed = 62_000
            history.record(playing, enabled = false)
            assertEquals(2_000, history.all().single().listenedMs)
            history.record(playing)
            elapsed = 70_000
            history.record(playing.copy(playing = false))
            assertEquals(1, history.all().size)
            assertEquals(10_000, history.all().single().listenedMs)
            assertEquals(1, history.statistics().getValue(song.id).playCount)
            history.record(playing.copy(playbackId = "disabled-play"), enabled = false)
            assertEquals(1, history.all().size)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun slowPlaybackCountsAPlayFromAudioHeardWhileStatisticsKeepTheActualListeningTime() = runBlocking {
        val directory = Files.createTempDirectory("slow-listening-history")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Slow song", durationMs = 20_000),
                TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { elapsed }, elapsedClock = { elapsed })
            val playing = PlaybackStatus(track.id, playing = true, durationMs = 20_000, entryId = "queue-entry", playbackId = "slow-play", speed = 0.5)
            history.record(playing)
            elapsed = 10_000
            history.record(playing.copy(positionMs = 5_000))
            assertEquals(0, history.statistics().getValue(track.id).playCount)
            elapsed = 20_000
            history.record(playing.copy(playing = false, positionMs = 10_000))
            assertEquals(1, history.statistics().getValue(track.id).playCount)
            assertEquals(20_000, history.statistics().getValue(track.id).listenedMs)
            assertEquals(10_000, history.all().single().playedMs)
            val fast = playing.copy(playbackId = "fast-play", speed = 2.0)
            history.record(fast)
            elapsed = 25_000
            history.record(fast.copy(playing = false, positionMs = 10_000))
            assertEquals(2, history.statistics().getValue(track.id).playCount)
            assertEquals(25_000, history.statistics().getValue(track.id).listenedMs)
            assertEquals(10_000, history.all().single { it.playbackId == "fast-play" }.playedMs)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun linkingTwoCopiesMovesTheirHistoryWithoutSplittingAnActiveListenOrLosingItsStatistics() = runBlocking {
        val directory = Files.createTempDirectory("linked-listening-history")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val local = library.importTrack(TrackMetadata("Local title", durationMs = 20_000),
                TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            val server = library.importTrack(TrackMetadata("Server title", durationMs = 20_000),
                TrackSource("server:test:song", SourceKind.SERVER, "song"))
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { elapsed }, elapsedClock = { elapsed })
            val playing = PlaybackStatus(server.id, playing = true, durationMs = 20_000, entryId = "queue-entry", playbackId = "same-play")
            history.record(playing)
            elapsed = 10_000
            history.record(playing.copy(positionMs = 10_000))
            library.linkTracks(local.id, server.id)
            // A queued player update may still contain the old catalogue ID.
            elapsed = 11_000
            history.record(playing.copy(positionMs = 11_000))
            elapsed = 12_000
            history.record(playing.copy(trackId = local.id, playing = false, positionMs = 12_000))
            val event = history.all().single()
            assertEquals(local.id, event.trackId)
            assertEquals("Server title", event.metadata.title)
            assertEquals(12_000, event.listenedMs)
            assertEquals(setOf(local.id), history.statistics().keys)
            assertEquals(1, history.statistics().getValue(local.id).playCount)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playbackOutsideTheIndexedLibraryDoesNotBreakRecordingForTheNextKnownSong() = runBlocking {
        val directory = Files.createTempDirectory("unknown-listening-track")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { elapsed }, elapsedClock = { elapsed })
            history.record(PlaybackStatus("outside-library", playing = true, entryId = "external", playbackId = "external-play"))
            assertEquals(emptyList(), history.all())
            val song = library.importTrack(TrackMetadata("Known song", durationMs = 2_000),
                TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            val playing = PlaybackStatus(song.id, playing = true, durationMs = 2_000, entryId = "known", playbackId = "known-play")
            history.record(playing)
            elapsed = 1_000
            history.record(playing.copy(playing = false))
            assertEquals(listOf(song.id), history.all().map { it.trackId })
            assertEquals(1, history.statistics().getValue(song.id).playCount)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun seeksAndPausedTimeDoNotInflateListeningAndTheHistorySurvivesRestart() = runBlocking {
        val directory = Files.createTempDirectory("listening-history")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        try {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", durationMs = 20_000,
                folder = "/private/music"), TrackSource("local", SourceKind.LOCAL, "/private/music/song.flac"))
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { 1_000 + elapsed }, elapsedClock = { elapsed })
            val playing = PlaybackStatus(track.id, playing = true, durationMs = 20_000, entryId = "queue-entry", playbackId = "first-play")
            history.record(playing)
            elapsed = 2_000
            history.record(playing.copy(positionMs = 2_000))
            history.record(playing.copy(positionMs = 17_000))
            elapsed = 4_000
            history.record(playing.copy(playing = false, positionMs = 19_000))
            elapsed = 64_000
            history.record(playing.copy(playing = false, positionMs = 19_000))
            assertEquals(4_000, history.all().single().listenedMs)
            history.record(playing.copy(positionMs = 19_000))
            elapsed = 69_000
            history.record(playing.copy(positionMs = 1_000))
            assertEquals(9_000, history.all().single().listenedMs, "A running listen is checkpointed every few seconds")
            assertEquals(0, history.statistics().getValue(track.id).playCount)
            elapsed = 70_000
            history.record(playing.copy(playing = false, positionMs = 5_000))
            store.close(); store = openMusicStore(database)
            val restored = ListeningHistory(store, MusicLibrary(store))
            val event = restored.all().single()
            assertEquals(track.id, event.trackId)
            assertEquals(1_000, event.startedAt)
            assertEquals(10_000, event.listenedMs)
            assertEquals("", event.metadata.folder)
            assertEquals(1, restored.statistics().getValue(track.id).playCount)
            assertEquals(10_000, restored.statistics().getValue(track.id).listenedMs)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
