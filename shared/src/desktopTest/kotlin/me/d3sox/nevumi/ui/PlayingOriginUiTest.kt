package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import org.jetbrains.compose.resources.getString

@OptIn(ExperimentalTestApi::class)
class PlayingOriginUiTest {
    @Test fun playingFromShowsTheSelectedLiveQueueAndOpensItsContents() {
        val directory = Files.createTempDirectory("playing-live-queue-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val songs = runBlocking { (1..2).map { index ->
            library.importTrack(TrackMetadata("Song $index", durationMs = 3_000),
                TrackSource("source:$index", SourceKind.LOCAL, audio))
        } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking {
                    controller.playTrack(songs[0].id, listOf(songs[0].id), contextName = "My album").join()
                    controller.pause().join()
                    controller.saveQueue("Evening mix").join()
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.savedQueues.isNotEmpty() }
                val savedId = requireNotNull(controller.state.value.queue.selectedSavedQueueId)
                onNodeWithTag("mini-player").performClick()
                fun header(name: String) = onNode(hasText(name) and hasAnyAncestor(hasContentDescription("Playing context")), useUnmergedTree = true)
                header("Evening mix").assertIsDisplayed()
                System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { output ->
                    val pixels = onNodeWithTag("now-playing").captureToImage().toPixelMap()
                    val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                    javax.imageio.ImageIO.write(image, "png", Path.of(output).also(Files::createDirectories).resolve("now-playing-live-queue.png").toFile())
                }
                onNodeWithContentDescription("Playing context").performClick()
                onNode(hasText("Evening mix") and hasAnyAncestor(hasTestTag("queue-header"))).assertIsDisplayed()
                onNodeWithTag("queue-mini-player").performClick()
                runBlocking { controller.renameSavedQueue(savedId, "Night mix").join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.savedQueues.any { it.name == "Night mix" } }
                header("Night mix").assertIsDisplayed()
                runBlocking {
                    controller.enqueue(listOf(songs[1].id), QueueAction.PLAY_NEXT).join()
                    controller.skip(1).join()
                    controller.pause().join()
                }
                header("Night mix").assertIsDisplayed()
                runBlocking { controller.detachSavedQueue().join() }
                header("Up next").assertIsDisplayed()
                runBlocking { controller.loadSavedQueue(savedId, confirmed = true).join() }
                header("Night mix").assertIsDisplayed()
                runBlocking { controller.removeSavedQueue(savedId).join() }
                header("Up next").assertIsDisplayed()
                runBlocking { controller.playTrack(songs[0].id, contextName = "My album", confirmed = true).join(); controller.pause().join() }
                header("My album").assertIsDisplayed()
                kotlin.test.assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun playingFromOpensTheAlbumOrPlaylistAtTheCurrentTrack() {
        val directory = Files.createTempDirectory("playing-origin-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val songs = runBlocking { (0 until 30).map { index ->
            library.importTrack(TrackMetadata("Song %02d".format(index), "Test artist", "Test album", trackNumber = index + 1, durationMs = 3_000),
                TrackSource("source:$index", SourceKind.LOCAL, audio))
        }.also { playlists.create("Night mix", it.map { song -> song.id }) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                for ((page, title) in listOf("Albums" to "Test album", "Playlists" to "Night mix")) {
                    openLibraryPage(page); onNodeWithText(title).performClick()
                    onNode(hasScrollAction()).performScrollToIndex(26)
                    onNodeWithTag("track-row:${songs[25].id}").performClick()
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentTrackId == songs[25].id }
                    runBlocking { controller.pause().join() }
                    onNodeWithTag("mini-player").performClick()
                    onNodeWithContentDescription("Playing context").performClick()
                    onNodeWithTag("track-row:${songs[25].id}").assertIsDisplayed()
                    kotlin.test.assertEquals(page, runBlocking { getString(controller.state.value.preferences.lastLibraryTab.label) })
                    onNode(hasScrollAction()).performScrollToIndex(1)
                    onNodeWithTag("track-row:${songs[0].id}").assertIsDisplayed()
                    openLibraryPage("Recently added")
                    onNodeWithContentDescription("Settings").performClick()
                    onNodeWithContentDescription("Back").performClick()
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.lastLibraryTab == LibraryTab.RECENT }
                    val recent = (hasText("Recently added") or hasContentDescription("Recently added")) and hasClickAction()
                    if (onAllNodes(recent).fetchSemanticsNodes().isNotEmpty()) onNode(recent).assertIsSelected()
                    else onNodeWithText("More").assertIsSelected()
                    openLibraryPage(page)
                    if (onAllNodesWithTag("track-row:${songs[0].id}").fetchSemanticsNodes().isEmpty() &&
                        onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()) onNodeWithText(title).performClick()
                    onNodeWithTag("track-row:${songs[0].id}").assertIsDisplayed()
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
