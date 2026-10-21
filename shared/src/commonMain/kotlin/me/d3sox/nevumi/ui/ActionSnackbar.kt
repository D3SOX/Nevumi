package me.d3sox.nevumi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.ActionDestination
import me.d3sox.nevumi.app.ActionNotice
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

internal class ActionSnackbarVisuals(val notice: ActionNotice) : SnackbarVisuals {
    override val message = notice.message
    override val actionLabel = if (notice.undo != null) { if (notice.isRedo) "Redo" else "Undo" }
        else if (notice.play != null) "Play" else notice.destination?.openLabel()
    override val withDismissAction = false
    override val duration = if (notice.undo != null || notice.play != null) SnackbarDuration.Long else SnackbarDuration.Short
}

internal fun ActionDestination.openLabel() = when (this) {
    ActionDestination.QUEUE -> "Open queue"
    ActionDestination.FAVORITES -> "Open favorites"
    ActionDestination.PLAYLISTS -> "Open playlists"
    ActionDestination.DOWNLOADS -> "Open downloads"
}

@Composable
internal fun ActionSnackbar(data: SnackbarData, open: (ActionDestination) -> Unit) {
    val notice = (data.visuals as? ActionSnackbarVisuals)?.notice
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    var offset by remember(data) { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(640f) }
    var animation by remember { mutableStateOf<Job?>(null) }
    fun release(cancelled: Boolean = false) {
        val accepted = !cancelled && abs(offset) > threshold
        val start = offset
        val target = if (accepted) sign(offset) * width * 1.5f else 0f
        animation = scope.launch {
            animate(initialValue = start, targetValue = target, animationSpec = tween(160)) { value, _ -> offset = value }
            if (accepted) data.dismiss()
        }
    }
    Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().onSizeChanged { width = it.width.toFloat() }
        .graphicsLayer { translationX = offset }
        .pointerInput(data) {
            detectHorizontalDragGestures(onDragStart = { animation?.cancel() }, onDragCancel = { release(true) },
                onDragEnd = { release() }) { change, delta -> change.consume(); offset += delta }
        }.semantics { liveRegion = LiveRegionMode.Polite; dismiss { data.dismiss(); true } },
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = 6.dp, shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon((if (notice == null) AppIcon.INFO else AppIcon.CHECK).vector, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(data.visuals.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = data::performAction) {
                    if (notice?.undo != null) { Icon((if (notice.isRedo) AppIcon.REDO else AppIcon.UNDO).vector, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(label)
                }
            }
            if (notice?.undo != null) notice.destination?.let { destination ->
                AppIconButton(when (destination) {
                    ActionDestination.QUEUE, ActionDestination.PLAYLISTS -> AppIcon.QUEUE
                    ActionDestination.FAVORITES -> AppIcon.FAVORITE
                    ActionDestination.DOWNLOADS -> AppIcon.DOWNLOAD
                }, destination.openLabel()) { data.dismiss(); open(destination) }
            }
            if (data.visuals.withDismissAction) AppIconButton(AppIcon.CLOSE, "Dismiss message", onClick = data::dismiss)
        }
    }
}
