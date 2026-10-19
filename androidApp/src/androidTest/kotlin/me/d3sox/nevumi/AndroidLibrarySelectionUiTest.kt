package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidLibrarySelectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test
    fun allSelectedServerSongsCanBeDownloadedOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {
            val controller = app.controller
            compose.waitUntil(5_000) { !controller.state.value.loading }
            runBlocking {
                controller.updatePreferences { it.copy(downloadOnMetered = true, cacheBytes = 0) }.join()
                controller.connectServer("http://127.0.0.1:14533", "fixture", "fixture-password").join()
            }
            compose.onNodeWithContentDescription("Track options: Northern Light").performClick()
            compose.onNodeWithText("Select song").performClick()
            compose.onNodeWithContentDescription("Select all").performClick()
            compose.onNodeWithText("3 songs selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Selection actions").performClick()
            compose.onNodeWithText("Download").performClick()
            compose.waitUntil(15_000) { controller.state.value.downloads.values.count { it.status == DownloadStatus.COMPLETE } == 3 }
            controller.state.value.downloads.values.forEach { assertTrue(File(requireNotNull(it.path)).length() > 0) }
        }
    }

    @Test
    fun combinedFiltersRestrictWhichSongsAreSelectedAndFavoritedOnAndroid() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val controller = app.controller
            compose.waitUntil(5_000) { !controller.state.value.loading }
            val songs = runBlocking {
                val wanted = controller.library.importTrack(TrackMetadata("New ambient", genres = listOf("Ambient"), year = 2024),
                    TrackSource("new", SourceKind.LOCAL, "/music/new.flac"))
                val excluded = controller.library.importTrack(TrackMetadata("Old ambient", genres = listOf("Ambient"), year = 1990),
                    TrackSource("old", SourceKind.LOCAL, "/music/old.flac"))
                controller.reload().join()
                wanted to excluded
            }
            compose.onNodeWithContentDescription("Music sources").performClick()
            compose.onNodeWithText("Filters").performClick()
            compose.onNode(hasText("Genre") and hasSetTextAction()).performTextInput("Ambient")
            compose.onNodeWithText("Year from").performScrollTo().performTextInput("2020")
            scenario.onActivity { it.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
            compose.onNodeWithText("Apply filters").performClick()
            compose.onNodeWithText("Old ambient").assertDoesNotExist()
            compose.onNodeWithContentDescription("Track options: New ambient").performClick()
            compose.onNodeWithText("Select song").performClick()
            compose.onNodeWithContentDescription("Select none").assertExists()
            compose.onNodeWithText("1 song selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Selection actions").performClick()
            compose.onNodeWithText("Add to favorites").performClick()
            compose.waitUntil(5_000) { controller.state.value.tracks.first { it.id == songs.first.id }.favorite }
            assertEquals(false, controller.state.value.tracks.first { it.id == songs.second.id }.favorite)
        }
    }
}
