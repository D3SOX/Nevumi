package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.ServerDiscovery
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ArtistDiscoveryUiTest {
    private fun ok(body: String) = """{"subsonic-response":{"status":"ok"$body}}"""
    private fun songs(vararg ids: String) = ids.joinToString(",", "[", "]") { """{"id":"$it","title":"Song $it"}""" }

    @Test
    fun theArtistPageShowsServerDiscoveryAndSongsOfferSimilarMusic() {
        val directory = Files.createTempDirectory("artist-discovery-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val responses = mapOf(
            "search3" to ok(""","searchResult3":{"artist":[{"id":"ar-1","name":"Main Artist"}]}"""),
            "getArtistInfo2" to ok(""","artistInfo2":{"biography":"Main Artist formed in a small town.","lastFmUrl":"https://www.last.fm/music/Main",
                "similarArtist":[{"id":"ar-2","name":"Library Friend"},{"name":"Stranger"}]}"""),
            "getTopSongs" to ok(""","topSongs":{"song":${songs("s3")}}"""),
            "getSimilarSongs" to ok(""","similarSongs":{"song":${songs("s2", "missing", "s3")}}"""))
        val http = HttpClient(MockEngine { request -> respond(responses[request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")] ?: ok("")) })
        val vault = object : CredentialVault {
            private var password: String? = null
            override suspend fun save(key: String, secret: String) { password = secret }
            override suspend fun read(key: String) = password
            override suspend fun delete(key: String) { password = null }
        }
        val connection = NavidromeConnection(store, http, vault)
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val ids = runBlocking {
            connection.connect("https://music.example.test", ServerCredentials("fixture", "test"))
            val serverId = connection.client()!!.serverId
            listOf(Triple("Seed", "Main Artist", "s1"), Triple("Second", "Library Friend", "s2"), Triple("Third", "Main Artist", "s3")).map { (title, artist, songId) ->
                val local = library.importTrack(TrackMetadata(title, artist), TrackSource("local:$title", SourceKind.LOCAL, audio))
                library.importTrack(TrackMetadata(title, artist), TrackSource("server:$serverId:$songId", SourceKind.SERVER, songId), linkTo = local.id).id
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection,
            preferences = MusicPreferences(store).withOptionsButtons(), discovery = ServerDiscovery(store, connection::client))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading && controller.state.value.server != null }
                onNodeWithText("Artists").performClick()
                onNodeWithText("Main Artist").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Main Artist formed in a small town.").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("About").assertExists()
                onNodeWithText("Open on Last.fm").assertExists()
                onNodeWithText("Top songs").assertExists()
                onNodeWithText("Stranger").assertIsNotEnabled()
                onNodeWithContentDescription("Stranger is not in your library").assertExists()
                onNodeWithText("Play similar").assertExists()
                onNodeWithText("Library Friend").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodes(hasText("Second") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }

                onNodeWithText("Songs").performClick()
                onNodeWithContentDescription("Track options: Seed").performClick()
                onNodeWithText("Play similar songs").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.map { it.trackId } == listOf(ids[1], ids[2]) }
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Playing 2 similar songs").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
