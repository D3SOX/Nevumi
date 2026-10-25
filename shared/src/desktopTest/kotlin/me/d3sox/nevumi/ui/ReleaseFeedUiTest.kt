package me.d3sox.nevumi.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.releases.ArtistCandidate
import me.d3sox.nevumi.releases.ArtistReleases
import me.d3sox.nevumi.releases.ECHO
import me.d3sox.nevumi.releases.FakeMusicBrainz
import me.d3sox.nevumi.releases.NOVA
import me.d3sox.nevumi.releases.releaseDate
import me.d3sox.nevumi.releases.releaseId
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class ReleaseFeedUiTest {
    private fun cover(from: Int, to: Int): ByteArray {
        val image = java.awt.image.BufferedImage(250, 250, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(from), 250f, 250f, java.awt.Color(to)); fillRect(0, 0, 250, 250); dispose()
        }
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun ComposeUiTest.snapshot(name: String, node: SemanticsNodeInteraction = onRoot()) = System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
        waitForIdle()
        val pixels = node.captureToImage().toPixelMap()
        val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        javax.imageio.ImageIO.write(image, "png", Path.of(directory).also(Files::createDirectories).resolve("$name.png").toFile())
    }

    private fun withPlayer(block: (MusicController, FakeMusicBrainz) -> Unit) {
        val directory = Files.createTempDirectory("nevumi-releases-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        runBlocking { library.importTrack(TrackMetadata("Song", "Nova", "First"), TrackSource("song", SourceKind.LOCAL, "/music/song.flac")) }
        val musicBrainz = FakeMusicBrainz()
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store),
            releases = ArtistReleases(store, musicBrainz.client))
        try { block(controller, musicBrainz) }
        finally { runBlocking { controller.closeAndJoin() }; musicBrainz.http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun anArtistSharingItsNameIsConfirmedBeforeItIsFollowed() = withPlayer { controller, musicBrainz ->
        musicBrainz.artists = """{"artists":[{"id":"$NOVA","name":"Nova","disambiguation":"synth duo"},{"id":"$ECHO","name":"Nova","disambiguation":"jazz singer"}]}"""
        runComposeUiTest {
            setContent {
                val state by controller.state.collectAsState()
                MusicTheme(state.preferences, null) { ArtistFollowButton("Nova", state, controller) }
            }
            onNodeWithContentDescription("Follow Nova").performClick()
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("jazz singer").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Several artists on MusicBrainz match Nova. Choose the one you mean.").assertIsDisplayed()
            snapshot("releases-artist-match", onNodeWithTag("artist-match"))
            onNodeWithTag("artist-candidate:$NOVA").performClick()
            waitUntil(timeoutMillis = 3_000) { controller.state.value.followedArtists.isNotEmpty() }
            assertEquals(listOf(NOVA), controller.state.value.followedArtists.map { it.id })
            onNodeWithContentDescription("Unfollow Nova").assertIsDisplayed()
            onNodeWithTag("artist-match").assertDoesNotExist()
        }
    }

    @Test fun homeLeadsToTheFeedWhichKeepsSavedReleasesThroughAFailedRefresh() = withPlayer { controller, musicBrainz ->
        val today = releaseDate(Clock.System.now().toEpochMilliseconds())
        musicBrainz.groups[NOVA] = mutableListOf(musicBrainz.group(releaseId(1), "First", today, NOVA to "Nova"),
            musicBrainz.group(releaseId(2), "Later", "2099-01-01", NOVA to "Nova", type = "EP"),
            musicBrainz.group(releaseId(3), "Night Shift", "2026-08-14", NOVA to "Nova", ECHO to "Echo", type = "Single"))
        musicBrainz.covers[releaseId(1)] = cover(0x6750A4, 0xF2B8B5)
        musicBrainz.covers[releaseId(3)] = cover(0x0B6E4F, 0xA8E6CF)
        runComposeUiTest {
            waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
            setContent {
                val state by controller.state.collectAsState()
                CompositionLocalProvider(LocalArtworkPicker provides ArtworkPicker { null }) {
                    MusicTheme(state.preferences, null) { HomeScreen(state, controller, state.tracks, null, true, {}) }
                }
            }
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Follow artists from their pages to see their new releases here.").fetchSemanticsNodes().isNotEmpty() }
            runBlocking { controller.releasesController.follow("Nova", ArtistCandidate(NOVA, "Nova")).join() }
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("home-release:${releaseId(1)}").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("home-page").performScrollToNode(hasTestTag("home-section:New releases"))
            snapshot("releases-home", onNodeWithTag("home-section:New releases"))

            onNodeWithContentDescription("See all New releases").performClick()
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("release-feed").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Upcoming").assertIsDisplayed()
            onNodeWithText("EP · 1 Jan 2099", substring = true).assertIsDisplayed()
            onNodeWithText("In library").assertIsDisplayed()
            snapshot("releases-feed")

            musicBrainz.status = HttpStatusCode.ServiceUnavailable
            onNodeWithContentDescription("Refresh releases").performClick()
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("releases-error").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Couldn’t refresh releases: MusicBrainz is busy. Try again in a moment. Saved releases are still shown.").assertIsDisplayed()
            onNodeWithTag("release:${releaseId(1)}").assertExists()
            snapshot("releases-error")

            musicBrainz.status = HttpStatusCode.OK
            onNodeWithText("Retry").performClick()
            waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("releases-error").fetchSemanticsNodes().isEmpty() }

            onNodeWithTag("release:${releaseId(1)}").performClick()
            onNodeWithText("Open album").assertIsDisplayed()
            onNodeWithText("Open on MusicBrainz").assertIsDisplayed()
            onNodeWithText("Search SoundCloud").assertDoesNotExist()
            snapshot("releases-options", onAllNodes(isRoot())[1])
        }
    }
}
