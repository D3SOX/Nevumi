package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.Track
import kotlin.math.abs

@Composable
internal fun PlayingTrackDetails(state: AppState, controller: MusicController, current: Track,
    motion: PlayerMotion, visible: Boolean, swipe: PlayingArtworkSwipe) {
    var managingPlaylist by remember { mutableStateOf<String?>(null) }
    val preview = swipe.preview
    val tracks = remember(state.tracks) { state.tracks.associateBy { it.id } }
    val entries = buildList {
        add(0 to (preview?.current ?: current))
        if (preview != null) {
            preview.previous?.let { add(-1 to it.track) }
            preview.next?.let { add(1 to it.track) }
        }
    }
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        entries.forEach { (direction, candidate) ->
            val track = tracks[candidate.id] ?: candidate
            val role = when { direction < 0 -> "previous"; direction > 0 -> "next"; else -> "current" }
            Row((if (direction == 0) Modifier.fillMaxWidth() else Modifier.matchParentSize())
                .testTag("now-playing-details:$role").graphicsLayer {
                    val progress = swipe.offset / swipe.width.coerceAtLeast(1f) + direction
                    translationX = progress * size.width
                    alpha = (1f - abs(progress)).coerceIn(0f, 1f)
                }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PlayerMarquee(track.metadata.title, MaterialTheme.typography.headlineSmall,
                        if (direction == 0) motion.anchor(PlayerPanel.PLAYING, PlayerElement.TITLE, visible) else Modifier,
                        marqueeState = if (direction == 0) motion.marquee(PlayerElement.TITLE, track.metadata.title) else null, active = visible)
                    PlayerMarquee(track.metadata.artist.ifBlank { "Unknown artist" }, MaterialTheme.typography.titleMedium,
                        if (direction == 0) motion.anchor(PlayerPanel.PLAYING, PlayerElement.ARTIST, visible) else Modifier,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        marqueeState = if (direction == 0) motion.marquee(PlayerElement.ARTIST, track.metadata.artist.ifBlank { "Unknown artist" }) else null, active = visible)
                }
                IconToggleButton(track.favorite, { controller.setFavorite(track.id, it) }) {
                    Icon((if (track.favorite) AppIcon.FAVORITE_FILLED else AppIcon.FAVORITE).vector,
                        if (track.favorite) "Remove favorite" else "Favorite",
                        tint = if (track.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val member = state.playlists.any { track.id in it.trackIds }
                AppIconButton(if (member) AppIcon.PLAYLIST_CHECK else AppIcon.PLAYLIST_PLUS, "Manage playlists",
                    Modifier.semantics { selected = member; stateDescription = if (member) "In a playlist" else "Not in a playlist" }) {
                    managingPlaylist = track.id
                }
            }
        }
    }
    managingPlaylist?.let { PlaylistPicker(controller, it) { managingPlaylist = null } }
}
