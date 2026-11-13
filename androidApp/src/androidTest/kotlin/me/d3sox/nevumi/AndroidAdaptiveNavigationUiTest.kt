package me.d3sox.nevumi

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import me.d3sox.nevumi.library.LibraryTab
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AndroidAdaptiveNavigationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    /**
     * Compose can hang forever if it syncs while a rotation detaches the old activity's views,
     * so wait for the recreated activity before touching Compose again.
     */
    private fun ActivityScenario<MainActivity>.rotate(orientation: Int) {
        onActivity { it.requestedOrientation = orientation }
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE
            else Configuration.ORIENTATION_PORTRAIT
        // A busy emulator can take well over 10 seconds to give the new window focus.
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (true) {
            var settled = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                settled = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any {
                    it is MainActivity && it.resources.configuration.orientation == expected && it.hasWindowFocus()
                }
            }
            if (settled) return
            check(SystemClock.uptimeMillis() < deadline) { "The activity did not settle after rotating" }
            Thread.sleep(50)
        }
    }

    @Test fun rotatingAPhoneShowsMorePagesAndRestoresTheSelectedOverflowPage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val controller = app.controller
        runBlocking { controller.updatePreferences { it.copy(lastLibraryTab = LibraryTab.RECOMMENDATIONS) }.join() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            scenario.onActivity { originalOrientation = it.requestedOrientation }
            scenario.rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            try {
                compose.waitForLibrary(controller)
                compose.onNodeWithText("More").assertIsSelected()
                val items = hasClickAction() and hasAnyAncestor(hasTestTag("library-navigation"))
                val portraitCount = compose.onAllNodes(items).fetchSemanticsNodes().size
                val portraitWidth = compose.onNodeWithTag("library-navigation").fetchSemanticsNode().boundsInRoot.width
                assertTrue(portraitCount <= 5)
                scenario.rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag("library-navigation")).fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.width
                        ?.let { it > portraitWidth } == true
                }
                assertTrue(compose.onAllNodes(items).fetchSemanticsNodes().size > 5)
                compose.openLibraryPage("Recommendations")
                compose.onNodeWithText("Songs").performClick().assertIsSelected()
                compose.waitUntil(5_000) { controller.state.value.preferences.lastLibraryTab == LibraryTab.SONGS }
                scenario.rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag("library-navigation")).fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.width
                        ?.let { it <= portraitWidth } == true
                }
                compose.onAllNodes(items).assertCountEquals(portraitCount)
                compose.onNodeWithText("Songs").assertIsSelected()
            } finally { scenario.onActivity { it.requestedOrientation = originalOrientation } }
        }
    }

    @Test fun enteringACodeDirectlyDoesNotRequestOrOpenTheCameraAndRetainsInvalidInput() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitForLibrary(app.controller)
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Paired devices").performScrollTo().performClick()
            compose.onNodeWithText("Enter code").performScrollTo().performClick()
            compose.onNodeWithText("Enter pairing code").assertIsDisplayed()
            compose.onNodeWithText("Scan pairing QR code").assertDoesNotExist()
            compose.onNodeWithContentDescription("Pairing camera preview").assertDoesNotExist()
            compose.onNodeWithText("Pairing invitation").assertIsFocused().performTextInput("invalid code")
            compose.onNodeWithText("Pair device").performClick()
            compose.waitUntil(5_000) { requireNotNull(app.controller.pairedDevices).state.value.error != null }
            compose.onNodeWithText("Pairing invitation").assertTextContains("invalid code")
            compose.onNodeWithText("Enter pairing code").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Enter pairing code").assertDoesNotExist()
            // The closing keyboard can reflow the page on small screens, so bring the button back into view first.
            compose.onNodeWithText("Enter code").performScrollTo().assertIsDisplayed()
        }
    }
}
