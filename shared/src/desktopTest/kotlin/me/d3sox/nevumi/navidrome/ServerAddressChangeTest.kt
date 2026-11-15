package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerAddressChangeTest {
    private val ownSongs = listOf("a1" to "Northern Light", "a2" to "Long Way Home", "a3" to "Night Walk")
    // Another server that numbers its songs the same way, as servers with sequential IDs do.
    private val otherSongs = listOf("a1" to "Morning Tide", "a2" to "Far Away", "a3" to "Dawn")

    private fun song(id: String, title: String) = """{"id":"$id","title":"$title","duration":200,"starred":"2024-01-01T00:00:00Z","userRating":4}"""

    private val http = HttpClient(MockEngine { request ->
        val songs = if (request.url.host == "other.example.test") otherSongs else ownSongs
        val body = when (request.url.encodedPath) {
            "/rest/ping.view" -> """{"status":"ok","type":"navidrome"}"""
            "/rest/search3.view" -> """{"status":"ok","searchResult3":{"song":[${
                if (request.url.parameters["songOffset"] == "0") songs.joinToString(",") { song(it.first, it.second) } else ""}]}}"""
            "/rest/getSong.view" -> songs.firstOrNull { it.first == request.url.parameters["id"] }
                ?.let { """{"status":"ok","song":${song(it.first, it.second)}}""" } ?: """{"status":"failed","error":{"code":70}}"""
            "/rest/getPlaylists.view" -> """{"status":"ok","playlists":{"playlist":[{"id":"p1","changed":"2024-01-01T00:00:00Z"}]}}"""
            "/rest/getPlaylist.view" -> """{"status":"ok","playlist":{"id":"p1","name":"Shared","owner":"fixture","changed":"2024-01-01T00:00:00Z","entry":[{"id":"a1"},{"id":"a2"}]}}"""
            else -> error("Unexpected endpoint: ${request.url.encodedPath}")
        }
        respond("""{"subsonic-response":$body}""")
    })

    private val vault = object : CredentialVault {
        override suspend fun save(key: String, secret: String) {}
        override suspend fun read(key: String) = "test"
        override suspend fun delete(key: String) {}
    }

    @Test
    fun aNewAddressOfTheSameServerKeepsItsSongsAndTheirDataWhileAnotherServerStaysSeparate() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-server-address")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val connection = NavidromeConnection(store, http, vault)
            val changes = NavidromeChanges(store, library, savedConnection = true, client = connection::client)
            val credentials = ServerCredentials("fixture", "test")
            connection.connect("http://192.168.1.5:4533", credentials)
            changes.refreshLibrary(requireNotNull(connection.client()))
            val serverId = requireNotNull(connection.profile()).serverId
            val before = library.tracks()
            val track = before.first { it.metadata.title == "Northern Light" }
            val local = Playlists(store).create("Local mix", listOf(track.id))
            ListeningHistory(store, library).import(listOf(Listen("listen", track.id, "playback", 1, track.metadata, playedMs = 200_000)))

            connection.connect("https://music.example.test", credentials)
            changes.refreshLibrary(requireNotNull(connection.client()))
            assertEquals("https://music.example.test", connection.profile()?.baseUrl)
            assertEquals(serverId, connection.profile()?.serverId)
            assertEquals(serverId, connection.client()?.serverId)
            val after = library.tracks()
            assertEquals(before.map { it.id to it.sources.map { source -> source.id } }.toSet(),
                after.map { it.id to it.sources.map { source -> source.id } }.toSet())
            assertTrue(after.all { it.favorite && it.rating == 4 && it.sources.all { source -> source.available } })
            val playlists = store.list<Playlist>("playlists")
            assertEquals(2, playlists.size)
            assertEquals(listOf(track.id), playlists.single { it.id == local.id }.trackIds)
            assertEquals(serverId, playlists.single { it.server != null }.server?.serverId)
            assertEquals(listOf(track.id), ListeningHistory(store, library).all().map { it.trackId })

            // Entering the password again at the new address does not lose the identity either.
            connection.connect("https://music.example.test/", ServerCredentials("fixture", "changed"))
            assertEquals(serverId, connection.client()?.serverId)

            connection.connect("https://other.example.test", credentials)
            val other = requireNotNull(connection.client())
            assertNotEquals(serverId, other.serverId)
            changes.refreshLibrary(other)
            val separate = library.tracks()
            assertEquals(6, separate.size)
            assertEquals(before.map { it.id }.toSet(), separate.filter { track -> track.sources.any {
                it.kind == SourceKind.SERVER && it.id.startsWith("server:$serverId:") } }.map { it.id }.toSet())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anotherAccountAtANewAddressGetsItsOwnIdentity() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-server-account")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val connection = NavidromeConnection(store, http, vault)
            connection.connect("http://192.168.1.5:4533", ServerCredentials("fixture", "test"))
            NavidromeChanges(store, library, client = connection::client).refreshLibrary(requireNotNull(connection.client()))
            val serverId = requireNotNull(connection.profile()).serverId
            connection.connect("https://music.example.test", ServerCredentials("someone", "test"))
            assertEquals(ServerProfile("https://music.example.test", "someone"), connection.profile())
            assertNotEquals(serverId, connection.client()?.serverId)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
