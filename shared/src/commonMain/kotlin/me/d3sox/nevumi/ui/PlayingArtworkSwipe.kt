package me.d3sox.nevumi.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.playback.RepeatMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.player_album_artwork
import me.d3sox.nevumi.resources.player_next_artwork
import me.d3sox.nevumi.resources.player_previous_artwork
import me.d3sox.nevumi.resources.player_queue_artwork
import org.jetbrains.compose.resources.stringResource

internal data class ArtworkNeighbor(val entryId: String, val track: Track)
internal data class ArtworkPreview(val current: Track, val previous: ArtworkNeighbor?, val next: ArtworkNeighbor?)

internal fun artworkPreview(state: AppState, current: Track): ArtworkPreview {
    fun neighbor(direction: Int): ArtworkNeighbor? {
        val candidate = state.queue.currentIndex + direction
        val index = if (state.queue.repeatMode == RepeatMode.ALL && state.queue.entries.isNotEmpty()) candidate.mod(state.queue.entries.size) else candidate
        val entry = state.queue.entries.getOrNull(index) ?: return null
        return state.tracks.firstOrNull { it.id == entry.trackId }?.let { ArtworkNeighbor(entry.id, it) }
    }
    return ArtworkPreview(current, neighbor(-1), neighbor(1))
}

@Stable
internal class PlayingArtworkSwipe {
    var offset by mutableFloatStateOf(0f)
        private set
    var preview by mutableStateOf<ArtworkPreview?>(null)
        private set
    var settling by mutableStateOf(false)
        private set
    var width = 1f
    fun begin(state: AppState, current: Track) { if (!settling) preview = artworkPreview(state, current) }
    fun drag(amount: Float) {
        if (settling || preview == null) return
        val neighbor = if (offset + amount < 0) preview?.next else preview?.previous
        offset = (offset + amount * if (neighbor == null) .25f else 1f).coerceIn(-width, width)
    }
    suspend fun settle(controller: MusicController, threshold: Float, commit: Boolean) {
        if (settling || preview == null) return
        settling = true
        try {
            val neighbor = if (offset < 0) preview?.next else preview?.previous
            if (commit && abs(offset) >= threshold && neighbor != null) {
                val direction = if (offset < 0) 1 else -1
                animate(offset, -direction * width, animationSpec = tween(180, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
                controller.skip(direction, restartPrevious = false).join()
                withTimeoutOrNull(1_500) { controller.state.first { it.playback.entryId == neighbor.entryId || it.playback.error != null } }
            } else animate(offset, 0f, animationSpec = tween(180, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
        } finally { offset = 0f; preview = null; settling = false }
    }
}

@Composable
internal fun PlayingArtwork(state: AppState, current: Track, swipe: PlayingArtworkSwipe, expansion: Float, queueProgress: Float) {
    val preview = swipe.preview ?: remember(state.queue.entries, state.queue.currentEntryId, current) { artworkPreview(state, current) }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { swipe.width = it.width.toFloat() }) {
        val covers = buildList {
            add(0 to preview.current)
            if (expansion > .9f && queueProgress < .1f) {
                preview.previous?.let { add(-1 to it.track) }
                preview.next?.let { add(1 to it.track) }
            }
        }
        covers.forEach { (direction, track) ->
            Surface(Modifier.fillMaxSize().graphicsLayer {
                translationX = swipe.offset + direction * size.width
                alpha = (1f - abs(translationX) / size.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
            }, shape = RoundedCornerShape((12 + 16 * expansion * (1 - queueProgress)).dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                MusicArtwork(state.artwork[track.id], track.metadata.title, when {
                    direction < 0 -> stringResource(Res.string.player_previous_artwork, track.metadata.title)
                    direction > 0 -> stringResource(Res.string.player_next_artwork, track.metadata.title)
                    queueProgress >= .999f -> stringResource(Res.string.player_queue_artwork, state.queue.currentEntryId.toString())
                    else -> stringResource(Res.string.player_album_artwork, track.metadata.title)
                }, maxDimension = 1024)
            }
        }
    }
}
