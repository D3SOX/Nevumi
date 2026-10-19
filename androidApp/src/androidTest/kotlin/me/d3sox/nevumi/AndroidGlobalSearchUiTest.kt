package me.d3sox.nevumi

import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidGlobalSearchUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test fun searchIgnoresHomeFiltersAndOffersTrackAndCollectionMenus() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val audio = File(app.cacheDir, "search-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { controller.state.value.libraryReady }
                val songs = runBlocking { (1..2).map { index -> controller.library.importTrack(
                    TrackMetadata("Signal song $index", "Signal artist", album = "Signal album", trackNumber = index),
                    TrackSource("search:$index", SourceKind.LOCAL, audio.path)) } }
                runBlocking {
                    controller.reload().join()
                    controller.createPlaylist("Signal playlist", songs.map { it.id }).join()
                    controller.updatePreferences { it.copy(lastLibraryTab = LibraryTab.HOME, startupTab = LibraryTab.HOME,
                        libraryFilter = LibraryQuery(text = "unrelated")) }.join()
                }
                compose.openLibraryPage("Home")
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Signal")
                compose.onNodeWithTag("search-type:SONGS").performClick()
                compose.onNodeWithTag("track-row:${songs[0].id}").performTouchInput { longClick() }
                compose.onNodeWithTag("track-actions").assertIsDisplayed()
                assertTrue(controller.state.value.queue.entries.isEmpty())
                compose.onNodeWithContentDescription("Close song actions").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("track-actions").fetchSemanticsNodes().isEmpty() }
                compose.onNodeWithTag("search-type:ALBUMS").performClick()
                compose.onNodeWithContentDescription("Open album: Signal album").performTouchInput { longClick() }
                compose.onNodeWithText("Add to queue").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.entries.size == 2 }
                compose.onNodeWithTag("search-type:PLAYLISTS").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Playlist options: Signal playlist").performClick()
                compose.onNodeWithText("Edit playlist").assertIsDisplayed()
                // Slow emulators sometimes drop an injected Back while the menu's window is opening, so repeat it
                // at most once a second until the menu closes.
                var lastBack = 0L
                compose.waitUntil(10_000) {
                    val open = compose.onAllNodesWithText("Edit playlist").fetchSemanticsNodes().isNotEmpty()
                    if (open && System.currentTimeMillis() - lastBack >= 1_000) {
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                        lastBack = System.currentTimeMillis()
                    }
                    !open
                }
                compose.onNodeWithContentDescription("Close search").performClick()
                compose.onNodeWithTag("home-page").assertIsDisplayed()
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.pause().join() }; audio.delete() }
    }
}
