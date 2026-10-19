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
            primary.filter { it != hiddenAction && available(it) }.forEach { action -> option(action.label(track.favorite, availableOffline), action.icon(track.favorite, track.rating,
                state.playlists.any { track.id in it.trackIds }, availableOffline),
                if (action == TrackAction.RATE && track.rating > 0) "${track.rating} / 5" else null) { perform(action) } }
            if (queueEntryId != null) {
                option("Remove from queue", AppIcon.REMOVE) { controller.removeQueueEntry(queueEntryId) }
                val entry = state.queue.entries.firstOrNull { it.id == queueEntryId }
                if (entry != null && state.queue.entries.indexOf(entry) > state.queue.currentIndex) {
                    val manual = entry.section == me.d3sox.nevumi.queue.QueueSection.MANUAL
                    option(if (manual) "Move to context" else "Move to Up next", if (manual) AppIcon.LIBRARY else AppIcon.QUEUE) {
                        controller.moveQueueEntryToSection(queueEntryId, if (manual) me.d3sox.nevumi.queue.QueueSection.CONTEXT else me.d3sox.nevumi.queue.QueueSection.MANUAL)
                    }
                }
            }
            val playlistIndex = playlistPosition ?: contextIndex
            if (playlistId != null && playlistIndex != null) {
                option("Remove from playlist", AppIcon.REMOVE) { controller.removePlaylistTrack(playlistId, playlistIndex) }
            }
        }
        val fileOptions = buildList {
            if (controller.localFileEditingAvailable && track.sources.any { it.kind == SourceKind.LOCAL && it.available }) {
                option("Edit tags", AppIcon.EDIT) { fileTarget = FileEditTarget.LOCAL; tagEditor = true }
                option("Rename file", AppIcon.EDIT) { fileTarget = FileEditTarget.LOCAL; renaming = true }
                option("Delete local file", AppIcon.REMOVE) { fileTarget = FileEditTarget.LOCAL; deleting = true }
                option("Replace artwork", AppIcon.ALBUM) {
                    scope.launch {
                        try { artworkPicker.pick()?.let { controller.replaceArtwork(track.id, it).join() } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { artworkError = error.message ?: "The selected image is unavailable" }
                    }
                }
            }
            if (controller.canEditServerTrack(track)) {
                option("Edit server tags", AppIcon.EDIT) { fileTarget = FileEditTarget.SERVER; tagEditor = true }
                option("Rename server file", AppIcon.EDIT) { fileTarget = FileEditTarget.SERVER; renaming = true }
                option("Delete server file", AppIcon.REMOVE) { fileTarget = FileEditTarget.SERVER; deleting = true }
                option("Replace server artwork", AppIcon.ALBUM) {
                    scope.launch {
                        try { artworkPicker.pick()?.let { controller.replaceArtwork(track.id, it, FileEditTarget.SERVER).join() } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { artworkError = error.message ?: "The selected image is unavailable" }
                    }
                }
            }
            if (state.sftp != null && state.sftp?.serverId == state.server?.serverId && track.sources.any { it.kind == SourceKind.LOCAL && it.available })
                option("Upload to server", AppIcon.CLOUD) { uploading = true }
            lastEdit?.let { edit -> option("Undo file edit", AppIcon.UNDO) { controller.undoFileEdit(edit.id) } }
        }
        TrackActionDialog(track, state.artwork[track.id], options, { menu = false },
            artworkOrigin = openingArtwork,
            onArtworkPlaced = { artworkPlaced = true },
            artworkCornerRadius = artworkCornerRadius,
            fileOptions = fileOptions.takeIf { it.isNotEmpty() })
    }
    if (sources) SongSources(track, controller) { sources = false }
    if (rating) SongRating(track, controller) { rating = false }
    if (playlistPicker) PlaylistPicker(state, controller, track.id) { playlistPicker = false }
    if (tagEditor) TagEditor(track, controller, fileTarget) { tagEditor = false }
    if (renaming) FileRename(track, controller, fileTarget) { renaming = false }
    if (deleting) FileDelete(track, controller, fileTarget) { deleting = false }
    if (uploading) ServerUploadDialog(track, controller) { uploading = false }
    artworkError?.let { error -> AlertDialog(onDismissRequest = { artworkError = null }, title = { Text("Artwork not changed") },
        text = { Text(error) }, confirmButton = { TextButton(onClick = { artworkError = null }) { Text("Done") } }) }
}
