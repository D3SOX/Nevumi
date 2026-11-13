package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import kotlin.test.*

class NavidromePrivacyTest {
    @Test
    fun theConnectedAccountReusesItsNativeSessionButStillRequiresAnUnlockedVault() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-native-session")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var logins = 0
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/rest/ping.view") -> """{"subsonic-response":{"status":"ok","type":"navidrome"}}"""
                request.url.encodedPath.endsWith("/auth/login") -> { logins++; """{"token":"session-$logins"}""" }
                request.url.encodedPath.endsWith("/api/song") -> "[]"
                else -> """{"subsonic-response":{"status":"ok","type":"navidrome","searchResult3":{"song":[]}}}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        var password: String? = null
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { password = secret }
            override suspend fun read(key: String) = password
            override suspend fun delete(key: String) { password = null }
        }
        try {
            val connection = NavidromeConnection(store, http, vault)
            connection.connect("https://music.example.test", ServerCredentials("fixture", "private-password"))
            repeat(3) { assertTrue(connection.client()!!.musicFiles().isEmpty()) }
            assertEquals(1, logins)
            password = null
            assertFailsWith<IllegalStateException> { connection.client() }
            connection.connect("https://music.example.test", ServerCredentials("other-account", "other-password"))
            assertTrue(connection.client()!!.musicFiles().isEmpty())
            assertEquals(2, logins)
            connection.disconnect()
            assertNull(connection.client())
            Unit
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anExpiredNativeSessionIsRenewedOnceAndTheFileRefreshContinues() = runBlocking {
        var logins = 0
        var reads = 0
        val http = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/rest/ping.view") -> respond("""{"subsonic-response":{"status":"ok","type":"navidrome"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/auth/login") -> {
                    logins++
                    respond("""{"token":"session-$logins"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                ++reads > 1 && request.headers["x-nd-authorization"] == "Bearer session-1" ->
                    respond("Expired", HttpStatusCode.Unauthorized)
                else -> respond("[]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "private-password"))
            assertTrue(client.musicFiles().isEmpty())
            assertTrue(client.musicFiles().isEmpty())
            assertEquals(2, logins)
            assertEquals(3, reads)
            Unit
        } finally { http.close() }
    }

    @Test
    fun aSongWithoutAFileEntryStaysInTheCatalogueAndANamedPlaylistLookupFetchesOnlyThatPlaylist() = runBlocking {
        val fetched = mutableListOf<String>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val http = HttpClient(MockEngine { request ->
            val parameters = request.url.parameters
            when {
                request.url.encodedPath.endsWith("/rest/ping.view") -> respond("""{"subsonic-response":{"status":"ok","type":"navidrome"}}""", headers = json)
                request.url.encodedPath.endsWith("/auth/login") -> respond("""{"token":"session"}""", headers = json)
                // Song b was scanned after the file catalogue was read.
                request.url.encodedPath.endsWith("/api/song") -> respond(if (parameters["_start"] == "0")
                    """[{"id":"a","path":"A/a.flac","libraryId":1}]""" else "[]", headers = json)
                request.url.encodedPath.endsWith("/rest/search3.view") -> respond(if (parameters["songOffset"] == "0")
                    """{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"a","title":"A"},{"id":"b","title":"B"}]}}}"""
                    else """{"subsonic-response":{"status":"ok","searchResult3":{"song":[]}}}""", headers = json)
                request.url.encodedPath.endsWith("/rest/getPlaylists.view") -> respond("""{"subsonic-response":{"status":"ok","playlists":{"playlist":[""" +
                    """{"id":"1","name":"Mix"},{"id":"2","name":"Nevumi pending x"}]}}}""", headers = json)
                request.url.encodedPath.endsWith("/rest/getPlaylist.view") -> {
                    val id = requireNotNull(parameters["id"]).also { fetched += it }
                    respond("""{"subsonic-response":{"status":"ok","playlist":{"id":"$id","name":"Nevumi pending x","owner":"fixture","entry":[]}}}""", headers = json)
                }
                else -> error("Unexpected request: ${request.url.encodedPath}")
            }
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "private-password"))
            assertEquals(listOf("A/a.flac", null), client.songs(includeFilePaths = true).sortedBy { it.id }.map { it.filePath })
            assertEquals(listOf("2"), client.playlists("Nevumi pending x").map { it.id })
            assertEquals(listOf("2"), fetched)
        } finally { http.close() }
    }

    @Test
    fun repeatedNativeFileRefreshesReuseTheirAuthenticatedSession() = runBlocking {
        var logins = 0
        val http = HttpClient(MockEngine { request ->
            val body = when {
                request.url.encodedPath.endsWith("/rest/ping.view") -> """{"subsonic-response":{"status":"ok","type":"navidrome"}}"""
                request.url.encodedPath.endsWith("/auth/login") -> { logins++; """{"token":"private-native-session"}""" }
                request.url.parameters["_start"] == "0" -> """[{"id":"song","path":"Artist/Album/real.flac","libraryId":1,"libraryName":"Music"}]"""
                else -> "[]"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "private-password"))
            repeat(3) { assertEquals("Artist/Album/real.flac", client.musicFiles().single().path) }
            assertEquals(1, logins)
            Unit
        } finally { http.close() }
    }

    @Test
    fun theNativeFileCatalogueNeverForwardsItsSessionTokenToARedirectDestination() = runBlocking {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.host
            when {
                request.url.encodedPath.endsWith("/rest/ping.view") -> respond("""{"subsonic-response":{"status":"ok","type":"navidrome"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/auth/login") -> respond("""{"token":"private-native-session"}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.host == "music.example.test" -> respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://other.example.test/api/song"))
                else -> respond("[]", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "private-password"))
            val failure = assertFailsWith<NavidromeRequestException> { client.musicFiles() }
            assertEquals(listOf("music.example.test", "music.example.test", "music.example.test"), requests)
            assertNull(failure.cause)
            assertFalse(failure.stackTraceToString().contains("private-native-session"))
            Unit
        } finally { http.close() }
    }

    @Test
    fun serverAddressesCannotStoreEmbeddedCredentialsQueriesOrFragments() = runBlocking {
        val directory = Files.createTempDirectory("server-address-privacy")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("""{"subsonic-response":{"status":"ok","type":"navidrome","searchResult3":{"song":[]}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        try {
            val credentials = ServerCredentials("private-account", "private-password")
            val connection = NavidromeConnection(store, http, vault)
            for (address in listOf("https://private-account:private-password@music.example.test",
                "https://music.example.test/?token=private-password", "https://music.example.test/#private-password",
                "ftp://music.example.test", "not a server private-password")) {
                val failure = assertFailsWith<IllegalArgumentException> { NavidromeClient(http, address, credentials) }
                assertEquals("Enter the server's HTTP or HTTPS address without credentials, a query, or a fragment", failure.message)
                assertFalse(failure.stackTraceToString().contains("private-password"))
                assertFailsWith<IllegalArgumentException> { connection.connect(address, credentials) }
                assertNull(connection.profile())
            }
            assertEquals(0, requests)
            assertTrue(secrets.isEmpty())
            connection.connect(" https://music.example.test/subpath/ ", credentials)
            assertEquals("https://music.example.test/subpath", connection.profile()!!.baseUrl)
            assertEquals(connection.profile()!!.serverId, connection.client()!!.serverId)
            assertEquals("/subpath/rest/stream.view", Url(connection.client()!!.streamUrl("song")).encodedPath)
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun malformedResponsesAndServerErrorsHideSecretsWhileLocalDownloadFailuresKeepTheirMeaning() = runBlocking {
        var response = "malformed"
        val http = HttpClient(MockEngine {
            when (response) {
                "malformed" -> respond("""{"subsonic-response":{"status":"ok","playlists":"private-password","playlist":"private-password"}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
                "authentication" -> respond("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Rejected private-password at ?u=private-account&t=private-token"}}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
                else -> respond(byteArrayOf(1, 2, 3, 4), headers = headersOf(HttpHeaders.ContentType, "audio/flac"))
            }
        })
        try {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("private-account", "private-password"))
            for (request in listOf<suspend () -> Unit>({ client.playlists(); Unit }, { client.playlist("song"); Unit },
                { client.savePlaylist("Playlist", listOf("song")); Unit })) {
                val failure = assertFailsWith<NavidromeRequestException> { request() }
                assertFalse(failure.stackTraceToString().contains("private-password"))
                assertNull(failure.cause)
            }
            response = "authentication"
            val authentication = assertFailsWith<NavidromeException> { client.setFavorite("song", true) }
            assertEquals(40, authentication.code)
            assertEquals("The music server rejected the username or password", authentication.message)
            assertFalse(authentication.stackTraceToString().contains("private-password"))
            response = "audio"
            val storage = IOException("Device storage unavailable")
            assertSame(storage, assertFailsWith<IOException> { client.downloadSong("song") { throw storage } })
            Unit
        } finally { http.close() }
    }

    @Test
    fun failedRequestsNeverExposeAuthenticatedUrlsOrTheirCauses() = runBlocking {
        val http = HttpClient(MockEngine { request -> throw IOException("Connection failed for ${request.url}") })
        try {
            val client = NavidromeClient(http, "https://music.example.test/subpath", ServerCredentials("private-account", "private-password"))
            val requests: List<suspend () -> Unit> = listOf(
                { client.songs(); Unit }, { client.playlists(); Unit }, { client.playlist("song"); Unit },
                { client.savePlaylist("Playlist", listOf("song")); Unit }, { client.deletePlaylist("playlist") },
                { client.setFavorite("song", true) }, { client.setRating("song", 4) },
                { client.scrobble("song", 1_000, true) }, { client.coverArt("cover"); Unit },
                { client.downloadSong("song") { }; Unit })
            for (request in requests) {
                val error = assertFailsWith<NavidromeRequestException> { request() }
                assertEquals("Could not complete the music server request. Check your connection and server address.", error.message)
                assertNull(error.cause)
                assertFalse(error.stackTraceToString().contains("private-account"))
                assertFalse(error.stackTraceToString().contains("private-password"))
                assertFalse(error.stackTraceToString().contains("?u="))
            }
            Unit
        } finally { http.close() }
    }
}
