package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** How far each open bottom sheet is pulled up, so the app behind it can dim and blur along with the drag. */
internal class SheetBackdrop {
    private val sheets = mutableStateMapOf<Any, Float>()
    val progress: Float get() = sheets.values.maxOrNull() ?: 0f

    fun update(sheet: Any, progress: Float) { if (sheets[sheet] != progress) sheets[sheet] = progress }
    fun remove(sheet: Any) { sheets -= sheet }
}

internal val LocalSheetBackdrop = staticCompositionLocalOf<SheetBackdrop?> { null }

private val BackdropScrim = Color.Black.copy(alpha = 0.32f)
private val BackdropBlur = 4.dp

/** Dims and blurs this content in step with the open bottom sheets. */
internal fun Modifier.sheetBackdrop(backdrop: SheetBackdrop) = drawWithContent {
    drawContent()
    val progress = backdrop.progress
    if (progress > 0f) drawRect(BackdropScrim, alpha = BackdropScrim.alpha * progress)
}.graphicsLayer {
    val radius = BackdropBlur.toPx() * backdrop.progress
    renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalSheetBackdrop.current
    val key = remember { Any() }
    if (backdrop != null) DisposableEffect(backdrop) { onDispose { backdrop.remove(key) } }
    // Material fades its scrim in one step once the sheet settles on a side. The backdrop follows the drag instead.
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = if (backdrop != null) Color.Transparent else BottomSheetDefaults.ScrimColor,
        modifier = Modifier.layout { measurable, constraints ->
            val sheet = measurable.measure(constraints)
            val expandedTop = (constraints.maxHeight - sheet.height).coerceAtLeast(0)
            layout(sheet.width, sheet.height) {
                val offset = sheetState.requireOffset()
                val travel = constraints.maxHeight - expandedTop
                if (travel > 0) backdrop?.update(key, ((constraints.maxHeight - offset) / travel).coerceIn(0f, 1f))
                // Material clamps dragging, but its release spring can overshoot the expanded
                // anchor. Keep that overshoot below the screen instead of lifting the content.
                val correction = (expandedTop - offset).coerceAtLeast(0f)
                sheet.placeRelative(0, correction.roundToInt())
            }
        }, content = content)
}
