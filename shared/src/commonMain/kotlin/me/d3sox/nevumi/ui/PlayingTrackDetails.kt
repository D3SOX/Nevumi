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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.manage_playlists
import me.d3sox.nevumi.resources.player_favorite
import me.d3sox.nevumi.resources.player_in_a_playlist
import me.d3sox.nevumi.resources.player_not_in_a_playlist
import me.d3sox.nevumi.resources.player_remove_favorite
import me.d3sox.nevumi.resources.unknown_artist
import org.jetbrains.compose.resources.stringResource

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
                    PlayerMarquee(track.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }, MaterialTheme.typography.titleMedium,
                        if (direction == 0) motion.anchor(PlayerPanel.PLAYING, PlayerElement.ARTIST, visible) else Modifier,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        marqueeState = if (direction == 0) motion.marquee(PlayerElement.ARTIST, track.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }) else null, active = visible)
                }
                IconToggleButton(track.favorite, { controller.setFavorite(track.id, it) }) {
                    Icon((if (track.favorite) AppIcon.FAVORITE_FILLED else AppIcon.FAVORITE).vector,
                        stringResource(if (track.favorite) Res.string.player_remove_favorite else Res.string.player_favorite),
                        tint = if (track.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val member = state.playlists.any { track.id in it.trackIds }
                val membership = stringResource(if (member) Res.string.player_in_a_playlist else Res.string.player_not_in_a_playlist)
                AppIconButton(if (member) AppIcon.PLAYLIST_CHECK else AppIcon.PLAYLIST_PLUS, stringResource(Res.string.manage_playlists),
                    Modifier.semantics { selected = member; stateDescription = membership }) {
                    managingPlaylist = track.id
                }
            }
        }
    }
    managingPlaylist?.let { PlaylistPicker(controller, it) { managingPlaylist = null } }
}
