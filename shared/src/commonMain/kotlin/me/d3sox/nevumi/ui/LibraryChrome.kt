package me.d3sox.nevumi.ui

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt

/**
 * In a short window, such as split screen, the library's header and navigation slide away while a page scrolls down and
 * return as soon as it scrolls up, so the page keeps room for its content.
 */
@Stable
internal class LibraryChrome {
    /** How much of the header and navigation has slid away, from 0 to 1. */
    var hidden by mutableFloatStateOf(0f)
        private set
    var enabled = false
    /** Measured while laying out, so scrolling never recomposes the library. */
    private var headerHeight = 0
    private var navigationHeight = 0

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Without a header, the navigation slides over its own height and leaves the scroll to the page,
            // which doesn't move with it.
            val distance = (if (headerHeight > 0) headerHeight else navigationHeight).toFloat()
            if (!enabled || distance == 0f || available.y == 0f) return Offset.Zero
            val previous = hidden
            hidden = (hidden - available.y / distance).coerceIn(0f, 1f)
            // The page moves up as the header above it shrinks, so the header takes the scroll it uses.
            return Offset(0f, (previous - hidden) * headerHeight)
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (enabled && hidden > 0f && hidden < 1f) settle(if (hidden >= .5f) 1f else 0f)
            return Velocity.Zero
        }
    }

    suspend fun show() { if (hidden > 0f) settle(0f) }

    private suspend fun settle(target: Float) = animate(hidden, target) { value, _ -> hidden = value }

    /** The header slides up out of the window's top. */
    fun header(modifier: Modifier) = modifier.slideAway(top = true) { headerHeight = it }

    /** The navigation slides down out of the window's bottom. */
    fun navigation(modifier: Modifier) = modifier.slideAway(top = false) { navigationHeight = it }

    private fun Modifier.slideAway(top: Boolean, measured: (Int) -> Unit) = clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        measured(placeable.height)
        val offset = (placeable.height * hidden).roundToInt()
        layout(placeable.width, placeable.height - offset) { placeable.place(0, if (top) -offset else 0) }
    }
}
