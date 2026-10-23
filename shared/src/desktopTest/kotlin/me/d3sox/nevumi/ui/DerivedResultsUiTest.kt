package me.d3sox.nevumi.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DerivedResultsUiTest {
    @Test
    fun changingKeysDuringTheInitialCalculationStillRunsOffTheUiThread() {
        val firstStarted = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val key = mutableIntStateOf(0)
        try {
            runComposeUiTest {
                setContent {
                    val selected = key.intValue
                    val value = rememberDerived("feed", listOf(selected), initial = { "Loading" }) {
                        assertFalse(EventQueue.isDispatchThread(), "A pending key change must not calculate on the UI thread")
                        (if (selected == 0) firstStarted else replacementStarted).countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                        "Ready $selected"
                    }
                    Text(value)
                }
                assertTrue(firstStarted.await(3, TimeUnit.SECONDS))
                runOnIdle { key.intValue = 1 }
                onNodeWithText("Loading").assertIsDisplayed()
                assertTrue(replacementStarted.await(3, TimeUnit.SECONDS))
                release.countDown()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Ready 1").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Ready 0").assertDoesNotExist()
            }
        } finally { release.countDown() }
    }

    @Test
    fun initialCalculationDoesNotBlockTheUiAndAReopenedTabReusesItsResult() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val results = DerivedResults()
        val visible = mutableStateOf(true)
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalDerivedResults provides results) {
                        if (visible.value) {
                            val value = rememberDerived("feed", emptyList(), initial = { "Loading" }) {
                                assertFalse(EventQueue.isDispatchThread(), "The first Home calculation must run off the UI thread")
                                started.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                                "Ready"
                            }
                            Text(value)
                        }
                    }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                onNodeWithText("Loading").assertIsDisplayed()
                release.countDown()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Ready").fetchSemanticsNodes().isNotEmpty() }
                runOnIdle { visible.value = false }
                onNodeWithText("Ready").assertDoesNotExist()
                runOnIdle { visible.value = true }
                onNodeWithText("Ready").assertIsDisplayed()
            }
        } finally { release.countDown() }
    }
}
