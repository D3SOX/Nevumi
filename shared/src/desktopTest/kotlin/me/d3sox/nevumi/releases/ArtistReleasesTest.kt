package me.d3sox.nevumi.releases

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Instant

/** A MusicBrainz stand-in whose release groups per artist can change between refreshes. */
internal class FakeMusicBrainz {
    val groups = mutableMapOf<String, MutableList<String>>()
    var artists = """{"artists":[]}"""
    var status = HttpStatusCode.OK
    val failingArtists = mutableSetOf<String>()
    val covers = mutableMapOf<String, ByteArray>()
    val queries = mutableListOf<String>()
    val userAgents = mutableListOf<String?>()
    val http = HttpClient(MockEngine { request ->
        userAgents += request.headers[HttpHeaders.UserAgent]
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val query = request.url.parameters["query"].orEmpty()
        if (status != HttpStatusCode.OK || failingArtists.any { "arid:$it" in query })
            return@MockEngine respond("{}", if (status != HttpStatusCode.OK) status else HttpStatusCode.ServiceUnavailable, json)
        queries += query
        when {
            request.url.encodedPath.endsWith("/artist") -> respond(artists, headers = json)
            request.url.encodedPath.endsWith("/release-group") -> {
                val artist = Regex("arid:([0-9a-f-]+)").find(query)!!.groupValues[1]
                val since = Regex("firstreleasedate:\\[(\\S+) TO").find(query)!!.groupValues[1]
                val matching = groups[artist].orEmpty().filter { Regex("\"first-release-date\":\"([^\"]*)\"").find(it)!!.groupValues[1] >= since }
                val offset = request.url.parameters["offset"]?.toInt() ?: 0
                val limit = request.url.parameters["limit"]!!.toInt()
                respond("""{"count":${matching.size},"release-groups":[${matching.drop(offset).take(limit).joinToString(",")}]}""", headers = json)
            }
            request.url.encodedPath.endsWith("/front-250") -> covers[request.url.encodedPath.split('/').dropLast(1).last()]
                ?.let { respond(it, headers = headersOf(HttpHeaders.ContentType, "image/png")) } ?: respond("", HttpStatusCode.NotFound)
            else -> respond("", HttpStatusCode.NotFound)
        }
    })
    val client = MusicBrainzClient(http, "test", spacingMs = 0)

    fun group(id: String, title: String, date: String, vararg artists: Pair<String, String>, type: String = "Album",
        releases: List<String> = emptyList()) = """{"id":"$id","title":"$title","first-release-date":"$date","primary-type":"$type",
        "artist-credit":[${artists.joinToString(",") { (artistId, name) -> """{"name":"$name","joinphrase":"${if (artistId == artists.last().first) "" else " & "}","artist":{"id":"$artistId","name":"$name"}}""" }}],
        "releases":[${releases.joinToString(",") { """{"id":"$it"}""" }}]}"""
}

internal const val NOVA = "11111111-1111-4111-8111-111111111111"
internal const val ECHO = "22222222-2222-4222-8222-222222222222"
internal fun releaseId(number: Int) = "00000000-0000-4000-8000-%012d".format(number)

class ArtistReleasesTest {
    private var now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilliseconds()

    private fun withReleases(block: suspend (ArtistReleases, FakeMusicBrainz) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("artist-releases")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val musicBrainz = FakeMusicBrainz()
        try { block(ArtistReleases(store, musicBrainz.client) { now }, musicBrainz) }
        finally { musicBrainz.http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun refreshingUpdatesReleasesInPlaceAndAnnouncesOnlyRecentOnesFoundAfterTheFirstCheck() = withReleases { releases, musicBrainz ->
        musicBrainz.groups[NOVA] = mutableListOf(musicBrainz.group(releaseId(1), "Old Light", "2026-03-01", NOVA to "Nova"))
        releases.follow(FollowedArtist(NOVA, "Nova"))
        assertEquals(ReleaseUpdate(emptyList()), releases.refresh(), "An artist's releases at the time it is followed are not new")
        assertEquals(listOf("Old Light"), releases.releases().map { it.title })
        assertTrue(musicBrainz.queries.last().contains("firstreleasedate:[2025-10-25 TO *]"), musicBrainz.queries.last())

        now += 86_400_000L
        musicBrainz.groups.getValue(NOVA) += listOf(
            musicBrainz.group(releaseId(2), "Upcoming", "2026-12-01", NOVA to "Nova", type = "Single"),
            musicBrainz.group(releaseId(3), "Just Catalogued", "2026-02-01", NOVA to "Nova"))
        val announced = releases.refresh().announced
        assertEquals(listOf("Upcoming"), announced.map { it.title }, "Only recent or upcoming new releases are announced")
        assertEquals(ReleaseType.SINGLE, announced.single().type)

        assertEquals(ReleaseUpdate(emptyList()), releases.refresh(), "Refreshing again announces nothing twice")
        assertEquals(3, releases.releases().size, "Refreshing never duplicates a release")
        assertEquals(now, releases.refreshedAt())

        // A year later the first releases fall out of the window and are forgotten.
        now += 300 * 86_400_000L
        releases.refresh()
        assertEquals(listOf("Upcoming"), releases.releases().map { it.title })
    }

    @Test fun unfollowingForgetsOnlyTheReleasesNoOtherFollowedArtistIsCreditedOn() = withReleases { releases, musicBrainz ->
        val duet = musicBrainz.group(releaseId(1), "Duet", "2026-09-01", NOVA to "Nova", ECHO to "Echo")
        musicBrainz.groups[NOVA] = mutableListOf(duet, musicBrainz.group(releaseId(2), "Solo", "2026-09-02", NOVA to "Nova"))
        musicBrainz.groups[ECHO] = mutableListOf(duet)
        releases.follow(FollowedArtist(NOVA, "Nova"))
        releases.follow(FollowedArtist(ECHO, "Echo"))
        releases.refresh()
        assertEquals("Nova & Echo", releases.releases().first { it.title == "Duet" }.artist)

        releases.unfollow(NOVA)
        assertEquals(listOf("Echo"), releases.followed().map { it.name })
        assertEquals(listOf("Duet"), releases.releases().map { it.title })
        val before = musicBrainz.queries.size
        releases.refresh()
        assertTrue(musicBrainz.queries.drop(before).none { "arid:$NOVA" in it }, "An unfollowed artist is no longer looked up")
    }

    @Test fun aFailingProviderKeepsTheCachedReleasesAndOtherArtistsRefresh() = withReleases { releases, musicBrainz ->
        musicBrainz.groups[NOVA] = mutableListOf(musicBrainz.group(releaseId(1), "Kept", "2026-09-01", NOVA to "Nova"))
        musicBrainz.groups[ECHO] = mutableListOf()
        releases.follow(FollowedArtist(NOVA, "Nova"))
        releases.follow(FollowedArtist(ECHO, "Echo"))
        releases.refresh()
        val refreshedAt = releases.refreshedAt()

        now += 86_400_000L
        musicBrainz.failingArtists += NOVA
        musicBrainz.groups.getValue(ECHO) += musicBrainz.group(releaseId(2), "Fresh", "2026-10-26", ECHO to "Echo")
        val update = releases.refresh()
        assertEquals("MusicBrainz is busy. Try again in a moment.", update.failure?.message)
        assertEquals(listOf("Fresh"), update.announced.map { it.title }, "Another artist's failure does not swallow this one's news")
        assertEquals(listOf("Fresh", "Kept"), releases.releases().map { it.title }.sorted())
        assertEquals(refreshedAt, releases.refreshedAt(), "A failed refresh does not count as a complete one")
    }

    @Test fun everyPageOfReleasesIsFetchedAndCreditsWithoutTheirOwnNameUseTheArtists() = withReleases { releases, musicBrainz ->
        musicBrainz.groups[NOVA] = (1..230).map { musicBrainz.group(releaseId(it), "Release $it", "2026-09-01", NOVA to "Nova") }.toMutableList()
        musicBrainz.groups.getValue(NOVA)[0] = """{"id":"${releaseId(1)}","title":"Unnamed credit","first-release-date":"2026-09-01",
            "artist-credit":[{"joinphrase":" feat. ","artist":{"id":"$NOVA","name":"Nova"}},{"name":"Echo","artist":{"id":"$ECHO","name":"Echo"}}]}"""
        releases.follow(FollowedArtist(NOVA, "Nova"))
        releases.refresh()
        assertEquals(230, releases.releases().size)
        assertEquals(3, musicBrainz.queries.size)
        assertEquals("Nova feat. Echo", releases.releases().first { it.title == "Unnamed credit" }.artist)
    }

    @Test fun artistSearchIdentifiesTheAppAndSearchesTheNameAsWritten() = withReleases { releases, musicBrainz ->
        musicBrainz.artists = """{"artists":[{"id":"$NOVA","name":"Nova","disambiguation":"synth duo","type":"Group","country":"SE"},
            {"id":"$ECHO","name":"Nova","type":"Person"},{"id":"","name":"Broken"}]}"""
        val candidates = releases.search("AC/DC \"Live\"")
        assertEquals(listOf(ArtistCandidate(NOVA, "Nova", "synth duo", "Group", "SE"), ArtistCandidate(ECHO, "Nova", type = "Person")), candidates)
        assertEquals("\"AC\\/DC \\\"Live\\\"\"", musicBrainz.queries.single())
        assertEquals("Nevumi/test (https://github.com/D3SOX/Nevumi)", musicBrainz.userAgents.single())
    }
}
