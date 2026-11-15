package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.add_to_queue
import me.d3sox.nevumi.resources.play_after
import me.d3sox.nevumi.resources.play_next
import me.d3sox.nevumi.resources.player_clear_selection
import me.d3sox.nevumi.resources.player_select_all
import me.d3sox.nevumi.resources.player_select_none
import me.d3sox.nevumi.resources.player_selection_actions
import me.d3sox.nevumi.resources.player_songs_selected
import me.d3sox.nevumi.resources.sources_save_to_this_device
import me.d3sox.nevumi.resources.sources_youtube_save_to_server
import me.d3sox.nevumi.youtube.YouTubeDestination
import me.d3sox.nevumi.youtube.YouTubeTrack
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The songs of one YouTube list, such as a page's song shelf, which [key] tells apart; read each time, so songs it loaded since count too. */
internal class YouTubeTrackList(val key: Any, val tracks: () -> List<YouTubeTrack>)

/** Songs picked from one YouTube list, kept in the order the list shows them, to queue or save together. */
internal class YouTubeSelection {
    var tracks by mutableStateOf(emptyList<YouTubeTrack>())
        private set
    var active by mutableStateOf(false)
        private set
    private var shown by mutableStateOf<YouTubeTrackList?>(null)
    private val visible get() = shown?.tracks?.invoke().orEmpty().distinctBy { it.videoId }
    val allSelected get() = visible.let { list -> list.isNotEmpty() && list.all(::contains) }
    operator fun contains(track: YouTubeTrack) = tracks.any { it.videoId == track.videoId }
    fun toggle(track: YouTubeTrack, list: YouTubeTrackList) {
        // A song from another list, such as a page's second song shelf, starts a new selection.
        if (shown?.key != list.key) tracks = emptyList()
        shown = list
        active = true
        val picked = if (track in this) tracks.filterNot { it.videoId == track.videoId } else tracks + track
        val order = visible
        tracks = picked.sortedBy { selected -> order.indexOfFirst { it.videoId == selected.videoId } }
    }
    fun selectAll() { tracks = if (allSelected) emptyList() else visible }
    fun clear() { tracks = emptyList(); active = false }
}

internal val LocalYouTubeSelection = compositionLocalOf<YouTubeSelection?> { null }

/** Replaces a YouTube list's header while songs are selected, like the library's selection header. */
@Composable
internal fun YouTubeSelectionHeader(selection: YouTubeSelection, state: AppState, controller: MusicController) {
    val tracks = selection.tracks
    var actions by remember { mutableStateOf(false) }
    val page = LocalYouTubePage.current
    fun enqueue(action: QueueAction) { controller.enqueueYouTube(tracks, action, page); selection.clear() }
    fun download(destination: YouTubeDestination) {
        controller.downloadFromYouTube(tracks, null, destination, state.preferences.youTubeReviewTags); selection.clear()
    }
    PlatformBackHandler(selection::clear)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcon.CLOSE, stringResource(Res.string.player_clear_selection), onClick = selection::clear)
        Text(pluralStringResource(Res.plurals.player_songs_selected, tracks.size, tracks.size), Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        AppIconButton(if (selection.allSelected) AppIcon.SELECT_NONE else AppIcon.SELECT_ALL,
            stringResource(if (selection.allSelected) Res.string.player_select_none else Res.string.player_select_all), onClick = selection::selectAll)
        AppIconButton(QueueAction.PLAY_NEXT.icon(), stringResource(Res.string.play_next), enabled = tracks.isNotEmpty()) { enqueue(QueueAction.PLAY_NEXT) }
        AppIconButton(QueueAction.APPEND.icon(), stringResource(Res.string.add_to_queue), enabled = tracks.isNotEmpty()) { enqueue(QueueAction.APPEND) }
        Box {
            AppIconButton(AppIcon.MORE, stringResource(Res.string.player_selection_actions), enabled = tracks.isNotEmpty()) { actions = true }
            DropdownMenu(actions, { actions = false }) {
                AppMenuItem(text = { Text(stringResource(Res.string.play_after)) }, leadingIcon = { Icon(AppIcon.PLAY_AFTER.vector, null) },
                    onClick = { actions = false; enqueue(QueueAction.PLAY_AFTER) })
                YouTubeSaveMenuItems(state, controller) { destination -> actions = false; download(destination) }
            }
        }
    }
}

/** The menu items that save YouTube songs to this device, or to it and the music server, when this device can. */
@Composable
internal fun YouTubeSaveMenuItems(state: AppState, controller: MusicController, save: (YouTubeDestination) -> Unit) {
    if (!controller.youTubeDeviceDownloads) return
    AppMenuItem({ Text(stringResource(Res.string.sources_save_to_this_device)) }, { save(YouTubeDestination.DEVICE) },
        leadingIcon = { Icon(AppIcon.DEVICE.vector, null) })
    val sftp = state.sftp
    if (controller.youTubeServerUploads && sftp != null) AppMenuItem({
        Column {
            Text(stringResource(Res.string.sources_youtube_save_to_server))
            Text(sftp.libraryName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, { save(YouTubeDestination.SERVER) }, leadingIcon = { Icon(AppIcon.SERVER.vector, null) })
}
