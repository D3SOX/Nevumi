package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.*
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.TrackAction
import me.d3sox.nevumi.youtube.YouTubeCollectionKind
import me.d3sox.nevumi.youtube.YouTubeDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.keep_as_one_artist
import me.d3sox.nevumi.resources.keep_as_one_artist_description
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.shares_share_link
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
import me.d3sox.nevumi.resources.player_song_menu_artwork
import me.d3sox.nevumi.resources.player_undo_file_edit
import me.d3sox.nevumi.resources.player_upload_to_server
import me.d3sox.nevumi.resources.sources_go_to_youtube_music_album
import me.d3sox.nevumi.resources.sources_go_to_youtube_music_artist
import me.d3sox.nevumi.resources.sources_open_on_youtube
import me.d3sox.nevumi.resources.sources_save_to_this_device
import me.d3sox.nevumi.resources.sources_youtube_save_to_server
import org.jetbrains.compose.resources.stringResource

/** Runs [action] on a right click that nothing inside has handled, such as to open a song's menu. */
@Composable
internal fun Modifier.onSecondaryClick(action: () -> Unit): Modifier {
    val latest by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && event.changes.none { it.isConsumed }) {
                    event.changes.forEach { it.consume() }
                    latest()
                }
            }
        }
    }
}

@Composable
internal fun SongActionHost(track: Track, state: AppState, controller: MusicController,
    contextTrackIds: List<String>? = null, contextIndex: Int? = null, playlistId: String? = null,
    onSelect: (() -> Unit)? = null, queueEntryId: String? = null, contextName: String = "Library",
    /** Where a song in the player or queue came from, which outside the library the page cannot tell. */
    queueOrigin: me.d3sox.nevumi.queue.QueueOrigin? = null,
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
    var choosingArtist by remember { mutableStateOf(false) }
    val navigate = LocalLibraryNavigate.current
    val origin = LocalLibraryOrigin.current
    var rating by remember { mutableStateOf(false) }
    val lastEdit = state.fileEdits.lastOrNull { it.trackId == track.id && it.applied && !it.undone }
    var artworkError by remember { mutableStateOf<String?>(null) }
    val artworkPicker = LocalArtworkPicker.current
    val scope = rememberCoroutineScope()
    val browser = androidx.compose.ui.platform.LocalUriHandler.current
    val openYouTubePage = LocalOpenYouTubeLinkedPage.current
    val youTubeVideoId = track.youTubeVideoId?.takeIf { controller.youTubeAvailable && state.preferences.youTube }
    // A song streamed from YouTube has a library artist page only when the library has songs by the same artist.
    val splitting = rememberArtistSplitting(state.preferences)
    val artists = remember(track.metadata, track.inLibrary, splitting, if (track.inLibrary) null else state.tracks) {
        splitting.names(track.metadata).filter { track.inLibrary || hasLibraryArtist(it, state.tracks, splitting) }
    }
    fun enqueue(action: QueueAction): Boolean { controller.enqueue(track.id, action, queueOrigin ?: origin); return true }
    // A song that only streams from YouTube is saved like a YouTube result, and the library views leave it out.
    val youTubeOnly = youTubeVideoId != null && !track.inLibrary
    fun inLibrary(matches: (Track) -> Boolean) = track.inLibrary || state.tracks.any { it.inLibrary && matches(it) }
    fun available(action: TrackAction): Boolean = when (action) {
        TrackAction.NONE, TrackAction.PLAY -> false
        TrackAction.DOWNLOAD -> if (youTubeOnly) controller.youTubeDeviceDownloads
            else track.sources.any { it.kind == SourceKind.SERVER || it.kind == SourceKind.LOCAL && it.available }
        TrackAction.GO_ARTIST -> artists.isNotEmpty()
        TrackAction.GO_ALBUM -> track.metadata.album.isNotBlank() && inLibrary { albumCollectionId(it.metadata) == albumCollectionId(track.metadata) }
        TrackAction.GO_ALBUM_ARTIST -> track.metadata.albumArtist.isNotBlank() &&
            inLibrary { it.metadata.albumArtist.ifBlank { it.metadata.artist } == track.metadata.albumArtist }
        TrackAction.GO_GENRE -> track.metadata.genres.isNotEmpty() && inLibrary { track.metadata.genres.first() in it.metadata.genres }
        TrackAction.GO_FOLDER -> track.metadata.folder.isNotBlank()
        TrackAction.SELECT -> onSelect != null
        // Stations and episodes stay outside the library, so they are neither rated nor kept in playlists.
        TrackAction.FAVORITE, TrackAction.RATE, TrackAction.ADD_TO_PLAYLIST -> !track.isRadio && !track.isPodcastEpisode
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
            TrackAction.DOWNLOAD -> if (youTubeOnly) controller.downloadFromYouTube(track.id, YouTubeDestination.DEVICE, state.preferences.youTubeReviewTags)
                else controller.downloadTracks(listOf(track.id))
            TrackAction.GO_ARTIST -> if (artists.size == 1) navigate(LibraryTab.ARTISTS, artists.single()) else choosingArtist = true
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
    Box(if (rightClickMenu) Modifier.onSecondaryClick(openMenu) else Modifier) {
        content(::available, ::perform, openMenu, menu && artworkPlaced)
    }
    if (menu) {
        val imageUnavailable = stringResource(Res.string.player_image_unavailable)
        val shareLink = rememberShareLink(controller, shareIds(controller, listOf(track)), track.metadata.title)
        val shareLabel = stringResource(Res.string.shares_share_link)
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
            primary.forEach { action ->
                // A YouTube song names where it saves to instead.
                if (action != hiddenAction && available(action) && !(youTubeOnly && action == TrackAction.DOWNLOAD)) option(action.displayLabel(track.favorite, availableOffline), action.icon(track.favorite, track.rating,
                    track.id in state.playlistTrackIds, availableOffline),
                    if (action == TrackAction.RATE && track.rating > 0) stringResource(Res.string.player_rating_out_of_five, track.rating) else null) { perform(action) }
                // The artist's and album's YouTube Music pages sit beside their library pages.
                if (action == TrackAction.GO_ARTIST && youTubeVideoId != null) option(stringResource(Res.string.sources_go_to_youtube_music_artist),
                    AppIcon.YOUTUBE) { openYouTubePage(youTubeVideoId, YouTubeCollectionKind.ARTIST) }
                if (action == TrackAction.GO_ALBUM && youTubeVideoId != null) option(stringResource(Res.string.sources_go_to_youtube_music_album),
                    AppIcon.ALBUM) { openYouTubePage(youTubeVideoId, YouTubeCollectionKind.ALBUM) }
            }
            youTubeVideoId?.let { videoId ->
                // A song streamed from YouTube has no file yet, so it is saved like a YouTube search result.
                if (!track.inLibrary && controller.youTubeDeviceDownloads) {
                    val reviewTags = state.preferences.youTubeReviewTags
                    option(stringResource(Res.string.sources_save_to_this_device), AppIcon.DEVICE) {
                        controller.downloadFromYouTube(track.id, YouTubeDestination.DEVICE, reviewTags)
                    }
                    val sftp = state.sftp
                    if (controller.youTubeServerUploads && sftp != null)
                        option(stringResource(Res.string.sources_youtube_save_to_server), AppIcon.SERVER, sftp.libraryName) {
                            controller.downloadFromYouTube(track.id, YouTubeDestination.SERVER, reviewTags)
                        }
                }
                option(stringResource(Res.string.sources_open_on_youtube), AppIcon.OPEN_EXTERNAL) {
                    runCatching { browser.openUri("https://www.youtube.com/watch?v=$videoId") }
                }
            }
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
            shareLink?.let { share -> option(shareLabel, AppIcon.SHARE, click = share) }
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
        val artworkDescription = stringResource(Res.string.player_song_menu_artwork, track.metadata.title)
        TrackActionDialog(track.metadata.title, track.metadata.artist, { maxDimension ->
            MusicArtwork(state, track.id, track.metadata.title, artworkDescription, maxDimension)
        }, options, { menu = false },
            artworkOrigin = openingArtwork,
            onArtworkPlaced = { artworkPlaced = true },
            artworkCornerRadius = artworkCornerRadius,
            fileOptions = fileOptions.takeIf { it.isNotEmpty() },
            onFileActions = { controller.prepareServerFileEdit(track) })
    }
    if (sources) SongSources(track, controller) { sources = false }
    // A refresh can remove every credit while the chooser is open; it then stays closed.
    LaunchedEffect(artists.isEmpty()) { if (artists.isEmpty()) choosingArtist = false }
    if (choosingArtist && artists.isNotEmpty()) {
        // A wrong split, such as "Tyler" and "The Creator", is fixed here by keeping those names together from now on.
        var joining by remember { mutableStateOf(false) }
        val joinable = remember(track.metadata, splitting) { splitting.joinedNames(track.metadata) }
        val close = { choosingArtist = false; joining = false }
        AlertDialog(onDismissRequest = close, title = {
            Text(if (joining) stringResource(Res.string.keep_as_one_artist) else TrackAction.GO_ARTIST.displayLabel())
        }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (joining) Text(stringResource(Res.string.keep_as_one_artist_description), Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (if (joining) joinable else artists).forEach { name ->
                    ListItem(headlineContent = { Text(name) }, leadingContent = { Icon((if (joining) AppIcon.ALBUM_ARTIST else AppIcon.ARTIST).vector, null) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable {
                            if (joining) controller.updatePreferences { it.copy(artistSeparatorExceptions = (it.artistSeparatorExceptions + name).distinct()) }
                            else navigate(LibraryTab.ARTISTS, name)
                            close()
                        })
                }
            }
        }, confirmButton = {
            if (!joining && joinable.isNotEmpty()) TextButton(onClick = { joining = true }) { Text(stringResource(Res.string.keep_as_one_artist)) }
        }, dismissButton = { TextButton(onClick = close) { Text(stringResource(Res.string.cancel)) } })
    }
    if (rating) SongRating(track, controller) { rating = false }
    if (playlistPicker) PlaylistPicker(controller, track.id) { playlistPicker = false }
    if (tagEditor) TagEditor(track, controller, fileTarget) { tagEditor = false }
    if (renaming) FileRename(track, controller, fileTarget) { renaming = false }
    if (deleting) FileDelete(track, controller, fileTarget) { deleting = false }
    if (uploading) ServerUploadDialog(track, controller) { uploading = false }
    artworkError?.let { error -> AlertDialog(onDismissRequest = { artworkError = null }, title = { Text(stringResource(Res.string.player_artwork_not_changed)) },
        text = { Text(error) }, confirmButton = { TextButton(onClick = { artworkError = null }) { Text(stringResource(Res.string.done)) } }) }
}
