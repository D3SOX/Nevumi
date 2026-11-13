package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import me.d3sox.nevumi.app.MusicController
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertLibraryBackInHeader() {
    val back = onNodeWithContentDescription("Back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val heading = onNodeWithTag("library-header").fetchSemanticsNode().boundsInRoot
    assertTrue(back.right <= heading.left && back.top <= heading.center.y && back.bottom >= heading.center.y,
        "Back should sit to the left of the library title in the same header row")
    onNodeWithContentDescription("Nevumi logo").assertDoesNotExist()
}

/**
 * MusicPlayerApp renders a copy of the controller state that a collector updates, so the controller can report a ready
 * library before the app has composed it. Waiting for the library pager as well keeps the next lookup from racing it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.waitForLibrary(controller: MusicController) = waitUntil(timeoutMillis = 5_000) {
    controller.state.value.libraryReady && onAllNodesWithTag("library-pager", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openLibraryPage(label: String) {
    val destination = (hasText(label) or hasContentDescription(label)) and hasClickAction()
    if (onAllNodes(destination).fetchSemanticsNodes().isEmpty()) {
        onNodeWithText("More").performClick()
        onNodeWithTag("library-more-pages").performScrollToNode(hasText(label))
        onNode(destination).performClick()
    } else onNode(destination).performClick()
}
