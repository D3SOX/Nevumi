package me.d3sox.nevumi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import me.d3sox.nevumi.playlists.PlaylistSort
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.recentAt
import kotlin.random.Random
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.materialkolor.ktx.themeColor
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.PLAYLIST_FOLDER_SEPARATOR
import me.d3sox.nevumi.playlists.folder
import me.d3sox.nevumi.playlists.folderPath
import me.d3sox.nevumi.playlists.folderSegments
import me.d3sox.nevumi.playlists.playlistName
import me.d3sox.nevumi.playlists.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.album
import me.d3sox.nevumi.resources.artist
import me.d3sox.nevumi.resources.ascending
import me.d3sox.nevumi.resources.back
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.descending
import me.d3sox.nevumi.resources.disabled
import me.d3sox.nevumi.resources.edit_rules
import me.d3sox.nevumi.resources.enabled
import me.d3sox.nevumi.resources.export_m3u
import me.d3sox.nevumi.resources.library_artwork_open_failed
import me.d3sox.nevumi.resources.library_auto_download_playlist
import me.d3sox.nevumi.resources.library_change_cover
import me.d3sox.nevumi.resources.library_changes_waiting_to_sync
import me.d3sox.nevumi.resources.library_create
import me.d3sox.nevumi.resources.library_custom_order
import me.d3sox.nevumi.resources.library_delete_playlist
import me.d3sox.nevumi.resources.library_description
import me.d3sox.nevumi.resources.library_edit_playlist
import me.d3sox.nevumi.resources.library_finish_rearranging
import me.d3sox.nevumi.resources.library_folder_name
import me.d3sox.nevumi.resources.library_inside_folder
import me.d3sox.nevumi.resources.library_new_folder
import me.d3sox.nevumi.resources.library_no_folder
import me.d3sox.nevumi.resources.library_play_playlist
import me.d3sox.nevumi.resources.library_playlist
import me.d3sox.nevumi.resources.library_playlist_artwork
import me.d3sox.nevumi.resources.library_playlist_cover_preview
import me.d3sox.nevumi.resources.library_playlist_description
import me.d3sox.nevumi.resources.library_playlist_folder
import me.d3sox.nevumi.resources.library_playlist_in_folder
import me.d3sox.nevumi.resources.library_playlist_options
import me.d3sox.nevumi.resources.library_playlist_song_order
import me.d3sox.nevumi.resources.library_playlist_sort_direction
import me.d3sox.nevumi.resources.library_playlist_title
import me.d3sox.nevumi.resources.library_publish_to_server
import me.d3sox.nevumi.resources.library_rearrange
import me.d3sox.nevumi.resources.library_resolve_conflict
import me.d3sox.nevumi.resources.library_server_playlist
import me.d3sox.nevumi.resources.library_shuffle_playlist
import me.d3sox.nevumi.resources.library_smart_playlist
import me.d3sox.nevumi.resources.library_smart_playlist_in_folder
import me.d3sox.nevumi.resources.library_use_generated_cover
import me.d3sox.nevumi.resources.library_use_song_artwork
import me.d3sox.nevumi.resources.recently_added
import me.d3sox.nevumi.resources.save
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

internal fun Playlist.artworkPath(state: AppState, custom: Boolean = true): String? = state.artwork["playlist:$id"].takeIf { custom }
    ?: state.artwork["server-playlist-cover:$id"]
    ?: trackIds.firstNotNullOfOrNull { state.artwork[it] }

@Composable
internal fun PlaylistHero(playlist: Playlist, state: AppState, controller: MusicController,
    orderedIds: List<String>, back: () -> Unit, edit: () -> Unit, delete: () -> Unit, export: () -> Unit, editRules: () -> Unit,
    publish: (() -> Unit)?, resolve: () -> Unit, busy: Boolean, rearrange: (() -> Unit)? = null,
    finishRearranging: (() -> Unit)? = null) {
    val path = playlist.artworkPath(state)
    val origin = LocalLibraryOrigin.current
    val bitmap = rememberArtwork(path, 128)
    val fallback = MaterialTheme.colorScheme.primary
    val color by produceState(fallback, bitmap) {
        value = if (bitmap == null) fallback else withContext(Dispatchers.Default) { bitmap.themeColor(fallback) }
    }
    val surface = MaterialTheme.colorScheme.surface
    var options by remember(playlist.id) { mutableStateOf(false) }
    var sortMenu by remember(playlist.id) { mutableStateOf(false) }
    val artworkDescription = stringResource(Res.string.library_playlist_artwork, playlist.name)
    val autoDownloadDescription = stringResource(Res.string.library_auto_download_playlist)
    val autoDownloadState = stringResource(if (playlist.autoDownload) Res.string.enabled else Res.string.disabled)
    val direction = stringResource(if (playlist.descending) Res.string.descending else Res.string.ascending)
    val sortState = "${playlist.sort.label()} · $direction"
    val sortDirectionDescription = stringResource(Res.string.library_playlist_sort_direction)
    val shuffleDescription = stringResource(Res.string.library_shuffle_playlist)
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color.copy(alpha = .4f), surface)))
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            CollectionArtwork(path, playlist.name, AppIcon.QUEUE,
                Modifier.size(200.dp).align(Alignment.Center).semantics { contentDescription = artworkDescription })
            AppIconButton(AppIcon.BACK, stringResource(Res.string.back), Modifier.align(Alignment.TopStart), onClick = back)
        }
        Text(playlist.title, style = MaterialTheme.typography.headlineMedium)
        val kind = playlist.folder?.let { stringResource(if (playlist.rule == null) Res.string.library_playlist_in_folder else Res.string.library_smart_playlist_in_folder, it) }
            ?: stringResource(if (playlist.rule == null) Res.string.library_playlist else Res.string.library_smart_playlist)
        Text("$kind · ${countLabel(playlist.trackIds.size, Res.plurals.songs)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (playlist.description.isNotBlank()) Text(playlist.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                if (finishRearranging != null) AppIconButton(AppIcon.CHECK, stringResource(Res.string.library_finish_rearranging), onClick = finishRearranging)
                else AppIconButton(AppIcon.MORE, stringResource(Res.string.library_playlist_options)) { options = true }
                DropdownMenu(options, { options = false }) {
                    fun close(action: () -> Unit) { options = false; action() }
                    QueueActionMenuItems(orderedIds, controller) { options = false }
                    HorizontalDivider()
                    if (playlist.server?.readOnly != true) {
                        AppMenuItem({ Text(stringResource(Res.string.library_edit_playlist)) }, { close(edit) }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) })
                        AppMenuItem({ Text(stringResource(Res.string.library_delete_playlist)) }, { close(delete) }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) })
                    }
                    AppMenuItem({ Text(stringResource(Res.string.export_m3u)) }, { close(export) }, enabled = !busy, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) })
                    if (rearrange != null) AppMenuItem({ Text(stringResource(Res.string.library_rearrange)) }, { close(rearrange) }, leadingIcon = { Icon(AppIcon.DRAG.vector, null) })
                    if (playlist.rule != null) AppMenuItem({ Text(stringResource(Res.string.edit_rules)) }, { close(editRules) }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) })
                    if (publish != null) AppMenuItem({ Text(stringResource(Res.string.library_publish_to_server)) }, { close(publish) }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) })
                    if (playlist.conflict != null) AppMenuItem({ Text(stringResource(Res.string.library_resolve_conflict)) }, { close(resolve) }, leadingIcon = { Icon(AppIcon.UNDO.vector, null) })
                }
            }
            IconToggleButton(playlist.autoDownload, { controller.setPlaylistDownload(playlist.id, it) },
                modifier = Modifier.semantics {
                    contentDescription = autoDownloadDescription
                    stateDescription = autoDownloadState
                }, colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary)) {
                Icon(AppIcon.DOWNLOAD.vector, null)
            }
            if (finishRearranging == null) Box {
                BadgedBox(badge = {
                    Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(playlist.sort.icon().vector, null, Modifier.size(14.dp).padding(1.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }) {
                    AppIconButton(AppIcon.SORT, stringResource(Res.string.library_playlist_song_order), Modifier.semantics {
                        stateDescription = sortState
                    }) { sortMenu = true }
                }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    PlaylistSort.entries.forEach { sort ->
                        AppMenuItem({ Text(sort.label()) }, {
                            sortMenu = false; controller.setPlaylistSort(playlist.id, sort, sort == PlaylistSort.RECENT)
                        }, leadingIcon = { Icon(sort.icon().vector, null) },
                            trailingIcon = if (playlist.sort == sort) ({ Icon(AppIcon.CHECK.vector, null) }) else null)
                    }
                    HorizontalDivider()
                    AppMenuItem({ Text(direction) }, {
                        sortMenu = false; controller.setPlaylistSort(playlist.id, playlist.sort, !playlist.descending)
                    }, leadingIcon = { Icon((if (playlist.descending) AppIcon.DESCENDING else AppIcon.ASCENDING).vector, null) },
                        modifier = Modifier.semantics { contentDescription = sortDirectionDescription })
                }
            }
            Spacer(Modifier.weight(1f))
            IconToggleButton(playlist.shuffle, { controller.setPlaylistShuffle(playlist.id, it) },
                modifier = Modifier.semantics { contentDescription = shuffleDescription; selected = playlist.shuffle },
                colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Icon(AppIcon.SHUFFLE.vector, null) }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                val index = if (playlist.shuffle) Random.nextInt(orderedIds.size) else 0
                controller.playTrack(orderedIds[index], orderedIds, index, playlist.name, shuffleContext = playlist.shuffle, origin = origin)
            }, enabled = orderedIds.isNotEmpty(), modifier = Modifier.size(56.dp)) {
                Icon(AppIcon.PLAY.vector, stringResource(Res.string.library_play_playlist), Modifier.size(32.dp))
            }
        }
        if (playlist.server != null) Text(stringResource(if (playlist.pendingSync) Res.string.library_changes_waiting_to_sync else Res.string.library_server_playlist),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EditPlaylistDialog(playlist: Playlist, state: AppState, controller: MusicController, dismiss: () -> Unit) {
    var name by remember(playlist.id) { mutableStateOf(playlist.title) }
    var folder by remember(playlist.id) { mutableStateOf(playlist.folder) }
    var description by remember(playlist.id) { mutableStateOf(playlist.description) }
    var cover by remember { mutableStateOf<ByteArray?>(null) }
    var removeCover by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val picker = LocalArtworkPicker.current
    val scope = rememberCoroutineScope()
    var pickerError by remember { mutableStateOf<String?>(null) }
    val titleDescription = stringResource(Res.string.library_playlist_title)
    val descriptionDescription = stringResource(Res.string.library_playlist_description)
    val preview by produceState<ImageBitmap?>(null, cover) { value = cover?.let { withContext(Dispatchers.Default) { decodeArtwork(it, 256) } } }
    KeyboardAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.library_edit_playlist)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(120.dp).align(Alignment.CenterHorizontally), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                if (preview != null) Image(requireNotNull(preview), stringResource(Res.string.library_playlist_cover_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else CollectionArtwork(if (removeCover && playlist.server != null) null else playlist.artworkPath(state, custom = !removeCover), playlist.name, AppIcon.QUEUE, Modifier.fillMaxSize())
            }
            OutlinedButton(onClick = {
                scope.launch {
                    picking = true
                    pickerError = null
                    try { picker.pick()?.let { cover = it; removeCover = false } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { pickerError = error.message ?: getString(Res.string.library_artwork_open_failed) }
                    finally { picking = false }
                }
            }, enabled = !picking, modifier = Modifier.fillMaxWidth()) { Icon(AppIcon.ARTWORK_COLORS.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.library_change_cover)) }
            if (state.artwork["playlist:${playlist.id}"] != null || cover != null || (playlist.server != null && !removeCover))
                OutlinedButton(onClick = { cover = null; removeCover = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcon.REMOVE.vector, null); Spacer(Modifier.width(8.dp))
                Text(stringResource(if (playlist.server != null) Res.string.library_use_generated_cover else Res.string.library_use_song_artwork))
            }
            pickerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(name, { name = it.filter { c -> c != PLAYLIST_FOLDER_SEPARATOR } }, label = { Text(stringResource(Res.string.title)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = titleDescription })
            PlaylistFolderPicker(state, folder) { folder = it }
            OutlinedTextField(description, { description = it }, label = { Text(stringResource(Res.string.library_description)) }, minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = descriptionDescription })
        }
    }, confirmButton = { Button(onClick = { controller.editPlaylist(playlist.id, playlistName(folder?.let(::folderSegments).orEmpty(), name), description, cover, removeCover); dismiss() },
        enabled = name.isNotBlank() && !picking) { Icon(AppIcon.CHECK.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(Res.string.cancel)) } })
}

@Composable
private fun PlaylistFolderPicker(state: AppState, folder: String?, choose: (String?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    // Offer every existing folder and its parents, plus a new folder that has no playlists yet.
    val folders = remember(state.playlists, folder) {
        (state.playlists.mapNotNull { it.folder } + listOfNotNull(folder)).flatMap { path ->
            folderSegments(path).let { segments -> segments.indices.map { folderPath(segments.take(it + 1)) } }
        }.distinct().sortedBy { it.lowercase() }
    }
    val folderDescription = stringResource(Res.string.library_playlist_folder)
    val noFolder = stringResource(Res.string.library_no_folder)
    Box {
        OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = folderDescription; stateDescription = folder ?: noFolder
        }) {
            Icon(AppIcon.FOLDER.vector, null); Spacer(Modifier.width(8.dp))
            Text(folder ?: noFolder, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(menu, { menu = false }) {
            (listOf(null) + folders).forEach { option ->
                AppMenuItem({ Text(option ?: noFolder) }, { choose(option); menu = false },
                    leadingIcon = { Icon((if (option == null) AppIcon.CLOSE else AppIcon.FOLDER).vector, null) },
                    trailingIcon = if (option == folder) ({ Icon(AppIcon.CHECK.vector, null) }) else null)
            }
            HorizontalDivider()
            AppMenuItem({ Text(stringResource(Res.string.library_new_folder)) }, { menu = false; creating = true }, leadingIcon = { Icon(AppIcon.PLUS.vector, null) })
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        KeyboardAlertDialog(onDismissRequest = { creating = false }, title = { Text(stringResource(Res.string.library_new_folder)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (folder != null) Text(stringResource(Res.string.library_inside_folder, folder))
                OutlinedTextField(name, { name = it.filter { c -> c != PLAYLIST_FOLDER_SEPARATOR } }, label = { Text(stringResource(Res.string.library_folder_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            choose(folderPath(folder?.let(::folderSegments).orEmpty() + name.trim())); creating = false
        }) { Text(stringResource(Res.string.library_create)) } }, dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(Res.string.cancel)) } })
    }
}

@Composable
internal fun PlaylistSort.label() = stringResource(when (this) {
    PlaylistSort.CUSTOM -> Res.string.library_custom_order; PlaylistSort.TITLE -> Res.string.title; PlaylistSort.ARTIST -> Res.string.artist
    PlaylistSort.ALBUM -> Res.string.album; PlaylistSort.RECENT -> Res.string.recently_added
})
internal fun PlaylistSort.icon() = when (this) {
    PlaylistSort.CUSTOM -> AppIcon.DRAG; PlaylistSort.TITLE -> AppIcon.TITLE; PlaylistSort.ARTIST -> AppIcon.ARTIST
    PlaylistSort.ALBUM -> AppIcon.ALBUM; PlaylistSort.RECENT -> AppIcon.RECENT
}
internal fun Playlist.orderedTracks(catalog: Map<String, Track>): List<IndexedValue<Track>> {
    val original = trackIds.mapIndexedNotNull { index, id -> catalog[id]?.let { IndexedValue(index, it) } }
    val comparator = when (sort) {
        PlaylistSort.CUSTOM -> compareBy<IndexedValue<Track>> { it.index }
        PlaylistSort.TITLE -> compareBy { it.value.metadata.title.lowercase() }
        PlaylistSort.ARTIST -> compareBy<IndexedValue<Track>> { it.value.metadata.artist.lowercase() }.thenBy { it.value.metadata.title.lowercase() }
        PlaylistSort.ALBUM -> compareBy<IndexedValue<Track>> { it.value.metadata.album.lowercase() }.thenBy { it.value.metadata.discNumber }.thenBy { it.value.metadata.trackNumber }
        PlaylistSort.RECENT -> compareBy { it.value.recentAt }
    }
    return original.sortedWith(if (descending) comparator.reversed() else comparator)
}
