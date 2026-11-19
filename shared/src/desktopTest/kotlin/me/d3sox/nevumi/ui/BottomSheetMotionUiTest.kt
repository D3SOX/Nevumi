package me.d3sox.nevumi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalMaterial3Api::class)
class BottomSheetMotionUiTest {
    @Test
    fun scrollingContentStillScrollsWithoutLiftingTheSheetAtItsEnd() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AppBottomSheet(onDismissRequest = {}) {
                    val scroll = rememberLazyListState()
                    LazyColumn(Modifier.fillMaxWidth().height(300.dp).testTag("sheet-list"), state = scroll) {
                        items(30) { Text("Item $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        waitForIdle()
        val list = onNodeWithTag("sheet-list")
        val restingTop = list.fetchSemanticsNode().boundsInRoot.top
        list.performTouchInput { swipeUp() }
        onNodeWithText("Item 0").assertDoesNotExist()
        list.performScrollToIndex(29)
        mainClock.autoAdvance = false
        list.performTouchInput { swipeUp(durationMillis = 100) }
        repeat(30) {
            mainClock.advanceTimeByFrame()
            assertTrue(list.fetchSemanticsNode().boundsInRoot.top >= restingTop - 1f)
        }
    }

    @Test
    fun upwardFlingDoesNotLiftExpandedSheetAndDownwardSwipeStillDismisses() = runComposeUiTest {
        var dismissed = false
        setContent {
            MaterialTheme {
                AppBottomSheet(onDismissRequest = { dismissed = true },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Box(Modifier.fillMaxWidth().height(300.dp).testTag("sheet-content"))
                }
            }
        }
        waitForIdle()
        val sheet = onNodeWithTag("sheet-content")
        val restingTop = sheet.fetchSemanticsNode().boundsInRoot.top
        mainClock.autoAdvance = false
        sheet.performTouchInput { swipe(center, center - Offset(0f, 120f), 100) }
        repeat(30) {
            mainClock.advanceTimeByFrame()
            val top = sheet.fetchSemanticsNode().boundsInRoot.top
            assertTrue(top >= restingTop - 1f, "Sheet jumped above its resting position: $top < $restingTop")
        }
        mainClock.autoAdvance = true
        sheet.performTouchInput { swipe(topCenter + Offset(0f, 10f), bottomCenter, 300) }
        waitUntil(timeoutMillis = 2_000) { dismissed }
    }

    @Test
    fun backdropFollowsTheSheetWhileItIsDragged() = runComposeUiTest {
        val backdrop = SheetBackdrop()
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalSheetBackdrop provides backdrop) {
                    AppBottomSheet(onDismissRequest = {}) {
                        Box(Modifier.fillMaxWidth().height(300.dp).testTag("sheet-content"))
                    }
                }
            }
        }
        waitForIdle()
        assertEquals(1f, backdrop.progress, 0.01f)
        val sheet = onNodeWithTag("sheet-content")
        sheet.performTouchInput {
            down(topCenter + Offset(0f, 10f))
            moveBy(Offset(0f, height * 0.4f))
        }
        waitForIdle()
        assertTrue(backdrop.progress in 0.4f..0.8f, "Backdrop did not follow the drag: ${backdrop.progress}")
        sheet.performTouchInput { up() }
    }

    @Test
    fun fullyOpenSheetDimsTheBackdropLikeMaterialsScrim() = runComposeUiTest {
        val backdrop = SheetBackdrop().apply { update(Any(), 1f) }
        setContent { Box(Modifier.size(40.dp).sheetBackdrop(backdrop).background(Color.White).testTag("backdrop")) }
        val pixel = onNodeWithTag("backdrop").captureToImage().toPixelMap()[20, 20]
        assertEquals(0.68f, pixel.red, 0.02f)
    }
}
