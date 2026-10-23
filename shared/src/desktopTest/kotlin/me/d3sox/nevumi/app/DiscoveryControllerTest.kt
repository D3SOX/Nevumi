package me.d3sox.nevumi.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.ServerDiscovery
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.discovery_nothing_similar
import me.d3sox.nevumi.resources.discovery_playing_similar_songs
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DiscoveryControllerTest {
    private fun ok(body: String) = """{"subsonic-response":{"status":"ok"$body}}"""
    private fun songs(vararg ids: String) = ids.joinToString(",", "[", "]") { """{"id":"$it","title":"Song $it","artist":"Someone"}""" }

    @Test fun similarSongsPlayOnlyWhatTheLibraryHasAndArtistsAreMatchedToTheLibrary() = runBlocking {
        val directory = Files.createTempDirectory("discovery-controller")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val responses = mutableMapOf<String, String>()
        val http = HttpClient(MockEngine { request ->
            respond(responses[request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")] ?: ok(""))
        })
        val vault = object : CredentialVault {
            private var password: String? = null
            override suspend fun save(key: String, secret: String) { password = secret }
            override suspend fun read(key: String) = password
            override suspend fun delete(key: String) { password = null }
        }
        val connection = NavidromeConnection(store, http, vault)
        connection.connect("https://music.example.test", ServerCredentials("fixture", "test"))
        val serverId = connection.client()!!.serverId
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        // Each song plays from a local file, so the test never streams, and is also on the server.
        suspend fun song(title: String, artist: String, songId: String?): Track {
            val local = library.importTrack(TrackMetadata(title, artist), TrackSource("local:$title", SourceKind.LOCAL, audio))
            return songId?.let { library.importTrack(TrackMetadata(title, artist), TrackSource("server:$serverId:$it", SourceKind.SERVER, it), linkTo = local.id) } ?: local
        }
        val seed = song("Seed", "Main Artist", "s1")
        val second = song("Second", "Library Friend", "s2")
        val third = song("Third", "Main Artist", "s3")
        song("Local only", "Main Artist", null)
        responses["getSimilarSongs"] = ok(""","similarSongs":{"song":${songs("s2", "missing", "s1", "s3", "s2")}}""")
        responses["search3"] = ok(""","searchResult3":{"artist":[{"id":"ar-1","name":"Main Artist"}]}""")
        responses["getArtistInfo2"] = ok(""","artistInfo2":{"biography":"Biography","lastFmUrl":"https://www.last.fm/music/Main",
            "similarArtist":[{"id":"ar-2","name":"library friend"},{"name":"Stranger"},{"name":"Main Artist"}]}""")
        responses["getTopSongs"] = ok(""","topSongs":{"song":${songs("missing", "s3", "s1")}}""")
        val queue = PlaybackQueue(store)
        val player = MusicController(library, queue, MpvPlaybackEngine("null"), connection,
            discovery = ServerDiscovery(store, connection::client))
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady && it.server != null } }

            val artist = player.discoveryController.artistInfo("Main Artist")!!
            assertEquals("Biography", artist.biography)
            assertEquals("https://www.last.fm/music/Main", artist.lastFmUrl)
            assertEquals(listOf(LibraryArtist("Library Friend", LibraryTab.ARTISTS)), artist.similarInLibrary)
            assertEquals(listOf("Stranger"), artist.similarElsewhere)
            assertEquals(listOf(third.id, seed.id), artist.topSongs.map { it.id })

            val notice = async(start = CoroutineStart.UNDISPATCHED) { player.notices.first() }
            player.discoveryController.playSimilarToTrack(seed.id).join()
            assertEquals(listOf(second.id, third.id), queue.snapshot().entries.map { it.trackId })
            assertEquals(Res.string.discovery_playing_similar_songs, notice.await().message)
            assertEquals(listOf<Any>(2), notice.await().arguments)
            player.pause().join()

            responses["getSimilarSongs"] = ok(""","similarSongs":{"song":${songs("missing")}}""")
            val nothing = async(start = CoroutineStart.UNDISPATCHED) { player.notices.first() }
            player.discoveryController.playSimilarToTrack(seed.id).join()
            assertEquals(Res.string.discovery_nothing_similar, nothing.await().message)
            assertEquals(listOf(second.id, third.id), queue.snapshot().entries.map { it.trackId }, "Nothing similar leaves the queue alone")
            assertNull(player.state.value.error)
        } finally { player.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun withoutAServerDiscoveryFindsNothing() = runBlocking {
        val directory = Files.createTempDirectory("discovery-offline")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Song", "Artist"), TrackSource("local:song", SourceKind.LOCAL, "/missing.flac"))
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            assertNull(player.discoveryController.artistInfo("Artist"))
            val notice = async(start = CoroutineStart.UNDISPATCHED) { player.notices.first() }
            player.discoveryController.playSimilarToTrack(track.id).join()
            assertEquals(Res.string.discovery_nothing_similar, notice.await().message)
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
