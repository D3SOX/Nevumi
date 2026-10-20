package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.playlists.Playlists
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidSmartPlaylistsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun changingASmartPlaylistRuleAutomaticallyDownloadsTheNewMatchingSong() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {
            val controller = app.controller
            compose.waitUntil(5_000) { !controller.state.value.loading }
            runBlocking {
                controller.updatePreferences { it.copy(downloadOnMetered = true) }.join()
                controller.connectServer("http://127.0.0.1:14533", "fixture", "fixture-password").join()
            }
            val northern = controller.state.value.tracks.single { it.metadata.title == "Northern Light" }
            val night = controller.state.value.tracks.single { it.metadata.title == "Night Walk" }
            compose.openLibraryPage("Playlists")
            compose.onNodeWithContentDescription("New smart playlist").performClick()
            compose.onNodeWithText("Playlist name").performTextInput("Smart offline songs")
            compose.onNodeWithText("Search text").performTextInput("Northern Light")
            compose.onNodeWithText("Create").performClick()
            compose.waitUntil(5_000) { controller.state.value.playlists.any { it.name == "Smart offline songs" } }
            compose.onNodeWithText("Smart offline songs").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Automatically download playlist").performScrollTo().performClick()
            compose.waitUntil(10_000) { controller.state.value.downloads[northern.id]?.status == DownloadStatus.COMPLETE }
            compose.onNodeWithContentDescription("Playlist options").performScrollTo().performClick()
            compose.onNodeWithText("Edit rules").performClick()
            compose.onNodeWithText("Search text").performTextReplacement("Night Walk")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(10_000) { controller.state.value.downloads[night.id]?.status == DownloadStatus.COMPLETE }
            val playlist = runBlocking { Playlists(app.store).all().single { it.name == "Smart offline songs" } }
            assertEquals(listOf(night.id), playlist.trackIds)
            assertTrue(playlist.autoDownload)
            val file = File(requireNotNull(controller.state.value.downloads[night.id]?.path))
            assertTrue(file.length() > 0)
            compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-pager")))
                .performScrollToNode(hasTestTag("track-row:${night.id}"))
            compose.onNodeWithText("Night Walk").assertIsDisplayed()
        }
    }
}
