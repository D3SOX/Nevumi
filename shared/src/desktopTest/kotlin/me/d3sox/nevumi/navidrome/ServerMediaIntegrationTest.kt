package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.inLibrary
import me.d3sox.nevumi.library.isPodcastEpisode
import me.d3sox.nevumi.library.isRadio
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.*

/**
 * Internet radio, podcasts and bookmarks against the gonic fixture, and share links against the Navidrome fixture. The radio
 * stream comes from scripts/test-radio-stream.py, and gonic reads the podcast feed that scripts/test-subsonic.py serves inside
 * its container.
 */
class ServerMediaIntegrationTest {
    private val gonic = "http://127.0.0.1:14537"
    private val radioStream = "http://127.0.0.1:14539/radio"
    private val feed = "http://127.0.0.1:8080/podcast.xml"

    private suspend fun NavidromeClient.removeFixtures() {
        radioStations().orEmpty().filter { it.streamUrl == radioStream }.forEach { deleteRadioStation(it.id) }
        podcasts().orEmpty().filter { it.url == feed }.forEach { deletePodcastChannel(it.id) }
    }

    private suspend fun NavidromeClient.subscribedEpisode(): PodcastEpisode {
        subscribePodcast(feed)
        val channel = withTimeout(30_000) {
            while (podcasts().orEmpty().none { it.url == feed && it.episodes.size == 2 }) delay(250)
            podcasts().orEmpty().single { it.url == feed }
        }
        assertEquals("Nevumi Fixture Podcast", channel.title)
        assertTrue(channel.episodes.none { it.playable }, "gonic lists new episodes without downloading them")
        // Both episodes download, so the second can also be reached from the first through the queue.
        channel.episodes.forEach { downloadPodcastEpisode(it.id) }
        return withTimeout(60_000) {
            var episodes: List<PodcastEpisode>
            do {
                delay(250)
                episodes = podcasts().orEmpty().single { it.url == feed }.episodes
                episodes.forEach { assertNotEquals(PodcastStatus.ERROR, it.status) }
            } while (!episodes.all { it.playable })
            episodes.single { it.title == "Fixture episode two" }
        }
    }

    @Test fun radioStationsPodcastsAndBookmarksWorkAgainstGonic() = runBlocking {
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, gonic, ServerCredentials("admin", "admin"))
        try {
            client.removeFixtures()
            assertEquals(ServerRoles(admin = true, podcast = true, share = false), client.roles())
            // gonic has no share links, so the app offers none.
            assertNull(client.shares())

            client.createRadioStation("Nevumi fixture radio", radioStream, "http://127.0.0.1:14539/")
            val station = client.radioStations().orEmpty().single { it.streamUrl == radioStream }
            assertEquals("Nevumi fixture radio", station.name)
            assertEquals("http://127.0.0.1:14539/", station.homepageUrl)
            client.updateRadioStation(station.id, "Renamed fixture radio", radioStream)
            assertEquals("Renamed fixture radio", client.radioStations().orEmpty().single { it.id == station.id }.name)
            client.deleteRadioStation(station.id)
            assertTrue(client.radioStations().orEmpty().none { it.id == station.id })

            val episode = client.subscribedEpisode()
            assertEquals(120_000, episode.durationMs)
            assertTrue(client.newestPodcasts(5).orEmpty().any { it.id == episode.id && it.playable })
            val streamId = requireNotNull(episode.streamId)
            val audio = http.get(client.streamUrl(streamId))
            assertEquals("audio/mpeg", audio.headers[HttpHeaders.ContentType])
            assertTrue(audio.readRawBytes().size > 100_000)

            client.saveBookmark(streamId, 42_000)
            assertEquals(42_000, client.bookmarks()?.get(streamId))
            client.deleteBookmark(streamId)
            client.deleteBookmark(streamId)
            assertNull(client.bookmarks()?.get(streamId))

            client.deletePodcastEpisode(episode.id)
            assertFalse(client.podcasts().orEmpty().single { it.url == feed }.episodes.single { it.id == episode.id }.playable)
            client.refreshPodcasts()
        } finally {
            client.removeFixtures()
            http.close()
        }
        assertTrue(NavidromeClient(HttpClient(OkHttp), gonic, ServerCredentials("admin", "admin")).podcasts().orEmpty().none { it.url == feed })
    }

    @Test fun shareLinksCanBeCreatedListedAndDeletedOnNavidrome() = runBlocking {
        val http = HttpClient(OkHttp)
        try {
            val client = NavidromeClient(http, "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
            assertTrue(client.roles().share)
            val song = client.songs().first()
            val share = client.createShare(listOf(song.id), "Nevumi fixture share")
            try {
                assertTrue(share.url.startsWith("http://127.0.0.1:14533/share/"), share.url)
                val listed = client.shares().orEmpty().single { it.id == share.id }
                assertEquals("Nevumi fixture share", listed.description)
                assertEquals(listOf(song.metadata.title), listed.entryTitles)
                assertNotNull(listed.expires)
            } finally { client.deleteShare(share.id) }
            assertTrue(client.shares().orEmpty().none { it.id == share.id })
            // Navidrome has no podcasts, so the app hides them.
            assertNull(client.podcasts())
        } finally { http.close() }
    }

    @Test fun theAppSharesOnlyOnTheServerThatSuppliedTheSongs() = runBlocking {
        val directory = Files.createTempDirectory("server-media-share")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val connection = NavidromeConnection(store, http, object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        })
        val navidrome = "http://127.0.0.1:14533"
        val client = NavidromeClient(http, navidrome, ServerCredentials("fixture", "fixture-password"))
        var controller: MusicController? = null
        try {
            connection.connect(navidrome, ServerCredentials("fixture", "fixture-password"))
            val player = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), connection)
            controller = player
            val media = player.serverMediaController
            val serverId = withTimeout(10_000) { media.media.first { it.sharingAvailable } }.serverId
            val song = client.songs().first()
            var delivered: ServerShare? = null
            // Song IDs taken from another server must not become a link here.
            media.share("another-server", listOf(song.id), "Wrong server") { delivered = it; false }.join()
            assertNull(delivered)
            assertEquals("The music server changed. Open the page again.", player.state.value.error)
            player.dismissError("The music server changed. Open the page again.")
            media.share(serverId, listOf(song.id), "Nevumi controller share") { delivered = it; false }.join()
            val share = requireNotNull(delivered)
            try { assertTrue(media.media.value.shares.orEmpty().any { it.id == share.id }) }
            finally { client.deleteShare(share.id) }
        } finally {
            controller?.closeAndJoin()
            connection.disconnect(); http.close(); store.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun aStationPlaysLiveWithItsStreamTitleAndAnEpisodeResumesFromItsBookmark() = runBlocking {
        val directory = Files.createTempDirectory("server-media-playback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val connection = NavidromeConnection(store, http, object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        })
        val client = NavidromeClient(http, gonic, ServerCredentials("admin", "admin"))
        val library = MusicLibrary(store)
        var controller: MusicController? = null
        try {
            client.removeFixtures()
            client.createRadioStation("Nevumi fixture radio", radioStream)
            client.createRadioStation("Nevumi fixture radio two", radioStream)
            val episode = client.subscribedEpisode()
            val streamId = requireNotNull(episode.streamId)
            client.saveBookmark(streamId, 30_000)
            connection.connect(gonic, ServerCredentials("admin", "admin"))
            val history = ListeningHistory(store, library)
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection, listeningHistory = history)
            controller = player
            val media = player.serverMediaController
            val loaded = withTimeout(10_000) {
                media.media.first { it.stations.orEmpty().count { station -> station.streamUrl == radioStream } == 2 && it.bookmarks[streamId] != null }
            }
            assertTrue(loaded.radioAvailable && loaded.podcastsAvailable && !loaded.sharingAvailable)
            // A dialog still showing another server's station must not change one with the same ID here.
            val listed = loaded.stations.orEmpty().first { it.streamUrl == radioStream }
            media.deleteStation("another-server", listed.id).join()
            assertTrue(client.radioStations().orEmpty().any { it.id == listed.id })
            assertEquals("The music server changed. Open the page again.", player.state.value.error)
            player.dismissError("The music server changed. Open the page again.")
            // A page that does not know its server yet reports it, rather than failing in the click handler.
            for (server in listOf("another-server", null)) {
                media.addStation(server, "Stale dialog", radioStream, "").join()
                assertTrue(client.radioStations().orEmpty().none { it.name == "Stale dialog" })
                assertEquals("The music server changed. Open the page again.", player.state.value.error)
                player.dismissError("The music server changed. Open the page again.")
            }

            media.playStation(loaded.stations.orEmpty().last { it.streamUrl == radioStream }).join()
            val live = withTimeout(10_000) { player.state.first { it.playback.playing && it.playback.streamTitle != null } }
            assertEquals("Fixture Artist - Live Song One", live.playback.streamTitle)
            assertEquals(0, live.playback.durationMs)
            val station = live.tracks.single { it.id == live.playback.trackId }
            assertTrue(station.isRadio && !station.inLibrary)
            delay(3_500)
            assertFalse(player.state.value.playback.ended)
            // A live stream has no start to restart, so Previous changes the station even after a few seconds.
            player.skip(-1).join()
            val previous = withTimeout(10_000) { player.state.first { it.playback.playing && it.playback.trackId != station.id } }
            assertTrue(previous.tracks.single { it.id == previous.playback.trackId }.isRadio)
            player.pause().join()

            media.playEpisode(loaded.podcasts.orEmpty().flatMap { it.episodes }.single { it.id == episode.id }).join()
            val resumed = withTimeout(10_000) { player.state.first { it.playback.playing && it.playback.positionMs >= 29_000 } }
            assertEquals(120_000, resumed.playback.durationMs, 1_000)
            assertNull(resumed.playback.streamTitle)
            assertTrue(resumed.tracks.single { it.id == resumed.playback.trackId }.isPodcastEpisode)
            delay(1_000)
            player.pause().join()
            // Pausing keeps the position as a bookmark on the server, where other clients find it too.
            val saved = withTimeout(10_000) { media.media.first { (it.bookmarks[streamId] ?: 0) > 30_000 } }.bookmarks.getValue(streamId)
            assertEquals(saved, client.bookmarks()?.get(streamId))
            // Reaching the episode through the queue resumes its bookmark too.
            val channel = media.media.value.podcasts.orEmpty().single { it.url == feed }
            media.playEpisode(channel.episodes.single { it.title == "Fixture episode one" }).join()
            withTimeout(10_000) { player.state.first { it.playback.playing } }
            player.skip(1).join()
            val queued = withTimeout(10_000) { player.state.first { it.playback.playing && it.playback.positionMs >= saved - 1_000 } }
            assertEquals(resumed.playback.trackId, queued.playback.trackId)
            player.pause().join()
            // Neither a station nor an episode counts as a listen.
            assertEquals(emptyList(), history.all())
        } finally {
            controller?.closeAndJoin()
            runCatching { client.deleteBookmark(requireNotNull(client.podcasts().orEmpty().firstOrNull { it.url == feed }
                ?.episodes?.firstOrNull { it.title == "Fixture episode two" }?.streamId)) }
            client.removeFixtures()
            connection.disconnect(); http.close(); store.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun assertEquals(expected: Long, actual: Long, tolerance: Long) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "Expected $expected ± $tolerance but was $actual")
}
