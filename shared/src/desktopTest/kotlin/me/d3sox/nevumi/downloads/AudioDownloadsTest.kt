package me.d3sox.nevumi.downloads

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.SourceResolver
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.ServerSong
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioDownloadsTest {
    @Test
    fun aDownloadOfUnknownSizeStopsBeforeConsumingTheReservedFreeSpace() = runBlocking {
        val directory = Files.createTempDirectory("unknown-download-size")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val reserved = 64L * 1024 * 1024
        val space = java.util.concurrent.atomic.AtomicLong(reserved + 128)
        val http = HttpClient(MockEngine {
            space.set(reserved + 4)
            respond(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
        })
        try {
            val library = MusicLibrary(store)
            val song = ServerSong("unknown-size", TrackMetadata("Unknown size"), "flac", 0)
            val track = library.importTrack(song.metadata, TrackSource("server:song", SourceKind.SERVER, song.id))
            val client = NavidromeClient(http, "https://music.example", ServerCredentials("fixture", "test"))
            val result = AudioDownloads(store, library, client, directory.resolve("audio").toString(),
                availableSpace = { space.get() }).download(track.id, song)
            assertEquals(DownloadStatus.FAILED to DownloadProblem.INSUFFICIENT_SPACE, result.status to result.problem)
            assertEquals(null, SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false))
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun changingAServerFileRestartsTheDownloadUsingItsHttpValidator() = runBlocking {
        val directory = Files.createTempDirectory("validated-audio-resume")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val oldAudio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val newAudio = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18)
        var attempts = 0
        var resumedValidator: String? = null
        val oldDate = "Sat, 10 Oct 2026 13:45:08 GMT"
        val newDate = "Sat, 10 Oct 2026 14:45:08 GMT"
        val http = HttpClient(MockEngine { request ->
            attempts++
            // The second attempt cannot reach the server, so it learns no validator.
            if (attempts == 2) throw java.net.ConnectException("Connection refused")
            if (attempts == 1) {
                respond(oldAudio.copyOfRange(0, 4), headers = headersOf(HttpHeaders.ContentType to listOf("audio/flac"),
                    HttpHeaders.LastModified to listOf(oldDate), HttpHeaders.Date to listOf("Sat, 10 Oct 2026 13:50:08 GMT")))
            } else {
                resumedValidator = request.headers[HttpHeaders.IfRange]
                if (request.headers[HttpHeaders.Range] == "bytes=4-" && resumedValidator != oldDate)
                    respond(newAudio.copyOfRange(4, 8), HttpStatusCode.PartialContent,
                        headersOf(HttpHeaders.ContentType to listOf("audio/flac"), HttpHeaders.ContentRange to listOf("bytes 4-7/8")))
                else respond(newAudio, headers = headersOf(HttpHeaders.ContentType to listOf("audio/flac"),
                    HttpHeaders.LastModified to listOf(newDate), HttpHeaders.Date to listOf("Sat, 10 Oct 2026 14:50:08 GMT")))
            }
        })
        try {
            val song = ServerSong("song", TrackMetadata("Changed audio"), "flac", 8)
            val library = MusicLibrary(store)
            val track = library.importTrack(song.metadata, TrackSource("server:song", SourceKind.SERVER, song.id))
            val client = NavidromeClient(http, "https://music.example", ServerCredentials("fixture", "test"))
            val downloads = AudioDownloads(store, library, client, directory.resolve("audio").toString())
            assertEquals(DownloadStatus.FAILED, downloads.download(track.id, song).status)
            assertEquals(DownloadStatus.FAILED, downloads.download(track.id, song).status)
            val result = downloads.download(track.id, song)
            assertEquals(DownloadStatus.COMPLETE, result.status)
            assertEquals(oldDate, resumedValidator, "A failed attempt must keep the validator of the partial file")
            assertEquals(newAudio.toList(), File(result.path!!).readBytes().toList())
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun changingTheServerAccountRestartsAPartialDownloadRatherThanMixingItsAudio() = runBlocking {
        val directory = Files.createTempDirectory("source-bound-resume")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val aliceAudio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val bobAudio = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18)
        val bobRanges = mutableListOf<String?>()
        val http = HttpClient(MockEngine { request ->
            if (request.url.parameters["u"] == "alice") {
                respond(aliceAudio.copyOfRange(0, 4), headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
            } else {
                val range = request.headers[HttpHeaders.Range]
                bobRanges += range
                if (range == "bytes=4-") respond(bobAudio.copyOfRange(4, 8), HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentType to listOf("audio/flac"), HttpHeaders.ContentRange to listOf("bytes 4-7/8")))
                else respond(bobAudio, headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
            }
        })
        try {
            val song = ServerSong("same-song-id", TrackMetadata("Different account audio"), "flac", 8)
            val library = MusicLibrary(store)
            val track = library.importTrack(song.metadata, TrackSource("server:alice:song", SourceKind.SERVER, song.id))
            val alice = NavidromeClient(http, "https://music.example", ServerCredentials("alice", "test"))
            val initial = AudioDownloads(store, library, alice, directory.resolve("audio").toString()).download(track.id, song)
            assertEquals(DownloadStatus.FAILED, initial.status)
            store.close(); store = openMusicStore(database)
            val bob = NavidromeClient(http, "https://music.example", ServerCredentials("bob", "test"))
            val result = AudioDownloads(store, MusicLibrary(store), bob, directory.resolve("audio").toString()).download(track.id, song)
            assertEquals(DownloadStatus.COMPLETE, result.status)
            assertEquals(bobAudio.toList(), File(result.path!!).readBytes().toList())
            assertEquals(listOf<String?>(null), bobRanges)
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun linkingAServerCopyKeepsItsDownloadedAudioAndManualProtectionAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("linked-offline-audio")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val audio = File(System.getProperty("nevumi.testRoot"), ".test-environment/music/Fixture Artist/Fixture Album/01 Northern Light.flac").readBytes()
        val http = HttpClient(MockEngine { respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/flac")) })
        val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))
        try {
            val library = MusicLibrary(store)
            val metadata = TrackMetadata("Northern Light")
            val local = library.importTrack(metadata, TrackSource("local:song", SourceKind.LOCAL, "/music/song.flac", available = false))
            val server = library.importTrack(metadata, TrackSource("server:song", SourceKind.SERVER, "song"))
            val downloads = AudioDownloads(store, library, client, directory.resolve("audio").toString())
            downloads.download(server.id, ServerSong("song", metadata, "flac", audio.size.toLong()))
            downloads.pin(server.id, "manual")
            library.linkTracks(local.id, server.id)
            store.close(); store = openMusicStore(database)
            val reopened = MusicLibrary(store)
            val restored = AudioDownloads(store, reopened, client, directory.resolve("audio").toString())
            restored.enforceCacheBudget(0)
            val record = kotlin.test.assertNotNull(restored.state(local.id))
            assertEquals(DownloadStatus.COMPLETE, record.status)
            assertEquals(null, restored.state(server.id))
            kotlin.test.assertTrue(File(record.path!!).readBytes().contentEquals(audio))
            val offline = kotlin.test.assertNotNull(SourceResolver.resolve(reopened.tracks().single().sources, online = false))
            assertEquals("download:${local.id}", offline.id)
            assertEquals(record.path, offline.location)
            restored.remove(local.id)
            assertEquals(null, SourceResolver.resolve(reopened.tracks().single().sources, online = false))
            assertEquals(false, File(record.path).exists())
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun filesNoDownloadUsesAreDeletedWhileResumableOnesStay() = runBlocking {
        val directory = Files.createTempDirectory("unused-download-files")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val audio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val http = HttpClient(MockEngine { request ->
            val bytes = if (request.url.parameters["id"] == "partial") audio.copyOfRange(0, 4) else audio
            respond(bytes, headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
        })
        try {
            val library = MusicLibrary(store)
            val client = NavidromeClient(http, "https://music.example", ServerCredentials("fixture", "test"))
            val root = directory.resolve("audio")
            val downloads = AudioDownloads(store, library, client, root.toString())
            suspend fun download(id: String): DownloadRecord {
                val song = ServerSong(id, TrackMetadata(id), "flac", audio.size.toLong())
                val track = library.importTrack(song.metadata, TrackSource("server:$id", SourceKind.SERVER, id))
                return downloads.download(track.id, song)
            }
            val kept = download("kept")
            val linked = download("linked")
            assertEquals(DownloadStatus.FAILED, download("partial").status)
            val resumable = root.toFile().listFiles()!!.single { it.name.endsWith(".part") }
            val abandoned = root.resolve("abandoned.flac.part").toFile().apply { writeBytes(audio) }
            // Linking two downloaded songs keeps one download; the other's audio is no longer used.
            library.linkTracks(kept.trackId, linked.trackId)
            downloads.removeUnusedFiles()
            kotlin.test.assertTrue(File(kept.path!!).exists())
            kotlin.test.assertFalse(File(linked.path!!).exists())
            kotlin.test.assertTrue(resumable.exists(), "A failed download keeps its partial file to resume")
            kotlin.test.assertFalse(abandoned.exists())
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun insufficientStorageExplainsTheFailureWithoutPublishingAnOfflineAudioSource() = runBlocking {
        val directory = Files.createTempDirectory("download-space")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { respond(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            headers = headersOf(HttpHeaders.ContentType, "audio/flac")) })
        try {
            val library = MusicLibrary(store)
            val song = ServerSong("song", TrackMetadata("Storage song"), "flac", 8)
            val track = library.importTrack(song.metadata, TrackSource("server:song", SourceKind.SERVER, song.id))
            val downloads = AudioDownloads(store, library,
                NavidromeClient(http, "https://music.example", ServerCredentials("test", "test")), directory.resolve("audio").toString(),
                availableSpace = { 4L })
            val result = downloads.download(track.id, song)
            assertEquals(DownloadStatus.FAILED to DownloadProblem.INSUFFICIENT_SPACE, result.status to result.problem)
            assertEquals(null, SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false))
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun rollingCacheEvictsTheLeastRecentlyPlayedTrackAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("cache-recency")
        val path = directory.resolve("library.db").toString()
        var store = openMusicStore(path)
        val audio = byteArrayOf(1, 2, 3, 4)
        val http = HttpClient(MockEngine { respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/flac")) })
        val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))
        try {
            var library = MusicLibrary(store)
            var downloads = AudioDownloads(store, library, client, directory.resolve("audio").toString())
            val ids = mutableMapOf<String, String>()
            // markUsed must make a-replayed newer than b-old, independent of download timing.
            for ((index, id) in listOf("a-replayed", "b-old").withIndex()) {
                val song = ServerSong(id, TrackMetadata(id), "flac", 4)
                val track = library.importTrack(song.metadata, TrackSource(id, SourceKind.SERVER, id))
                ids[id] = track.id
                val record = downloads.download(track.id, song)
                store.put("download:${track.id}", "downloads", record.copy(lastUsedAt = index.toLong()))
            }
            downloads.markUsed(ids.getValue("a-replayed"))
            val newest = ServerSong("c-new", TrackMetadata("c-new"), "flac", 4)
            val newTrack = library.importTrack(newest.metadata, TrackSource(newest.id, SourceKind.SERVER, newest.id))
            downloads.download(newTrack.id, newest)
            store.close()
            store = openMusicStore(path)
            library = MusicLibrary(store)
            downloads = AudioDownloads(store, library, client, directory.resolve("audio").toString())
            downloads.enforceCacheBudget(8)
            assertEquals(listOf("a-replayed", "c-new"), library.tracks().filter {
                SourceResolver.resolve(it.sources, online = false) != null
            }.map { it.metadata.title }.sorted())
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun cacheEvictionKeepsAManualDownloadWhenItsPlaylistProtectionIsRemoved() = runBlocking {
        val directory = Files.createTempDirectory("audio-cache-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val audio = File(System.getProperty("nevumi.testRoot"),
            ".test-environment/music/Fixture Artist/Fixture Album/01 Northern Light.flac").readBytes()
        val http = HttpClient(MockEngine { respond(audio, headers = headersOf(HttpHeaders.ContentType, "audio/flac")) })
        try {
            val library = MusicLibrary(store)
            val downloads = AudioDownloads(store, library,
                NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test")),
                directory.resolve("audio").toString())
            val ids = mutableMapOf<String, String>()
            for (id in listOf("protected", "rolling-cache")) {
                val song = ServerSong(id, TrackMetadata(id), "flac", audio.size.toLong())
                val track = library.importTrack(song.metadata, TrackSource(id, SourceKind.SERVER, id))
                ids[id] = track.id
                downloads.download(track.id, song)
            }
            downloads.pin(ids.getValue("protected"), "manual")
            downloads.pin(ids.getValue("protected"), "playlist:evening")
            downloads.unpinOwner("playlist:evening")
            downloads.enforceCacheBudget(0)

            assertEquals(listOf("protected"), library.tracks().filter {
                SourceResolver.resolve(it.sources, online = false) != null
            }.map { it.metadata.title })
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aServerThatIgnoresResumeRestartsTheFileWithoutDuplicatingAudio() = runBlocking {
        val directory = Files.createTempDirectory("audio-download-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val audio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        var first = true
        val http = HttpClient(MockEngine {
            val bytes = if (first) audio.copyOfRange(0, 4) else audio
            first = false
            respond(bytes, headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
        })
        try {
            val song = ServerSong("opaque-id", TrackMetadata("Northern Light"), "flac", 8)
            val library = MusicLibrary(store)
            val track = library.importTrack(song.metadata,
                TrackSource("server:opaque-id", SourceKind.SERVER, song.id))
            val downloads = AudioDownloads(store, library,
                NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test")),
                directory.resolve("audio").toString())
            downloads.download(track.id, song)
            val result = downloads.download(track.id, song)

            assertEquals(DownloadStatus.COMPLETE to audio.toList(),
                result.status to result.path?.let { File(it).readBytes().toList() })
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aCompletedDownloadRemainsPlayableOfflineAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("audio-download-test")
        val path = directory.resolve("library.db").toString()
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val song = client.songs().single { it.metadata.title == "Northern Light" }
            val store = openMusicStore(path)
            val library = MusicLibrary(store)
            val track = library.importTrack(song.metadata,
                TrackSource("server:${song.id}", SourceKind.SERVER, song.id))
            AudioDownloads(store, library, client, directory.resolve("audio").toString()).download(track.id, song)
            store.close()

            val restored = openMusicStore(path)
            try {
                val sources = requireNotNull(MusicLibrary(restored).findTrack(track.id)).sources
                val offline = SourceResolver.resolve(sources, online = false)
                assertEquals(song.sizeBytes, offline?.location?.let { File(it).takeIf(File::isFile)?.length() })
            } finally { restored.close() }
        } finally { http.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun cancellingATransferPersistsAPausedState() = runBlocking {
        val directory = Files.createTempDirectory("audio-download-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val requested = CompletableDeferred<Unit>()
        val body = ByteChannel(autoFlush = true)
        body.writeFully(ByteArray(64))
        val http = HttpClient(MockEngine {
            requested.complete(Unit)
            respond(body, headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
        })
        try {
            val library = MusicLibrary(store)
            val song = ServerSong("opaque-id", TrackMetadata("Northern Light"), "flac", 100_000)
            val track = library.importTrack(song.metadata,
                TrackSource("server:opaque-id", SourceKind.SERVER, song.id))
            val downloads = AudioDownloads(store, library,
                NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test")),
                directory.resolve("audio").toString())
            val transfer = launch { downloads.download(track.id, song) }
            requested.await()
            kotlinx.coroutines.withTimeout(2_000) {
                while (downloads.state(track.id)?.bytesDownloaded != 64L) kotlinx.coroutines.delay(10)
            }
            transfer.cancelAndJoin()

            assertEquals(DownloadStatus.PAUSED to 64L, downloads.state(track.id)?.let { it.status to it.bytesDownloaded })
        } finally {
            body.cancel(kotlinx.coroutines.CancellationException("Test complete"))
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun anInterruptedDownloadResumesAfterTheLibraryIsReopened() = runBlocking {
        val directory = Files.createTempDirectory("audio-download-test")
        val path = directory.resolve("library.db").toString()
        val audio = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        var firstRequest = true
        val http = HttpClient(MockEngine { request ->
            if (firstRequest) {
                firstRequest = false
                respond(audio.copyOfRange(0, 4), headers = headersOf(HttpHeaders.ContentType to listOf("audio/flac"), HttpHeaders.ETag to listOf("\"audio-v1\"")))
            } else if (request.headers[HttpHeaders.Range] == "bytes=4-") {
                respond(audio.copyOfRange(4, 8), HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentType to listOf("audio/flac"),
                        HttpHeaders.ContentRange to listOf("bytes 4-7/8"), HttpHeaders.ETag to listOf("\"audio-v1\"")))
            } else {
                respond("Range required", HttpStatusCode.RequestedRangeNotSatisfiable)
            }
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))
            val song = ServerSong("opaque-id", TrackMetadata("Northern Light"), "flac", 8)
            val store = openMusicStore(path)
            val library = MusicLibrary(store)
            val track = library.importTrack(song.metadata,
                TrackSource("server:opaque-id", SourceKind.SERVER, song.id))
            AudioDownloads(store, library, client, directory.resolve("audio").toString()).download(track.id, song)
            store.close()

            val restored = openMusicStore(path)
            try {
                val result = AudioDownloads(restored, MusicLibrary(restored), client,
                    directory.resolve("audio").toString()).download(track.id, song)

                assertEquals(DownloadStatus.COMPLETE to audio.toList(),
                    result.status to result.path?.let { File(it).readBytes().toList() })
            } finally { restored.close() }
        } finally { http.close(); directory.toFile().deleteRecursively() }
    }
}
