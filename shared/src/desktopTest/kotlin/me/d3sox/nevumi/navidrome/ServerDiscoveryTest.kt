package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerDiscoveryTest {
    private fun ok(body: String) = """{"subsonic-response":{"status":"ok"$body}}"""
    private fun songs(vararg ids: String) = ids.joinToString(",", "[", "]") { """{"id":"$it","title":"Song $it","artist":"Artist","albumId":"album"}""" }

    /** A server that answers every discovery endpoint, or fails them all with [failure]; records each endpoint it serves. */
    private class Server(var failure: String? = null) {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            requests += endpoint
            when {
                failure == "http" -> respondError(HttpStatusCode.NotFound)
                failure == "unsupported" -> respond("""{"subsonic-response":{"status":"failed","error":{"code":0,"message":"Not implemented"}}}""")
                failure == "down" -> respondError(HttpStatusCode.InternalServerError)
                else -> respond(responses[endpoint] ?: """{"subsonic-response":{"status":"ok"}}""")
            }
        })
        val responses = mutableMapOf<String, String>()
        fun client() = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "test"))
    }

    private fun Server.discoveryResponses() = apply {
        responses["search3"] = ok(""","searchResult3":{"artist":[{"id":"ar-other","name":"Artist Two"},{"id":"ar-1","name":"Artist"}]}""")
        responses["getSong"] = ok(""","song":{"id":"s1","title":"One","artistId":"ar-from-song"}""")
        responses["getArtistInfo2"] = ok(""","artistInfo2":{"biography":"A band from Somewhere. <a href=\"https://www.last.fm/music/Artist\">Read more on Last.fm</a>",
            "musicBrainzId":"mbid-1","lastFmUrl":"https://www.last.fm/music/Artist","largeImageUrl":"https://img/l.jpg","smallImageUrl":"",
            "similarArtist":[{"id":"ar-2","name":"Library Friend"},{"name":"Stranger"}]}""")
        responses["getSimilarSongs"] = ok(""","similarSongs":{"song":${songs("s2", "s3")}}""")
        responses["getSimilarSongs2"] = ok(""","similarSongs2":{"song":${songs("s4")}}""")
        responses["getTopSongs"] = ok(""","topSongs":{"song":${songs("s1", "s5")}}""")
    }

    @Test fun theFourDiscoveryEndpointsAreParsed() = runBlocking {
        val server = Server().discoveryResponses()
        val client = server.client()
        try {
            assertEquals("ar-1", client.artistId("artist"))
            val info = client.artistInfo("ar-1")!!
            assertEquals("A band from Somewhere.", info.biography)
            assertEquals("mbid-1", info.musicBrainzId)
            assertEquals("https://www.last.fm/music/Artist", info.lastFmUrl)
            assertEquals(listOf("https://img/l.jpg"), info.imageUrls)
            assertEquals(listOf(ServerArtistRef("ar-2", "Library Friend"), ServerArtistRef(null, "Stranger")), info.similarArtists)
            assertEquals(listOf("s2", "s3"), client.similarSongs("s1", 10)?.map { it.id })
            assertEquals(listOf("s4"), client.similarSongsByArtist("ar-1", 10)?.map { it.id })
            assertEquals(listOf("s1", "s5"), client.topSongs("Artist")?.map { it.id })
            assertEquals("Song s5", client.topSongs("Artist")?.last()?.metadata?.title)
            // Discovery never looks up album releases for its songs.
            assertEquals(0, server.requests.count { it == "getAlbum" })
        } finally { server.http.close() }
    }

    @Test fun anArtistTheSearchMissesIsFoundThroughOneOfTheirSongs() = runBlocking {
        val server = Server().discoveryResponses()
        server.responses["search3"] = ok(""","searchResult3":{"artist":[{"id":"ar-other","name":"Someone Else"}]}""")
        try {
            assertEquals("ar-from-song", server.client().artistId("Artist", songId = "s1"))
            assertNull(server.client().artistId("Artist"))
        } finally { server.http.close() }
    }

    @Test fun serversWithoutTheEndpointsGiveNoResultInsteadOfAnError() = runBlocking {
        for (failure in listOf("http", "unsupported")) {
            val server = Server(failure)
            val client = server.client()
            try {
                assertNull(client.artistId("Artist", "s1"), failure)
                assertNull(client.artistInfo("ar-1"), failure)
                assertNull(client.similarSongs("s1"), failure)
                assertNull(client.similarSongsByArtist("ar-1"), failure)
                assertNull(client.topSongs("Artist"), failure)
            } finally { server.http.close() }
        }
    }

    @Test fun artistDetailsAreCachedForAWeekAndReusedWhileTheServerIsDown() = runBlocking {
        val directory = Files.createTempDirectory("server-discovery-cache")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val server = Server().discoveryResponses()
        val client = server.client()
        var clock = 1_000_000L
        val discovery = ServerDiscovery(store, { client }, now = { clock })
        try {
            val first = discovery.artist("Artist")!!
            assertEquals("ar-1", first.artistId)
            assertEquals(listOf("s1", "s5"), first.topSongIds)
            assertEquals("A band from Somewhere.", first.info?.biography)
            val fetched = server.requests.size
            clock += 6 * 24 * 60 * 60 * 1_000L
            assertEquals(first, discovery.artist("ARTIST"))
            assertEquals(fetched, server.requests.size, "Fresh details must come from the cache")
            assertEquals(1, store.list<ServerArtistDetails>(ARTIST_INFO_KIND).size)

            clock += 2 * 24 * 60 * 60 * 1_000L
            server.responses["getTopSongs"] = ok(""","topSongs":{"song":${songs("s9")}}""")
            assertEquals(listOf("s9"), discovery.artist("Artist")?.topSongIds)
            assert(server.requests.size > fetched) { "Expired details must be fetched again" }

            clock += 8 * 24 * 60 * 60 * 1_000L
            server.failure = "down"
            assertEquals(listOf("s9"), discovery.artist("Artist")?.topSongIds, "Stale details are better than none while the server is down")
            assertNull(discovery.artist("Unknown Artist"))
            assertNull(discovery.similarToSong("s1"))

            server.failure = null
            assertEquals(listOf("s2", "s3"), discovery.similarToSong("s1")?.map { it.id })
            assertEquals(listOf("s4"), discovery.similarToArtist("Artist")?.map { it.id })
            val disconnected = ServerDiscovery(store, { null })
            assertNull(disconnected.serverId())
            assertNull(disconnected.artist("Artist"))
            assertNull(disconnected.similarToSong("s1"))
            assertNull(ServerDiscovery(store, { error("Unlock your credential store") }).artist("Artist"))
        } finally { server.http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
