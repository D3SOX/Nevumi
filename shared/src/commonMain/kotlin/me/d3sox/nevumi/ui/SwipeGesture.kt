package me.d3sox.nevumi.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import kotlin.math.sign

// A swipe must travel at least this many times farther sideways than vertically (about 27° from horizontal).
private const val HORIZONTAL_DOMINANCE = 2f

/**
 * Waits for a drag that is clearly horizontal. Diagonal or vertical drags return null so the enclosing list keeps scrolling.
 * Returns the change that crossed the touch slop and the horizontal distance beyond it.
 */
internal suspend fun AwaitPointerEventScope.awaitHorizontalSwipeStart(pointer: PointerId): Pair<PointerInputChange, Float>? {
    val slop = viewConfiguration.touchSlop
    var total = Offset.Zero
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer } ?: return null
        if (change.isConsumed || !change.pressed) return null
        total += change.positionChange()
        if (abs(total.y) > slop) return null
        if (abs(total.x) > slop) {
            if (abs(total.x) < abs(total.y) * HORIZONTAL_DOMINANCE) return null
            change.consume()
            return change to total.x - sign(total.x) * slop
        }
        // Give up once the list has claimed the pointer.
        awaitPointerEvent(PointerEventPass.Final)
        if (change.isConsumed) return null
    }
}

/** Horizontal swipe that leaves vertical and diagonal drags to the enclosing scrollable. */
internal fun Modifier.horizontalSwipe(vararg keys: Any?, onStart: () -> Unit = {}, onDrag: (Float) -> Unit,
    onEnd: (released: Boolean) -> Unit): Modifier = pointerInput(*keys) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val (start, overSlop) = awaitHorizontalSwipeStart(down.id) ?: return@awaitEachGesture
        onStart()
        onDrag(overSlop)
        // Consume vertical movement too once the gesture belongs to the swipe.
        val released = drag(start.id) { change ->
            val amount = change.positionChange().x
            change.consume()
            onDrag(amount)
        }
        onEnd(released)
    }
}
