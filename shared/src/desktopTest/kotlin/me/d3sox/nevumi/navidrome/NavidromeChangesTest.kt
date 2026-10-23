package me.d3sox.nevumi.navidrome

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import java.nio.file.Files
import kotlin.test.*
import me.d3sox.nevumi.playlists.Playlists
import kotlin.uuid.Uuid
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

class NavidromeChangesTest {
    @Test
    fun pullingPlaylistsImportsServerEditsAndDownloadsCoversOnlyWhenAPlaylistChanged() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-pull-playlists")
        val store = openMusicStore(directory.resolve("library.db").toString())
        fun png(rgb: Int) = java.io.ByteArrayOutputStream().also { output ->
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
                for (x in 0..7) for (y in 0..7) setRGB(x, y, rgb) }, "png", output)
        }.toByteArray()
        var name = "Mix"
        var changed = "t1"
        var cover = png(0x00FF00)
        var playlistRequests = 0
        var coverRequests = 0
        var coverFails = false
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/rest/getPlaylists.view" -> respond("""{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"mix","changed":"$changed"}]}}}""")
                "/rest/getPlaylist.view" -> { playlistRequests++; respond("""{"subsonic-response":{"status":"ok","playlist":{"id":"mix","name":"$name",""" +
                    """"owner":"fixture","changed":"$changed","coverArt":"pl-mix","entry":[{"id":"song"}]}}}""") }
                "/rest/getCoverArt.view" -> if (coverFails) respond("broken", io.ktor.http.HttpStatusCode.InternalServerError) else { coverRequests++; respond(cover, headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "image/png")) }
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
        })
        try {
            val library = MusicLibrary(store)
            val artwork = me.d3sox.nevumi.artwork.ArtworkLibrary(directory.resolve("artwork").toString())
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "test"))
            val changes = NavidromeChanges(store, library, artwork) { client }
            val track = changes.importSong(client, ServerSong("song", TrackMetadata("Server song")))
            changes.pullPlaylists()
            val playlist = Playlists(store).all().single()
            val key = "server-playlist-cover:${playlist.id}"
            assertEquals(listOf(track.id), playlist.trackIds)
            assertContentEquals(me.d3sox.nevumi.artwork.artworkPreview(cover), artwork.read(key))
            changes.pullPlaylists()
            assertEquals(1, playlistRequests, "An unchanged playlist must not be downloaded again")
            assertEquals(1, coverRequests, "An unchanged playlist cover must not be downloaded again")
            name = "Evening mix"
            changed = "t2"
            cover = png(0xFF0000)
            coverFails = true
            changes.pullPlaylists()
            assertEquals("Evening mix", Playlists(store).get(playlist.id)!!.name)
            coverFails = false
            changes.pullPlaylists()
            assertContentEquals(me.d3sox.nevumi.artwork.artworkPreview(cover), artwork.read(key), "A failed cover download must be retried")
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun refreshingADeletedServerSongKeepsItsLocalCopyAndReferencesAndRestoresItWhenItReturns() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-deleted-server-song")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var present = false
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/search3.view") -> {
                    val songs = if (present && request.url.parameters["songOffset"] == "0")
                        """[{"id":"song","title":"Retained song"}]""" else "[]"
                    """{"subsonic-response":{"status":"ok","searchResult3":{"song":$songs}}}"""
                }
                request.url.encodedPath.endsWith("/getPlaylists.view") ->
                    """{"subsonic-response":{"status":"ok","playlists":{"playlist":[]}}}"""
                request.url.encodedPath.endsWith("/getAlbumList2.view") ->
                    """{"subsonic-response":{"status":"ok","albumList2":{"album":[]}}}"""
                request.url.encodedPath.endsWith("/getIndexes.view") ->
                    """{"subsonic-response":{"status":"ok","indexes":{}}}"""
                else -> error("Unexpected catalogue request: ${request.url.encodedPath}")
            }
            respond(body)
        })
        try {
            val library = MusicLibrary(store)
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "test"))
            val changes = NavidromeChanges(store, library) { client }
            val track = changes.importSong(client, ServerSong("song", TrackMetadata("Retained song")))
            library.importTrack(track.metadata, TrackSource("local:copy", SourceKind.LOCAL, "/music/song.flac"), linkTo = track.id)
            library.setFavorite(track.id, true)
            val playlists = Playlists(store)
            val playlist = playlists.create("Keep my music", listOf(track.id))
            val queue = PlaybackQueue(store)
            queue.replace(listOf(track.id))
            changes.refreshLibrary(client)
            val missing = requireNotNull(library.findTrack(track.id))
            assertFalse(missing.sources.single { it.kind == SourceKind.SERVER }.available)
            assertTrue(missing.sources.single { it.kind == SourceKind.LOCAL }.available)
            assertTrue(missing.favorite)
            assertEquals(listOf(track.id), playlists.get(playlist.id)!!.trackIds)
            assertEquals(listOf(track.id), queue.snapshot().entries.map { it.trackId })
            present = true
            changes.refreshLibrary(client)
            assertTrue(library.findTrack(track.id)!!.sources.single { it.kind == SourceKind.SERVER }.available)
            assertEquals(1, library.tracks().size)
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun explicitlyLinkingAnAlreadyIndexedUploadMergesItsReferencesAndKeepsLocalFavoritesAndRatings() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-indexed-upload-link")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        try {
            val library = MusicLibrary(store)
            val local = library.importTrack(TrackMetadata("Local song"), TrackSource("local:upload", SourceKind.LOCAL, "/music/upload.mp3"))
            library.setFavorite(local.id, true)
            library.setRating(local.id, 4)
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "fixture-password"))
            val changes = NavidromeChanges(store, library) { client }
            val song = ServerSong("uploaded", TrackMetadata("Local song"))
            val discovered = changes.importSong(client, song)
            val queue = PlaybackQueue(store)
            queue.replace(listOf(local.id, discovered.id))
            val playlists = Playlists(store)
            val playlist = playlists.create("Discovered copy", listOf(discovered.id))
            val linked = changes.importSong(client, song, linkTo = local.id)
            assertEquals(1, library.tracks().size)
            assertEquals(local.id, linked.id)
            assertEquals(true, linked.favorite)
            assertEquals(4, linked.rating)
            assertEquals(listOf(local.id, local.id), queue.snapshot().entries.map { it.trackId })
            assertEquals(listOf(local.id), playlists.get(playlist.id)!!.trackIds)
            assertEquals(2, changes.pendingCount())
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun manuallyLinkingARatedLocalCopyPublishesItsMergedRatingToNavidrome() = runBlocking {
        val directory = Files.createTempDirectory("manual-link-rating-sync")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val original = client.songs().first { it.metadata.title == "Northern Light" }
        var controller: MusicController? = null
        try {
            client.setRating(original.id, 0)
            val library = MusicLibrary(store)
            val local = library.importTrack(original.metadata.copy(recordingId = "", releaseId = ""),
                TrackSource("local:song", SourceKind.LOCAL, "/music/song.flac"))
            library.setRating(local.id, 5)
            val changes = NavidromeChanges(store, library) { client }
            val server = changes.importSong(client, original.copy(rating = 0))
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), serverChanges = changes)
            controller = player
            withTimeout(3_000) { player.state.first { !it.loading } }
            player.linkTracks(local.id, server.id).join()
            withTimeout(3_000) {
                while (client.songs().first { it.id == original.id }.rating != 5) delay(25)
            }
            assertEquals(5, library.findTrack(local.id)!!.rating)
        } finally {
            controller?.closeAndJoin()
            client.setRating(original.id, original.rating)
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun importingANewServerCopyPreservesAnExistingLocalRatingAndQueuesItsServerUpdate() = runBlocking {
        val directory = Files.createTempDirectory("linked-rating-import")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        try {
            val library = MusicLibrary(store)
            val metadata = TrackMetadata("Northern Light", durationMs = 180_000, trackNumber = 1,
                recordingId = "189002e7-3285-4e2e-92a3-7f6c30d407a2", releaseId = "6e1d48f7-717c-416e-af35-5d2454a13af2")
            val local = library.importTrack(metadata, TrackSource("local:song", SourceKind.LOCAL, "/music/song.flac"))
            library.setRating(local.id, 5)
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))
            val changes = NavidromeChanges(store, library) { client }
            val song = ServerSong("song", metadata, rating = 3)
            val linked = changes.importSong(client, song)
            assertEquals(local.id, linked.id)
            assertEquals(5, linked.rating)
            assertEquals(1, changes.pendingCount())
            assertEquals(5, changes.importSong(client, song).rating)
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun offlineRatingsSurviveRefreshAndRestartThenSetAndClearTheRatingOnNavidrome() = runBlocking {
        val directory = Files.createTempDirectory("offline-ratings")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val original = client.songs().first { it.metadata.title == "Northern Light" }
        try {
            val library = MusicLibrary(store)
            val offline = NavidromeChanges(store, library) { null }
            val track = offline.importSong(client, original)
            offline.setRating(track.id, 5)
            assertEquals(5, library.findTrack(track.id)!!.rating)
            assertEquals(1, offline.pendingCount())
            offline.importSong(client, original)
            assertEquals(5, library.findTrack(track.id)!!.rating)
            store.close(); store = openMusicStore(database)
            val restored = NavidromeChanges(store, MusicLibrary(store)) { client }
            restored.synchronize()
            assertEquals(5, client.songs().first { it.id == original.id }.rating)
            assertEquals(5, MusicLibrary(store).findTrack(track.id)!!.rating)
            assertEquals(0, restored.pendingCount())
            restored.setRating(track.id, 0)
            restored.synchronize()
            assertEquals(0, client.songs().first { it.id == original.id }.rating)
            assertEquals(0, MusicLibrary(store).findTrack(track.id)!!.rating)
        } finally { client.setRating(original.id, original.rating); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun deletingAPlaylistWhileItsPublishIsInFlightDoesNotLeaveAnOrphanOnTheServer() = runBlocking {
        val directory = Files.createTempDirectory("delete-during-publish")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val transport = HttpClient(OkHttp)
        var deleteOnCreation = false
        var playlistId: String? = null
        val interrupted = HttpClient(MockEngine { request ->
            val response = transport.get(request.url)
            val body = response.bodyAsText()
            if (deleteOnCreation && request.url.encodedPath.endsWith("/createPlaylist.view") && request.url.parameters["playlistId"] == null) {
                deleteOnCreation = false
                Playlists(store).delete(requireNotNull(playlistId))
            }
            respond(body, response.status, response.headers)
        })
        val url = System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533"
        val credentials = ServerCredentials("fixture", "fixture-password")
        val ordinary = NavidromeClient(transport, url, credentials)
        val unreliable = NavidromeClient(interrupted, url, credentials)
        val before = ordinary.playlists().map { it.id }.toSet()
        try {
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { unreliable }
            val tracks = ordinary.songs().map { changes.importSong(ordinary, it) }
            val playlist = Playlists(store).create("Delete publish ${Uuid.random()}", tracks.map { it.id })
            playlistId = playlist.id
            changes.publishPlaylist(playlist.id)
            deleteOnCreation = true
            changes.synchronize()
            store.close(); store = openMusicStore(database)
            val restarted = NavidromeChanges(store, MusicLibrary(store)) { ordinary }
            restarted.synchronize()
            restarted.importPlaylists(ordinary)
            assertEquals(emptyList(), ordinary.playlists().filter { it.id !in before })
            assertEquals(null, Playlists(store).get(playlist.id))
        } finally {
            ordinary.playlists().filter { it.id !in before }.forEach { ordinary.deletePlaylist(it.id) }
            interrupted.close(); transport.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aPublicPlaylistOwnedByAnotherUserCanBePlayedAndDownloadedButCannotBeChanged() = runBlocking {
        val directory = Files.createTempDirectory("read-only-server-playlist")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/getPlaylists.view") ->
                    """{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"public"}]}}}"""
                request.url.encodedPath.endsWith("/getPlaylist.view") ->
                    """{"subsonic-response":{"status":"ok","playlist":{"id":"public","name":"Shared music","owner":"another-user","coverArt":"pl-public_1","entry":[{"id":"song"},{"id":"song"}]}}}"""
                else -> error("Unexpected playlist request: ${request.url.encodedPath}")
            }
            respond(body)
        })
        try {
            val library = MusicLibrary(store)
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "test"))
            val changes = NavidromeChanges(store, library) { client }
            val track = changes.importSong(client, ServerSong("song", TrackMetadata("Shared song")))
            changes.importPlaylists(client)
            val playlists = Playlists(store)
            val playlist = playlists.all().single()
            assertEquals(listOf(track.id, track.id), playlist.trackIds)
            assertEquals("pl-public_1", playlist.server?.coverArt)
            assertFailsWith<IllegalArgumentException> { playlists.append(playlist.id, track.id) }
            assertFailsWith<IllegalArgumentException> { playlists.replaceTracks(playlist.id, emptyList()) }
            assertFailsWith<IllegalArgumentException> { playlists.moveTrack(playlist.id, 0, 1) }
            assertFailsWith<IllegalArgumentException> { playlists.rename(playlist.id, "Changed") }
            assertFailsWith<IllegalArgumentException> { playlists.delete(playlist.id) }
            playlists.setAutoDownload(playlist.id, true)
            assertEquals(true, playlists.get(playlist.id)!!.autoDownload)
            assertEquals(playlist.trackIds, playlists.get(playlist.id)!!.trackIds)
            changes.synchronize()
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anEditToAPlaylistTheServerMadeReadOnlyIsReplacedByTheServerVersion() = runBlocking {
        val directory = Files.createTempDirectory("read-only-playlist-edit")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var synced = false
        val edits = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val songs = if (synced) """[{"id":"first"},{"id":"second"}]""" else """[{"id":"first"}]"""
            val body = when {
                request.url.encodedPath.endsWith("/getPlaylists.view") ->
                    """{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"m3u"}]}}}"""
                request.url.encodedPath.endsWith("/getPlaylist.view") ->
                    """{"subsonic-response":{"status":"ok","playlist":{"id":"m3u","name":"From a file","owner":"fixture","readonly":$synced,"entry":$songs}}}"""
                else -> { edits += request.url.encodedPath; """{"subsonic-response":{"status":"failed","error":{"code":50,"message":"User is not authorized for the given operation"}}}""" }
            }
            respond(body)
        })
        try {
            val library = MusicLibrary(store)
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "test"))
            val changes = NavidromeChanges(store, library) { client }
            val first = changes.importSong(client, ServerSong("first", TrackMetadata("First")))
            val second = changes.importSong(client, ServerSong("second", TrackMetadata("Second")))
            changes.importPlaylists(client)
            val playlists = Playlists(store)
            val playlist = playlists.all().single()
            playlists.replaceTracks(playlist.id, emptyList())
            // Navidrome now syncs the playlist from an M3U file, which also changed its songs.
            synced = true
            val discarded = async(start = CoroutineStart.UNDISPATCHED) { changes.discardedEdits.first() }
            changes.synchronize()
            assertEquals("From a file", withTimeout(1_000) { discarded.await() })
            val restored = playlists.get(playlist.id)!!
            assertEquals(listOf(first.id, second.id), restored.trackIds)
            assertFalse(restored.pendingSync)
            assertEquals(true, restored.server?.readOnly)
            assertEquals(emptyList(), edits, "A read-only playlist must not receive the edit")
            changes.importPlaylists(client)
            assertEquals(true, playlists.get(playlist.id)?.server?.readOnly, "The owner cannot edit a playlist the server keeps read-only")
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun retryingAPublishAfterALostCreationResponseDoesNotCreateASecondServerPlaylist() = runBlocking {
        val directory = Files.createTempDirectory("lost-playlist-publish")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val transport = HttpClient(OkHttp)
        var loseResponse = true
        val interrupted = HttpClient(MockEngine { request ->
            val response = transport.get(request.url)
            val body = response.bodyAsText()
            if (loseResponse && request.url.encodedPath.endsWith("/createPlaylist.view") && request.url.parameters["playlistId"] == null) {
                loseResponse = false
                throw IOException("Connection lost after creating the playlist")
            }
            respond(body, response.status, response.headers)
        })
        val url = System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533"
        val credentials = ServerCredentials("fixture", "fixture-password")
        val ordinary = NavidromeClient(transport, url, credentials)
        val unreliable = NavidromeClient(interrupted, url, credentials)
        val name = "Retry ${Uuid.random()}"
        val before = ordinary.playlists().map { it.id }.toSet()
        try {
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { unreliable }
            val tracks = ordinary.songs().map { changes.importSong(ordinary, it) }
            val playlist = Playlists(store).create(name, tracks.map { it.id })
            changes.publishPlaylist(playlist.id)
            assertFailsWith<NavidromeRequestException> { changes.synchronize() }
            store.close(); store = openMusicStore(database)
            val restarted = NavidromeChanges(store, MusicLibrary(store)) { ordinary }
            restarted.importPlaylists(ordinary)
            assertEquals(1, Playlists(store).all().size)
            restarted.synchronize()
            restarted.importPlaylists(ordinary)
            val created = ordinary.playlists().filter { it.id !in before }
            assertEquals(1, created.size)
            assertEquals(name, created.single().name)
            assertEquals(false, Playlists(store).get(playlist.id)!!.pendingSync)
            assertEquals(playlist.id, Playlists(store).all().single { it.server?.id == created.single().id }.id)
        } finally {
            ordinary.playlists().filter { it.id !in before }.forEach { ordinary.deletePlaylist(it.id) }
            interrupted.close(); transport.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun publishingAServerOnlyPlaylistSurvivesRestartAndDoesNotCreateADuplicateOnRefresh() = runBlocking {
        val directory = Files.createTempDirectory("publish-server-playlist")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val name = "Published ${Uuid.random()}"
        var playlistId: String? = null
        try {
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { client }
            val songs = client.songs()
            val tracks = songs.map { changes.importSong(client, it) }
            val playlist = Playlists(store).create(name, listOf(tracks[0].id, tracks[1].id, tracks[0].id))
            playlistId = playlist.id
            changes.publishPlaylist(playlist.id)
            assertEquals(true, Playlists(store).get(playlist.id)!!.pendingSync)
            store.close(); store = openMusicStore(database)
            val restored = NavidromeChanges(store, MusicLibrary(store)) { client }
            restored.synchronize()
            val saved = Playlists(store).get(playlist.id)!!
            assertEquals(false, saved.pendingSync)
            val remote = client.playlist(requireNotNull(saved.server?.id))
            assertEquals(name, remote.name)
            assertEquals(listOf(songs[0].id, songs[1].id, songs[0].id), remote.songIds)
            restored.importPlaylists(client)
            assertEquals(1, Playlists(store).all().count { it.server?.id == remote.id })
            assertEquals(playlist.id, Playlists(store).all().single { it.server?.id == remote.id }.id)
        } finally {
            client.playlists().filter { it.name == name || it.name == "Nevumi pending $playlistId" }.forEach { client.deletePlaylist(it.id) }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun importingANewServerCopyPreservesALocalFavoriteAndQueuesItsServerUpdate() = runBlocking {
        val directory = Files.createTempDirectory("linked-favorite-import")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        try {
            val library = MusicLibrary(store)
            val metadata = TrackMetadata("Northern Light", durationMs = 180_000, trackNumber = 1,
                recordingId = "189002e7-3285-4e2e-92a3-7f6c30d407a2", releaseId = "6e1d48f7-717c-416e-af35-5d2454a13af2")
            val local = library.importTrack(metadata, TrackSource("local:song", SourceKind.LOCAL, "/music/song.flac"))
            library.setFavorite(local.id, true)
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("test", "test"))
            val changes = NavidromeChanges(store, library) { client }
            val song = ServerSong("song", metadata, favorite = false)
            val linked = changes.importSong(client, song)
            assertEquals(local.id, linked.id)
            assertEquals(true, linked.favorite)
            assertEquals(1, changes.pendingCount())
            assertEquals(true, changes.importSong(client, song).favorite)
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anOfflineDeletionDoesNotEraseAPlaylistThatChangedOnTheServer() = runBlocking {
        val directory = Files.createTempDirectory("playlist-deletion-conflict")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var remoteId: String? = null
        try {
            val changes = NavidromeChanges(store, MusicLibrary(store)) { client }
            client.songs().forEach { changes.importSong(client, it) }
            val remote = client.savePlaylist("Delete ${Uuid.random()}", listOf(client.songs().first().id))
            remoteId = remote.id
            changes.importPlaylists(client)
            val playlists = Playlists(store)
            val imported = playlists.all().single { it.server?.id == remote.id }
            playlists.delete(imported.id)
            client.savePlaylist("Edited while offline", remote.songIds, remote.id)
            changes.synchronize()
            assertEquals(true, client.playlists().any { it.id == remote.id })
            assertEquals("Edited while offline", playlists.get(imported.id)?.conflict?.name)
            playlists.resolveConflict(imported.id, keepLocal = false)
            changes.synchronize()
            assertEquals("Edited while offline", playlists.get(imported.id)?.name)
            playlists.delete(imported.id)
            client.savePlaylist("Edited again", remote.songIds, remote.id)
            changes.synchronize()
            assertEquals("Edited again", playlists.get(imported.id)?.conflict?.name)
            playlists.resolveConflict(imported.id, keepLocal = true)
            changes.synchronize()
            assertEquals(false, client.playlists().any { it.id == remote.id })
            remoteId = null
        } finally {
            remoteId?.let { id -> if (client.playlists().any { it.id == id }) client.deletePlaylist(id) }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun deletingAServerPlaylistOfflineSurvivesRestartAndRemovesItFromNavidrome() = runBlocking {
        val directory = Files.createTempDirectory("server-playlist-delete")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var remoteId: String? = null
        try {
            val changes = NavidromeChanges(store, MusicLibrary(store)) { null }
            client.songs().forEach { changes.importSong(client, it) }
            val remote = client.savePlaylist("Delete ${Uuid.random()}", listOf(client.songs().first().id))
            remoteId = remote.id
            changes.importPlaylists(client)
            val playlists = Playlists(store)
            val imported = playlists.all().single { it.server?.id == remote.id }
            playlists.delete(imported.id)
            assertEquals(null, playlists.get(imported.id))
            changes.importPlaylists(client)
            assertEquals(false, playlists.all().any { it.server?.id == remote.id })
            store.close()
            store = openMusicStore(path)
            NavidromeChanges(store, MusicLibrary(store)) { client }.synchronize()
            assertEquals(false, client.playlists().any { it.id == remote.id })
            remoteId = null
        } finally {
            remoteId?.let { client.deletePlaylist(it) }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aLocalOnlySongCanJoinAMixedPlaylistButCannotBeAddedToAServerPlaylist() = runBlocking {
        val directory = Files.createTempDirectory("playlist-sources")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var remoteId: String? = null
        try {
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { client }
            client.songs().forEach { changes.importSong(client, it) }
            val remote = client.savePlaylist("Sources ${Uuid.random()}", listOf(client.songs().first().id))
            remoteId = remote.id
            changes.importPlaylists(client)
            val playlists = Playlists(store)
            val imported = playlists.all().single { it.server?.id == remote.id }
            val local = library.importTrack(TrackMetadata("Local only"), TrackSource("local-only", SourceKind.LOCAL, "/music/local.flac"))
            assertFailsWith<IllegalArgumentException> { playlists.append(imported.id, local.id) }
            val mixed = playlists.create("Mixed", imported.trackIds + local.id)
            assertEquals(imported.trackIds + local.id, mixed.trackIds)
            assertEquals(imported.trackIds, playlists.get(imported.id)?.trackIds)
        } finally {
            remoteId?.let { client.deletePlaylist(it) }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentPlaylistEditsKeepBothVersionsUntilTheUserChoosesOne() = runBlocking {
        val directory = Files.createTempDirectory("playlist-conflict")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var remoteId: String? = null
        try {
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { client }
            val tracks = client.songs().associate { song -> song.metadata.title to changes.importSong(client, song) }
            val northern = tracks.getValue("Northern Light")
            val night = tracks.getValue("Night Walk")
            val remote = client.savePlaylist("Fixture ${Uuid.random()}", listOf(northern.sources.single().location))
            remoteId = remote.id
            changes.importPlaylists(client)
            val playlists = Playlists(store)
            val imported = playlists.all().single { it.server?.id == remote.id }
            playlists.replaceTracks(imported.id, listOf(night.id, northern.id))
            client.savePlaylist("Changed on server", listOf(northern.sources.single().location), remote.id)
            changes.synchronize()
            val local = playlists.get(imported.id)!!
            assertEquals(listOf(night.id, northern.id), local.trackIds)
            assertEquals("Changed on server", local.conflict?.name)
            assertEquals(listOf(northern.sources.single().location), client.playlist(remote.id).songIds)
            playlists.resolveConflict(imported.id, keepLocal = true)
            changes.synchronize()
            assertEquals(listOf(night.sources.single().location, northern.sources.single().location), client.playlist(remote.id).songIds)
            assertEquals(null, playlists.get(imported.id)?.conflict)
        } finally {
            remoteId?.let { client.deletePlaylist(it) }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun offlineServerPlaylistEditsSurviveRestartAndSyncTheNameAndSongOrder() = runBlocking {
        val directory = Files.createTempDirectory("server-playlist-journal")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var remoteId: String? = null
        try {
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { null }
            val tracks = client.songs().associate { song -> song.metadata.title to changes.importSong(client, song) }
            val northern = tracks.getValue("Northern Light")
            val night = tracks.getValue("Night Walk")
            val remote = client.savePlaylist("Fixture ${Uuid.random()}", listOf(northern.sources.single().location))
            remoteId = remote.id
            changes.importPlaylists(client)
            val playlists = Playlists(store)
            val imported = playlists.all().single { it.server?.id == remote.id }
            playlists.replaceTracks(imported.id, listOf(night.id, northern.id, night.id))
            playlists.edit(imported.id, "Offline evening", "Music for late evenings")
            store.close()
            store = openMusicStore(path)
            val restored = Playlists(store)
            NavidromeChanges(store, MusicLibrary(store)) { client }.synchronize()
            val received = client.playlist(remote.id)
            assertEquals("Offline evening" to listOf(night.sources.single().location, northern.sources.single().location, night.sources.single().location),
                received.name to received.songIds)
            assertEquals("Music for late evenings", received.description)
            assertEquals(false, restored.get(imported.id)?.pendingSync)
        } finally {
            remoteId?.let { client.deletePlaylist(it) }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun catalogueRefreshImportsServerFavoritesWithoutOverwritingAnOfflineEdit() = runBlocking {
        val directory = Files.createTempDirectory("favorite-refresh")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "https://music.example", ServerCredentials("fixture", "fixture-password"))
        try {
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { null }
            val song = ServerSong("remote-id", TrackMetadata("A favorite"), favorite = true)
            val track = changes.importSong(client, song)
            assertEquals(true, library.findTrack(track.id)?.favorite)
            changes.setFavorite(track.id, false)
            changes.importSong(client, song)
            assertEquals(false, library.findTrack(track.id)?.favorite)
            assertEquals(1, changes.pendingCount())
        } finally { store.close(); http.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun offlineFavoriteEditsSurviveRestartAndOnlyTheLatestChoiceReachesTheServer() = runBlocking {
        val directory = Files.createTempDirectory("favorite-journal")
        val path = directory.resolve("music.db").toString()
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val original = client.songs().first { it.metadata.title == "Northern Light" }
        val desired = !original.favorite
        try {
            val store = openMusicStore(path)
            val library = MusicLibrary(store)
            val track = library.importTrack(original.metadata,
                TrackSource("server:${client.serverId}:${original.id}", SourceKind.SERVER, original.id))
            val changes = NavidromeChanges(store, library) { null }
            changes.setFavorite(track.id, desired)
            changes.setFavorite(track.id, original.favorite)
            changes.setFavorite(track.id, desired)
            changes.synchronize()
            assertEquals(desired, library.findTrack(track.id)?.favorite)
            assertEquals(original.favorite, client.songs().first { it.id == original.id }.favorite)
            store.close()

            val restored = openMusicStore(path)
            try {
                val resumed = NavidromeChanges(restored, MusicLibrary(restored)) { client }
                assertEquals(1, resumed.pendingCount())
                resumed.synchronize()
                assertEquals(desired, client.songs().first { it.id == original.id }.favorite)
                assertEquals(0, resumed.pendingCount())
            } finally { restored.close() }
        } finally {
            client.setFavorite(original.id, original.favorite)
            http.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aChangeForADeletedServerSongIsDroppedAndDoesNotBlockOtherChanges() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-sync-isolation")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val starred = mutableListOf<String>()
        var ratingAttempts = 0
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/rest/setRating.view" -> { ratingAttempts++
                    if (request.url.parameters["id"] == "gone") respond("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"not found"}}}""")
                    else respond("""{"subsonic-response":{"status":"failed","error":{"code":0,"message":"busy"}}}""") }
                "/rest/star.view" -> { starred += request.url.parameters["id"]!!; respond("""{"subsonic-response":{"status":"ok"}}""") }
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
        })
        try {
            val library = MusicLibrary(store)
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "test"))
            val changes = NavidromeChanges(store, library) { client }
            val gone = changes.importSong(client, ServerSong("gone", TrackMetadata("Deleted on the server")))
            val busy = changes.importSong(client, ServerSong("busy", TrackMetadata("Temporarily failing")))
            val kept = changes.importSong(client, ServerSong("kept", TrackMetadata("Still there")))
            changes.setRating(gone.id, 4)
            changes.setRating(busy.id, 3)
            changes.setFavorite(kept.id, true)
            assertFailsWith<NavidromeException> { changes.synchronize() }
            assertEquals(listOf("kept"), starred, "A failing rating must not block the favorite that follows it")
            assertEquals(1, changes.pendingCount(), "The deleted song's rating is dropped; the failing one is retried later")
            assertEquals(2, ratingAttempts)
            // An unreachable server would fail every entry, so the run stops after the first attempt.
            var offlineRequests = 0
            val offline = NavidromeChanges(store, library) { NavidromeClient(HttpClient(MockEngine { offlineRequests++; throw java.net.ConnectException("Connection refused") }),
                "https://music.example.test", ServerCredentials("fixture", "test")) }
            changes.setFavorite(gone.id, true)
            val before = starred.size
            assertFailsWith<NavidromeRequestException> { offline.synchronize() }
            assertEquals(before, starred.size)
            assertEquals(1, offlineRequests, "An unreachable server stops the run after the first request")
            assertEquals(2, offline.pendingCount(), "Nothing is dropped while the server is unreachable")
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
