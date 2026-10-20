package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.layout { measurable, constraints ->
            val sheet = measurable.measure(constraints)
            val expandedTop = (constraints.maxHeight - sheet.height).coerceAtLeast(0)
            layout(sheet.width, sheet.height) {
                // Material clamps dragging, but its release spring can overshoot the expanded
                // anchor. Keep that overshoot below the screen instead of lifting the content.
                val correction = (expandedTop - sheetState.requireOffset()).coerceAtLeast(0f)
                sheet.placeRelative(0, correction.roundToInt())
            }
        }, content = content)
}
