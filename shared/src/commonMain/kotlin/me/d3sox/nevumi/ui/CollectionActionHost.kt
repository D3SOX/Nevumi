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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.delete
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.download
import me.d3sox.nevumi.resources.edit_rules
import me.d3sox.nevumi.resources.export_m3u
import me.d3sox.nevumi.resources.library_add_to_favorites
import me.d3sox.nevumi.resources.library_choose_version_named
import me.d3sox.nevumi.resources.library_delete_named
import me.d3sox.nevumi.resources.library_delete_playlist
import me.d3sox.nevumi.resources.library_edit_playlist
import me.d3sox.nevumi.resources.library_keep_this_device
import me.d3sox.nevumi.resources.library_open
import me.d3sox.nevumi.resources.library_overwrite
import me.d3sox.nevumi.resources.library_overwrite_description
import me.d3sox.nevumi.resources.library_overwrite_named
import me.d3sox.nevumi.resources.library_overwrite_saved_queue
import me.d3sox.nevumi.resources.library_playlist_changed_on_server
import me.d3sox.nevumi.resources.library_playlist_export
import me.d3sox.nevumi.resources.library_playlist_export_failed
import me.d3sox.nevumi.resources.library_publish
import me.d3sox.nevumi.resources.library_publish_named_to_server
import me.d3sox.nevumi.resources.library_publish_to_server
import me.d3sox.nevumi.resources.library_publish_to_server_question
import me.d3sox.nevumi.resources.library_remove_favorites
import me.d3sox.nevumi.resources.library_remove_saved_queue
import me.d3sox.nevumi.resources.library_resolve_conflict
import me.d3sox.nevumi.resources.library_select_queue
import me.d3sox.nevumi.resources.library_songs_remain
import me.d3sox.nevumi.resources.library_songs_remain_server
import me.d3sox.nevumi.resources.library_use_server
import me.d3sox.nevumi.resources.manage_playlists
import me.d3sox.nevumi.resources.play
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

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
            AppMenuItem(text = { Text(stringResource(if (saved != null) Res.string.library_select_queue else Res.string.play)) }, leadingIcon = { Icon(AppIcon.PLAY.vector, null) },
                enabled = trackIds.isNotEmpty(), onClick = { close {
                    if (saved != null) controller.loadSavedQueue(saved.id)
                    else controller.playTrack(trackIds.first(), trackIds, 0, entry.title,
                        shuffleContext = playlist?.shuffle == true || tab == LibraryTab.HOME, origin = QueueOrigin(tab, entry.id))
                } })
            QueueActionMenuItems(trackIds, controller) { menu = false }
            AppMenuItem(text = { Text(stringResource(Res.string.manage_playlists)) }, leadingIcon = { Icon(AppIcon.PLAYLIST_PLUS.vector, null) },
                enabled = trackIds.isNotEmpty(), onClick = { close { picker = true } })
            if (tracks.isNotEmpty()) {
                val favorite = tracks.all { it.favorite }
                AppMenuItem(text = { Text(stringResource(if (favorite) Res.string.library_remove_favorites else Res.string.library_add_to_favorites)) },
                    leadingIcon = { Icon((if (favorite) AppIcon.FAVORITE_FILLED else AppIcon.FAVORITE).vector, null) },
                    onClick = { close { controller.setFavorite(trackIds, !favorite) } })
            }
            if (tracks.any { it.sources.any { source -> source.kind == SourceKind.SERVER } })
                AppMenuItem(text = { Text(stringResource(Res.string.download)) }, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) }, onClick = { close { controller.downloadTracks(trackIds) } })
            if (playlist != null) {
                HorizontalDivider()
                if (playlist.server?.readOnly != true) {
                    AppMenuItem(text = { Text(stringResource(Res.string.library_edit_playlist)) }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) }, onClick = { close { editing = true } })
                    if (playlist.rule != null) AppMenuItem(text = { Text(stringResource(Res.string.edit_rules)) }, onClick = { close { rules = true } })
                    AppMenuItem(text = { Text(stringResource(Res.string.library_delete_playlist)) }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) }, onClick = { close { deleting = true } })
                }
                AppMenuItem(text = { Text(stringResource(Res.string.export_m3u)) }, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) }, enabled = !exporting, onClick = { close {
                    scope.launch {
                        exporting = true
                        try {
                            val filename = playlist.name.map { if (it in "/\\" || it.code < 32) '_' else it }.joinToString("").take(120) + ".m3u8"
                            requireNotNull(files).save(filename, controller.exportM3u(playlist.id))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: getString(Res.string.library_playlist_export_failed) }
                        finally { exporting = false }
                    }
                } })
                val server = state.server
                if (playlist.server == null && playlist.rule == null && server != null && controller.serverPlaylistPublishingAvailable &&
                    tracks.all { track -> track.sources.any { it.kind == SourceKind.SERVER && it.id.startsWith("server:${server.serverId}:") } })
                    AppMenuItem(text = { Text(stringResource(Res.string.library_publish_to_server)) }, onClick = { close { publishing = true } })
                if (playlist.conflict != null) AppMenuItem(text = { Text(stringResource(Res.string.library_resolve_conflict)) }, onClick = { close { resolving = true } })
            }
            if (saved != null) {
                AppMenuItem(text = { Text(stringResource(Res.string.library_overwrite_saved_queue)) }, leadingIcon = { Icon(AppIcon.SAVE.vector, null) },
                    enabled = controller.state.value.queue.entries.isNotEmpty(), onClick = { close { overwriting = true } })
                AppMenuItem(text = { Text(stringResource(Res.string.library_remove_saved_queue)) }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) }, onClick = { close { controller.removeSavedQueue(saved.id) } })
            }
            AppMenuItem(text = { Text(stringResource(Res.string.library_open)) }, leadingIcon = { Icon(AppIcon.FORWARD.vector, null) }, onClick = { close(open) })
        }
    }
    if (picker) PlaylistPicker(controller, rememberTracks().first) { picker = false }
    if (editing && playlist != null) EditPlaylistDialog(playlist, state, controller) { editing = false }
    if (rules && playlist != null) SmartPlaylistDialog(state, controller, playlist) { rules = false }
    if (deleting && playlist != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(Res.string.library_delete_named, playlist.name)) },
        text = { Text(stringResource(if (playlist.server != null) Res.string.library_songs_remain_server else Res.string.library_songs_remain)) },
        confirmButton = { TextButton(onClick = { controller.deletePlaylist(playlist.id); deleting = false }) { Text(stringResource(Res.string.delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(Res.string.cancel)) } })
    if (overwriting && saved != null) AlertDialog(onDismissRequest = { overwriting = false }, title = { Text(stringResource(Res.string.library_overwrite_named, saved.name)) },
        text = { Text(stringResource(Res.string.library_overwrite_description)) },
        confirmButton = { TextButton(onClick = { controller.overwriteSavedQueue(saved.id); overwriting = false }) { Text(stringResource(Res.string.library_overwrite)) } },
        dismissButton = { TextButton(onClick = { overwriting = false }) { Text(stringResource(Res.string.cancel)) } })
    if (publishing && playlist != null) AlertDialog(onDismissRequest = { publishing = false }, title = { Text(stringResource(Res.string.library_publish_to_server_question)) },
        text = { Text(stringResource(Res.string.library_publish_named_to_server, playlist.name)) },
        confirmButton = { TextButton(onClick = { controller.publishPlaylist(playlist.id); publishing = false }) { Text(stringResource(Res.string.library_publish)) } },
        dismissButton = { TextButton(onClick = { publishing = false }) { Text(stringResource(Res.string.cancel)) } })
    if (resolving && playlist != null) AlertDialog(onDismissRequest = { resolving = false }, title = { Text(stringResource(Res.string.library_playlist_changed_on_server)) },
        text = { Text(stringResource(Res.string.library_choose_version_named, playlist.name)) },
        confirmButton = { TextButton(onClick = { controller.resolvePlaylistConflict(playlist.id, true); resolving = false }) { Text(stringResource(Res.string.library_keep_this_device)) } },
        dismissButton = { TextButton(onClick = { controller.resolvePlaylistConflict(playlist.id, false); resolving = false }) { Text(stringResource(Res.string.library_use_server)) } })
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text(stringResource(Res.string.library_playlist_export)) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(Res.string.done)) } }) }
}
