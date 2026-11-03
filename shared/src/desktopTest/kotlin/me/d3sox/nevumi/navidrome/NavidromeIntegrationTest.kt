package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.uuid.Uuid

class NavidromeIntegrationTest {
    @Test
    fun serverSourcesRetainTheRelativeMusicFilePathNeededForSftpEditing() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-server-paths")
        val store = me.d3sox.nevumi.storage.openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http, "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
            val library = me.d3sox.nevumi.library.MusicLibrary(store)
            val imported = NavidromeChanges(store, library) { client }.refreshLibrary(client, includeFilePaths = true)
            assertEquals(mapOf(
                "Northern Light" to "Fixture Artist/Fixture Album/01 Northern Light.flac",
                "Long Way Home" to "Fixture Artist/Fixture Album/02 Long Way Home.mp3",
                "Night Walk" to "Fixture Artist/Fixture Album/03 Night Walk.wav"),
                imported.associate { (track, _) -> track.metadata.title to track.sources.single().filePath })
            assertEquals(setOf(1L), imported.map { it.first.sources.single().fileLibraryId }.toSet())
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aRatingCanBeSetAndRemovedOnTheRealServer() = runBlocking {
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val original = client.songs().first { it.metadata.title == "Northern Light" }
        try {
            client.setRating(original.id, 4)
            assertEquals(4, client.songs().first { it.id == original.id }.rating)
            client.setRating(original.id, 0)
            assertEquals(0, client.songs().first { it.id == original.id }.rating)
            assertFailsWith<IllegalArgumentException> { client.setRating(original.id, 6) }
            assertEquals(0, client.songs().first { it.id == original.id }.rating)
        } finally { client.setRating(original.id, original.rating); http.close() }
    }

    @Test
    fun theServerCatalogueRetainsRecordingReleaseAndAllGenreIdentifiers() = runBlocking {
        val http = HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/search3.view") && request.url.parameters["songOffset"] == "0" ->
                    """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"song","title":"Northern Light","albumId":"album","duration":180,"track":1,"discNumber":1,"mediaType":"song","musicBrainzId":"189002e7-3285-4e2e-92a3-7f6c30d407a2","genre":"Ambient","genres":[{"name":"Ambient"},{"name":"Electronic"}]}]}}}"""
                request.url.encodedPath.endsWith("/search3.view") ->
                    """{"subsonic-response":{"status":"ok","searchResult3":{"song":[]}}}"""
                request.url.encodedPath.endsWith("/getAlbum.view") ->
                    """{"subsonic-response":{"status":"ok","album":{"id":"album","name":"Fixture Album","musicBrainzId":"6e1d48f7-717c-416e-af35-5d2454a13af2","song":[]}}}"""
                else -> error("Unexpected music request: ${request.url.encodedPath}")
            }
            respond(body, headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        })
        try {
            val metadata = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "test")).songs().single().metadata
            assertEquals("189002e7-3285-4e2e-92a3-7f6c30d407a2", metadata.recordingId)
            assertEquals("6e1d48f7-717c-416e-af35-5d2454a13af2", metadata.releaseId)
            assertEquals(listOf("Ambient", "Electronic"), metadata.genres)
        } finally { http.close() }
    }

    @Test
    fun aServerCatalogueKeepsTheYearReportedForEveryFormat() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            assertEquals(mapOf("Northern Light" to 2024, "Long Way Home" to 2024, "Night Walk" to 2024),
                client.songs().associate { it.metadata.title to it.metadata.year })
        } finally { http.close() }
    }

    @Test
    fun aPlaylistTooLongForAUrlIsSavedAsAFormOnTheRealServer() = runBlocking {
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())
        // Recorded in the engine, which the client's per-request copies of this HttpClient share.
        val http = HttpClient(OkHttp) { engine { addInterceptor { chain ->
            methods += "${chain.request().method} ${chain.request().url.encodedPath.substringAfterLast('/')}"
            chain.proceed(chain.request())
        } } }
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var playlistId: String? = null
        try {
            val ids = client.songs().map { it.id }
            // Sixty entries pass the form threshold; behind a reverse proxy a thousand would no longer fit in a URL.
            val long = List(60) { ids[it % ids.size] }
            val created = client.savePlaylist("Long ${Uuid.random()}", long)
            playlistId = created.id
            assertEquals(long, client.playlists().single { it.id == created.id }.songIds)
            assertEquals(true, "POST createPlaylist.view" in methods, methods.toString())
            assertEquals(false, "GET createPlaylist.view" in methods, "The server must accept the form without the URL fallback: $methods")
        } finally {
            playlistId?.let { client.deletePlaylist(it) }
            http.close()
        }
    }

    @Test
    fun aServerPlaylistCanBeCreatedEditedAndDeletedWithoutLosingRepeatedSongs() = runBlocking {
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var playlistId: String? = null
        try {
            val songs = client.songs().associateBy { it.metadata.title }
            val northern = songs.getValue("Northern Light").id
            val night = songs.getValue("Night Walk").id
            val created = client.savePlaylist("Fixture ${Uuid.random()}", listOf(northern, night, northern))
            playlistId = created.id
            client.savePlaylist("Evening & morning", listOf(night, northern, night), created.id)
            val received = client.playlists().single { it.id == created.id }
            assertEquals("Evening & morning" to listOf(night, northern, night), received.name to received.songIds)
            client.deletePlaylist(created.id)
            playlistId = null
            assertEquals(false, client.playlists().any { it.id == created.id })
        } finally {
            playlistId?.let { client.deletePlaylist(it) }
            http.close()
        }
    }

    @Test
    fun aFavoriteCanBeSetAndRemovedOnTheRealServer() = runBlocking {
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val original = client.songs().first { it.metadata.title == "Northern Light" }
        try {
            client.setFavorite(original.id, true)
            assertEquals(true, client.songs().first { it.id == original.id }.favorite)
            client.setFavorite(original.id, false)
            assertEquals(false, client.songs().first { it.id == original.id }.favorite)
        } finally { client.setFavorite(original.id, original.favorite); http.close() }
    }

    @Test
    fun serverAlbumsKeepTrackOrderAlbumArtistAndFolders() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            assertEquals(mapOf("Northern Light" to listOf("Fixture Artist", "1", "1", "Fixture Artist/Fixture Album"),
                "Long Way Home" to listOf("Fixture Artist", "2", "1", "Fixture Artist/Fixture Album"),
                "Night Walk" to listOf("Fixture Artist", "3", "1", "Fixture Artist/Fixture Album")),
                client.songs().associate { song -> song.metadata.let { metadata ->
                    metadata.title to listOf(metadata.albumArtist, metadata.trackNumber.toString(),
                        metadata.discNumber.toString(), metadata.folder)
                } })
        } finally { http.close() }
    }

    @Test
    fun anAudioRequestWithInvalidCredentialsCannotBecomeACachedFile() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "wrong-password"))
            val failure = assertFailsWith<NavidromeException> {
                client.downloadSong("unknown-track") { }
            }
            assertEquals(40, failure.code)
        } finally { http.close() }
    }

    @Test
    fun aRealNavidromeServerReturnsTheEntireFixtureLibrary() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))

            assertEquals(setOf("Northern Light", "Long Way Home", "Night Walk"),
                client.songs().map { it.metadata.title }.toSet())
        } finally { http.close() }
    }

    @Test
    fun invalidCredentialsAreReportedAsAnAuthenticationFailure() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "wrong-password"))

            val failure = assertFailsWith<NavidromeException> { client.songs() }
            assertEquals(40, failure.code)
        } finally { http.close() }
    }

    @Test
    fun aDownloadedTrackExactlyMatchesTheOriginalAudioFile() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val song = client.songs().single { it.metadata.title == "Northern Light" }
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val expected = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(original))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val received = MessageDigest.getInstance("SHA-256")

            client.downloadSong(song.id) { received.update(it) }

            assertEquals(expected, received.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        } finally { http.close() }
    }

    @Test
    fun commonFormatTracksRetainTheirAudioPropertiesAndGenre() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))

            assertEquals(mapOf(
                "Northern Light" to Triple("flac", 3_000L, listOf("Ambient")),
                "Long Way Home" to Triple("mp3", 3_000L, listOf("Ambient")),
                "Night Walk" to Triple("wav", 3_000L, listOf("Ambient")),
            ), client.songs().associate { it.metadata.title to Triple(it.format,
                it.metadata.durationMs, it.metadata.genres) })
        } finally { http.close() }
    }

    @Test
    fun aTrackDownloadCanResumeFromAnExistingByteOffset() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http,
                System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                ServerCredentials("fixture", "fixture-password"))
            val song = client.songs().single { it.metadata.title == "Northern Light" }
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val bytes = Files.readAllBytes(original)
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(4_096, bytes.size))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val received = MessageDigest.getInstance("SHA-256")

            client.downloadSong(song.id, offset = 4_096) { received.update(it) }

            assertEquals(expected, received.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
        } finally { http.close() }
    }
}
