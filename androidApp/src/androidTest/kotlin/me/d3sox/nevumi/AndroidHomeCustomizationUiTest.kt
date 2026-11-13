package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.settings.HomeSectionId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AndroidHomeCustomizationUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun theHomeSectionOrderCanBeChangedAndSurvivesActivityRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForLibrary(app.controller)
            compose.onNodeWithContentDescription("Home").performClick()
            compose.onNodeWithContentDescription("Customize Home").performClick()
            compose.onNodeWithContentDescription("Move Mixes down").performClick()
            compose.onNodeWithText("Save order").performClick()
            compose.waitUntil(5_000) { app.controller.state.value.preferences.homeSections.first() == HomeSectionId.RECENT }
            assertEquals(listOf(HomeSectionId.RECENT, HomeSectionId.MIXES), app.controller.state.value.preferences.homeSections.take(2))
            scenario.recreate()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Customize Home").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("home-section-order").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("home-section-order").performScrollToIndex(0)
            compose.onNodeWithContentDescription("Move Recent listens up").assertIsNotEnabled()
            compose.onNodeWithContentDescription("Move Mixes up").assertIsEnabled()
        }
    }
}
