package me.d3sox.nevumi.playback

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import me.d3sox.nevumi.downloads.*
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.security.SecretServiceVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf

class QueuePlaybackTest {
    @Test
    fun startingPlaybackAndAddingSongsDoNotWaitForUnrelatedFileHistory() = runBlocking {
        val directory = Files.createTempDirectory("queue-independent-of-file-history")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val path = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment/music/Fixture Artist/Fixture Album/01 Northern Light.flac").toString()
        val song = library.importTrack(TrackMetadata("Ready to play"), TrackSource("local", SourceKind.LOCAL, path))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val files = me.d3sox.nevumi.library.LocalFileEdits(store, library, directory.resolve("backups").toString())
        val delayed = object : me.d3sox.nevumi.library.MusicFileEdits by files {
            override suspend fun history(): List<me.d3sox.nevumi.library.LocalFileEdit> {
                entered.complete(Unit); release.await(); return files.history()
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), fileEdits = delayed)
        try {
            withTimeout(3_000) { entered.await() }
            val starting = player.playTrack(song.id)
            withTimeout(3_000) { player.state.first { it.playback.entryId != null } }
            withTimeout(500) { player.enqueue(song.id, QueueAction.APPEND).join() }
            assertEquals(2, player.state.value.queue.entries.size)
            withTimeout(500) { starting.join() }
        } finally { release.complete(Unit); player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun undoingAnInsertionAfterItsSongStartsKeepsThatSongAndItsPlayhead() = runBlocking {
        val directory = Files.createTempDirectory("undo-current-queue-insertion")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString())
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            player.playTrack(songs[0].id, songs.take(2).map { it.id }).join(); player.pause().join()
            val before = player.state.value.queue
            val notice = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { player.notices.first() }
            player.enqueue(listOf(songs[2].id, songs[1].id), QueueAction.PLAY_NEXT).join()
            val undo = kotlin.test.assertNotNull(notice.await().undo)
            player.skip(1).join(); player.pause().join(); player.seek(1_500).join()
            withTimeout(3_000) { player.state.first { it.playback.positionMs >= 1_500 } }
            val current = player.state.value.queue.entries[player.state.value.queue.currentIndex]
            undo().join()
            assertEquals(current.id, player.state.value.queue.currentEntryId, "Undo must not jump back to the previous song")
            assertEquals(listOf(before.entries[0], current, before.entries[1]), player.state.value.queue.entries)
            kotlin.test.assertTrue(player.state.value.playback.positionMs >= 1_450)
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun clearingTheQueueCanBeUndoneWithoutRewindingPlayback() = runBlocking {
        val directory = Files.createTempDirectory("undo-clear-queue")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString())
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            player.playTrack(songs.first().id, songs.map { it.id }).join(); player.pause().join()
            val before = player.state.value.queue
            val notice = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { player.notices.first() }
            player.clearQueue().join()
            assertEquals(1, player.state.value.queue.entries.size)
            val undo = kotlin.test.assertNotNull(notice.await().undo, "Queue clear confirmation must offer Undo")
            player.seek(1_500).join()
            withTimeout(3_000) { player.state.first { it.queue.positionMs >= 1_500 } }
            undo().join()
            assertEquals(before.entries, player.state.value.queue.entries)
            assertEquals(before.currentEntryId, player.state.value.queue.currentEntryId)
            kotlin.test.assertTrue(player.state.value.queue.positionMs >= 1_500, "Undo must preserve the current playhead")
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun removingAnUpcomingSongIsVisibleBeforeTheAudioSourceFinishesResponding() = runBlocking {
        val directory = Files.createTempDirectory("immediate-queue-removal")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val path = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val songs = listOf("Playing", "Remove me").map { library.importTrack(TrackMetadata(it), TrackSource(it, SourceKind.LOCAL, path)) }
        val delayed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), sourceAccessible = { location ->
            if (delayed.get()) { entered.complete(Unit); release.await(5, TimeUnit.SECONDS) }
            FileAudioAccess.available(location)
        })
        try {
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(songs[0].id, songs.map { it.id }).join()
            player.pause().join()
            val removed = player.state.value.queue.entries.last().id
            delayed.set(true)
            val removing = player.removeQueueEntry(removed)
            withTimeout(3_000) { entered.await() }
            withTimeout(200) { player.state.first { state -> state.queue.entries.none { it.id == removed } } }
            assertEquals(songs[0].id, player.state.value.queue.currentTrackId)
            release.countDown()
            removing.join()
            assertEquals(false, player.state.value.playback.playing)
        } finally { release.countDown(); player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pausingDuringASlowTrackChangeKeepsTheNewSongPausedWhenItFinishesLoading() = runBlocking {
        val directory = Files.createTempDirectory("pause-during-track-change")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = listOf("First", "Next").map { title -> library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL,
            Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString())) }
        val delayed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), sourceAccessible = { location ->
            if (delayed.compareAndSet(true, false)) { entered.complete(Unit); release.await(5, TimeUnit.SECONDS) }
            FileAudioAccess.available(location)
        })
        try {
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(songs[0].id, songs.map { it.id }).join()
            withTimeout(3_000) { player.state.first { it.playback.playing } }
            delayed.set(true)
            val loading = player.skip(1)
            withTimeout(3_000) { entered.await() }
            withTimeout(250) { player.pause().join() }
            release.countDown()
            loading.join()
            assertEquals(songs[1].id, player.state.value.playback.trackId)
            assertEquals(false, player.engine.status.value.playWhenReady)
            assertEquals(false, player.state.value.playback.playWhenReady)
        } finally { release.countDown(); player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playAndPauseRespondImmediatelyWhileAQueueUpdateWaitsForFileAccess() = runBlocking {
        val directory = Files.createTempDirectory("responsive-playback-controls")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Local song"), TrackSource("local", SourceKind.LOCAL,
            Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                "Fixture Album", "01 Northern Light.flac").toString()))
        val delayed = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), sourceAccessible = { location ->
            if (delayed.get()) { entered.complete(Unit); release.await(5, TimeUnit.SECONDS) }
            FileAudioAccess.available(location)
        })
        try {
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(track.id, listOf(track.id)).join()
            withTimeout(3_000) { player.state.first { it.playback.playing } }
            delayed.set(true)
            val update = player.enqueue(track.id, QueueAction.APPEND)
            withTimeout(3_000) { entered.await() }
            withTimeout(250) { player.pause().join() }
            assertEquals(false, player.state.value.playback.playWhenReady)
            withTimeout(250) { player.togglePlayback().join() }
            assertEquals(true, player.state.value.playback.playWhenReady)
            withTimeout(250) { player.togglePlayback().join() }
            assertEquals(false, player.state.value.playback.playWhenReady)
            release.countDown()
            update.join()
            assertEquals(false, player.engine.status.value.playWhenReady)
        } finally { release.countDown(); player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun startingASongDoesNotWaitForAnUnrelatedQueuedFileProvider() = runBlocking {
        val directory = Files.createTempDirectory("responsive-queue-start")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val slowFile = Files.copy(fixture, directory.resolve("slow-provider.flac"))
        val first = library.importTrack(TrackMetadata("Play immediately"), TrackSource("first", SourceKind.LOCAL, fixture.toString()))
        val later = library.importTrack(TrackMetadata("Provider responds later"), TrackSource("later", SourceKind.LOCAL, slowFile.toString()))
        val providerReady = CountDownLatch(1)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), sourceAccessible = { path ->
            if (path == slowFile.toString()) providerReady.await(10, TimeUnit.SECONDS)
            Files.isReadable(Path.of(path))
        })
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(first.id, listOf(first.id, later.id))
            val playing = withTimeout(2_000) { controller.state.first { it.playback.trackId == first.id && it.playback.playing } }
            assertEquals(listOf(first.id, later.id), playing.queue.entries.map { it.trackId })
        } finally { providerReady.countDown(); controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun remainingSongRepeatsSurviveRestartAndFinishBeforeTheQueueContinues() = runBlocking {
        val directory = Files.createTempDirectory("repeat-count-restart")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var controller: MusicController? = null
        try {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Repeated song"), TrackSource("local", SourceKind.LOCAL,
                Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                    "Fixture Album", "01 Northern Light.flac").toString()))
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(track.id, listOf(track.id, track.id)).join()
            player.pause().join()
            player.setQueueRepeat(RepeatMode.ONE, playCount = 3).join()
            player.seek(2_000).join()
            player.togglePlayback().join()
            withTimeout(3_000) { player.state.first { it.playback.playing && it.playback.positionMs >= 1_950 } }
            withTimeout(4_000) { player.state.first { it.playback.playing && it.playback.positionMs in 50..500 && it.queue.repeatPlays == 2 } }
            player.pause().join()
            player.closeAndJoin()
            val before = player.queue.snapshot()
            store.close()
            store = openMusicStore(database)
            val restored = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
            controller = restored
            val after = withTimeout(3_000) { restored.state.first { it.playback.durationMs == 3_000L } }
            assertEquals(RepeatMode.ONE to 2, after.queue.repeatMode to after.queue.repeatPlays)
            assertEquals(before.entries, after.queue.entries)
            assertEquals(before.currentEntryId, after.playback.entryId)
            assertEquals(false, after.playback.playing)
            restored.seek(2_000).join()
            restored.togglePlayback().join()
            withTimeout(4_000) { restored.state.first { it.queue.repeatPlays == 1 && it.playback.playing && it.playback.positionMs in 50..500 } }
            restored.seek(2_000).join()
            val next = withTimeout(4_000) { restored.state.first { it.playback.entryId == before.entries[1].id && it.playback.playing } }
            assertEquals(RepeatMode.OFF to null, next.queue.repeatMode to next.queue.repeatPlays)
        } finally { controller?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playbackCanBePausedWhileTheServerCatalogueIsWaitingForANetworkResponse() = runBlocking {
        val directory = Files.createTempDirectory("playback-during-server-refresh")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val refreshing = AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            if (refreshing.get()) { entered.complete(Unit); release.await() }
            respond("""{"subsonic-response":{"status":"ok","searchResult3":{}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val connection = NavidromeConnection(store, http, SecretServiceVault("music-player-test-${UUID.randomUUID()}"))
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Local song"), TrackSource("local", SourceKind.LOCAL,
            Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                "Fixture Album", "01 Northern Light.flac").toString()))
        var controller: MusicController? = null
        try {
            connection.connect("https://music.example", ServerCredentials("test", "test"))
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection)
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(track.id, listOf(track.id)).join()
            withTimeout(3_000) { player.state.first { it.playback.playing } }
            refreshing.set(true)
            val refresh = player.refreshServer()
            withTimeout(3_000) { entered.await() }
            withTimeout(1_000) { player.pause().join() }
            assertEquals(false, player.engine.status.value.playWhenReady)
            release.complete(Unit)
            refresh.join()
            assertEquals(track.id, player.state.value.queue.currentTrackId)
        } finally {
            release.complete(Unit); controller?.closeAndJoin(); connection.disconnect(); http.close(); store.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun delayedFileAccessDoesNotLetAnOldProgressEventUndoRapidQueueSelections() = runBlocking {
        val directory = Files.createTempDirectory("stale-playback-progress")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()).associateBy { it.metadata.title }
        val shouldDelay = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), sourceAccessible = { path ->
            if (shouldDelay.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "File probe was not released" }
            }
            FileAudioAccess.available(path)
        })
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            val first = tracks.getValue("Northern Light").id
            val second = tracks.getValue("Long Way Home").id
            val third = tracks.getValue("Night Walk").id
            controller.playTrack(first, listOf(first, second, third)).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.positionMs >= 100 } }
            shouldDelay.set(true)
            val next = controller.skip(1)
            kotlin.test.assertTrue(entered.await(3, TimeUnit.SECONDS), "File probe did not start")
            delay(150)
            release.countDown()
            next.join()
            controller.skip(1).join()
            val selected = withTimeout(3_000) { controller.state.first { it.playback.trackId == third && it.playback.playing } }
            assertEquals(2, selected.queue.currentIndex)
        } finally { release.countDown(); controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun twoRapidPlaybackTogglesResumeAudioWithoutWaitingForAProgressTick() = runBlocking {
        val directory = Files.createTempDirectory("rapid-playback-controls")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            val track = tracks.first { it.metadata.title == "Northern Light" }
            controller.playTrack(track.id, listOf(track.id)).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.positionMs in 100..200 } }
            controller.togglePlayback().join()
            controller.togglePlayback().join()
            val resumed = withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.positionMs >= 400 } }
            assertEquals(track.id, resumed.playback.trackId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pressingPlayAfterTheQueueFinishesRestartsTheCurrentOccurrenceAtTheBeginning() = runBlocking {
        val directory = Files.createTempDirectory("queue-restart-ended")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            val track = tracks.first { it.metadata.title == "Northern Light" }
            controller.playTrack(track.id, listOf(track.id)).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            controller.seek(2_700).join()
            val finished = withTimeout(3_000) { controller.state.first { it.playback.ended } }
            controller.togglePlayback().join()
            val restarted = withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.positionMs < 500 } }
            assertEquals(finished.queue.currentEntryId, restarted.playback.entryId)
            assertEquals(track.id, restarted.playback.trackId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun closingWhilePlayingSavesTheCurrentPlayheadBeforeTheDatabaseIsClosed() = runBlocking {
        val directory = Files.createTempDirectory("playing-close")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var controller: MusicController? = null
        try {
            val library = MusicLibrary(store)
            val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
                ".test-environment", "music").toString())
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            val track = tracks.first { it.metadata.title == "Northern Light" }
            player.playTrack(track.id, listOf(track.id)).join()
            withTimeout(3_000) { player.state.first { it.playback.playing && it.playback.positionMs in 400..700 } }
            player.closeAndJoin()
            val before = player.queue.snapshot()
            kotlin.test.assertTrue(before.positionMs in 400..900, "Saved playhead: $before")
            store.close()
            store = openMusicStore(database)
            val restored = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
            controller = restored
            val after = withTimeout(3_000) { restored.state.first { it.playback.durationMs > 0 } }
            assertEquals(before.positionMs, after.playback.positionMs)
            assertEquals(before.currentEntryId, after.playback.entryId)
            assertEquals(false, after.playback.playing)
        } finally { controller?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun skippingWhilePausedSelectsTheNextSongWithoutStartingPlayback() = runBlocking {
        val directory = Files.createTempDirectory("paused-skip")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()).associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            val first = tracks.getValue("Northern Light").id
            val next = tracks.getValue("Night Walk").id
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(first, listOf(first, next)).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            controller.togglePlayback().join()
            withTimeout(3_000) { controller.state.first { !it.playback.playing } }
            controller.skip(1).join()
            val selected = withTimeout(3_000) { controller.state.first { it.playback.trackId == next && it.playback.durationMs > 0 } }
            assertEquals(false, selected.playback.playing)
            assertEquals(next, selected.queue.currentTrackId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLongRunOfUnplayableSongsDoesNotHideTheSongsAfterItFromTheDecoder() = runBlocking {
        val directory = Files.createTempDirectory("long-unplayable-run")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val path = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val current = library.importTrack(TrackMetadata("Playing"), TrackSource("local:playing", SourceKind.LOCAL, path))
        // More unplayable songs than the decoder's window holds, followed by one that plays.
        val missing = (1..150).map { library.importTrack(TrackMetadata("Missing $it"), TrackSource("local:missing:$it", SourceKind.LOCAL,
            directory.resolve("missing-$it.flac").toString())) }
        library.setSourceAvailability(missing.flatMap { track -> track.sources.map { it.id } }.toSet(), false)
        val after = library.importTrack(TrackMetadata("After the gap"), TrackSource("local:after", SourceKind.LOCAL, path))
        val decoder = MpvPlaybackEngine("null")
        val handed = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val engine = object : PlaybackEngine by decoder {
            override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
                handed += tracks.map { it.id }; decoder.loadQueue(tracks, startIndex, positionMs, play)
            }
            override fun updateQueue(tracks: List<PlayableTrack>) { handed += tracks.map { it.id }; decoder.updateQueue(tracks) }
        }
        val controller = MusicController(library, PlaybackQueue(store), engine)
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(current.id, listOf(current.id) + missing.map { it.id } + after.id).join()
            withTimeout(3_000) { controller.state.first { it.playback.trackId == current.id } }
            assertEquals(listOf(current.id, after.id), handed.last())
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun addingASongKeepsThePlayingSongInTheDecoderWhileItsFileIsBrieflyUnreachable() = runBlocking {
        val directory = Files.createTempDirectory("unreachable-current-song")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val path = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val songs = listOf("Playing", "Added").map { library.importTrack(TrackMetadata(it), TrackSource("local:$it", SourceKind.LOCAL, path)) }
        val reachable = AtomicBoolean(true)
        val decoder = MpvPlaybackEngine("null")
        val handed = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val engine = object : PlaybackEngine by decoder {
            override fun updateQueue(tracks: List<PlayableTrack>) { handed += tracks.map { it.id }; decoder.updateQueue(tracks) }
        }
        val controller = MusicController(library, PlaybackQueue(store), engine, sourceAccessible = { reachable.get() && FileAudioAccess.available(it) })
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(songs[0].id, listOf(songs[0].id)).join()
            withTimeout(3_000) { controller.state.first { it.playback.trackId == songs[0].id && it.playback.durationMs > 0 } }
            // A network share that stops answering for a moment must not take the playing song out of the decoder.
            reachable.set(false)
            controller.enqueue(songs[1].id, QueueAction.APPEND).join()
            assertEquals(listOf(songs[0].id, songs[1].id), handed.last())
            assertEquals(songs[0].id, controller.state.value.playback.trackId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun repeatAllKeepsCyclingALongQueueWhoseFewPlayableSongsFitTheDecoder() = runBlocking {
        val directory = Files.createTempDirectory("repeat-sparse-long-queue")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val path = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val playable = (1..3).map { library.importTrack(TrackMetadata("Song $it"), TrackSource("local:song:$it", SourceKind.LOCAL, path)) }
        val missing = (1..150).map { library.importTrack(TrackMetadata("Missing $it"), TrackSource("local:missing:$it", SourceKind.LOCAL,
            directory.resolve("missing-$it.flac").toString())) }
        library.setSourceAvailability(missing.flatMap { track -> track.sources.map { it.id } }.toSet(), false)
        val decoder = MpvPlaybackEngine("null")
        val handed = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
        val repeats = java.util.concurrent.CopyOnWriteArrayList<RepeatMode>()
        val engine = object : PlaybackEngine by decoder {
            override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
                handed += tracks.map { it.id }; decoder.loadQueue(tracks, startIndex, positionMs, play)
            }
            override fun updateQueue(tracks: List<PlayableTrack>) { handed += tracks.map { it.id }; decoder.updateQueue(tracks) }
            override fun setRepeat(mode: RepeatMode, playCount: Int?) { repeats += mode; decoder.setRepeat(mode, playCount) }
        }
        val controller = MusicController(library, PlaybackQueue(store), engine)
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            // The middle song plays first, so a window starting at it would leave the first song only behind the decoder.
            val ids = listOf(playable[0].id) + missing.map { it.id } + playable[1].id + playable[2].id
            controller.playTrack(playable[1].id, ids).join()
            withTimeout(3_000) { controller.state.first { it.playback.trackId == playable[1].id } }
            controller.setQueueRepeat(RepeatMode.ALL).join()
            assertEquals(playable.map { it.id }, handed.last(), "Every playable song stays in queue order")
            assertEquals(RepeatMode.ALL, repeats.last(), "The decoder repeats the whole cycle itself")
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun skippingPassesOverSongsWithoutAnAvailableSource() = runBlocking {
        val directory = Files.createTempDirectory("skip-unavailable")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()).associateBy { it.metadata.title }
        val missing = library.importTrack(TrackMetadata("Removed file"), TrackSource("local:missing", SourceKind.LOCAL,
            directory.resolve("missing.flac").toString()))
        library.setSourceAvailability(setOf("local:missing"), false)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            val first = tracks.getValue("Northern Light").id
            val last = tracks.getValue("Night Walk").id
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(first, listOf(first, missing.id, last)).join()
            withTimeout(3_000) { controller.state.first { it.playback.trackId == first && it.playback.playing } }
            controller.skip(1).join()
            val skipped = withTimeout(3_000) { controller.state.first { it.playback.trackId == last && it.playback.durationMs > 0 } }
            assertEquals(last, skipped.queue.currentTrackId)
            assertEquals(null, skipped.error)
            controller.skip(-1).join()
            val back = withTimeout(3_000) { controller.state.first { it.playback.trackId == first && it.playback.durationMs > 0 } }
            assertEquals(first, back.queue.currentTrackId)
            // With repeat all and nothing playable, skipping must not select an entry that cannot play.
            library.setSourceAvailability(tracks.values.flatMap { track -> track.sources.map { it.id } }.toSet(), false)
            controller.setQueueRepeat(me.d3sox.nevumi.playback.RepeatMode.ALL).join()
            controller.skip(1).join()
            val stuck = withTimeout(3_000) { controller.state.first { it.error != null } }
            assertEquals("No upcoming song has an available audio source", stuck.error)
            assertEquals(first, stuck.queue.currentTrackId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun evictingAFutureQueuedDownloadFallsBackToStreamingWithoutInterruptingTheCurrentTrack() = runBlocking {
        val directory = Files.createTempDirectory("queue-cache-fallback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val connection = NavidromeConnection(store, http, SecretServiceVault("music-player-test-${UUID.randomUUID()}"))
        var controller: MusicController? = null
        var offline: OfflineMusic? = null
        try {
            connection.connect(System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val client = connection.client()!!
            val song = client.songs().first { it.metadata.title == "Northern Light" }
            val remote = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val local = library.importTrack(TrackMetadata("Night Walk"), TrackSource("local", SourceKind.LOCAL,
                Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "03 Night Walk.wav").toString()))
            val cache = OfflineMusic(store, library, connection::client, directory.resolve("audio").toString(), Playlists(store))
            offline = cache
            cache.request(listOf(remote.id), owner = null)
            withTimeout(5_000) { cache.state.first { it[remote.id]?.status == DownloadStatus.COMPLETE } }
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection, offline = cache)
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(local.id, listOf(local.id, remote.id)).join()
            withTimeout(3_000) { player.state.first { it.playback.trackId == local.id && it.playback.playing } }
            player.togglePlayback().join()
            withTimeout(3_000) { player.state.first { !it.playback.playing } }
            cache.enforceCacheBudget(0)
            withTimeout(3_000) { player.state.first { remote.id !in it.downloads } }
            assertEquals(local.id, player.state.value.playback.trackId)
            player.togglePlayback().join()
            val advanced = withTimeout(5_000) { player.state.first { it.playback.trackId == remote.id && it.playback.playing } }
            assertEquals(remote.id, advanced.queue.currentTrackId)
        } finally {
            controller?.closeAndJoin(); offline?.closeAndJoin(); connection.disconnect(); http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun playingAServerSongKeepsItsFullAudioInTheRollingCacheAfterSkipping() = runBlocking {
        val directory = Files.createTempDirectory("playback-cache")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val connection = NavidromeConnection(store, http, SecretServiceVault("music-player-test-${UUID.randomUUID()}"))
        var controller: MusicController? = null
        var offline: OfflineMusic? = null
        try {
            connection.connect(System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
            val client = connection.client()!!
            val song = client.songs().first { it.metadata.title == "Long Way Home" }
            val remote = library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            val local = library.importTrack(TrackMetadata("Night Walk"), TrackSource("local", SourceKind.LOCAL,
                Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "03 Night Walk.wav").toString()))
            val cache = OfflineMusic(store, library, connection::client, directory.resolve("audio").toString(), Playlists(store))
            offline = cache
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection, offline = cache)
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(remote.id).join()
            withTimeout(5_000) { player.state.first { it.playback.playing && it.playback.trackId == remote.id } }
            player.skip(1).join()
            withTimeout(3_000) { player.state.first { it.playback.playing && it.playback.trackId == local.id } }
            val downloaded = withTimeout(5_000) { cache.state.first { it[remote.id]?.status == DownloadStatus.COMPLETE } }.getValue(remote.id)
            assertEquals(song.sizeBytes, Files.size(Path.of(downloaded.path!!)))
            cache.enforceCacheBudget(0)
            assertEquals(false, library.findTrack(remote.id)!!.sources.any { it.kind == SourceKind.DOWNLOAD })
        } finally { controller?.closeAndJoin(); offline?.closeAndJoin(); connection.disconnect(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun reopeningTheAppRestoresTheSameQueueOccurrenceAndPausedPlayhead() = runBlocking {
        val directory = Files.createTempDirectory("playback-recovery")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var controller: MusicController? = null
        try {
            val library = MusicLibrary(store)
            val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
                ".test-environment", "music").toString())
            val first = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
            controller = first
            withTimeout(3_000) { first.state.first { !it.loading } }
            first.playTrack(tracks.first { it.metadata.title == "Northern Light" }.id).join()
            withTimeout(3_000) { first.state.first { it.playback.playing } }
            first.togglePlayback().join()
            first.seek(1_400).join()
            val before = withTimeout(3_000) { first.state.first {
                !it.playback.playing && it.queue.positionMs in 1_390..1_410
            } }.queue
            first.close(); store.close()
            store = openMusicStore(database)
            val reopened = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
            controller = reopened
            val after = try { withTimeout(3_000) { reopened.state.first {
                it.playback.durationMs > 0 && it.playback.positionMs in 1_390..1_410
            } } } catch (error: Exception) {
                throw AssertionError("Restored playhead: ${reopened.state.value.playback}; saved: $before", error)
            }
            assertEquals(before.entries to before.currentEntryId, after.queue.entries to after.queue.currentEntryId)
            assertEquals(false, after.playback.playing)
        } finally { controller?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playNextChangesTheLivePlaylistAndPersistsTheAutomaticTransition() = runBlocking {
        val directory = Files.createTempDirectory("queue-playback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()).associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(tracks.getValue("Long Way Home").id).join()
            controller.enqueue(tracks.getValue("Northern Light").id, QueueAction.PLAY_NEXT).join()
            val advanced = withTimeout(5_000) { controller.state.first {
                it.playback.trackId == tracks.getValue("Northern Light").id && it.playback.playing
            } }
            assertEquals(tracks.getValue("Northern Light").id, advanced.queue.currentTrackId)
            assertEquals(advanced.queue.currentEntryId, controller.queue.snapshot().currentEntryId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
