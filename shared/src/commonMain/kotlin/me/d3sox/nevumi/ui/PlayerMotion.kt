package me.d3sox.nevumi.ui

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.Track
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.next_track
import me.d3sox.nevumi.resources.pause
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.previous_track
import me.d3sox.nevumi.resources.unknown_artist
import org.jetbrains.compose.resources.stringResource

internal enum class PlayerElement { ARTWORK, TITLE, ARTIST, PREVIOUS, PLAY, NEXT }

/** Measures each destination once; the same elements follow the panel's drag progress. */
@Stable
internal class PlayerMotion(private val sheet: AnchoredDraggableState<PlayerPanel>, private val scope: CoroutineScope) {
    var origin by mutableStateOf(Offset.Zero)
    var miniAnchor = 0f
    var height = 0f
    var layoutOffset = 0f
    private val targets = mutableStateMapOf<Pair<PlayerPanel, PlayerElement>, Rect>()
    private val marquees = mutableMapOf<PlayerElement, Pair<String, PlayerMarqueeState>>()

    fun marquee(element: PlayerElement, text: String): PlayerMarqueeState {
        val previous = marquees[element]
        if (previous?.first == text) return previous.second
        return PlayerMarqueeState(scope).also { marquees[element] = text to it }
    }

    fun anchor(panel: PlayerPanel, element: PlayerElement, visible: Boolean = false): Modifier = Modifier
        .onGloballyPositioned { coordinates ->
            val translation = layoutOffset
            val correction = when (panel) {
                PlayerPanel.MINI -> miniAnchor - translation
                PlayerPanel.PLAYING -> -translation.coerceAtLeast(0f)
                PlayerPanel.QUEUE -> -translation - height
            }
            val position = coordinates.positionInRoot() - origin + Offset(0f, correction)
            val rect = Rect(position, coordinates.size.toSize())
            val key = panel to element
            val before = targets[key]
            if (before == null || abs(rect.left - before.left) > .5f || abs(rect.top - before.top) > .5f || rect.size != before.size) targets[key] = rect
        }.then(if (visible) Modifier else Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {})

    fun bounds(element: PlayerElement, expansion: Float, queueProgress: Float): Rect? {
        val mini = targets[PlayerPanel.MINI to element]
        val playing = targets[PlayerPanel.PLAYING to element] ?: mini ?: return null
        if (queueProgress == 0f) return lerp(mini ?: playing, playing, expansion)
        val queue = targets[PlayerPanel.QUEUE to element] ?: targets[PlayerPanel.QUEUE to PlayerElement.ARTWORK] ?: playing
        return lerp(playing, queue, queueProgress)
    }
}

@Composable
internal fun PlayerMotionGlow(motion: PlayerMotion, path: String?, expansion: Float, queueProgress: Float,
    modifier: Modifier = Modifier) {
    if (expansion <= .001f || queueProgress >= .999f) return
    val bounds = motion.bounds(PlayerElement.ARTWORK, expansion, queueProgress) ?: return
    val density = LocalDensity.current
    Box(modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
        .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })) {
        PlayerArtworkGlow(path, expansion * (1 - queueProgress))
    }
}

@Composable
internal fun PlayerMotionElements(motion: PlayerMotion, state: AppState, controller: MusicController, track: Track,
    expansion: Float, queueProgress: Float, drag: Modifier, artworkSwipe: PlayingArtworkSwipe, artworkModifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val requested = state.playback.playWhenReady && !state.playback.ended && state.playback.error == null
    val theme = MaterialTheme.typography
    PlayerElement.entries.forEach { element ->
        if (expansion >= .999f && queueProgress == 0f && element != PlayerElement.ARTWORK) return@forEach
        if (queueProgress >= .999f && element in setOf(PlayerElement.PREVIOUS, PlayerElement.PLAY, PlayerElement.NEXT)) return@forEach
        val destination = motion.bounds(element, expansion, queueProgress) ?: return@forEach
        // Metadata takes a shallow arc around the shrinking cover instead of crossing it.
        val arc = sin(queueProgress * PI).toFloat()
        val bounds = if (element == PlayerElement.TITLE || element == PlayerElement.ARTIST)
            destination.translate(with(density) { Offset(16.dp.toPx() * arc, 24.dp.toPx() * arc) }) else destination
        val modifier = Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
        when (element) {
            PlayerElement.ARTWORK -> Box(modifier.then(artworkModifier).then(drag)) {
                PulsingArtwork(controller.engine, state.playback.playing && expansion > .9f && queueProgress < .1f, expansion * (1 - queueProgress)) {
                PlayingArtwork(state, track, artworkSwipe, expansion, queueProgress)
                }
            }
            PlayerElement.TITLE, PlayerElement.ARTIST -> {
                val title = element == PlayerElement.TITLE
                val full = if (title) theme.headlineSmall else theme.titleMedium
                val small = if (title) theme.titleSmall else theme.bodySmall
                val queue = if (title) theme.titleSmall else theme.bodySmall
                val style = if (queueProgress > 0) lerp(full, queue, queueProgress) else lerp(small, full, expansion)
                Box(modifier.then(drag), contentAlignment = Alignment.CenterStart) {
                    PlayerMarquee(if (title) track.metadata.title else track.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) },
                        style = style, centerProgress = 0f,
                        marqueeState = motion.marquee(element, if (title) track.metadata.title else track.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }),
                        color = if (title) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> Box(modifier.graphicsLayer {
                val visible = if (element == PlayerElement.PLAY) 1f else (1 - queueProgress * 2).coerceAtLeast(0f)
                alpha = visible * visible
            }.then(drag), contentAlignment = Alignment.Center) {
                when (element) {
                    PlayerElement.PREVIOUS -> AppIconButton(AppIcon.PREVIOUS, stringResource(Res.string.previous_track), Modifier.fillMaxSize()) { controller.skip(-1) }
                    PlayerElement.NEXT -> AppIconButton(AppIcon.NEXT, stringResource(Res.string.next_track), Modifier.fillMaxSize()) { controller.skip(1) }
                    else -> FilledIconButton(onClick = { controller.togglePlayback() }, modifier = Modifier.fillMaxSize(), shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                        Icon((if (requested) AppIcon.PAUSE else AppIcon.PLAY).vector, stringResource(if (requested) Res.string.pause else Res.string.play),
                            Modifier.size((24 + 12 * expansion * (1 - queueProgress)).dp))
                    }
                }
            }
        }
    }
}
