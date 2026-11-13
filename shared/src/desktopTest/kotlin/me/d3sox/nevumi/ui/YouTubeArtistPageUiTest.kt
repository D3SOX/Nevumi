package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
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
            return recorded(if (link.browseId.startsWith("UC")) "artist" else "album")
        }
        override suspend fun browseMore(continuation: YouTubeContinuation) = YouTubePage(emptyList(), null)
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
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, youTube = youTube,
            sourceAccessible = { true })
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
                onNodeWithText("408m monthly audience").assertExists()
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

                // Going back shows the pages and results that were open, without loading them again.
                onAllNodesWithContentDescription("Back").filterToOne(hasAnyAncestor(hasTestTag("youtube-page"))).performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("12 songs", substring = true).fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Back").performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("youtube-page").fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Open artist: Taylor Swift").assertIsDisplayed()
                assertEquals(listOf("UCPC0L1d253x-KuMNwa05TpA", "MPREb_L64BnYDQpHZ"), youTube.browsed.toList())
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
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
