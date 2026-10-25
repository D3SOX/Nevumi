package me.d3sox.nevumi.app

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.releases.ArtistCandidate
import me.d3sox.nevumi.releases.ArtistReleases
import me.d3sox.nevumi.releases.ECHO
import me.d3sox.nevumi.releases.FakeMusicBrainz
import me.d3sox.nevumi.releases.NOVA
import me.d3sox.nevumi.releases.releaseDate
import me.d3sox.nevumi.releases.releaseId
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_following_artist
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Clock

class ReleasesControllerTest {
    private val today = releaseDate(Clock.System.now().toEpochMilliseconds())

    @Test fun followingRefreshingAndNotifyingNeverRepeatAReleaseAndFailuresKeepTheFeed() = runBlocking {
        val directory = Files.createTempDirectory("releases-controller")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val musicBrainz = FakeMusicBrainz()
        val notifications = mutableListOf<Pair<String, String>>()
        val player = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store),
            releases = ArtistReleases(store, musicBrainz.client), releaseNotifier = { title, text -> notifications += title to text })
        val releases = player.releasesController
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            musicBrainz.artists = """{"artists":[{"id":"$NOVA","name":"Nova","disambiguation":"synth duo"},
                {"id":"$ECHO","name":"nova","disambiguation":"jazz singer"}]}"""
            val ambiguous = assertIs<ArtistMatch.Ambiguous>(releases.findArtist("Nova"))
            assertEquals(listOf(NOVA, ECHO), ambiguous.candidates.map { it.id }, "Artists sharing a name are offered to choose from")
            musicBrainz.artists = """{"artists":[{"id":"$NOVA","name":"Nova"},{"id":"$ECHO","name":"Nova Bossa"}]}"""
            assertEquals(ArtistMatch.Found(ArtistCandidate(NOVA, "Nova")), releases.findArtist("nova"))

            musicBrainz.groups[NOVA] = mutableListOf(musicBrainz.group(releaseId(1), "First", today, NOVA to "Nova"))
            val notice = async(start = CoroutineStart.UNDISPATCHED) { player.notices.first() }
            releases.follow("Nova", ArtistCandidate(NOVA, "Nova")).join()
            assertEquals(Res.string.notice_following_artist, notice.await().message)
            val followed = player.state.value
            assertEquals(listOf("Nova"), followed.followedArtists.map { it.libraryName })
            assertEquals(listOf("First"), followed.releases.map { it.title })

            player.updatePreferences { it.copy(notifyNewReleases = true) }.join()
            musicBrainz.groups.getValue(NOVA) += musicBrainz.group(releaseId(2), "Second", today, NOVA to "Nova")
            releases.refresh().join()
            releases.refresh().join()
            assertEquals(listOf("New release from Nova" to "Second"), notifications, "Following announced nothing; each new release once")
            assertEquals(2, player.state.value.releases.size)

            musicBrainz.status = HttpStatusCode.InternalServerError
            releases.refresh().join()
            val failed = player.state.value
            assertEquals("MusicBrainz request failed: HTTP 500", failed.releaseRefresh.error)
            assertFalse(failed.releaseRefresh.running)
            assertEquals(listOf("First", "Second"), failed.releases.map { it.title }.sorted(), "Cached releases stay readable")

            musicBrainz.status = HttpStatusCode.OK
            releases.refresh().join()
            assertNull(player.state.value.releaseRefresh.error, "Retrying clears the error")

            releases.unfollow(NOVA).join()
            withTimeout(3_000) { while (player.state.value.followedArtists.isNotEmpty()) delay(10) }
            assertEquals(emptyList(), player.state.value.releases)
            assertEquals(1, notifications.size)
        } finally { player.closeAndJoin(); musicBrainz.http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun nothingIsLookedUpUntilAnArtistIsFollowed() = runBlocking {
        val directory = Files.createTempDirectory("releases-idle")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val musicBrainz = FakeMusicBrainz()
        val player = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store),
            releases = ArtistReleases(store, musicBrainz.client))
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            player.releasesController.refreshIfStale()
            assertEquals(emptyList(), musicBrainz.queries)
        } finally { player.closeAndJoin(); musicBrainz.http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
