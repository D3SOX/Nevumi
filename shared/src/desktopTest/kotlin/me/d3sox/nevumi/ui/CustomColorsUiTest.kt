package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.*
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CustomColorsUiTest {
    @Test fun customColorsArePickedLiveAndSavedWithoutArtworkOverridingThem() {
        val directory = Files.createTempDirectory("nevumi-custom-colors")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForLibrary(controller)
                assertFalse(LibraryTab.ALBUM_ARTISTS in controller.state.value.preferences.libraryTabs)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Appearance").performScrollTo().performClick()
                onNodeWithContentDescription("Colors").performClick()
                onNodeWithText("Custom").performClick()
                onNodeWithContentDescription("Custom color: Green").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.customColor == 0xFF4CAF50 }
                assertEquals(ColorMode.CUSTOM, controller.state.value.preferences.colors)
                onNodeWithContentDescription("Custom color: Green").assertIsOn()
                onNodeWithContentDescription("Custom color: Purple").assertIsOff()
            }
            runBlocking { controller.closeAndJoin() }; store.close(); store = openMusicStore(database)
            val restored = runBlocking { MusicPreferences(store).load() }
            assertEquals(ColorMode.CUSTOM, restored.colors)
            assertEquals(0xFF4CAF50, restored.customColor)
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
