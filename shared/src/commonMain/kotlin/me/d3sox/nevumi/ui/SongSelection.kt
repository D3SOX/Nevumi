package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.FileEditTarget
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.queue.QueueAction

internal class SongSelection {
    var selectedIds by mutableStateOf(emptyList<String>())
        private set
    private var visibleIds = emptyList<String>()
    var active by mutableStateOf(false)
        private set
    val allSelected get() = visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }
    fun toggle(id: String, visible: List<String>) {
        visibleIds = visible.distinct()
        active = true
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    fun selectAll() { selectedIds = if (allSelected) emptyList() else visibleIds }
    fun clear() { selectedIds = emptyList(); active = false }
}

internal val LocalSongSelection = compositionLocalOf<SongSelection?> { null }

@Composable
internal fun SongSelectionHeader(selection: SongSelection, controller: MusicController) {
    val state by (LocalSteadyState.current ?: controller.state).collectAsState()
    val catalog = rememberDerived(null, emptyList(), listOf(state.tracks)) { state.tracks.associateBy { it.id } }
    val tracks = selection.selectedIds.mapNotNull(catalog::get)
    var actions by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var fileTarget by remember { mutableStateOf(FileEditTarget.LOCAL) }
    var playlistPicker by remember { mutableStateOf(false) }
    fun enqueue(operation: QueueAction) { controller.enqueue(tracks.map { it.id }, operation); selection.clear() }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcon.CLOSE, "Clear selection", onClick = selection::clear)
        Text(if (tracks.size == 1) "1 song selected" else "${tracks.size} songs selected", Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        AppIconButton(if (selection.allSelected) AppIcon.SELECT_NONE else AppIcon.SELECT_ALL,
            if (selection.allSelected) "Select none" else "Select all", onClick = selection::selectAll)
        AppIconButton(QueueAction.PLAY_NEXT.icon(), "Play next", enabled = tracks.isNotEmpty()) { enqueue(QueueAction.PLAY_NEXT) }
        AppIconButton(QueueAction.APPEND.icon(), "Add to queue", enabled = tracks.isNotEmpty()) { enqueue(QueueAction.APPEND) }
        Box {
            AppIconButton(AppIcon.MORE, "Selection actions", enabled = tracks.isNotEmpty()) { actions = true }
            DropdownMenu(actions, { actions = false }) {
                AppMenuItem(text = { Text("Play after") }, leadingIcon = { Icon(AppIcon.PLAY_AFTER.vector, null) },
                    onClick = { actions = false; enqueue(QueueAction.PLAY_AFTER) })
                AppMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon((if (tracks.all { track ->
                    track.id in state.playlistTrackIds }) AppIcon.PLAYLIST_CHECK else AppIcon.PLAYLIST_PLUS).vector, null) },
                    onClick = { playlistPicker = true; actions = false })
                val favorite = !tracks.all { it.favorite }
                AppMenuItem(text = { Text(if (favorite) "Add to favorites" else "Remove from favorites") },
                    leadingIcon = { Icon(AppIcon.FAVORITE.vector, null) }, onClick = {
                        controller.setFavorite(tracks.map { it.id }, favorite); actions = false; selection.clear()
                    })
                if (controller.localFileEditingAvailable && tracks.all { track -> track.sources.any { it.kind == SourceKind.LOCAL && it.available } })
                    AppMenuItem(text = { Text("Edit tags") }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) },
                        onClick = { fileTarget = FileEditTarget.LOCAL; editing = true; actions = false })
                if (tracks.all(controller::canEditServerTrack))
                    AppMenuItem(text = { Text("Edit server tags") }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) },
                        onClick = { fileTarget = FileEditTarget.SERVER; editing = true; actions = false })
                if (tracks.any { track -> track.sources.any { it.kind == SourceKind.SERVER } })
                    AppMenuItem(text = { Text("Download") }, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) }, onClick = {
                        controller.downloadTracks(tracks.filter { track -> track.sources.any { it.kind == SourceKind.SERVER } }.map { it.id })
                        actions = false; selection.clear()
                    })
            }
        }
    }
    if (editing) BulkTagEditor(tracks, controller, fileTarget) { editing = false }
    if (playlistPicker) PlaylistPicker(state, controller, tracks.map { it.id }) { playlistPicker = false }
}
