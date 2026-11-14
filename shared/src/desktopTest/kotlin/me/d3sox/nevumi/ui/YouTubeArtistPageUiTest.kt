package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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
import me.d3sox.nevumi.library.MusicLibrary
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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class YouTubeArtistPageUiTest {
    private fun ComposeUiTest.snapshot(name: String) = System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
        val pixels = onNodeWithTag("app-window").captureToImage().toPixelMap()
        val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        javax.imageio.ImageIO.write(image, "png", Path.of(directory).also(Files::createDirectories).resolve("$name.png").toFile())
    }

    /** Finds one artist and answers with recorded YouTube Music pages: an artist, and an album for any album. */
    private class FakeYouTube : YouTubeSource {
        val searches = Collections.synchronizedList(mutableListOf<YouTubeSearchKind>())
        val browsed = Collections.synchronizedList(mutableListOf<String>())
        private fun recorded(name: String) = YouTubeMusicPages.page(Json.parseToJsonElement(
            requireNotNull(javaClass.getResource("/youtube-music/$name.json")).readText()).jsonObject)
        override suspend fun search(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation?): YouTubePage {
            searches += kind
            return YouTubePage(when (kind) {
                YouTubeSearchKind.ARTISTS -> listOf(YouTubeCollection(YouTubeLink("UCPC0L1d253x-KuMNwa05TpA"), YouTubeCollectionKind.ARTIST,
                    "Taylor Swift", "Artist · 408m monthly audience"))
                YouTubeSearchKind.ALBUMS -> listOf(YouTubeCollection(YouTubeLink("MPREb_p6BGcsuOpGt"), YouTubeCollectionKind.ALBUM,
                    "The Life of a Showgirl", "Album · Taylor Swift · 2025"))
                else -> emptyList()
            }, null)
        }
        override suspend fun browse(link: YouTubeLink): YouTubeBrowsePage {
            browsed += link.browseId
            return recorded(when { link.browseId.startsWith("UC") -> "artist"; link.browseId.startsWith("VL") -> "playlist"
                link.browseId.startsWith("MPAD") -> "discography"; else -> "album" })
        }
        /** While set, each continuation waits for its own gate, so a test decides the order they finish in. */
        var hold = false
        val gates = Collections.synchronizedList(mutableListOf<kotlinx.coroutines.CompletableDeferred<Unit>>())
        @Volatile var cancelled = 0
        override suspend fun page(videoId: String, kind: YouTubeCollectionKind): YouTubeCollection? = null
        override suspend fun browseMore(continuation: YouTubeContinuation): YouTubePage {
            if (hold) try { kotlinx.coroutines.CompletableDeferred<Unit>().also { gates += it }.await() }
                catch (stopped: kotlinx.coroutines.CancellationException) { cancelled++; throw stopped }
            return YouTubeMusicPages.continued(Json.parseToJsonElement(
                requireNotNull(javaClass.getResource("/youtube-music/continued.json")).readText()).jsonObject)
        }
        override suspend fun audio(videoId: String, fresh: Boolean) = YouTubeAudio("https://media.test/$videoId", "audio/mp4", 1, 128)
        override fun playbackLocation(videoId: String) = "http://127.0.0.1:9/$videoId"
        override suspend fun artwork(url: String) = ByteArray(0)
    }

    @Test fun anArtistFoundOnYouTubeOpensWithItsShelvesAndItsAlbumsPlayInOrder() {
        val directory = Files.createTempDirectory("youtube-artist-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(youTube = true) } }
        val youTube = FakeYouTube()
        // Downloads only need to start here, so YouTube's media servers stay unreachable.
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
        val downloads = YouTubeDownloads(store, http, youTube, directory.resolve("work").toString(),
            me.d3sox.nevumi.soundcloud.DirectoryMusicFiles(directory.resolve("Music/YouTube").toString()), me.d3sox.nevumi.library.LocalFiles(library))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, youTube = youTube,
            youTubeDownloads = downloads, sourceAccessible = { true })
        try {
            runSkikoComposeUiTest(size = Size(420f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady && controller.state.value.preferences.youTube }
                onNodeWithContentDescription("Search").performClick()
                onNodeWithContentDescription("Search your library").performTextInput("Taylor")
                onNodeWithTag("search-youtube").performScrollTo().performClick()
                onNodeWithTag("youtube-kind:ARTISTS").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Open artist: Taylor Swift").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("Open artist: Taylor Swift").performClick()

                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Top songs").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("408m monthly audience") and hasAnyAncestor(hasTestTag("youtube-page-shelves"))).assertExists()
                onNode(hasText("Taylor Swift") and hasAnyAncestor(hasTestTag("youtube-page-header"))).assertIsDisplayed()
                onNodeWithContentDescription("YouTube song: The Fate of Ophelia by Taylor Swift").assertExists()
                snapshot("youtube-artist")
                val shelves = onNodeWithTag("youtube-page-shelves")
                listOf("Albums", "Singles and EPs", "Videos", "Featured on", "Fans might also like").forEach { title ->
                    shelves.performScrollToNode(hasText(title))
                    onNodeWithText(title).assertIsDisplayed()
                }
                shelves.performScrollToNode(hasContentDescription("Open artist: Shawn Mendes"))
                shelves.performScrollToNode(hasContentDescription("Open album: The Life of a Showgirl: The Encore"))
                onNodeWithContentDescription("Open album: The Life of a Showgirl: The Encore").performClick()

                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("12 songs", substring = true).fetchSemanticsNodes().isNotEmpty() }
                snapshot("youtube-album")
                onNodeWithContentDescription("YouTube song: The Fate of Ophelia by Taylor Swift").performClick()
                // Tapping an album's song plays the album from that song.
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.entries.size == 3 }
                val state = controller.state.value
                assertEquals(listOf("The Fate of Ophelia", "Elizabeth Taylor", "Opalite"),
                    state.queue.entries.map { entry -> state.tracks.single { it.id == entry.trackId }.metadata.title })
                assertEquals(setOf("Taylor Swift"), state.queue.entries.map { entry -> state.tracks.single { it.id == entry.trackId }.metadata.artist }.toSet())
                // The whole album can join the queue, like an album of the library.
                onNodeWithContentDescription("Collection options").performClick()
                snapshot("youtube-album-options")
                onNodeWithText("Add to queue").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.entries.size == 6 }
                assertEquals(state.queue.entries.map { it.trackId }, controller.state.value.queue.entries.drop(3).map { it.trackId })
                // It can be saved to this device too, a download for each song.
                onNodeWithContentDescription("Collection options").performClick()
                onNodeWithText("Open on YouTube Music").assertExists()
                onNodeWithText("Save to this device").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.youTubeJobs.size == 3 }
                assertEquals(state.queue.entries.map { entry -> state.tracks.single { it.id == entry.trackId }.sources.single().location }.toSet(),
                    controller.state.value.youTubeJobs.map { it.track.videoId }.toSet())
                // The header names the page above its shelves, like a library page.
                onNode(hasText("The Life of a Showgirl") and hasAnyAncestor(hasTestTag("youtube-page-header"))).assertIsDisplayed()

                // Picked songs are queued together, in the album's order. An album's own songs do not offer to open it again.
                onNodeWithContentDescription("More options: Elizabeth Taylor").performScrollTo().performClick()
                onNodeWithText("Go to artist on YouTube Music").performScrollTo().assertIsDisplayed()
                onAllNodesWithText("Go to album on YouTube Music").assertCountEquals(0)
                onNodeWithText("Select song").performScrollTo().performClick()
                onNodeWithContentDescription("YouTube song: The Fate of Ophelia by Taylor Swift").performClick()
                onNodeWithText("2 songs selected").assertIsDisplayed()
                snapshot("youtube-album-selection")
                onNodeWithContentDescription("Add to queue").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.entries.size == 8 }
                assertEquals(state.queue.entries.take(2).map { it.trackId }, controller.state.value.queue.entries.takeLast(2).map { it.trackId })
                onAllNodesWithText("2 songs selected").assertCountEquals(0)

                // Going back shows the pages and results that were open, without loading them again.
                onAllNodesWithContentDescription("Back").filterToOne(hasAnyAncestor(hasTestTag("youtube-page"))).performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("12 songs", substring = true).fetchSemanticsNodes().isEmpty() }

                // An album card's menu, which a right click or long press opens, queues its songs without opening the album.
                // Past the albums, so the card is clear of the mini player.
                shelves.performScrollToNode(hasText("Videos"))
                onNodeWithContentDescription("Open album: The Life of a Showgirl: The Encore").performTouchInput { longClick() }
                onNodeWithText("Play").assertIsDisplayed()
                snapshot("youtube-card-options")
                onNodeWithText("Add to queue").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.entries.size == 11 }
                // Only the album's songs were queued; nothing under the menu was opened.
                waitForIdle()
                assertEquals(listOf("UCPC0L1d253x-KuMNwa05TpA", "MPREb_L64BnYDQpHZ", "MPREb_L64BnYDQpHZ"), youTube.browsed.toList())

                // All albums open as a grid of equally wide cards.
                shelves.performScrollToNode(hasText("Albums"))
                // Up near the top, so its button is clear of the notice and the mini player.
                shelves.performSemanticsAction(SemanticsActions.ScrollBy) { scroll ->
                    scroll(0f, onNodeWithText("Albums").fetchSemanticsNode().boundsInRoot.top - 200f)
                }
                val albumsTop = onNodeWithText("Albums").fetchSemanticsNode().boundsInRoot.center.y
                onAllNodesWithText("See all").fetchSemanticsNodes().single { kotlin.math.abs(it.boundsInRoot.center.y - albumsTop) < 40 }
                    .let { see -> onNode(hasText("See all") and SemanticsMatcher("at the Albums heading") { it.id == see.id }).performClick() }
                waitUntil(timeoutMillis = 5_000) { youTube.browsed.last() == "MPADUCPC0L1d253x-KuMNwa05TpA" &&
                    onAllNodesWithContentDescription("Open album: The Life of a Showgirl + Acoustic Collection").fetchSemanticsNodes().isNotEmpty() }
                snapshot("youtube-albums-grid")
                val widths = listOf("The Life of a Showgirl: The Encore", "The Life of a Showgirl + Acoustic Collection").map {
                    onNodeWithContentDescription("Open album: $it").fetchSemanticsNode().boundsInRoot.width
                }
                assertEquals(widths[0], widths[1])
                assertTrue(widths[0] > 150f, "Grid cards are ${widths[0]}px wide")
                onNodeWithContentDescription("Back").performClick()
                // Only the grid lists the album itself.
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Open album: The Life of a Showgirl").fetchSemanticsNodes().isEmpty() }

                // Playing a long playlist loads its remaining pages first, so the queue holds more than the songs shown.
                shelves.performScrollToNode(hasText("Top songs"))
                onAllNodesWithText("See all")[0].performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("150 songs", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("Play collection").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.entries.size == 4 }
                onNodeWithContentDescription("Back").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("150 songs", substring = true).fetchSemanticsNodes().isEmpty() }

                // A later tap stops an earlier one that still waits for the list, so the earlier one cannot replace it later.
                youTube.hold = true
                shelves.performScrollToNode(hasText("Top songs"))
                onAllNodesWithText("See all")[0].performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("150 songs", substring = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("YouTube song: The Fate of Ophelia by Taylor Swift").performClick()
                waitUntil(timeoutMillis = 5_000) { youTube.gates.size == 1 }
                onNodeWithContentDescription("YouTube song: Blank Space by Taylor Swift").performClick()
                waitUntil(timeoutMillis = 5_000) { youTube.gates.size == 2 && youTube.cancelled == 1 }
                youTube.gates[1].complete(Unit)
                waitUntil(timeoutMillis = 5_000) { controller.state.value.let { state ->
                    state.tracks.firstOrNull { it.id == state.queue.currentTrackId }?.metadata?.title == "Blank Space" } }
                youTube.hold = false
                onNodeWithContentDescription("Back").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("150 songs", substring = true).fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Back").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("youtube-page").fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Open artist: Taylor Swift").assertIsDisplayed()
                assertEquals(listOf("UCPC0L1d253x-KuMNwa05TpA", "MPREb_L64BnYDQpHZ", "MPREb_L64BnYDQpHZ", "MPADUCPC0L1d253x-KuMNwa05TpA", "VLOLAK5uy_kRl6HdICkQpZF7zuHu_Yx-RDVHw-hboxo",
                    "VLOLAK5uy_kRl6HdICkQpZF7zuHu_Yx-RDVHw-hboxo"), youTube.browsed.toList())
                assertEquals(1, youTube.searches.count { it == YouTubeSearchKind.ARTISTS })

                // An album found by a search opens its page too.
                onNodeWithTag("youtube-kind:ALBUMS").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithContentDescription("Open album: The Life of a Showgirl").fetchSemanticsNodes().isNotEmpty() }
                snapshot("youtube-album-search")
                onNodeWithContentDescription("Open album: The Life of a Showgirl").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("12 songs", substring = true).fetchSemanticsNodes().isNotEmpty() }
                assertEquals("MPREb_p6BGcsuOpGt", youTube.browsed.last())
                // The open page takes the keyboard, so Escape closes it and leaves the search open.
                onNodeWithTag("youtube-page").assertIsFocused().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("youtube-page").fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Open album: The Life of a Showgirl").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
