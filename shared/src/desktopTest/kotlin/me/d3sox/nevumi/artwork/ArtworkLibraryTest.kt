package me.d3sox.nevumi.artwork

import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.LocalFileEdit
import me.d3sox.nevumi.library.LocalFileEdits
import me.d3sox.nevumi.library.MusicFileEdits
import me.d3sox.nevumi.storage.openMusicStore
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.images.ArtworkFactory
import java.nio.file.Path
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.SecretServiceVault
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class ArtworkLibraryTest {
    @Test
    fun artworkChangesDuringARefreshAreNotReplacedByItsOlderSnapshot() = runBlocking {
        val directory = Files.createTempDirectory("refresh-artwork-change")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val covers = ArtworkLibrary(directory.resolve("artwork").toString())
        val track = library.importTrack(TrackMetadata("Changing cover"), TrackSource("cover", SourceKind.LOCAL, "/music/song.flac"))
        val marker = LocalFileEdit("ready", track.id, "", "", "", "", "", 0)
        var changeDuringRefresh: (suspend () -> Unit)? = null
        val edits = object : MusicFileEdits by LocalFileEdits(store, library, directory.resolve("edits").toString()) {
            override suspend fun history(): List<LocalFileEdit> {
                val change = changeDuringRefresh
                changeDuringRefresh = null
                change?.invoke()
                return listOf(marker)
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = covers, fileEdits = edits)
        try {
            withTimeout(3_000) { controller.state.first { it.fileEdits == listOf(marker) } }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            changeDuringRefresh = {
                covers.save(track.id, bytes)
                withTimeout(3_000) { controller.state.first { it.artwork[track.id] != null } }
            }
            controller.refresh()
            assertEquals(covers.path(track.id), controller.state.value.artwork[track.id])
            changeDuringRefresh = {
                covers.remove(track.id)
                withTimeout(3_000) { controller.state.first { track.id !in it.artwork } }
            }
            controller.refresh()
            assertEquals(null, controller.state.value.artwork[track.id])
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun artworkArrivingDuringLibraryUseAppearsWithoutWaitingForAnotherRefresh() = runBlocking {
        val directory = Files.createTempDirectory("progressive-library-cover")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val covers = ArtworkLibrary(directory.resolve("artwork").toString())
        val track = library.importTrack(TrackMetadata("Cover arrives later"), TrackSource("later-cover", SourceKind.LOCAL, "/music/song.flac"))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = covers)
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
            covers.save(track.id, bytes)
            // Nothing triggers another refresh here, so a generous limit only absorbs slow CI runners.
            val visible = withTimeout(5_000) { controller.state.first { it.artwork[track.id] != null } }
            assertEquals(covers.path(track.id), visible.artwork[track.id])
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLargeEmbeddedCoverDoesNotAbortScanningAndRemainsAvailableForDisplay() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("large-embedded-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val image = BufferedImage(1_800, 1_800, BufferedImage.TYPE_INT_RGB)
            val random = java.util.Random(42)
            for (x in 0 until image.width) for (y in 0 until image.height) image.setRGB(x, y, random.nextInt(0x1000000))
            val cover = directory.resolve("large.png").toFile()
            ImageIO.write(image, "png", cover)
            kotlin.test.assertTrue(cover.length() > 8 * 1024 * 1024)
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("01-with-cover.flac").toFile()
            Files.copy(fixture, audio.toPath())
            AudioFileIO.read(audio).also { it.tag.setField(ArtworkFactory.createArtworkFromFile(cover)); it.commit() }
            Files.copy(fixture, directory.resolve("02-without-cover.flac"))
            val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
            val tracks = LocalFiles(MusicLibrary(store), artwork).scan(directory.toString())
            assertEquals(2, tracks.size)
            val saved = requireNotNull(artwork.read(tracks.single { it.sources.single().location == audio.canonicalPath }.id))
            kotlin.test.assertNotNull(ImageIO.read(ByteArrayInputStream(saved)))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun connectingAServerRetainsItsCatalogueArtworkForOfflineBrowsing() = runBlocking {
        val directory = Files.createTempDirectory("catalogue-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        val png = output.toByteArray()
        var coverRequests = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/rest/getCoverArt.view") {
                coverRequests++
                respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            } else if (request.url.encodedPath == "/rest/ping.view")
                respond("""{"subsonic-response":{"status":"ok","type":"navidrome"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else {
                val songs = if (request.url.parameters["songOffset"] == "0")
                    """[{"id":"song-a","title":"Server Cover","coverArt":"album-cover"}]""" else "[]"
                respond("""{"subsonic-response":{"status":"ok","searchResult3":{"song":$songs}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            NavidromeConnection(store, http, vault), artwork = artwork)
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.connectServer("https://music.example.test", "fixture", "fixture-password").join()
            controller.refreshServer().join()
            assertEquals(1, coverRequests, "A refresh must not download a Navidrome cover the library already holds")
            val track = library.tracks().single()
            http.close()
            assertContentEquals(png, ArtworkLibrary(directory.resolve("artwork").toString()).read(track.id))
        } finally { controller.close(); vault.delete("navidrome"); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aRefreshDownloadsMissingCoversBeforeRefreshingExistingOnes() = runBlocking {
        val directory = Files.createTempDirectory("missing-artwork-first")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val png = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        // More covers than parallel downloads, so the last song's cover would wait for a free download if it kept its catalogue place.
        val songs = (1..8).joinToString(",") { """{"id":"song-$it","title":"Song $it","coverArt":"cover-$it"}""" }
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/rest/getCoverArt.view") {
                requests += requireNotNull(request.url.parameters["id"])
                kotlinx.coroutines.delay(50)
                respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
            } else respond("""{"subsonic-response":{"status":"ok","searchResult3":{"song":${if (request.url.parameters["songOffset"] == "0") "[$songs]" else "[]"}}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            NavidromeConnection(store, http, vault), artwork = artwork)
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.connectServer("https://music.example.test", "fixture", "fixture-password").join()
            artwork.remove(library.tracks().single { it.metadata.title == "Song 8" }.id)
            requests.clear()
            controller.refreshServer().join()
            kotlin.test.assertTrue("cover-8" in requests.take(6), "The missing cover must not wait behind covers the library already holds: $requests")
            assertEquals(8, requests.size)
        } finally { controller.close(); vault.delete("navidrome"); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aNavidromePlaylistShowsItsServerCoverAndUploadsCustomCoversToTheServer() = runBlocking {
        val directory = Files.createTempDirectory("server-playlist-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        fun png(rgb: Int) = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).apply { for (x in 0..31) for (y in 0..31) setRGB(x, y, rgb) }, "png", output)
        }.toByteArray()
        val generated = png(0x00FF00)
        val uploads = mutableListOf<String>()
        var deletions = 0
        var rejectUpload = true
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path == "/rest/getCoverArt.view" -> respond(if (request.url.parameters["id"] == "pl-mix") generated else png(0x0000FF),
                    headers = headersOf(HttpHeaders.ContentType, "image/png"))
                path == "/rest/getPlaylists.view" -> respond("""{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"mix"}]}}}""")
                path == "/rest/getPlaylist.view" -> respond("""{"subsonic-response":{"status":"ok","playlist":{"id":"mix","name":"Mix","owner":"fixture","coverArt":"pl-mix","entry":[{"id":"song-a"}]}}}""")
                path == "/auth/login" -> respond("""{"token":"native-token"}""")
                path == "/api/playlist/mix" -> respond("""{"id":"mix","uploadedImage":""}""")
                path == "/api/playlist/mix/image" -> {
                    assertEquals("Bearer native-token", request.headers["x-nd-authorization"])
                    if (request.method != io.ktor.http.HttpMethod.Delete && rejectUpload) {
                        rejectUpload = false
                        return@MockEngine respond("broken", io.ktor.http.HttpStatusCode.InternalServerError)
                    }
                    if (request.method == io.ktor.http.HttpMethod.Delete) deletions++
                    else uploads += String(request.body.toByteArray(), Charsets.ISO_8859_1)
                    respond("""{"status":"ok"}""")
                }
                else -> {
                    val songs = if (request.url.parameters["songOffset"] == "0") """[{"id":"song-a","title":"Server song","coverArt":"album-cover"}]""" else "[]"
                    respond("""{"subsonic-response":{"status":"ok","searchResult3":{"song":$songs}}}""")
                }
            }
        })
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val connection = NavidromeConnection(store, http, vault)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection, artwork = artwork,
            playlists = me.d3sox.nevumi.playlists.Playlists(store),
            serverChanges = me.d3sox.nevumi.navidrome.NavidromeChanges(store, library, artwork, connection::client))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.connectServer("https://music.example.test", "fixture", "fixture-password").join()
            val playlist = controller.state.value.playlists.single()
            val key = "server-playlist-cover:${playlist.id}"
            assertContentEquals(artworkPreview(generated), artwork.read(key))
            assertEquals(artwork.path(key), controller.state.value.artwork[key])
            val custom = png(0xFF0000)
            val appOnly = png(0xFFFF00)
            artwork.save("playlist:${playlist.id}", appOnly)
            controller.editPlaylist(playlist.id, "Mix", "", cover = custom).join()
            assertContentEquals(artworkPreview(appOnly), artwork.read("playlist:${playlist.id}"), "A failed upload must keep the app-only cover")
            assertContentEquals(artworkPreview(generated), artwork.read(key))
            controller.editPlaylist(playlist.id, "Mix", "", cover = custom).join()
            assertEquals(1, uploads.size)
            kotlin.test.assertTrue("name=\"image\"; filename=" in uploads.single())
            kotlin.test.assertTrue(String(custom, Charsets.ISO_8859_1) in uploads.single())
            assertEquals(null, artwork.read("playlist:${playlist.id}"), "A server playlist cover must not be kept only in the app")
            assertContentEquals(artworkPreview(custom), artwork.read(key))
            controller.editPlaylist(playlist.id, "Mix", "", removeCover = true).join()
            assertEquals(1, deletions)
            assertContentEquals(artworkPreview(generated), artwork.read(key))
        } finally { controller.close(); vault.delete("navidrome"); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun scanningLocalMusicRetainsItsEmbeddedCover() = runBlocking {
        val directory = Files.createTempDirectory("embedded-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
            for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0x00E05040)
            val cover = directory.resolve("cover.png").toFile()
            ImageIO.write(image, "png", cover)
            val audio = directory.resolve("song.flac").toFile()
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), audio.toPath())
            val tagged = AudioFileIO.read(audio)
            tagged.tag.setField(ArtworkFactory.createArtworkFromFile(cover))
            AudioFileIO.write(tagged)
            val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
            val track = LocalFiles(MusicLibrary(store), artwork).scan(directory.toString()).single()
            assertContentEquals(cover.readBytes(), artwork.read(track.id))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aServerCoverRemainsAvailableOfflineAfterTheAppRestarts() = runBlocking {
        val directory = Files.createTempDirectory("offline-artwork")
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0x00E05040)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        val png = output.toByteArray()
        val http = HttpClient(MockEngine { request ->
            assertEquals("/rest/getCoverArt.view", request.url.encodedPath)
            assertEquals("album-cover", request.url.parameters["id"])
            respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "fixture-password"))
            ArtworkLibrary(directory.toString()).fetch("server:track", client, "album-cover")
            http.close()
            val reopened = ArtworkLibrary(directory.toString())
            assertContentEquals(png, reopened.read("server:track"))
        } finally { http.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun songsSharingACoverDownloadItOnce() = runBlocking {
        val directory = Files.createTempDirectory("shared-artwork")
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (x in 0..15) for (y in 0..15) image.setRGB(x, y, 0x002050E0)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        val png = output.toByteArray()
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            assertEquals("album-cover", request.url.parameters["id"])
            respond(png, headers = headersOf(HttpHeaders.ContentType, "image/png"))
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "fixture-password"))
            val artwork = ArtworkLibrary(directory.toString())
            artwork.fetch(listOf("server:one", "server:two", "server:one"), client, "album-cover")
            assertEquals(1, requests)
            assertContentEquals(png, artwork.read("server:one"))
            assertContentEquals(png, artwork.read("server:two"))
        } finally { http.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLibraryRefreshDownloadsOnlyNewOrChangedServerCovers() = runBlocking {
        val directory = Files.createTempDirectory("changed-artwork")
        fun png(rgb: Int) = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB).apply { for (x in 0..15) for (y in 0..15) setRGB(x, y, rgb) }, "png", output)
        }.toByteArray()
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val id = requireNotNull(request.url.parameters["id"])
            requests += id
            respond(png(if (id == "al-1_b") 0xFF0000 else 0x0000FF), headers = headersOf(HttpHeaders.ContentType, "image/png"))
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "fixture-password"))
            val artwork = ArtworkLibrary(directory.toString())
            artwork.fetchChanged(listOf("one", "two"), client, "al-1_a")
            ArtworkLibrary(directory.toString()).fetchChanged(listOf("one", "two"), client, "al-1_a")
            assertEquals(listOf("al-1_a"), requests, "A refresh must not download covers the library already holds")
            artwork.fetchChanged(listOf("one", "two", "three"), client, "al-1_a")
            assertEquals(listOf("al-1_a", "al-1_a"), requests, "A new song must receive its cover")
            assertContentEquals(png(0x0000FF), artwork.read("three"))
            artwork.fetchChanged(listOf("one"), client, "al-1_b")
            assertContentEquals(png(0xFF0000), artwork.read("one"), "A changed cover must replace the stored one")
            artwork.save("two", png(0x00FF00))
            artwork.fetchChanged(listOf("two"), client, "al-1_a")
            assertContentEquals(png(0x0000FF), artwork.read("two"), "A refresh must restore the server cover after another image replaced it")
            assertEquals(listOf("al-1_a", "al-1_a", "al-1_b", "al-1_a"), requests)
        } finally { http.close(); directory.toFile().deleteRecursively() }
    }
}
