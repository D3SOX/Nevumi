package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import me.d3sox.nevumi.app.MusicController

/**
 * MusicPlayerApp renders a copy of the controller state that a collector updates, so the controller can report a ready
 * library before the app has composed it. Waiting for the library pager as well keeps the next lookup from racing it.
 */
internal fun ComposeTestRule.waitForLibrary(controller: MusicController, timeoutMillis: Long = 5_000) = waitUntil(timeoutMillis) {
    controller.state.value.libraryReady && onAllNodesWithTag("library-pager", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}

internal fun ComposeTestRule.openLibraryPage(label: String) {
    val destination = (hasText(label) or hasContentDescription(label)) and hasClickAction()
    if (onAllNodes(destination).fetchSemanticsNodes().isEmpty()) {
        onNodeWithText("More").performClick()
        onNodeWithTag("library-more-pages").performScrollToNode(hasText(label))
        onNode(destination).performClick()
    } else onNode(destination).performClick()
}

internal fun ComposeTestRule.openFileAction(label: String) {
    if (onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
        onNodeWithText("File actions").performClick()
    }
    onNodeWithText(label).performScrollTo().performClick()
}
