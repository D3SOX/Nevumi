package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.youtube.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class YouTubeSearchUiTest {
    private fun ComposeUiTest.snapshot(name: String) = System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
        val pixels = onNodeWithTag("app-window").captureToImage().toPixelMap()
        val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        javax.imageio.ImageIO.write(image, "png", Path.of(directory).also(Files::createDirectories).resolve("$name.png").toFile())
    }

    /** Fails the first search like a lost connection, then finds two songs. */
    private class FakeYouTube : YouTubeSource {
        val searches = Collections.synchronizedList(mutableListOf<Pair<String, YouTubeSearchKind>>())
        override suspend fun search(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation?): YouTubePage {
            searches += query to kind
            if (searches.size == 1) throw YouTubeException("Could not reach YouTube. Check your connection.")
            // YouTube can list a video twice on one page.
            return YouTubePage(listOf(YouTubeTrack("aaaaaaaaaaa", "Night Drive", "Artist", 201_000, song = true),
                YouTubeTrack("aaaaaaaaaaa", "Night Drive", "Artist", 201_000, song = true),
                YouTubeTrack("bbbbbbbbbbb", "Other - Night Swim (Official Video)", "OtherVEVO", 180_000)), null)
        }
        override suspend fun audio(videoId: String, fresh: Boolean) = YouTubeAudio("https://media.test/$videoId", "audio/mp4", 1, 128)
        override fun playbackLocation(videoId: String) = "http://127.0.0.1:9/$videoId"
        override suspend fun artwork(url: String) = ByteArray(0)
    }

    @Test fun aSearchFromTheLibraryRetriesAfterAFailureAndQueuesAPickedVideoWithoutAddingItToTheLibrary() {
        val directory = Files.createTempDirectory("youtube-search-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.mp3")
        runBlocking { library.importTrack(TrackMetadata("Night Drive", "Artist"), TrackSource("owned", SourceKind.LOCAL, audio.toString())) }
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(youTube = true) } }
        val youTube = FakeYouTube()
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), localSources = LocalFiles(library),
            preferences = preferences, youTube = youTube)
        try {
            runSkikoComposeUiTest(size = Size(420f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady && controller.state.value.preferences.youTube }
                onNodeWithContentDescription("Search").performClick()
                onNodeWithContentDescription("Search your library").performTextInput("Night")
                onNodeWithTag("search-youtube").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Could not reach YouTube. Check your connection.").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Retry").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("YouTube song: Night Drive by Artist").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(listOf("Night" to YouTubeSearchKind.SONGS, "Night" to YouTubeSearchKind.SONGS), youTube.searches.toList())
                onAllNodesWithText("In library", useUnmergedTree = true).assertCountEquals(1)
                onAllNodesWithContentDescription("YouTube song: Night Drive by Artist").assertCountEquals(1)
                snapshot("youtube-search")

                onNodeWithContentDescription("More options: Other - Night Swim (Official Video)").performClick()
                onNodeWithText("Play next").performClick()
                // An uploaded video titled "Artist - Title" is queued under that artist and title.
                waitUntil(timeoutMillis = 5_000) {
                    val state = controller.state.value
                    state.queue.entries.any { entry -> state.tracks.firstOrNull { it.id == entry.trackId }?.metadata?.title == "Night Swim (Official Video)" }
                }
                val queued = controller.state.value.tracks.single { it.metadata.title == "Night Swim (Official Video)" }
                assertEquals("Other", queued.metadata.artist)
                assertEquals(listOf(SourceKind.YOUTUBE), queued.sources.map { it.kind })
                assertEquals(false, queued.inLibrary)

                onNodeWithTag("youtube-kind:VIDEOS").performClick()
                waitUntil(timeoutMillis = 5_000) { youTube.searches.lastOrNull() == "Night" to YouTubeSearchKind.VIDEOS }

                // A streamed song stays out of the library views, but a favorite one is listed in Favorites.
                controller.setFavorite(queued.id, true).let { runBlocking { it.join() } }
                onNodeWithContentDescription("Close search").performClick()
                // The mini player shows the queued song too, so only list rows count.
                val listed = hasText("Night Swim (Official Video)") and !hasContentDescription("Mini-player", substring = true)
                openLibraryPage("Favorites")
                waitUntil(timeoutMillis = 5_000) { onAllNodes(listed).fetchSemanticsNodes().isNotEmpty() }
                openLibraryPage("Songs")
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Night Drive").fetchSemanticsNodes().isNotEmpty() }
                onAllNodes(listed).assertCountEquals(0)

                // Turning YouTube off stops this device from streaming the YouTube songs it already queued.
                controller.updatePreferences { it.copy(youTube = false) }.let { runBlocking { it.join() } }
                val refused = runCatching { runBlocking { controller.playbackController.requirePlayable(queued.id) } }.exceptionOrNull()
                assertEquals("This track has no available audio source", refused?.message)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
