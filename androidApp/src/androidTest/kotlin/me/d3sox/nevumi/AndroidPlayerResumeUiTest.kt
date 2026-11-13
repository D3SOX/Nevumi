package me.d3sox.nevumi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidPlayerResumeUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun launcherAndNotificationResumeTheOpenPlayerPanel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val audio = File(app.cacheDir, "resume-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitForLibrary(controller)
                runBlocking {
                    val track = controller.library.importTrack(TrackMetadata("Resume fixture", durationMs = 3_000),
                        TrackSource("resume-fixture", SourceKind.LOCAL, audio.path))
                    controller.reload().join()
                    controller.playTrack(track.id).join()
                    controller.pause().join()
                }
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithContentDescription("Favorite").assertIsDisplayed()
                resumeFromHome(app.packageManager.getLaunchIntentForPackage(app.packageName)!!)
                compose.onNodeWithContentDescription("Favorite").assertIsDisplayed()
                compose.onNodeWithContentDescription("Queue").performClick()
                compose.onNodeWithTag("queue-header").assertIsDisplayed()
                resumeFromHome(app.packageManager.getLaunchIntentForPackage(app.packageName)!!)
                compose.onNodeWithTag("queue-header").assertIsDisplayed()
                // MediaSession and download notifications launch the activity directly.
                resumeFromHome(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                compose.onNodeWithTag("queue-header").assertIsDisplayed()
            }
        } finally { runBlocking { controller.pause().join() }; audio.delete() }
    }

    private fun resumeFromHome(intent: Intent) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        compose.waitUntil(5_000) { !mainActivityResumed() }
        instrumentation.targetContext.startActivity(intent)
        compose.waitUntil(5_000) { mainActivityResumed() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("app-window").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun mainActivityResumed(): Boolean {
        var resumed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
        }
        return resumed
    }
}
