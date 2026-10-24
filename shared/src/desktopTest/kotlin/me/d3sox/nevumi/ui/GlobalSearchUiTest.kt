package me.d3sox.nevumi.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class GlobalSearchUiTest {
    @Test fun searchCanMoveToNavigationAndReturnToTheHeader() {
        val directory = Files.createTempDirectory("search-navigation-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(libraryTabs = listOf(LibraryTab.HOME, LibraryTab.SEARCH, LibraryTab.SONGS)) } }
        val library = MusicLibrary(store)
        val song = runBlocking { library.importTrack(TrackMetadata("Signal song"), TrackSource("search-selection", SourceKind.LOCAL, "/music/signal.flac")) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runSkikoComposeUiTest(size = Size(420f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onAllNodesWithContentDescription("Search").assertCountEquals(1)
                onNodeWithContentDescription("Search").assert(hasAnyAncestor(hasTestTag("library-navigation"))).performClick()
                onNodeWithContentDescription("Search").assertIsSelected()
                onNodeWithText("Enter a search term").assertIsDisplayed()
                onNodeWithContentDescription("Close search").assertDoesNotExist()
                captureSearch("search-empty")
                onNodeWithTag("library-navigation").assertIsDisplayed()
                onNodeWithContentDescription("Search your library").performTextInput("Signal")
                onNodeWithTag("track-row:${song.id}").performTouchInput { longClick() }
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Clear selection").assertIsDisplayed()
                onNodeWithContentDescription("Songs").performClick()
                onNodeWithContentDescription("Search").performClick()
                onNodeWithContentDescription("Search your library").assertTextEquals("Signal")
                onNodeWithContentDescription("Clear selection").assertDoesNotExist()
                onNodeWithContentDescription("Songs").performClick()
                onNodeWithContentDescription("Songs").assertIsSelected()
                runOnIdle { controller.updatePreferences { it.copy(libraryTabs = listOf(LibraryTab.HOME, LibraryTab.SONGS)) } }
                waitUntil { onAllNodes(hasContentDescription("Search") and hasAnyAncestor(hasTestTag("library-navigation"))).fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Search").performClick()
                onNodeWithTag("library-search-page").assertIsDisplayed()
                onNodeWithContentDescription("Close search").performClick()
                onNodeWithContentDescription("Songs").assertIsSelected()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun restoringSearchKeepsTheCursorAtTheEndOfTheQuery() {
        val directory = Files.createTempDirectory("search-cursor-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runSkikoComposeUiTest(size = Size(420f, 900f)) {
                var visible by mutableStateOf(true)
                setContent {
                    val savedState = rememberSaveableStateHolder()
                    if (visible) savedState.SaveableStateProvider("app") { MusicPlayerApp(controller) }
                }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Search").performClick()
                onNodeWithText("Enter a search term").assertIsDisplayed()
                onNodeWithContentDescription("Search your library").performTextInput("Signal")
                onNodeWithText("Enter a search term").assertDoesNotExist()
                runOnIdle { visible = false }
                waitForIdle()
                runOnIdle { visible = true }
                onNodeWithTag("library-search-page").assertIsDisplayed()
                onNodeWithContentDescription("Search your library")
                    .assertTextEquals("Signal")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(6)))
                    .performTextInput(" song")
                onNodeWithContentDescription("Search your library").assertTextEquals("Signal song")
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun openingTheQueueFromSearchAndSwipingDownTwicePreservesTheSearch() {
        val directory = Files.createTempDirectory("search-queue-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val queue = PlaybackQueue(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val song = runBlocking { library.importTrack(TrackMetadata("Signal song", "Signal artist"),
            TrackSource("search-queue", SourceKind.LOCAL, audio)) }
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runSkikoComposeUiTest(size = Size(420f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Search").performClick()
                onNodeWithContentDescription("Search your library").performTextInput("Signal")
                onNodeWithTag("search-type:SONGS").performClick()
                onNodeWithContentDescription("Track options: Signal song").performClick()
                onNodeWithText("Add to queue").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Added to queue").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("Open queue").performClick()
                onNodeWithTag("queue-header").assertIsDisplayed()
                onNodeWithTag("queue-mini-player").performTouchInput {
                    swipe(center, center.copy(y = center.y + 600f), 400)
                }
                onNodeWithTag("now-playing").assertIsDisplayed().performTouchInput {
                    swipe(center.copy(y = 32f), center.copy(y = height * .75f), 400)
                }
                onNodeWithTag("mini-player").assertIsDisplayed()
                onNodeWithTag("library-search-page").assertIsDisplayed()
                onNodeWithContentDescription("Search your library").assertTextEquals("Signal")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(6)))
                onNodeWithTag("search-type:SONGS").assertIsSelected()
                onNodeWithTag("track-row:${song.id}").assertIsDisplayed()
                assertEquals(listOf(song.id), controller.state.value.queue.entries.map { it.trackId })
                assertFalse(controller.state.value.playback.playing)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun searchFromHomeCoversTheLibraryAndLongPressUsesTheCollectionActions() {
        val directory = Files.createTempDirectory("global-search-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val queue = PlaybackQueue(store)
        val preferences = MusicPreferences(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val songs = runBlocking { (1..3).map { index -> library.importTrack(TrackMetadata("Arc song $index", "Pulse", album = "Arc album", trackNumber = index),
            TrackSource("source:$index", SourceKind.LOCAL, audio)) } }
        runBlocking {
            playlists.create("Arc playlist", songs.map { it.id })
            preferences.update { it.copy(startupTab = LibraryTab.HOME, libraryTabs = listOf(LibraryTab.HOME, LibraryTab.SONGS, LibraryTab.ALBUMS, LibraryTab.PLAYLISTS)) }
        }
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"), playlists = playlists, preferences = preferences.withOptionsButtons())
        try {
            runSkikoComposeUiTest(size = Size(1000f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onNodeWithTag("home-page").assertIsDisplayed()
                onNodeWithContentDescription("Search").performClick()
                onNodeWithTag("library-search-page").assertIsDisplayed()
                onNodeWithContentDescription("Search your library").performTextInput("Arc")
                onNodeWithTag("search-type:ALBUMS").performClick()
                onNodeWithContentDescription("Open album: Arc album").performTouchInput { longClick() }
                onNodeWithText("Add to queue").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.entries.size == 3 }
                assertEquals(songs.map { it.id }, runBlocking { queue.snapshot().entries.map { it.trackId } })
                onNodeWithContentDescription("Open album: Arc album").performClick()
                onNodeWithContentDescription("Track options: Arc song 3").assertIsDisplayed()
                onNodeWithContentDescription("Back").assertDoesNotExist()
                onAllNodesWithContentDescription("Close search").assertCountEquals(1)
                onNodeWithContentDescription("Close search").performClick()
                onNodeWithTag("search-type:PLAYLISTS").performClick()
                onNodeWithTag("search-type:PLAYLISTS").assertIsSelected()
                captureSearch("global-search")
                onNodeWithContentDescription("Playlist options: Arc playlist").performClick()
                onNodeWithText("Edit playlist").performClick()
                onNodeWithContentDescription("Playlist title").performTextReplacement("Arc renamed")
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.playlists.single().name == "Arc renamed" }
                onNodeWithTag("search-type:SONGS").performClick()
                onNodeWithTag("track-row:${songs[0].id}").performTouchInput { longClick() }
                onNodeWithText("Play next").assertIsDisplayed()
                onNodeWithText("Select song").assertIsDisplayed()
                onNodeWithContentDescription("Close song actions").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("track-actions").fetchSemanticsNodes().isEmpty() }
                onNodeWithContentDescription("Close search").performClick()
                onNodeWithTag("home-page").assertIsDisplayed()
                onNode(hasContentDescription("Home") and hasAnyAncestor(hasTestTag("library-navigation"))).assertIsSelected()
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    private fun ComposeUiTest.captureSearch(name: String) {
        System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
            val pixels = onNodeWithTag("app-window").captureToImage().toPixelMap()
            val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
            val output = Path.of(directory).also(Files::createDirectories).resolve("$name.png")
            javax.imageio.ImageIO.write(image, "png", output.toFile())
        }
    }
}
