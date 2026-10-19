package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import me.d3sox.nevumi.library.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal data class TrackOption(val label: String, val icon: AppIcon, val detail: String? = null, val click: () -> Unit)

@Composable
internal fun TrackActionDialog(track: Track, artwork: String?, options: List<TrackOption>, dismiss: () -> Unit,
    artworkOrigin: Rect? = null, artworkCornerRadius: Dp = 16.dp,
    onArtworkPlaced: () -> Unit = {},
    fileOptions: List<TrackOption>? = null) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(dismiss)
    var closing by remember { mutableStateOf(false) }
    fun close(action: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        scope.launch {
            progress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            if (action != null) action() else currentDismiss()
        }
    }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var targetArtwork by remember { mutableStateOf<Rect?>(null) }
    val lift = with(LocalDensity.current) { 32.dp.toPx() }
    val coverSize = with(LocalDensity.current) { 48.dp.toPx() }
    val originCorner = with(LocalDensity.current) { artworkCornerRadius.toPx() }
    val targetCorner = with(LocalDensity.current) { 16.dp.toPx() }
    val from = artworkOrigin?.takeIf { it.left.isFinite() && it.top.isFinite() && it.width > 0 && it.height > 0 }
    // 0 shows the song actions, 1 the file actions below them.
    var page by remember { mutableFloatStateOf(0f) }
    var pageAnimation by remember { mutableStateOf<Job?>(null) }
    val pageHeights = remember { IntArray(2) }
    var touching by remember { mutableStateOf(false) }
    val onFiles by remember { derivedStateOf { page >= .5f } }
    val showActions by remember { derivedStateOf { page < 1f } }
    val showFiles by remember { derivedStateOf { page > 0f } }
    fun showPage(target: Float, velocity: Float = 0f) {
        pageAnimation?.cancel()
        pageAnimation = scope.launch {
            animate(page, target, velocity, tween(280, easing = FastOutSlowInEasing)) { value, _ -> page = value }
        }
    }
    fun settlePage(velocity: Float) = showPage(when {
        velocity < -600f -> 1f
        velocity > 600f -> 0f
        else -> if (onFiles) 1f else 0f
    }, -velocity / pageHeights[0].coerceAtLeast(1))
    val pageScroll = remember(fileOptions != null) {
        object : NestedScrollConnection {
            fun drag(dy: Float): Offset {
                if (fileOptions == null || !touching) return Offset.Zero
                pageAnimation?.cancel()
                val distance = pageHeights[0].coerceAtLeast(1).toFloat()
                val next = (page - dy / distance).coerceIn(0f, 1f)
                val consumed = (page - next) * distance
                page = next
                return Offset(0f, consumed)
            }
            override fun onPreScroll(available: Offset, source: NestedScrollSource) =
                if (page > 0f && page < 1f) drag(available.y) else Offset.Zero
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = drag(available.y)
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (page == 0f || page == 1f) return Velocity.Zero
                settlePage(available.y)
                return available
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (page > 0f && page < 1f && pageAnimation?.isActive != true) settlePage(available.y)
                return Velocity.Zero
            }
        }
    }
    TrackMenuWindow({ close() }) {
        LaunchedEffect(Unit) { progress.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionOnScreen() },
            contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = progress.value }
                .background(Color.Black.copy(alpha = .6f))
                .pointerInput(Unit) { detectTapGestures { close() } })
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(.94f).graphicsLayer {
                alpha = progress.value
                translationY = lift * (1f - progress.value)
            }.pointerInput(Unit) { detectTapGestures {} },
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.heightIn(max = maxHeight * .85f).testTag("track-actions")) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (from != null) Spacer(Modifier.size(48.dp).onGloballyPositioned {
                            if (targetArtwork == null) targetArtwork = Rect(it.positionOnScreen() - Offset(0f, lift * (1f - progress.value)),
                                Size(it.size.width.toFloat(), it.size.height.toFloat()))
                        })
                        else CollectionArtwork(artwork, track.metadata.title, AppIcon.MUSIC, Modifier.size(48.dp).testTag("track-action-artwork"))
                        Column(Modifier.weight(1f)) {
                            Text(track.metadata.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(track.metadata.artist.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (onFiles) AppIconButton(AppIcon.BACK, "Back to song actions", enabled = !closing, onClick = { showPage(0f) })
                        AppIconButton(AppIcon.CLOSE, "Close song actions", enabled = !closing, onClick = { close() })
                    }
                    Layout({
                        if (showActions) TrackOptionPage(options, !closing, ::close, Modifier.layoutId(0)) {
                            if (fileOptions != null) TextButton(onClick = { showPage(1f) }, enabled = !closing, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Icon(AppIcon.EDIT.vector, null); Spacer(Modifier.width(8.dp)); Text("File actions")
                            } else Spacer(Modifier.height(12.dp))
                        }
                        if (fileOptions != null && showFiles) TrackOptionPage(fileOptions, !closing, ::close, Modifier.layoutId(1)) {
                            Spacer(Modifier.height(12.dp))
                        }
                    }, Modifier.weight(1f, fill = false).clipToBounds().nestedScroll(pageScroll).pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                touching = event.changes.any { it.pressed && it.type == PointerType.Touch }
                            }
                        }
                    }) { measurables, constraints ->
                        val placeables = measurables.associate { it.layoutId as Int to it.measure(constraints.copy(minHeight = 0)) }
                        placeables.forEach { (index, placeable) -> pageHeights[index] = placeable.height }
                        val actionsHeight = pageHeights[0]
                        val filesHeight = pageHeights[1].takeIf { it > 0 } ?: actionsHeight
                        val offset = (actionsHeight * page).roundToInt()
                        layout(constraints.maxWidth, (actionsHeight + (filesHeight - actionsHeight) * page).roundToInt()) {
                            placeables[0]?.place(0, -offset)
                            placeables[1]?.place(0, actionsHeight - offset)
                        }
                    }
                }
            }
            if (from != null) {
                Surface(Modifier.align(Alignment.TopStart).size(48.dp).graphicsLayer {
                    val bounds = lerp(from, targetArtwork ?: from, progress.value)
                    translationX = bounds.left - rootOrigin.x
                    translationY = bounds.top - rootOrigin.y
                    scaleX = bounds.width / coverSize
                    scaleY = bounds.height / coverSize
                    transformOrigin = TransformOrigin(0f, 0f)
                    shape = RoundedCornerShape(((originCorner + (targetCorner - originCorner) * progress.value) / scaleX / density).dp)
                    clip = true
                }.onGloballyPositioned { onArtworkPlaced() }.testTag("track-action-artwork"), shape = RectangleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    MusicArtwork(artwork, track.metadata.title, "Song menu artwork: ${track.metadata.title}",
                        maxDimension = if (from.width > coverSize * 2) 1024 else 256)
                }
            }
        }
    }
}

@Composable
private fun TrackOptionPage(options: List<TrackOption>, enabled: Boolean, close: (() -> Unit) -> Unit, modifier: Modifier,
    footer: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            options.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { option ->
                        Column(Modifier.weight(1f).heightIn(min = 80.dp).clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = enabled) { close(option.click) }.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                            Icon(option.icon.vector, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(option.label, style = MaterialTheme.typography.labelMedium,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            option.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        footer()
    }
}
