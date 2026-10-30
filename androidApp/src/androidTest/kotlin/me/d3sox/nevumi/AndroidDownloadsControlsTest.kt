package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.d3sox.nevumi.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDownloadsControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun downloadPauseIsPersistedAndTheControlsWorkAfterActivityRecreation() {
        val app = compose.activity.application as MusicApplication
        val preferences = MusicPreferences(app.store)
        val original = runBlocking { preferences.load() }
        try {
            compose.waitUntil(5_000) { !app.controller.state.value.loading }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Downloads & cache").performScrollTo().performClick()
            compose.onNodeWithText("Manage downloads").performScrollTo().performClick()
            compose.onNodeWithText(if (original.downloadsPaused) "Resume downloads" else "Pause downloads").performClick()
            compose.waitUntil(5_000) { app.controller.state.value.preferences.downloadsPaused != original.downloadsPaused }
            assertEquals(!original.downloadsPaused, runBlocking { preferences.load() }.downloadsPaused)
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Downloads").assertIsDisplayed()
            compose.onNodeWithText(if (original.downloadsPaused) "Pause downloads" else "Resume downloads").performClick()
            compose.waitUntil(5_000) { app.controller.state.value.preferences.downloadsPaused == original.downloadsPaused }
        } finally { runBlocking { app.controller.updatePreferences { original }.join() } }
    }
}
