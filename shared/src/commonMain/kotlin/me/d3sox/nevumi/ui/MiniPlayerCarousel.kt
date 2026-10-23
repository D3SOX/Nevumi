package me.d3sox.nevumi.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.playback.RepeatMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.next
import me.d3sox.nevumi.resources.next_track
import me.d3sox.nevumi.resources.pause
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.previous
import me.d3sox.nevumi.resources.previous_track
import org.jetbrains.compose.resources.stringResource

private data class PreviewTrack(val entryId: String, val track: Track)
private data class Preview(val current: Track, val previous: PreviewTrack?, val next: PreviewTrack?)

@Composable
internal fun MiniPlayerCarousel(state: AppState, controller: MusicController, current: Track, motion: PlayerMotion,
    visible: Boolean, expand: () -> Unit) {
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(1f) }
    var settling by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Preview?>(null) }
    val latest by rememberUpdatedState(state)
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val requested = state.playback.playWhenReady && !state.playback.ended && state.playback.error == null
    fun neighbors(): Preview {
        val state = latest
        fun neighbor(direction: Int): PreviewTrack? {
            val candidate = state.queue.currentIndex + direction
            val index = if (state.queue.repeatMode == RepeatMode.ALL && state.queue.entries.isNotEmpty()) candidate.mod(state.queue.entries.size) else candidate
            val entry = state.queue.entries.getOrNull(index) ?: return null
            return state.tracks.firstOrNull { it.id == entry.trackId }?.let { PreviewTrack(entry.id, it) }
        }
        return Preview(state.tracks.firstOrNull { it.id == state.playback.trackId } ?: current, neighbor(-1), neighbor(1))
    }
    fun settle(commit: Boolean) {
        if (settling) return
        settling = true
        scope.launch {
            try {
                val next = if (offset < 0) preview?.next else preview?.previous
                if (commit && abs(offset) > threshold && next != null) {
                    val direction = if (offset < 0) 1 else -1
                    animate(offset, -direction * width, animationSpec = spring()) { value, _ -> offset = value }
                    controller.skip(direction, restartPrevious = false).join()
                    withTimeoutOrNull(3_000) { controller.state.first { it.playback.entryId == next.entryId || it.playback.error != null } }
                    offset = 0f
                } else animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value }
            } finally { preview = null; settling = false }
        }
    }
    Row(Modifier.fillMaxSize().padding(bottom = 12.dp).pointerInput(controller) {
        detectHorizontalDragGestures(onDragStart = { if (!settling) preview = neighbors() },
            onHorizontalDrag = { change, amount ->
                change.consume()
                if (!settling) {
                    val neighbor = if (offset + amount < 0) preview?.next else preview?.previous
                    offset = (offset + if (neighbor == null) amount * .25f else amount).coerceIn(-width, width)
                }
            }, onDragEnd = { settle(true) }, onDragCancel = { settle(false) })
    }.clickable(onClick = expand).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
            SideEffect { width = widthPx }
            val neighbor = if (offset < 0) preview?.next else preview?.previous
            MiniTrackInformation(state, preview?.current ?: current, Modifier.fillMaxSize().offset { IntOffset(offset.roundToInt(), 0) },
                motion = motion, visible = visible)
            if (offset != 0f && neighbor != null) MiniTrackInformation(state, neighbor.track,
                Modifier.fillMaxSize().offset { IntOffset((offset + if (offset < 0) widthPx else -widthPx).roundToInt(), 0) },
                stringResource(if (offset < 0) Res.string.next else Res.string.previous))
        }
        AppVolume(controller, Modifier.width(200.dp).padding(end = 12.dp))
        AppIconButton(AppIcon.PREVIOUS, stringResource(Res.string.previous_track), motion.anchor(PlayerPanel.MINI, PlayerElement.PREVIOUS, visible)) { controller.skip(-1) }
        FilledIconButton(onClick = { controller.togglePlayback() }, modifier = Modifier.size(48.dp).then(motion.anchor(PlayerPanel.MINI, PlayerElement.PLAY, visible)), shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
            Icon((if (requested) AppIcon.PAUSE else AppIcon.PLAY).vector, stringResource(if (requested) Res.string.pause else Res.string.play))
        }
        AppIconButton(AppIcon.NEXT, stringResource(Res.string.next_track), motion.anchor(PlayerPanel.MINI, PlayerElement.NEXT, visible)) { controller.skip(1) }
    }
}
