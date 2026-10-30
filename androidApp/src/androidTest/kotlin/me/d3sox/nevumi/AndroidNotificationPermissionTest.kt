package me.d3sox.nevumi

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.settings.MusicPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidNotificationPermissionTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun requestingADownloadOffersAndroidNotificationPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as MusicApplication
        val controller = app.controller
        val original = runBlocking { MusicPreferences(app.store).load() }
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking {
                    controller.updatePreferences { it.copy(downloadsPaused = true) }.join()
                    controller.connectServer("http://127.0.0.1:14533", "fixture", "fixture-password").join()
                }
                val track = controller.state.value.tracks.single { it.metadata.title == "Northern Light" && it.sources.any { source -> source.kind == SourceKind.SERVER } }
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Northern Light")
                compose.onNodeWithContentDescription("Search your library").performImeAction()
                val row = hasTestTag("track-row:${track.id}")
                compose.onNode(row, useUnmergedTree = true).performScrollTo()
                compose.onNode(hasContentDescription("Track options: Northern Light") and hasAnyAncestor(row), useUnmergedTree = true).performClick()
                compose.onNodeWithText("Download").performScrollTo().performClick()
                compose.waitForIdle()
                runBlocking {
                    withTimeout(5_000) { instrumentation.uiAutomation.clickSystemDialogButton { it.text?.toString() == "Allow" } }
                    withTimeout(5_000) {
                        while (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) delay(20)
                    }
                }
                assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            }
        } finally { runBlocking { controller.updatePreferences { original }.join() } }
    }
}
