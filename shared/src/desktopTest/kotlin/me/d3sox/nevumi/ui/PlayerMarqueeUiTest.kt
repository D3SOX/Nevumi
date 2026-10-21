package me.d3sox.nevumi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PlayerMarqueeUiTest {
    @Test fun endpointsShowUnfadedTextOnTheReachedEdge() = runComposeUiTest {
        lateinit var scrolling: PlayerMarqueeState
        mainClock.autoAdvance = false
        setContent {
            val scope = rememberCoroutineScope()
            val state = remember { PlayerMarqueeState(scope) }
            SideEffect { scrolling = state }
            val text = "M".repeat(32)
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = Color.Black)
            val layout = rememberTextMeasurer().measure(text, style, softWrap = false, maxLines = 1)
            val density = LocalDensity.current
            val em = with(density) { 16.sp.toPx() }
            Column {
                Box(Modifier.width(200.dp).background(Color.White).testTag("marquee")) {
                    PlayerMarquee(text, style, marqueeState = state)
                }
                Canvas(Modifier.width(200.dp).height(with(density) { layout.size.height.toDp() })
                    .background(Color.White).testTag("reference")) {
                    val offset = (state.position * em).coerceAtMost((layout.size.width - size.width).coerceAtLeast(0f))
                    drawText(layout, topLeft = Offset(-offset, 0f))
                }
            }
        }
        mainClock.advanceTimeBy(100)
        fun edgeDifference(left: Boolean): Double {
            val actual = onNodeWithTag("marquee").captureToImage().toPixelMap()
            val reference = onNodeWithTag("reference").captureToImage().toPixelMap()
            val columns = if (left) 0 until 12 else actual.width - 12 until actual.width
            var total = 0.0
            for (y in 0 until actual.height) for (x in columns) {
                total += abs(actual[x, y].red - reference[x, y].red)
            }
            return total / (12 * actual.height)
        }
        assertEquals(0f, scrolling.position)
        val startDifference = edgeDifference(left = true)
        assertTrue(edgeDifference(left = false) > .02, "The overflowing right edge should still fade")
        mainClock.advanceTimeUntil(timeoutMillis = 20_000) { scrolling.range > 0f && scrolling.position == scrolling.range }
        val endDifference = edgeDifference(left = false)
        assertTrue(edgeDifference(left = true) > .02, "The overflowing left edge should still fade")
        assertTrue(startDifference < .001, "The first characters must be unfaded at the start: $startDifference")
        assertTrue(endDifference < .001, "The last characters must be unfaded at the end: $endDifference")
    }


    @Test fun handingTextToAnotherPlayerViewKeepsItsScrollPhase() = runComposeUiTest {
        var expanded by mutableStateOf(false)
        lateinit var shared: PlayerMarqueeState
        mainClock.autoAdvance = false
        setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            val scrolling = remember { PlayerMarqueeState(scope) }
            SideEffect { shared = scrolling }
            key(expanded) {
                Box(Modifier.width(200.dp).background(Color.White).testTag("marquee")) {
                    PlayerMarquee("ABCDEFGHIJKLMNOPQRSTUVWXYZ ".repeat(5), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                        color = Color.Black, marqueeState = scrolling)
                }
            }
        } }
        mainClock.advanceTimeBy(100)
        val initial = onNodeWithTag("marquee").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(4_000)
        val before = onNodeWithTag("marquee").captureToImage().toPixelMap()
        val beforePosition = shared.position
        assertTrue(difference(initial, before) > .03)
        runOnIdle { expanded = true }
        mainClock.advanceTimeBy(32)
        val after = onNodeWithTag("marquee").captureToImage().toPixelMap()
        assertTrue(abs(shared.position - beforePosition) < .1f, "A handoff must preserve the scroll phase")
        // A fractional-pixel shift changes glyph antialiasing; compare against an actual reset as well.
        assertTrue(difference(before, after) < difference(initial, after) * .75,
            "Moving the title between player views must retain its visible position")
        mainClock.advanceTimeBy(500)
        assertTrue(difference(after, onNodeWithTag("marquee").captureToImage().toPixelMap()) > .02,
            "The title should continue scrolling immediately after the handoff")
    }

    @Test fun changingThePlayerViewportKeepsTheCurrentMarqueePosition() = runComposeUiTest {
        var width by mutableStateOf(200.dp)
        mainClock.autoAdvance = false
        setContent { MaterialTheme {
            Box(Modifier.width(width).background(Color.White).testTag("marquee")) {
                PlayerMarquee("ABCDEFGHIJKLMNOPQRSTUVWXYZ ".repeat(5), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp), color = Color.Black)
            }
        } }
        mainClock.advanceTimeBy(100)
        val initial = onNodeWithTag("marquee").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(4_000)
        val scrolling = onNodeWithTag("marquee").captureToImage().toPixelMap()
        assertTrue(difference(initial, scrolling) > .03, "The test must reach a visibly scrolled title")
        runOnIdle { width = 201.dp }
        mainClock.advanceTimeBy(32)
        val resized = onNodeWithTag("marquee").captureToImage().toPixelMap()
        assertTrue(difference(scrolling, resized) < .025, "The marquee must retain its position when the player width changes")
    }

    @Test fun aMarqueeStopsAnimatingOnceItsTextFitsOrIsHidden() = runComposeUiTest {
        var width by mutableStateOf(200.dp)
        var active by mutableStateOf(true)
        lateinit var shared: PlayerMarqueeState
        mainClock.autoAdvance = false
        setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            val scrolling = remember { PlayerMarqueeState(scope) }
            SideEffect { shared = scrolling }
            Box(Modifier.requiredWidth(width)) {
                PlayerMarquee("ABCDEFGHIJKLMNOPQRSTUVWXYZ ".repeat(5), TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp),
                    marqueeState = scrolling, active = active)
            }
        } }
        mainClock.advanceTimeBy(4_000)
        assertTrue(shared.position > 0f, "The test must reach a scrolling title")
        runOnIdle { width = 4_000.dp }
        mainClock.advanceTimeBy(100)
        val fitting = shared.position
        mainClock.advanceTimeBy(3_000)
        assertEquals(fitting, shared.position, "Text that fits must not keep requesting frames")
        runOnIdle { width = 200.dp; active = false }
        mainClock.advanceTimeBy(100)
        val hidden = shared.position
        mainClock.advanceTimeBy(3_000)
        assertEquals(hidden, shared.position, "Hidden text must not keep requesting frames")
    }

    @Test fun theScrollSpeedSettingScalesHowFastTitlesMove() = runComposeUiTest {
        lateinit var normal: PlayerMarqueeState
        lateinit var fast: PlayerMarqueeState
        mainClock.autoAdvance = false
        setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            val first = remember { PlayerMarqueeState(scope) }
            val second = remember { PlayerMarqueeState(scope) }
            SideEffect { normal = first; fast = second }
            val text = "ABCDEFGHIJKLMNOPQRSTUVWXYZ ".repeat(5)
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp)
            Column {
                Box(Modifier.width(200.dp)) { PlayerMarquee(text, style, marqueeState = first) }
                CompositionLocalProvider(LocalMarqueeSpeed provides 2f) {
                    Box(Modifier.width(200.dp)) { PlayerMarquee(text, style, marqueeState = second) }
                }
            }
        } }
        mainClock.advanceTimeBy(3_000)
        assertTrue(normal.position > 0f, "The test must reach a scrolling title")
        assertEquals(2f, fast.position / normal.position, .05f)
    }

    private fun difference(first: PixelMap, second: PixelMap): Double {
        val width = minOf(128, first.width, second.width)
        val height = minOf(first.height, second.height)
        return (-2..2).minOf { shift ->
            var total = 0.0
            for (y in 0 until height) for (x in 18 until width) total += abs(first[x, y].red - second[x + shift, y].red)
            total / ((width - 18) * height)
        }
    }
}
