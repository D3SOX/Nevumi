package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SettingsSearchUiTest {
    @Test
    fun searchingAcrossCategoriesOpensTheSettingAndBackRestoresTheCategory() {
        val directory = Files.createTempDirectory("settings-search")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val player = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextInput("crossfade")
                onNodeWithText("Crossfade").assertIsDisplayed().performClick()
                onNodeWithContentDescription("Crossfade").assertIsDisplayed()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextReplacement("black")
                onNodeWithText("AMOLED black").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Crossfade").assertIsDisplayed()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextReplacement("black")
                onNodeWithText("AMOLED black").performClick()
                onNodeWithContentDescription("AMOLED black").assertIsDisplayed()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextReplacement("clear favorites")
                onNodeWithText("Clear favorites").assertIsDisplayed().performClick()
                onNodeWithText("Library & gestures").assertIsDisplayed()
                onNodeWithText("Clear favorites").performScrollTo().assertIsNotEnabled()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextReplacement("clear ratings")
                onNodeWithText("Clear ratings").performClick()
                onNodeWithText("Clear ratings").performScrollTo().assertIsNotEnabled()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextReplacement("scroll positions")
                onNodeWithText("Remember scroll positions").performClick()
                onNodeWithContentDescription("Remember scroll positions").performScrollTo().assertIsOn()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
