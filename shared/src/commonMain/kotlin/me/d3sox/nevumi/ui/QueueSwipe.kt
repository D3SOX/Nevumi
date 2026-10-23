package me.d3sox.nevumi.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.remove
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun QueueSwipe(entryId: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
    var offset by remember(entryId) { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var dismissing by remember(entryId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val latestRemove by rememberUpdatedState(onRemove)
    fun settle(remove: Boolean) {
        if (dismissing) return
        val accepted = remove && offset <= -threshold
        if (accepted) dismissing = true
        scope.launch {
            if (accepted) {
                animate(offset, -width.toFloat(), animationSpec = tween(180, easing = FastOutLinearInEasing)) { value, _ -> offset = value }
                latestRemove()
            } else animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value }
        }
    }
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }.clipToBounds()) {
        Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.errorContainer) {
            if (offset < 0f) Column(Modifier.fillMaxSize().padding(end = 24.dp),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                Icon(AppIcon.REMOVE.vector, null, Modifier.size(24.dp))
                Text(stringResource(Res.string.remove), style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.horizontalSwipe(entryId, dismissing, onDrag = { amount ->
            if (!dismissing) offset = (offset + amount).coerceAtMost(0f)
        }, onEnd = ::settle)) { content() }
    }
}
