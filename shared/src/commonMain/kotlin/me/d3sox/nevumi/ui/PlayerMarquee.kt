package me.d3sox.nevumi.ui

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isUnspecified
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Multiplies the base scroll speed of every marquee; set from the user's settings. */
internal val LocalMarqueeSpeed = compositionLocalOf { 1f }

@Stable
internal class PlayerMarqueeState(private val scope: CoroutineScope) {
    var position by mutableFloatStateOf(0f)
        private set
    var range by mutableFloatStateOf(0f)
        private set
    private val consumers = mutableMapOf<Any, Float>()
    private var job: Job? = null
    private var pause = 2f
    private var direction = 1
    private var velocity = 0f

    fun update(key: Any, overflow: Float, velocity: Float) {
        consumers[key] = overflow
        // Hidden copies use other text sizes; only text that scrolls sets the speed.
        if (overflow > 0f) this.velocity = velocity
        refresh()
    }

    fun remove(key: Any) {
        consumers.remove(key)
        refresh()
    }

    // Only overflowing visible text requests frames; an idle marquee would redraw the window on every vsync.
    private fun refresh() {
        range = consumers.values.maxOrNull() ?: 0f
        if (range <= 0f) { job?.cancel(); job = null; return }
        position = position.coerceAtMost(range)
        if (job?.isActive == true) return
        job = scope.launch {
            var previous = 0L
            while (isActive) withInfiniteAnimationFrameNanos { now ->
                val delta = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                previous = now
                if (pause > 0) pause -= delta
                else {
                    position = (position + direction * velocity * delta).coerceIn(0f, range)
                    if (position == 0f || position == range) { direction = -direction; pause = 2f }
                }
            }
        }
    }
}

@Composable
internal fun PlayerMarquee(text: String, style: TextStyle, modifier: Modifier = Modifier,
    color: Color = Color.Unspecified, centerProgress: Float = 0f, marqueeState: PlayerMarqueeState? = null, active: Boolean = true) {
    val scope = rememberCoroutineScope()
    val scrolling = marqueeState ?: remember(text) { PlayerMarqueeState(scope) }
    val key = remember(scrolling) { Any() }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val contentColor = if (color != Color.Unspecified) color else if (style.color != Color.Unspecified) style.color else LocalContentColor.current
    val layout = remember(text, style, contentColor, measurer) { measurer.measure(text, style.copy(color = contentColor), softWrap = false, maxLines = 1) }
    val em = with(density) { if (style.fontSize.isUnspecified) 16.sp.toPx() else style.fontSize.toPx() }
    val velocity = with(density) { 30.dp.toPx() } * LocalMarqueeSpeed.current / em
    var viewport by remember { mutableIntStateOf(0) }
    LaunchedEffect(scrolling, viewport, layout.size.width, em, velocity, active) {
        val overflow = layout.size.width - viewport
        if (viewport > 0) scrolling.update(key, if (active && overflow > 1) overflow / em else 0f, velocity)
    }
    DisposableEffect(scrolling, key) { onDispose { scrolling.remove(key) } }
    Canvas(modifier.fillMaxWidth().height(with(density) { layout.size.height.toDp() }).onSizeChanged { viewport = it.width }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .semantics {
            this.text = AnnotatedString(text)
            getTextLayoutResult { it.add(layout); true }
        }) {
        val overflow = (layout.size.width - size.width).coerceAtLeast(0f)
        val offset = (scrolling.position * em).coerceAtMost(overflow)
        val center = (size.width - layout.size.width).coerceAtLeast(0f) * .5f * centerProgress
        drawText(layout, topLeft = Offset(center - offset, 0f))
        if (overflow > 1f) {
            val edge = minOf(16.dp.toPx(), size.width / 4)
            val leftEdge = minOf(edge, offset)
            val rightEdge = minOf(edge, overflow - offset)
            if (leftEdge > 0f) {
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, leftEdge), blendMode = BlendMode.DstIn)
            }
            if (rightEdge > 0f) {
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - rightEdge, size.width), blendMode = BlendMode.DstIn)
            }
        }
    }
}
