package me.d3sox.nevumi.library

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.security.SecretServiceVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.UUID

class SourcePlaybackTest {
    @Test
    fun delayedLibraryUpdatesDoNotUndoNewFavoritesAndRatings(): Unit = runBlocking {
        val directory = Files.createTempDirectory("delayed-library-feedback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
            "Fixture Album", "01 Northern Light.flac")
        val track = library.importTrack(TrackMetadata("Playing song"), TrackSource("local", SourceKind.LOCAL, audio.toString()))
        val other = library.importTrack(TrackMetadata("Another song"), TrackSource("other", SourceKind.LOCAL, audio.toString()))
        val recoveryStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRecovery = kotlinx.coroutines.CompletableDeferred<Unit>()
        val files = LocalFileEdits(store, library, directory.resolve("backups").toString())
        val slowFiles = object : MusicFileEdits by files {
            override suspend fun recover() {
                recoveryStarted.complete(Unit)
                releaseRecovery.await()
            }
        }
        val loadingStarted = java.util.concurrent.CountDownLatch(1)
        val releaseLoading = java.util.concurrent.CountDownLatch(1)
        val decoder = MpvPlaybackEngine("null")
        val engine = object : me.d3sox.nevumi.playback.PlaybackEngine by decoder {
            override fun loadQueue(tracks: List<me.d3sox.nevumi.playback.PlayableTrack>, startIndex: Int,
                positionMs: Long, play: Boolean) {
                loadingStarted.countDown()
                check(releaseLoading.await(3, java.util.concurrent.TimeUnit.SECONDS))
                decoder.loadQueue(tracks, startIndex, positionMs, play)
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), engine, fileEdits = slowFiles)
        try {
            withTimeout(3_000) { recoveryStarted.await() }
            val playing = controller.playTrack(track.id, listOf(track.id))
            check(loadingStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val favorite = controller.setFavorite(track.id, true)
            val rating = controller.setRating(track.id, 4)
            // Queue an observer snapshot behind the edits while the audio load holds the controller lock.
            library.importTrack(other.metadata.copy(title = "Updated song"), other.sources.single(), linkTo = other.id)
            kotlinx.coroutines.delay(300)
            val drained = controller.setRating(other.id, 1)
            releaseLoading.countDown()
            playing.join(); favorite.join(); rating.join(); drained.join()
            assertEquals(true, controller.state.value.tracks.first { it.id == track.id }.favorite)
            assertEquals(4, controller.state.value.tracks.first { it.id == track.id }.rating)
            assertEquals("Updated song", controller.state.value.tracks.first { it.id == other.id }.metadata.title)
        } finally {
            releaseLoading.countDown(); releaseRecovery.complete(Unit)
            controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun startingMusicWhileFileRecoveryFinishesDoesNotReloadItPaused(): Unit = runBlocking {
        val directory = Files.createTempDirectory("startup-playback-order")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
            "Fixture Album", "01 Northern Light.flac")
        val track = library.importTrack(TrackMetadata("Startup song"), TrackSource("local", SourceKind.LOCAL, audio.toString()))
        val recoveryStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val releaseRecovery = kotlinx.coroutines.CompletableDeferred<Unit>()
        val startupFinished = kotlinx.coroutines.CompletableDeferred<Unit>()
        val files = LocalFileEdits(store, library, directory.resolve("backups").toString())
        val slowFiles = object : MusicFileEdits by files {
            override suspend fun recover() {
                recoveryStarted.complete(Unit)
                releaseRecovery.await()
            }
            override suspend fun history(): List<LocalFileEdit> {
                startupFinished.complete(Unit)
                return files.history()
            }
        }
        val decoder = MpvPlaybackEngine("null")
        val loads = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val engine = object : me.d3sox.nevumi.playback.PlaybackEngine by decoder {
            override fun loadQueue(tracks: List<me.d3sox.nevumi.playback.PlayableTrack>, startIndex: Int,
                positionMs: Long, play: Boolean) {
                loads += play
                decoder.loadQueue(tracks, startIndex, positionMs, play)
            }
        }
        val sourceChecks = java.util.concurrent.atomic.AtomicInteger()
        val overlappingRestore = java.util.concurrent.CountDownLatch(1)
        val controller = MusicController(library, PlaybackQueue(store), engine, fileEdits = slowFiles,
            sourceAccessible = {
                if (sourceChecks.incrementAndGet() == 1) {
                    releaseRecovery.complete(Unit)
                    // A slow source lookup gives startup recovery time to finish during the play action.
                    overlappingRestore.await(1, java.util.concurrent.TimeUnit.SECONDS)
                } else overlappingRestore.countDown()
                true
            })
        try {
            withTimeout(3_000) { recoveryStarted.await() }
            controller.playTrack(track.id, listOf(track.id)).join()
            withTimeout(3_000) { startupFinished.await() }
            assertEquals(listOf(true), loads, "Startup must not reload the user's new queue paused")
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
        } finally {
            releaseRecovery.complete(Unit)
            overlappingRestore.countDown()
            controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun connectingAnotherServerKeepsOldSongIdsFromReplacingTheLocalCopy() = runBlocking {
        val directory = Files.createTempDirectory("server-source-ownership")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val connection = NavidromeConnection(store, http, SecretServiceVault("music-player-test-${UUID.randomUUID()}"))
        var controller: MusicController? = null
        try {
            connection.connect(System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val client = connection.client()!!
            val song = client.songs().single { it.metadata.title == "Northern Light" }
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                "Fixture Album", "01 Northern Light.flac")
            val local = directory.resolve("local.flac")
            val process = ProcessBuilder("ffmpeg", "-v", "error", "-i", original.toString(), "-t", "1", local.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            check(process.waitFor() == 0) { output }
            val library = MusicLibrary(store)
            val track = library.importTrack(song.metadata, TrackSource("local", SourceKind.LOCAL, local.toString()))
            val previous = NavidromeClient(http, "https://previous.example", ServerCredentials("test", "test"))
            library.importTrack(song.metadata, TrackSource("server:${previous.serverId}:${song.id}", SourceKind.SERVER, song.id), linkTo = track.id)
            val preferences = MusicPreferences(store)
            preferences.update { it.copy(sourcePriority = listOf(SourceKind.SERVER, SourceKind.LOCAL, SourceKind.DOWNLOAD)) }
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection, preferences = preferences)
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(track.id, listOf(track.id)).join()
            val playing = withTimeout(3_000) { player.state.first { it.playback.playing && it.playback.durationMs > 0 } }
            assertEquals("local", playing.playback.sourceId)
            assertEquals(1_000L, playing.playback.durationMs)
        } finally {
            controller?.closeAndJoin(); connection.disconnect(); http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun changingSourcePriorityUpdatesTheNextSongWithoutReplacingThePlayingOccurrence() = runBlocking {
        val directory = Files.createTempDirectory("queued-source-priority")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val full = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
            "Fixture Album", "01 Northern Light.flac")
        val short = directory.resolve("short.flac")
        val process = ProcessBuilder("ffmpeg", "-v", "error", "-i", full.toString(), "-t", "1", short.toString())
            .redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().decodeToString()
        check(process.waitFor() == 0) { output }
        val first = library.importTrack(TrackMetadata("First"), TrackSource("first-local", SourceKind.LOCAL, full.toString()))
        val next = library.importTrack(TrackMetadata("Next"), TrackSource("next-local", SourceKind.LOCAL, short.toString()))
        library.importTrack(next.metadata, TrackSource("next-download", SourceKind.DOWNLOAD, full.toString()), linkTo = next.id)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(first.id, listOf(first.id, next.id)).join()
            val playing = withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.durationMs > 0 } }
            controller.pause().join()
            controller.updatePreferences { it.copy(sourcePriority = listOf(SourceKind.DOWNLOAD, SourceKind.LOCAL, SourceKind.SERVER)) }.join()
            assertEquals(playing.playback.entryId, controller.engine.status.value.entryId)
            assertEquals("first-local", controller.engine.status.value.sourceId)
            controller.seek(2_700).join()
            controller.play().join()
            val advanced = withTimeout(3_000) { controller.state.first { it.playback.trackId == next.id && it.playback.playing && it.playback.durationMs > 0 } }
            assertEquals(3_000L, advanced.playback.durationMs)
            assertEquals("next-download", advanced.playback.sourceId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pressingPlayAfterRepairingAFailedFileRetriesTheSameQueueOccurrence() = runBlocking {
        val directory = Files.createTempDirectory("repair-playback-source")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val path = directory.resolve("repaired.flac")
        Files.writeString(path, "This is not FLAC audio")
        val track = library.importTrack(TrackMetadata("Northern Light"), TrackSource("local", SourceKind.LOCAL, path.toString()))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(track.id, listOf(track.id)).join()
            val failed = withTimeout(3_000) { controller.state.first { it.error != null } }
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                "Fixture Album", "01 Northern Light.flac"), path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            controller.togglePlayback().join()
            val recovered = withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.durationMs == 3_000L } }
            assertEquals(failed.queue.currentEntryId, recovered.playback.entryId)
            assertEquals(track.id, recovered.playback.trackId)
            assertEquals(null, recovered.error)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun streamingAfterALocalDecodeFailureStillCachesTheFullServerSong() = runBlocking {
        val directory = Files.createTempDirectory("stream-fallback-cache")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val connection = NavidromeConnection(store, http, SecretServiceVault("music-player-test-${UUID.randomUUID()}"))
        var controller: MusicController? = null
        var offline: OfflineMusic? = null
        try {
            connection.connect(System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val client = connection.client()!!
            val song = client.songs().first { it.metadata.title == "Northern Light" }
            val library = MusicLibrary(store)
            val corrupt = directory.resolve("corrupt.flac")
            Files.writeString(corrupt, "This is not FLAC audio")
            val original = library.importTrack(song.metadata, TrackSource("local", SourceKind.LOCAL, corrupt.toString()))
            val serverSource = "server:${client.serverId}:${song.id}"
            library.importTrack(song.metadata, TrackSource(serverSource, SourceKind.SERVER, song.id), linkTo = original.id)
            val cache = OfflineMusic(store, library, connection::client, directory.resolve("audio").toString(), Playlists(store))
            offline = cache
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection, offline = cache)
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(original.id, listOf(original.id)).join()
            val streamed = withTimeout(3_000) { player.state.first { it.playback.playing && it.playback.sourceId == serverSource } }
            val downloaded = withTimeout(5_000) { cache.state.first { it[original.id]?.status == DownloadStatus.COMPLETE } }.getValue(original.id)
            assertEquals(song.sizeBytes, Files.size(Path.of(downloaded.path!!)))
            assertEquals(original.id, streamed.playback.trackId)
        } finally {
            controller?.closeAndJoin(); offline?.closeAndJoin(); connection.disconnect(); http.close(); store.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aCorruptLocalFileFallsBackToItsDownloadedCopyWithoutChangingTheQueueOccurrence() = runBlocking {
        val directory = Files.createTempDirectory("corrupt-source-fallback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val corrupt = directory.resolve("corrupt.flac")
        Files.writeString(corrupt, "This is not FLAC audio")
        val original = library.importTrack(TrackMetadata("Northern Light"), TrackSource("local", SourceKind.LOCAL, corrupt.toString()))
        library.importTrack(original.metadata, TrackSource("download", SourceKind.DOWNLOAD,
            Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()), linkTo = original.id)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(original.id, listOf(original.id)).join()
            val entryId = controller.queue.snapshot().currentEntryId
            val playing = withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.durationMs == 3_000L } }
            assertEquals(original.id, playing.playback.trackId)
            assertEquals(entryId, playing.playback.entryId)
            assertEquals(1, playing.queue.entries.size)
            assertEquals(null, playing.error)
            assertEquals(true, library.findTrack(original.id)!!.sources.first { it.kind == SourceKind.LOCAL }.available)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSavedDownloadFirstPriorityOverridesAnAvailableLocalVariant() = runBlocking {
        val directory = Files.createTempDirectory("source-priority")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var controller: MusicController? = null
        try {
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val shortLocal = directory.resolve("short.flac")
            val process = ProcessBuilder("ffmpeg", "-v", "error", "-i", audio.toString(), "-t", "1", shortLocal.toString())
                .redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().decodeToString()
            check(process.waitFor() == 0) { output }
            val library = MusicLibrary(store)
            val original = library.importTrack(TrackMetadata("Northern Light"), TrackSource("local", SourceKind.LOCAL, shortLocal.toString()))
            library.importTrack(original.metadata, TrackSource("download", SourceKind.DOWNLOAD, audio.toString()), linkTo = original.id)
            MusicPreferences(store).update { it.copy(sourcePriority = listOf(SourceKind.DOWNLOAD, SourceKind.LOCAL, SourceKind.SERVER)) }
            store.close()
            store = openMusicStore(database)
            val player = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.playTrack(original.id).join()
            val playing = withTimeout(3_000) { player.state.first { it.playback.playing && it.playback.durationMs > 0 } }
            assertEquals(3_000L, playing.playback.durationMs)
        } finally { controller?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aMissingLocalFileFallsBackToTheDownloadedVariantOfTheSameTrack() = runBlocking {
        val directory = Files.createTempDirectory("source-fallback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val original = library.importTrack(TrackMetadata("Northern Light"),
            TrackSource("local", SourceKind.LOCAL, directory.resolve("missing.flac").toString()))
        library.importTrack(original.metadata, TrackSource("download", SourceKind.DOWNLOAD,
            Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()), linkTo = original.id)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(original.id).join()
            val playing = withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.durationMs == 3_000L } }
            assertEquals(original.id, playing.playback.trackId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
