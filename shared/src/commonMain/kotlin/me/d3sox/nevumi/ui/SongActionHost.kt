package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.*
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.TrackAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.edit_server_tags
import me.d3sox.nevumi.resources.edit_tags
import me.d3sox.nevumi.resources.player_artwork_not_changed
import me.d3sox.nevumi.resources.player_delete_local_file
import me.d3sox.nevumi.resources.player_delete_server_file
import me.d3sox.nevumi.resources.player_image_unavailable
import me.d3sox.nevumi.resources.player_move_to_context
import me.d3sox.nevumi.resources.player_move_to_up_next
import me.d3sox.nevumi.resources.player_rating_out_of_five
import me.d3sox.nevumi.resources.player_remove_from_playlist
import me.d3sox.nevumi.resources.player_remove_from_queue
import me.d3sox.nevumi.resources.player_rename_file
import me.d3sox.nevumi.resources.player_rename_server_file
import me.d3sox.nevumi.resources.player_replace_artwork
import me.d3sox.nevumi.resources.player_replace_server_artwork
import me.d3sox.nevumi.resources.player_undo_file_edit
import me.d3sox.nevumi.resources.player_upload_to_server
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SongActionHost(track: Track, state: AppState, controller: MusicController,
    contextTrackIds: List<String>? = null, contextIndex: Int? = null, playlistId: String? = null,
    onSelect: (() -> Unit)? = null, queueEntryId: String? = null, contextName: String = "Library",
    playlistPosition: Int? = null, shuffleContext: Boolean = false,
    artworkOrigin: () -> androidx.compose.ui.geometry.Rect? = { null },
    artworkCornerRadius: androidx.compose.ui.unit.Dp = 16.dp,
    hiddenAction: TrackAction = TrackAction.NONE,
    rightClickMenu: Boolean = true,
    content: @Composable ((TrackAction) -> Boolean, (TrackAction) -> Boolean, () -> Unit, Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var artworkPlaced by remember { mutableStateOf(false) }
    val currentTrack by rememberUpdatedState(track)
    var openingArtwork by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var playlistPicker by remember { mutableStateOf(false) }
    var tagEditor by remember { mutableStateOf(false) }
    var fileTarget by remember { mutableStateOf(FileEditTarget.LOCAL) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    val navigate = LocalLibraryNavigate.current
    val origin = LocalLibraryOrigin.current
    var rating by remember { mutableStateOf(false) }
    val lastEdit = state.fileEdits.lastOrNull { it.trackId == track.id && it.applied && !it.undone }
    var artworkError by remember { mutableStateOf<String?>(null) }
    val artworkPicker = LocalArtworkPicker.current
    val scope = rememberCoroutineScope()
    fun enqueue(action: QueueAction): Boolean { controller.enqueue(track.id, action); return true }
    fun available(action: TrackAction): Boolean = when (action) {
        TrackAction.NONE, TrackAction.PLAY -> false
        TrackAction.DOWNLOAD -> track.sources.any { it.kind == SourceKind.SERVER || it.kind == SourceKind.LOCAL && it.available }
        TrackAction.GO_ARTIST -> track.metadata.artist.isNotBlank()
        TrackAction.GO_ALBUM -> track.metadata.album.isNotBlank()
        TrackAction.GO_ALBUM_ARTIST -> track.metadata.albumArtist.isNotBlank()
        TrackAction.GO_GENRE -> track.metadata.genres.isNotEmpty()
        TrackAction.GO_FOLDER -> track.metadata.folder.isNotBlank()
        TrackAction.SELECT -> onSelect != null
        else -> true
    }
    fun perform(action: TrackAction): Boolean {
        if (!available(action)) return false
        when (action) {
            TrackAction.NONE -> Unit
            TrackAction.PLAY_NEXT -> enqueue(QueueAction.PLAY_NEXT)
            TrackAction.PLAY_AFTER -> enqueue(QueueAction.PLAY_AFTER)
            TrackAction.APPEND -> enqueue(QueueAction.APPEND)
            TrackAction.PLAY -> controller.playTrack(track.id, contextTrackIds, contextIndex, contextName,
                shuffleContext = shuffleContext, origin = origin, preserveManualQueue = true)
            TrackAction.FAVORITE -> controller.setFavorite(currentTrack.id, !currentTrack.favorite)
            TrackAction.RATE -> rating = true
            TrackAction.ADD_TO_PLAYLIST -> playlistPicker = true
            TrackAction.DOWNLOAD -> controller.downloadTracks(listOf(track.id))
            TrackAction.GO_ARTIST -> navigate(LibraryTab.ARTISTS, track.metadata.artist)
            TrackAction.GO_ALBUM -> navigate(LibraryTab.ALBUMS, albumCollectionId(track.metadata))
            TrackAction.GO_ALBUM_ARTIST -> navigate(LibraryTab.ALBUM_ARTISTS, track.metadata.albumArtist)
            TrackAction.GO_GENRE -> navigate(LibraryTab.GENRES, track.metadata.genres.first())
            TrackAction.GO_FOLDER -> navigate(LibraryTab.FOLDERS, track.metadata.folder)
            TrackAction.SELECT -> onSelect?.invoke()
            TrackAction.SHOW_SOURCES -> sources = true
        }
        return true
    }
    val openMenu = { openingArtwork = artworkOrigin(); artworkPlaced = false; menu = true }
    val latestOpenMenu by rememberUpdatedState(openMenu)
    val contextMenu = if (!rightClickMenu) Modifier else Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && event.changes.none { it.isConsumed }) {
                    event.changes.forEach { it.consume() }
                    latestOpenMenu()
                }
            }
        }
    }
    Box(contextMenu) {
        content(::available, ::perform, openMenu, menu && artworkPlaced)
    }
    if (menu) {
        val imageUnavailable = stringResource(Res.string.player_image_unavailable)
        fun MutableList<TrackOption>.option(label: String, icon: AppIcon, detail: String? = null, click: () -> Unit) {
            add(TrackOption(label, icon, detail) { menu = false; click() })
        }
        val options = buildList {
            val primary = listOf(TrackAction.PLAY_NEXT, TrackAction.PLAY_AFTER, TrackAction.APPEND,
                TrackAction.FAVORITE, TrackAction.RATE, TrackAction.ADD_TO_PLAYLIST,
                TrackAction.GO_ARTIST, TrackAction.GO_ALBUM, TrackAction.GO_ALBUM_ARTIST,
                TrackAction.GO_GENRE, TrackAction.GO_FOLDER, TrackAction.SHOW_SOURCES,
                TrackAction.SELECT, TrackAction.DOWNLOAD)
            val availableOffline = track.sources.any { it.kind == SourceKind.LOCAL && it.available }
            primary.filter { it != hiddenAction && available(it) }.forEach { action -> option(action.displayLabel(track.favorite, availableOffline), action.icon(track.favorite, track.rating,
                track.id in state.playlistTrackIds, availableOffline),
                if (action == TrackAction.RATE && track.rating > 0) stringResource(Res.string.player_rating_out_of_five, track.rating) else null) { perform(action) } }
            if (queueEntryId != null) {
                option(stringResource(Res.string.player_remove_from_queue), AppIcon.REMOVE) { controller.removeQueueEntry(queueEntryId) }
                val entry = state.queue.entries.firstOrNull { it.id == queueEntryId }
                if (entry != null && state.queue.entries.indexOf(entry) > state.queue.currentIndex) {
                    val manual = entry.section == me.d3sox.nevumi.queue.QueueSection.MANUAL
                    option(stringResource(if (manual) Res.string.player_move_to_context else Res.string.player_move_to_up_next), if (manual) AppIcon.LIBRARY else AppIcon.QUEUE) {
                        controller.moveQueueEntryToSection(queueEntryId, if (manual) me.d3sox.nevumi.queue.QueueSection.CONTEXT else me.d3sox.nevumi.queue.QueueSection.MANUAL)
                    }
                }
            }
            playSimilarOption(track, state, controller) { menu = false }
            val playlistIndex = playlistPosition ?: contextIndex
            if (playlistId != null && playlistIndex != null) {
                option(stringResource(Res.string.player_remove_from_playlist), AppIcon.REMOVE) { controller.removePlaylistTrack(playlistId, playlistIndex) }
            }
        }
        val fileOptions = buildList {
            if (controller.localFileEditingAvailable && track.sources.any { it.kind == SourceKind.LOCAL && it.available }) {
                option(stringResource(Res.string.edit_tags), AppIcon.EDIT) { fileTarget = FileEditTarget.LOCAL; tagEditor = true }
                option(stringResource(Res.string.player_rename_file), AppIcon.EDIT) { fileTarget = FileEditTarget.LOCAL; renaming = true }
                option(stringResource(Res.string.player_delete_local_file), AppIcon.REMOVE) { fileTarget = FileEditTarget.LOCAL; deleting = true }
                option(stringResource(Res.string.player_replace_artwork), AppIcon.ALBUM) {
                    scope.launch {
                        try { artworkPicker.pick()?.let { controller.replaceArtwork(track.id, it).join() } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { artworkError = error.message ?: imageUnavailable }
                    }
                }
            }
            if (controller.canEditServerTrack(track)) {
                option(stringResource(Res.string.edit_server_tags), AppIcon.EDIT) { fileTarget = FileEditTarget.SERVER; tagEditor = true }
                option(stringResource(Res.string.player_rename_server_file), AppIcon.EDIT) { fileTarget = FileEditTarget.SERVER; renaming = true }
                option(stringResource(Res.string.player_delete_server_file), AppIcon.REMOVE) { fileTarget = FileEditTarget.SERVER; deleting = true }
                option(stringResource(Res.string.player_replace_server_artwork), AppIcon.ALBUM) {
                    scope.launch {
                        try { artworkPicker.pick()?.let { controller.replaceArtwork(track.id, it, FileEditTarget.SERVER).join() } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { artworkError = error.message ?: imageUnavailable }
                    }
                }
            }
            if (state.sftp != null && state.sftp?.serverId == state.server?.serverId && track.sources.any { it.kind == SourceKind.LOCAL && it.available })
                option(stringResource(Res.string.player_upload_to_server), AppIcon.CLOUD) { uploading = true }
            lastEdit?.let { edit -> option(stringResource(Res.string.player_undo_file_edit), AppIcon.UNDO) { controller.undoFileEdit(edit.id) } }
        }
        TrackActionDialog(track, state.artwork[track.id], options, { menu = false },
            artworkOrigin = openingArtwork,
            onArtworkPlaced = { artworkPlaced = true },
            artworkCornerRadius = artworkCornerRadius,
            fileOptions = fileOptions.takeIf { it.isNotEmpty() },
            onFileActions = { controller.prepareServerFileEdit(track) })
    }
    if (sources) SongSources(track, controller) { sources = false }
    if (rating) SongRating(track, controller) { rating = false }
    if (playlistPicker) PlaylistPicker(controller, track.id) { playlistPicker = false }
    if (tagEditor) TagEditor(track, controller, fileTarget) { tagEditor = false }
    if (renaming) FileRename(track, controller, fileTarget) { renaming = false }
    if (deleting) FileDelete(track, controller, fileTarget) { deleting = false }
    if (uploading) ServerUploadDialog(track, controller) { uploading = false }
    artworkError?.let { error -> AlertDialog(onDismissRequest = { artworkError = null }, title = { Text(stringResource(Res.string.player_artwork_not_changed)) },
        text = { Text(error) }, confirmButton = { TextButton(onClick = { artworkError = null }) { Text(stringResource(Res.string.done)) } }) }
}
