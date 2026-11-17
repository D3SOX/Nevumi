package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.SourceResolver
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.library.inLibrary
import me.d3sox.nevumi.library.isPodcastEpisode
import me.d3sox.nevumi.library.isRadio
import kotlin.test.*

class ServerMediaTest {
    private fun envelope(body: String): JsonObject =
        Json.parseToJsonElement("""{"status":"ok",$body}""").jsonObject

    @Test fun radioStationsAcceptBothHomepageSpellingsAndSkipStationsWithoutAStream() {
        val stations = parseRadioStations(envelope(""""internetRadioStations":{"internetRadioStation":[
            {"id":"ir-1","name":"Jazz","streamUrl":"https://jazz.test/live","homePageUrl":"https://jazz.test"},
            {"id":"ir-2","name":"","streamUrl":"https://news.test/stream","homepageUrl":"https://news.test"},
            {"id":"ir-3","name":"Broken"}]}"""))
        assertEquals(listOf(RadioStation("ir-1", "Jazz", "https://jazz.test/live", "https://jazz.test"),
            RadioStation("ir-2", "https://news.test/stream", "https://news.test/stream", "https://news.test")), stations)
    }

    @Test fun onlyWebAddressesFromTheServerReachThePlayerOrTheBrowser() {
        val stations = parseRadioStations(envelope(""""internetRadioStations":{"internetRadioStation":[
            {"id":"1","name":"Web","streamUrl":"https://web.test/stream","homePageUrl":"javascript:alert(1)"},
            {"id":"2","name":"File","streamUrl":"file:///etc/passwd","homePageUrl":"https://file.test"}]}"""))
        assertEquals(listOf(RadioStation("1", "Web", "https://web.test/stream", "")), stations)
        assertEquals(emptyList(), parseShares(envelope(""""shares":{"share":[{"id":"s1","url":"intent://share/s1"}]}""")))
    }

    @Test fun emptyListsAreEmptyHoweverTheServerWritesThem() {
        assertEquals(emptyList(), parseRadioStations(envelope(""""internetRadioStations":{}""")))
        assertEquals(emptyList(), parsePodcasts(envelope(""""podcasts":{"channel":null}""")))
        assertEquals(emptyList(), parseNewestPodcasts(envelope(""""newestPodcasts":{"episode":null}""")))
        assertEquals(emptyList(), parseShares(envelope(""""shares":{}""")))
        assertEquals(emptyMap(), parseBookmarks(envelope(""""bookmarks":{}""")))
    }

    @Test fun aSingleEntryWrittenAsAnObjectIsAList() {
        val stations = parseRadioStations(envelope(""""internetRadioStations":{"internetRadioStation":{"id":"1","name":"One","streamUrl":"http://one.test"}}"""))
        assertEquals(listOf("1"), stations.map { it.id })
    }

    @Test fun podcastEpisodesTakeTheirChannelsTitleAndCoverAndPlayOnlyOnceDownloaded() {
        val channels = parsePodcasts(envelope(""""podcasts":{"channel":[{"id":"pd-1","url":"https://feed.test/rss","title":"Show",
            "description":"About things","coverArt":"pd-1","status":"completed","episode":[
            {"id":"pe-1","streamId":"pe-1","channelId":"pd-1","title":"First","publishDate":"2024-11-02T10:00:00Z","status":"skipped","duration":120},
            {"id":"pe-2","streamId":"s-2","channelId":"pd-1","title":"Second","publishDate":"2024-11-09T10:00:00Z","status":"completed","duration":90,"artist":"Host"},
            {"id":"pe-3","channelId":"pd-1","title":"Third","publishDate":"2024-11-16T10:00:00Z","status":"downloading"}]}]}"""))
        val channel = channels.single()
        assertEquals(PodcastChannel("pd-1", "https://feed.test/rss", "Show", "About things", "pd-1", PodcastStatus.COMPLETED,
            episodes = channel.episodes), channel)
        assertEquals(listOf("pe-3", "pe-2", "pe-1"), channel.episodes.map { it.id })
        val second = channel.episodes.single { it.id == "pe-2" }
        assertEquals(PodcastEpisode("pe-2", "pd-1", "Second", "s-2", publishDate = "2024-11-09T10:00:00Z", status = PodcastStatus.COMPLETED,
            durationMs = 90_000, coverArt = "pd-1", channelTitle = "Show", artist = "Host"), second)
        assertEquals(listOf("pe-2"), channel.episodes.filter { it.playable }.map { it.id })
        assertEquals(PodcastStatus.NEW, PodcastStatus.of("unknown"))
    }

    @Test fun newestEpisodesNameTheirChannel() {
        val channels = listOf(PodcastChannel("pd-1", "https://feed.test/rss", "Show"))
        val episodes = parseNewestPodcasts(envelope(""""newestPodcasts":{"episode":[
            {"id":"pe-2","streamId":"pe-2","channelId":"pd-1","title":"Second","status":"completed","album":"Album title"},
            {"id":"pe-9","channelId":"pd-9","title":"Elsewhere","status":"new","album":"Other show"}]}"""), channels)
        assertEquals(listOf("Show", "Other show"), episodes.map { it.channelTitle })
    }

    @Test fun sharesListTheirSongsAndAccountRolesDefaultToAllowed() {
        val shares = parseShares(envelope(""""shares":{"share":[{"id":"s1","url":"https://music.test/share/s1","description":"For you",
            "created":"2026-11-16T21:16:56Z","expires":"2027-11-16T21:16:56Z","visitCount":3,"entry":[{"id":"a","title":"Song A"},{"id":"b","title":"Song B"}]},
            {"id":"s2"}]}"""))
        assertEquals(listOf(ServerShare("s1", "https://music.test/share/s1", "For you", "2026-11-16T21:16:56Z", "2027-11-16T21:16:56Z", 3,
            listOf("Song A", "Song B"))), shares)
        assertEquals(ServerRoles(admin = false, podcast = true, share = false),
            parseRoles(envelope(""""user":{"username":"me","adminRole":false,"podcastRole":true,"shareRole":false}""")))
        assertEquals(ServerRoles(), parseRoles(envelope(""""user":{"username":"me"}""")))
    }

    @Test fun bookmarksMapStreamIdsToPositions() {
        assertEquals(mapOf("pe-2" to 42_000L), parseBookmarks(envelope(""""bookmarks":{"bookmark":[
            {"entry":{"id":"pe-2","title":"Second"},"position":42000},{"entry":{"id":"bad"}}]}""")))
    }

    @Test fun unsupportedFeaturesAreNullWhilePermissionErrorsStillFail() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")) {
                // Navidrome answers podcast requests, and share requests while sharing is off, with HTTP 501; gonic has no shares.
                "getPodcasts" -> respond("This endpoint is not implemented", HttpStatusCode.NotImplemented)
                "getShares" -> respond("""{"subsonic-response":{"status":"failed","error":{"code":70,"message":"view not found"}}}""")
                "getInternetRadioStations" -> respond("""{"subsonic-response":{"status":"ok","internetRadioStations":{}}}""")
                "createInternetRadioStation" -> respond("""{"subsonic-response":{"status":"failed","error":{"code":50}}}""")
                "getUser" -> respond("""{"subsonic-response":{"status":"failed","error":{"code":50}}}""")
                else -> error("Unexpected request ${request.url}")
            }
        })
        try {
            val client = NavidromeClient(http, "https://server.test", ServerCredentials("user", "secret"), retryDelaysMillis = emptyList())
            assertNull(client.podcasts())
            assertNull(client.shares())
            assertEquals(emptyList(), client.radioStations())
            assertEquals(50, assertFailsWith<NavidromeException> { client.createRadioStation("Jazz", "https://jazz.test/live") }.code)
            assertEquals(ServerRoles(), client.roles())
            assertFailsWith<IllegalArgumentException> { client.createRadioStation("Jazz", "jazz.test/live") }
            assertFailsWith<IllegalArgumentException> { client.subscribePodcast("feed.test/rss") }
            Unit
        } finally { http.close() }
    }

    @Test fun sharesSendEverySongAndTheDescription() = runBlocking {
        var parameters: Parameters? = null
        val http = HttpClient(MockEngine { request ->
            parameters = request.url.parameters
            respond("""{"subsonic-response":{"status":"ok","shares":{"share":[{"id":"s1","url":"https://music.test/share/s1"}]}}}""")
        })
        try {
            val share = NavidromeClient(http, "https://server.test", ServerCredentials("user", "secret")).createShare(listOf("a", "b"), "Album")
            assertEquals("https://music.test/share/s1", share.url)
            assertEquals(listOf("a", "b"), parameters?.getAll("id"))
            assertEquals("Album", parameters?.get("description"))
        } finally { http.close() }
    }

    @Test fun stationsAndEpisodesStreamButStayOutOfTheLibrary() {
        val radio = Track("r", TrackMetadata("Jazz"), listOf(TrackSource("radio:server:ir-1", SourceKind.RADIO, "https://jazz.test/live")))
        val episode = Track("e", TrackMetadata("Second"), listOf(TrackSource("podcast:server:pe-2", SourceKind.PODCAST, "pe-2")))
        assertFalse(radio.inLibrary)
        assertFalse(episode.inLibrary)
        assertTrue(radio.isRadio && !radio.isPodcastEpisode)
        assertTrue(episode.isPodcastEpisode && !episode.isRadio)
        assertEquals("radio:server:ir-1", SourceResolver.resolve(radio.sources)?.id)
        assertNull(SourceResolver.resolve(radio.sources, online = false))
        assertNull(SourceResolver.resolve(episode.sources, online = false))
        assertTrue(SourceKind.RADIO.streams && SourceKind.PODCAST.streams && !SourceKind.LOCAL.streams)
    }
}
