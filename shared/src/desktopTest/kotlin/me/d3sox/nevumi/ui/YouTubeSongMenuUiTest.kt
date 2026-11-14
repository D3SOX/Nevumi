package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.soundcloud.DirectoryMusicFiles
import me.d3sox.nevumi.soundcloud.ScGateTags
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.youtube.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class YouTubeSongMenuUiTest {
    private fun ComposeUiTest.snapshot(name: String) = System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
        val pixels = onNodeWithTag("app-window").captureToImage().toPixelMap()
        val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        javax.imageio.ImageIO.write(image, "png", Path.of(directory).also(Files::createDirectories).resolve("$name.png").toFile())
    }

    /** Names Taylor Swift as every video's artist and answers with her recorded YouTube Music page. */
    private class FakeYouTube : YouTubeSource {
        val artistLookups = Collections.synchronizedList(mutableListOf<String>())
        val albumLookups = Collections.synchronizedList(mutableListOf<String>())
        val browsed = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun search(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation?) = YouTubePage(emptyList(), null)
        override suspend fun browse(link: YouTubeLink): YouTubeBrowsePage {
            browsed += link.browseId
            return YouTubeMusicPages.page(Json.parseToJsonElement(requireNotNull(javaClass.getResource("/youtube-music/artist.json")).readText()).jsonObject)
        }
        override suspend fun page(videoId: String, kind: YouTubeCollectionKind): YouTubeCollection {
            if (kind == YouTubeCollectionKind.ARTIST) artistLookups += videoId else albumLookups += videoId
            return if (kind == YouTubeCollectionKind.ARTIST) YouTubeCollection(YouTubeLink("UCPC0L1d253x-KuMNwa05TpA"), kind, "Taylor Swift")
                else YouTubeCollection(YouTubeLink("MPREb_p6BGcsuOpGt"), kind, "The Life of a Showgirl")
        }
        override suspend fun browseMore(continuation: YouTubeContinuation) = YouTubePage(emptyList(), null)
        override suspend fun audio(videoId: String, fresh: Boolean) = YouTubeAudio("https://media.test/$videoId", "audio/mp4", 1, 128)
        override fun playbackLocation(videoId: String) = "http://127.0.0.1:9/$videoId"
        override suspend fun artwork(url: String) = ByteArray(0)
    }

    @Test fun thePlayingYouTubeSongSavesOpensOnYouTubeAndGoesToItsArtistInTheLibraryOrOnYouTubeMusic() {
        val directory = Files.createTempDirectory("youtube-song-menu-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.mp3")
        runBlocking { library.importTrack(TrackMetadata("Night Drive", "Artist"), TrackSource("owned", SourceKind.LOCAL, audio.toString())) }
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(youTube = true) } }
        val youTube = FakeYouTube()
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) })
        val localFiles = LocalFiles(library)
        val downloads = YouTubeDownloads(store, http, youTube, directory.resolve("work").toString(),
            DirectoryMusicFiles(directory.resolve("Music/YouTube").toString()), localFiles)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = localFiles,
            preferences = preferences, youTube = youTube, youTubeDownloads = downloads)
        try {
            runSkikoComposeUiTest(size = Size(420f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady && controller.state.value.preferences.youTube }
                controller.playYouTube(YouTubeTrack("aaaaaaaaaaa", "Day Ride", "Artist", 201_000, song = true))
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("mini-player").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("mini-player").performClick()

                // The playing song only streams from YouTube, so its menu offers what a YouTube search result's does.
                onNodeWithContentDescription("Track options: Day Ride").performClick()
                onNodeWithTag("track-actions").assertIsDisplayed()
                listOf("Go to artist", "Go to artist on YouTube Music", "Go to album on YouTube Music", "Save to this device", "Open on YouTube").forEach {
                    onNodeWithText(it).performScrollTo().assertIsDisplayed()
                }
                snapshot("youtube-playing-menu")
                onNodeWithText("Save to this device").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.youTubeJobs.isNotEmpty() }
                val job = controller.state.value.youTubeJobs.single()
                assertEquals("aaaaaaaaaaa", job.track.videoId)
                assertEquals(YouTubeDestination.DEVICE, job.destination)
                assertEquals(ScGateTags("Day Ride", "Artist"), job.tags.copy(album = ""))

                // Its YouTube Music artist opens above the search page, without going through the library.
                onNodeWithContentDescription("Track options: Day Ride").performClick()
                onNodeWithText("Go to artist on YouTube Music").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Top songs").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(listOf("aaaaaaaaaaa"), youTube.artistLookups.toList())
                assertEquals(listOf("UCPC0L1d253x-KuMNwa05TpA"), youTube.browsed.toList())
                onNodeWithTag("library-search-page", useUnmergedTree = true).assertExists()
                snapshot("youtube-artist-from-player")

                // A YouTube Music song offers its artist on YouTube Music, and in the library only when the library has their songs.
                onNodeWithContentDescription("More options: The Fate of Ophelia").performClick()
                onNodeWithTag("track-actions").assertIsDisplayed()
                onNodeWithText("Go to artist on YouTube Music").performScrollTo().assertIsDisplayed()
                onAllNodesWithText("Go to artist").assertCountEquals(0)
                onNodeWithText("Go to album on YouTube Music").performScrollTo().assertIsDisplayed()
                snapshot("youtube-song-menu")
                onNodeWithText("Go to artist on YouTube Music").performClick()
                waitUntil(timeoutMillis = 5_000) { youTube.browsed.size == 2 }

                // The playing song's album on YouTube Music opens the same way.
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Track options: Day Ride").performClick()
                onNodeWithText("Go to album on YouTube Music").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { youTube.browsed.size == 3 }
                assertEquals(listOf("aaaaaaaaaaa"), youTube.albumLookups.toList())
                assertEquals("MPREb_p6BGcsuOpGt", youTube.browsed.last())
            }
        } finally { runBlocking { controller.closeAndJoin(); downloads.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
