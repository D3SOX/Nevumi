package me.d3sox.nevumi.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.playlists.PlaylistServer
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

class ServerRefreshTest {
    @Test
    fun cancellingAndRetryingARefreshDoesNotWaitForAPlaylistCoverEdit() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-refresh-cancel-lock")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val searchEntered = CompletableDeferred<Unit>()
        val retryEntered = CompletableDeferred<Unit>()
        val coverEntered = CompletableDeferred<Unit>()
        val releaseCover = CompletableDeferred<Unit>()
        val searches = AtomicInteger()
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/rest/ping.view" -> respond("""{"subsonic-response":{"status":"ok"}}""")
                "/rest/search3.view" -> {
                    if (searches.incrementAndGet() == 2) retryEntered.complete(Unit)
                    searchEntered.complete(Unit)
                    awaitCancellation()
                }
                "/auth/login" -> respond("""{"token":"fixture-token"}""")
                "/api/playlist/mix" -> { coverEntered.complete(Unit); releaseCover.await(); respond("""{"uploadedImage":""}""") }
                "/api/playlist/mix/image" -> respond("{}")
                "/rest/getPlaylist.view" -> respond("""{"subsonic-response":{"status":"ok","playlist":{"id":"mix","name":"Mix","owner":"fixture"}}}""")
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) {}
            override suspend fun read(key: String) = "test"
            override suspend fun delete(key: String) {}
        }
        val connection = NavidromeConnection(store, http, vault)
        connection.connect("https://music.example.test", ServerCredentials("fixture", "test"))
        val playlists = Playlists(store)
        val playlist = playlists.create("Mix", emptyList())
        store.put("playlist:${playlist.id}", "playlists", playlist.copy(
            server = PlaylistServer(connection.client()!!.serverId, "mix", "Mix", emptyList(), "fixture")))
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), connection, playlists = playlists)
        try {
            withTimeout(3_000) { controller.state.first { it.libraryReady } }
            val refresh = controller.refreshServer()
            withTimeout(3_000) { searchEntered.await() }
            controller.editPlaylist(playlist.id, "Mix", "", removeCover = true)
            withTimeout(3_000) { coverEntered.await() }
            controller.cancelServerRefresh()
            withTimeout(1_000) { refresh.join() }
            assertEquals(ServerRefreshStage.CANCELLED, controller.state.value.serverRefresh?.stage)
            assertFalse(controller.state.value.loading)
            val retry = controller.refreshServer()
            withTimeout(3_000) { retryEntered.await() }
            assertEquals(ServerRefreshStage.SONGS, controller.state.value.serverRefresh?.stage)
            controller.cancelServerRefresh()
            withTimeout(1_000) { retry.join() }
            assertEquals(ServerRefreshStage.CANCELLED, controller.state.value.serverRefresh?.stage)
        } finally {
            releaseCover.complete(Unit); controller.closeAndJoin()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun serverOverloadStopsTheArtworkBatchKeepsImportedSongsAndAllowsRetry() = verifyCoverOverload(false)

    @Test
    fun serverOverloadDuringPlaylistImportReportsSavedSongsAndAllowsRetry() = verifyCoverOverload(true)

    private fun verifyCoverOverload(playlistCover: Boolean) = runBlocking {
        val directory = Files.createTempDirectory("nevumi-refresh-overload")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val overloaded = AtomicBoolean(true)
        val requests = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cover = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/rest/ping.view" -> respond("""{"subsonic-response":{"status":"ok","type":"navidrome"}}""")
                "/rest/search3.view" -> {
                    val songs = if (request.url.parameters["songOffset"] == "0") (1..30).joinToString(",") {
                        """{"id":"$it","title":"Song $it","coverArt":"cover-$it"}"""
                    } else ""
                    respond("""{"subsonic-response":{"status":"ok","searchResult3":{"song":[$songs]}}}""")
                }
                "/rest/getPlaylists.view" -> respond(if (playlistCover)
                    """{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"mix","name":"Mix"}]}}}"""
                    else """{"subsonic-response":{"status":"ok","playlists":{}}}""")
                "/rest/getPlaylist.view" -> respond("""{"subsonic-response":{"status":"ok","playlist":{"id":"mix","name":"Mix","owner":"fixture","coverArt":"mix-cover","entry":[{"id":"1"}]}}}""")
                "/rest/getCoverArt.view" -> {
                    requests.incrementAndGet()
                    if (overloaded.get()) {
                        entered.complete(Unit); release.await()
                        respond("busy", HttpStatusCode.TooManyRequests)
                    } else respond(cover, headers = headersOf(HttpHeaders.ContentType, "image/png"))
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) {}
            override suspend fun read(key: String) = "test"
            override suspend fun delete(key: String) {}
        }
        val connection = NavidromeConnection(store, http, vault)
        connection.connect("https://music.example.test", ServerCredentials("fixture", "test"))
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val changes = NavidromeChanges(store, library, artwork, connection::client)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            connection, artwork = artwork, serverChanges = changes)
        try {
            withTimeout(3_000) { controller.state.first { it.libraryReady } }
            val refresh = controller.refreshServer()
            withTimeout(5_000) { entered.await() }
            assertEquals(if (playlistCover) ServerRefreshStage.PLAYLISTS else ServerRefreshStage.ARTWORK,
                controller.state.value.serverRefresh?.stage)
            assertEquals(if (playlistCover) 1 else 30, controller.state.value.serverRefresh?.total)
            // An unrelated UI refresh must not clear the busy state of the server import.
            controller.refresh()
            assertTrue(controller.state.value.loading)
            withTimeout(1_000) { controller.refreshServer().join() }
            release.complete(Unit)
            withTimeout(5_000) { refresh.join() }
            // A small Navidrome server draws two covers at once; further requests would wait out their timeout in its queue.
            assertTrue(requests.get() <= 2, "An overloaded server must not receive the remaining cover requests")
            withTimeout(3_000) { controller.state.first { it.tracks.size == 30 } }
            assertEquals(30, library.tracks().size)
            assertFalse(controller.state.value.loading)
            assertEquals(ServerRefreshStage.FAILED, controller.state.value.serverRefresh?.stage)
            assertTrue(controller.state.value.serverRefresh?.message.orEmpty().contains(
                if (playlistCover) "Songs are saved. Playlists could not finish." else "Songs are saved. Artwork could not finish."))
            assertTrue(controller.state.value.serverRefresh?.message.orEmpty().contains("429"))
            overloaded.set(false)
            withTimeout(5_000) { controller.refreshServer().join() }
            assertEquals(ServerRefreshStage.COMPLETE, controller.state.value.serverRefresh?.stage)
            assertEquals(30, controller.state.value.serverRefresh?.processed)
            assertEquals(30, controller.state.value.artwork.size)
            if (playlistCover) {
                val playlist = Playlists(store).all().single()
                assertNotNull(artwork.path("server-playlist-cover:${playlist.id}"))
            }
            assertFalse(controller.state.value.loading)
            controller.dismissServerRefresh()
            assertNull(controller.state.value.serverRefresh)
        } finally {
            release.complete(Unit); controller.closeAndJoin()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }
}
