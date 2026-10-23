package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.queue.QueueOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CollectionActionHost(entry: CollectionItem, tab: LibraryTab, state: AppState, controller: MusicController,
    open: () -> Unit, content: @Composable (openMenu: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var rules by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var overwriting by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val playlist = state.playlists.firstOrNull { tab == LibraryTab.PLAYLISTS && it.id == entry.id }
    val saved = state.savedQueues.firstOrNull { tab == LibraryTab.HOME && it.id == entry.id }
    // Resolving songs is costly for large playlists and folders, so only an open menu or picker does it.
    @Composable fun rememberTracks() = remember(playlist, entry.trackIds, state.tracks) {
        val catalog = state.tracks.associateBy { it.id }
        val tracks = playlist?.orderedTracks(catalog)?.map { it.value } ?: entry.trackIds.mapNotNull(catalog::get)
        (if (playlist != null) tracks.map { it.id } else entry.trackIds) to tracks
    }
    val files = if (playlist != null) LocalPlaylistFiles.current else null
    val scope = rememberCoroutineScope()
    val openMenu = { menu = true }
    val latest by rememberUpdatedState(openMenu)
    fun close(action: () -> Unit) { menu = false; action() }
    Box(Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && event.changes.none { it.isConsumed }) {
                    event.changes.forEach { it.consume() }; latest()
                }
            }
        }
    }) {
        content(openMenu)
        DropdownMenu(menu, { menu = false }) {
            val (trackIds, tracks) = rememberTracks()
            AppMenuItem(text = { Text(if (saved != null) "Select queue" else "Play") }, leadingIcon = { Icon(AppIcon.PLAY.vector, null) },
                enabled = trackIds.isNotEmpty(), onClick = { close {
                    if (saved != null) controller.loadSavedQueue(saved.id)
                    else controller.playTrack(trackIds.first(), trackIds, 0, entry.title,
                        shuffleContext = playlist?.shuffle == true || tab == LibraryTab.HOME, origin = QueueOrigin(tab, entry.id))
                } })
            QueueActionMenuItems(trackIds, controller) { menu = false }
            AppMenuItem(text = { Text("Manage playlists") }, leadingIcon = { Icon(AppIcon.PLAYLIST_PLUS.vector, null) },
                enabled = trackIds.isNotEmpty(), onClick = { close { picker = true } })
            if (tracks.isNotEmpty()) {
                val favorite = tracks.all { it.favorite }
                AppMenuItem(text = { Text(if (favorite) "Remove favorites" else "Add to favorites") },
                    leadingIcon = { Icon((if (favorite) AppIcon.FAVORITE_FILLED else AppIcon.FAVORITE).vector, null) },
                    onClick = { close { controller.setFavorite(trackIds, !favorite) } })
            }
            if (tracks.any { it.sources.any { source -> source.kind == SourceKind.SERVER } })
                AppMenuItem(text = { Text("Download") }, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) }, onClick = { close { controller.downloadTracks(trackIds) } })
            if (playlist != null) {
                HorizontalDivider()
                if (playlist.server?.readOnly != true) {
                    AppMenuItem(text = { Text("Edit playlist") }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) }, onClick = { close { editing = true } })
                    if (playlist.rule != null) AppMenuItem(text = { Text("Edit rules") }, onClick = { close { rules = true } })
                    AppMenuItem(text = { Text("Delete playlist") }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) }, onClick = { close { deleting = true } })
                }
                AppMenuItem(text = { Text("Export M3U") }, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) }, enabled = !exporting, onClick = { close {
                    scope.launch {
                        exporting = true
                        try {
                            val filename = playlist.name.map { if (it in "/\\" || it.code < 32) '_' else it }.joinToString("").take(120) + ".m3u8"
                            requireNotNull(files).save(filename, controller.exportM3u(playlist.id))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "The playlist could not be exported" }
                        finally { exporting = false }
                    }
                } })
                val server = state.server
                if (playlist.server == null && playlist.rule == null && server != null && controller.serverPlaylistPublishingAvailable &&
                    tracks.all { track -> track.sources.any { it.kind == SourceKind.SERVER && it.id.startsWith("server:${server.serverId}:") } })
                    AppMenuItem(text = { Text("Publish to your music server") }, onClick = { close { publishing = true } })
                if (playlist.conflict != null) AppMenuItem(text = { Text("Resolve playlist conflict") }, onClick = { close { resolving = true } })
            }
            if (saved != null) {
                AppMenuItem(text = { Text("Overwrite saved queue") }, leadingIcon = { Icon(AppIcon.SAVE.vector, null) },
                    enabled = controller.state.value.queue.entries.isNotEmpty(), onClick = { close { overwriting = true } })
                AppMenuItem(text = { Text("Remove saved queue") }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) }, onClick = { close { controller.removeSavedQueue(saved.id) } })
            }
            AppMenuItem(text = { Text("Open") }, leadingIcon = { Icon(AppIcon.FORWARD.vector, null) }, onClick = { close(open) })
        }
    }
    if (picker) PlaylistPicker(controller, rememberTracks().first) { picker = false }
    if (editing && playlist != null) EditPlaylistDialog(playlist, state, controller) { editing = false }
    if (rules && playlist != null) SmartPlaylistDialog(state, controller, playlist) { rules = false }
    if (deleting && playlist != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete ${playlist.name}?") },
        text = { Text("Your songs remain in the library.${if (playlist.server != null) " The playlist will also be deleted from your music server." else ""}") },
        confirmButton = { TextButton(onClick = { controller.deletePlaylist(playlist.id); deleting = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
    if (overwriting && saved != null) AlertDialog(onDismissRequest = { overwriting = false }, title = { Text("Overwrite ${saved.name}?") },
        text = { Text("Replace this saved queue with the current songs and playback position, and select it for automatic saving.") },
        confirmButton = { TextButton(onClick = { controller.overwriteSavedQueue(saved.id); overwriting = false }) { Text("Overwrite") } },
        dismissButton = { TextButton(onClick = { overwriting = false }) { Text("Cancel") } })
    if (publishing && playlist != null) AlertDialog(onDismissRequest = { publishing = false }, title = { Text("Publish to your music server?") },
        text = { Text("Publish ${playlist.name} to your connected server.") },
        confirmButton = { TextButton(onClick = { controller.publishPlaylist(playlist.id); publishing = false }) { Text("Publish") } },
        dismissButton = { TextButton(onClick = { publishing = false }) { Text("Cancel") } })
    if (resolving && playlist != null) AlertDialog(onDismissRequest = { resolving = false }, title = { Text("Playlist changed on the server") },
        text = { Text("Choose which version of ${playlist.name} to keep.") },
        confirmButton = { TextButton(onClick = { controller.resolvePlaylistConflict(playlist.id, true); resolving = false }) { Text("Keep this device") } },
        dismissButton = { TextButton(onClick = { controller.resolvePlaylistConflict(playlist.id, false); resolving = false }) { Text("Use server") } })
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("Playlist export") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("Done") } }) }
}
