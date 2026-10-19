package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.library.filterTracks
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.recentAt
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.PlaylistImport
import me.d3sox.nevumi.playlists.folder
import me.d3sox.nevumi.playlists.folderPath
import me.d3sox.nevumi.playlists.folderSegments
import me.d3sox.nevumi.playlists.isInFolder
import me.d3sox.nevumi.playlists.parentFolder
import me.d3sox.nevumi.playlists.title
import me.d3sox.nevumi.settings.CollectionLayout
import me.d3sox.nevumi.settings.PlaylistPickerSort
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun PlaylistsScreen(state: AppState, controller: MusicController, backEnabled: Boolean = true, destination: LibraryDestination? = null,
    rearranging: Boolean = false, finishRearranging: () -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var rearrangingSongs by rememberSaveable(selected) { mutableStateOf(false) }
    var handledDestination by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(destination) {
        if (destination?.tab == me.d3sox.nevumi.library.LibraryTab.PLAYLISTS && destination.revision != handledDestination) {
            val id = destination.collectionId
            if (id?.startsWith(FOLDER_PREFIX) == true) { folder = id.removePrefix(FOLDER_PREFIX); selected = null }
            else { selected = id; folder = null }
            handledDestination = destination.revision
        }
    }
    var creating by remember { mutableStateOf(false) }
    var smartEditor by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    var fileBusy by remember { mutableStateOf(false) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var imported by remember { mutableStateOf<PlaylistImport?>(null) }
    val files = LocalPlaylistFiles.current
    val scope = rememberCoroutineScope()
    val playlist = state.playlists.firstOrNull { it.id == selected }
    val sourceFilter = state.preferences.sourceFilter
    val visiblePlaylists = remember(state.playlists, state.tracks, sourceFilter) {
        if (sourceFilter == null) state.playlists else {
            val matchingIds = state.tracks.filter { track -> track.sources.any { it.kind == sourceFilter } }.map { it.id }.toSet()
            state.playlists.filter { collection -> collection.trackIds.any { it in matchingIds } }
        }
    }
    val recentAt = remember(state.tracks) { state.tracks.associate { it.id to it.recentAt } }
    // An open playlist belongs to its current folder, which also covers playlists opened from the player.
    // A folder disappears once its last playlist moves out, so fall back to the closest folder that still exists.
    val openFolder = if (playlist != null) playlist.folder else generateSequence(folder, ::parentFolder).firstOrNull { name -> visiblePlaylists.any { isInFolder(it.folder, name) } }
    fun collections(page: String?) = visiblePlaylists.filter { it.folder == page }.map { collection ->
        CollectionItem(collection.id, collection.title,
            "${countLabel(collection.trackIds.size, "song")}${if (collection.rule != null) " · Smart playlist" else ""}${if (collection.autoDownload) " · Automatic downloads" else ""}",
            collection.artworkPath(state), AppIcon.QUEUE,
            songCount = collection.trackIds.size, recentAt = collection.trackIds.maxOfOrNull { recentAt[it] ?: 0 } ?: 0,
            trackIds = collection.trackIds)
    } + visiblePlaylists.mapNotNull { playlist ->
        // Group everything below this page by the subfolder it sits in.
        val path = playlist.folder?.takeIf { it != page && (page == null || isInFolder(it, page)) } ?: return@mapNotNull null
        folderPath(folderSegments(path).take((page?.let(::folderSegments)?.size ?: 0) + 1)) to playlist
    }.groupBy({ it.first }, { it.second }).map { (name, contents) ->
        val trackIds = contents.flatMap(Playlist::trackIds).distinct()
        CollectionItem(FOLDER_PREFIX + name, folderSegments(name).last(), countLabel(contents.size, "playlist"), null, AppIcon.FOLDER,
            songCount = trackIds.size, recentAt = trackIds.maxOfOrNull { recentAt[it] ?: 0 } ?: 0, trackIds = trackIds)
    }
    fun closePlaylist() { folder = openFolder; selected = null }
    fun open(id: String) { if (id.startsWith(FOLDER_PREFIX)) folder = id.removePrefix(FOLDER_PREFIX) else selected = id }
    if (rearranging && backEnabled && selected == null) PlatformBackHandler(finishRearranging)
    val current = selected ?: openFolder?.let { FOLDER_PREFIX + it }
    val parent = (if (selected != null) openFolder else openFolder?.let(::parentFolder))?.let { FOLDER_PREFIX + it }
    AnimatedNavigation(current, parent, canGoBack = backEnabled && current != null,
        onBack = { if (selected != null) closePlaylist() else folder = openFolder?.let(::parentFolder) }, depth = { page -> when {
            page == null -> 0
            page.startsWith(FOLDER_PREFIX) -> folderSegments(page.removePrefix(FOLDER_PREFIX)).size
            else -> 1 + (state.playlists.firstOrNull { it.id == page }?.folder?.let(::folderSegments)?.size ?: 0)
        } }) { page ->
    val playlist = state.playlists.firstOrNull { it.id == page }
    val pageFolder = page?.takeIf { playlist == null && it.startsWith(FOLDER_PREFIX) }?.removePrefix(FOLDER_PREFIX)
    Column(Modifier.fillMaxSize()) {
        if (fileBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        fileMessage?.let { Text(it, Modifier.padding(horizontal = 24.dp)) }
        if (pageFolder != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(AppIcon.BACK, "Back") { folder = parentFolder(pageFolder) }
                Text(pageFolder, style = MaterialTheme.typography.titleLarge)
            }
            val entries = remember(visiblePlaylists, state.artwork, recentAt, pageFolder) { collections(pageFolder) }
            CompositionLocalProvider(LocalLibraryScrollKey provides "${LocalLibraryScrollKey.current}:folder:$pageFolder") {
                CollectionBrowser(entries, me.d3sox.nevumi.library.LibraryTab.PLAYLISTS, state, controller, Modifier.weight(1f),
                    total = visiblePlaylists.count { isInFolder(it.folder, pageFolder) }, rearranging = rearranging, finishRearranging = finishRearranging, open = ::open)
            }
        } else if (playlist == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaylistCreationButton("New", "New playlist", AppIcon.PLUS, Modifier.weight(1f)) { creating = true }
                PlaylistCreationButton("Smart", "New smart playlist", AppIcon.RECOMMENDATIONS, Modifier.weight(1f)) { smartEditor = true }
                PlaylistCreationButton("Import", "Import M3U", AppIcon.FOLDER, Modifier.weight(1f), !fileBusy) {
                scope.launch {
                    fileBusy = true; fileMessage = null
                    try { files.open()?.let { imported = controller.importM3u(it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { fileMessage = error.message ?: "The playlist could not be imported" }
                    finally { fileBusy = false }
                }
                }
            }
            val entries = remember(visiblePlaylists, state.artwork, recentAt) { collections(null) }
            CollectionBrowser(entries, me.d3sox.nevumi.library.LibraryTab.PLAYLISTS, state, controller, Modifier.weight(1f), total = visiblePlaylists.size,
                rearranging = rearranging, finishRearranging = finishRearranging, open = ::open)
        } else {
            val catalog = remember(state.tracks) { state.tracks.associateBy { it.id } }
            val ordered = remember(playlist, catalog) { playlist.orderedTracks(catalog) }
            val server = state.server
            val publishable = playlist.server == null && playlist.rule == null && server != null && controller.serverPlaylistPublishingAvailable &&
                playlist.trackIds.all { id -> catalog[id]?.sources?.any {
                    it.kind == SourceKind.SERVER && it.id.startsWith("server:${server.serverId}:")
                } == true }
            val editable = playlist.rule == null && playlist.server?.readOnly != true
            val header: @Composable () -> Unit = {
                    PlaylistHero(playlist, state, controller, ordered.map { it.value.id }, back = ::closePlaylist, edit = { renaming = true }, delete = { deleting = true }, export = {
                        scope.launch {
                            fileBusy = true; fileMessage = null
                            try {
                                val filename = playlist.name.map { if (it in "/\\" || it.code < 32) '_' else it }.joinToString("").take(120) + ".m3u8"
                                if (files.save(filename, controller.exportM3u(playlist.id))) fileMessage = "Playlist exported"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { fileMessage = error.message ?: "The playlist could not be exported" }
                            finally { fileBusy = false }
                        }
                    }, editRules = { smartEditor = true }, publish = if (publishable) ({ publishing = true }) else null,
                        resolve = { resolving = true }, busy = fileBusy,
                        rearrange = if (editable && playlist.trackIds.size > 1) ({
                            controller.setPlaylistSort(playlist.id, me.d3sox.nevumi.playlists.PlaylistSort.CUSTOM, false)
                            rearrangingSongs = true
                        }) else null, finishRearranging = if (rearrangingSongs) ({ rearrangingSongs = false }) else null)
            }
            CompositionLocalProvider(LocalLibraryOrigin provides me.d3sox.nevumi.queue.QueueOrigin(me.d3sox.nevumi.library.LibraryTab.PLAYLISTS, playlist.id)) {
            if (rearrangingSongs && editable) PlaylistRearrangeList(playlist, state, controller, Modifier.weight(1f), header)
            else SongList(ordered.map { it.value }, controller, Modifier.weight(1f), playlist.id.takeIf { editable },
                scrollIndicator = { "${it + 1} / ${playlist.trackIds.size}" }, contextName = playlist.name,
                playlistPositions = ordered.map { it.index }, shuffleContext = playlist.shuffle, header = header)
            }
        }
    }
    }
    imported?.let { result ->
        AlertDialog(onDismissRequest = { imported = null }, title = { Text("Playlist imported") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${countLabel(result.playlist.trackIds.size, "song")} added to ${result.playlist.name}")
                if (result.unresolved.isNotEmpty()) {
                    Text("Unresolved entries")
                    Text("These entries were not found in your library.")
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(result.unresolved) { Text(it) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { imported = null }) { Text("Done") } })
    }
    if (creating) CreatePlaylistDialog(controller) { creating = false }
    if (publishing && playlist != null) AlertDialog(onDismissRequest = { publishing = false },
        title = { Text("Publish ${playlist.name}?") },
        text = { Text("Create this playlist on your music server and sync future changes to it.") },
        confirmButton = { TextButton(onClick = { controller.publishPlaylist(playlist.id); publishing = false }) { Text("Publish") } },
        dismissButton = { TextButton(onClick = { publishing = false }) { Text("Cancel") } })
    if (smartEditor) SmartPlaylistDialog(state, controller, playlist) { smartEditor = false }
    if (deleting && playlist != null) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text("Delete ${playlist.name}?") }, text = { Text(if (playlist.server != null)
            "This playlist will also be deleted from your music server when connected. The music files will stay in your library."
            else "The music files will stay in your library.") },
        confirmButton = { TextButton(onClick = {
            controller.deletePlaylist(playlist.id); deleting = false; closePlaylist()
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
    val conflict = playlist?.conflict
    if (resolving && playlist != null && conflict != null) {
        AlertDialog(onDismissRequest = { resolving = false }, title = { Text("Playlist changed on the server") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (conflict.deletionRequested) "You requested deletion of this playlist."
                    else "Your version: ${playlist.name} · ${countLabel(playlist.trackIds.size, "song")}")
                Text("Server version: ${conflict.name} · ${countLabel(conflict.trackIds.size, "song")}")
                Text("Choose which version to keep.")
            }
        }, confirmButton = { TextButton(onClick = {
            controller.resolvePlaylistConflict(playlist.id, true); resolving = false
        }) { Text(if (conflict.deletionRequested) "Delete server playlist" else "Keep my version") } }, dismissButton = { TextButton(onClick = {
            controller.resolvePlaylistConflict(playlist.id, false); resolving = false
        }) { Text("Use server version") } })
    }
    if (renaming && playlist != null) EditPlaylistDialog(playlist, state, controller) { renaming = false }
}

private const val FOLDER_PREFIX = "playlist-folder:"

/** A playlist picker row: a folder header (keyed by its path) or a playlist (keyed by its id). */
private class PickerRow(val key: String, val label: String, val depth: Int, val playlist: Playlist?)

/** A playlist or folder in the picker; a folder takes its most recent times from the playlists inside it. */
private class PickerNode(val item: CollectionItem, val updatedAt: Long, val createdAt: Long, val playedAt: Long,
    val playlist: Playlist?, val children: List<PickerNode> = emptyList())

internal fun PlaylistPickerSort.label() = when (this) {
    PlaylistPickerSort.UPDATED -> "Recently updated"; PlaylistPickerSort.ADDED -> "Recently added"
    PlaylistPickerSort.NAME -> "Alphabetical"; PlaylistPickerSort.PLAYED -> "Recently played"
    PlaylistPickerSort.LIBRARY -> "Same as Playlists tab"
}
internal fun PlaylistPickerSort.icon() = when (this) {
    PlaylistPickerSort.UPDATED -> AppIcon.EDIT; PlaylistPickerSort.ADDED -> AppIcon.RECENT; PlaylistPickerSort.NAME -> AppIcon.TITLE
    PlaylistPickerSort.PLAYED -> AppIcon.HISTORY; PlaylistPickerSort.LIBRARY -> AppIcon.QUEUE
}

/** Orders each folder's playlists and subfolders together, then lists each subfolder's contents below its header. */
private fun pickerRows(playlists: List<Playlist>, sort: PlaylistPickerSort, layout: CollectionLayout, recentAt: Map<String, Long>): List<PickerRow> {
    fun segments(playlist: Playlist) = playlist.folder?.let(::folderSegments).orEmpty()
    fun item(id: String, title: String, trackIds: List<String>) = CollectionItem(id, title, "", null, AppIcon.QUEUE,
        songCount = trackIds.size, recentAt = trackIds.maxOfOrNull { recentAt[it] ?: 0 } ?: 0)
    // Break case-only ties so "Road" and "road" stay in a stable order.
    val comparator = when (sort) {
        PlaylistPickerSort.UPDATED -> compareByDescending<PickerNode> { it.updatedAt }
        PlaylistPickerSort.ADDED -> compareByDescending { it.createdAt }
        PlaylistPickerSort.PLAYED -> compareByDescending { it.playedAt }
        PlaylistPickerSort.NAME -> compareBy { it.item.title.lowercase() }
        PlaylistPickerSort.LIBRARY -> compareBy(layout.comparator()) { it.item }
    }.thenBy { it.item.title.lowercase() }.thenBy { it.item.title }
    fun nodes(folder: List<String>, contents: List<Playlist>): List<PickerNode> {
        val (own, nested) = contents.partition { segments(it).size == folder.size }
        return (own.map { PickerNode(item(it.id, it.title, it.trackIds), it.updatedAt, it.createdAt, it.playedAt, it) } +
            nested.groupBy { segments(it)[folder.size] }.map { (name, inside) ->
                val children = nodes(folder + name, inside)
                PickerNode(item(FOLDER_PREFIX + folderPath(folder + name), name, inside.flatMap(Playlist::trackIds).distinct()),
                    children.maxOf { it.updatedAt }, children.maxOf { it.createdAt }, children.maxOf { it.playedAt }, null, children)
            }).sortedWith(comparator)
    }
    fun rows(nodes: List<PickerNode>, depth: Int): List<PickerRow> = nodes.flatMap { node ->
        listOf(PickerRow(node.item.id, node.item.title, depth, node.playlist)) + rows(node.children, depth + 1)
    }
    return rows(nodes(emptyList(), playlists), 0)
}

@Composable
internal fun PlaylistPicker(state: AppState, controller: MusicController, trackId: String, dismiss: () -> Unit) {
    PlaylistPicker(state, controller, listOf(trackId), dismiss)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlaylistPicker(state: AppState, controller: MusicController, trackIds: List<String>, dismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var choices by remember(trackIds) { mutableStateOf(emptyMap<String, Boolean>()) }
    var query by remember { mutableStateOf("") }
    val sort = state.preferences.playlistPickerSort
    val layout = state.preferences.collectionLayout(me.d3sox.nevumi.library.LibraryTab.PLAYLISTS)
    val recentAt = remember(state.tracks) { state.tracks.associate { it.id to it.recentAt } }
    val rows = remember(state.playlists, query, sort, layout, recentAt) {
        pickerRows(state.playlists.filter { it.name.contains(query.trim(), ignoreCase = true) }, sort, layout, recentAt)
    }
    val selected = remember(trackIds) { trackIds.distinct() }
    val catalog = remember(state.tracks) { state.tracks.associateBy { it.id } }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .testTag("playlist-membership-sheet"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Manage playlists", style = MaterialTheme.typography.titleLarge)
            if (state.playlists.size > 1) SettingChoice("Sort by", sort, PlaylistPickerSort.entries.map { it to it.label() }, card = false) { choice ->
                controller.updatePreferences { it.copy(playlistPickerSort = choice) }
            }
            if (state.playlists.isNotEmpty()) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                label = { Text("Search playlists") }, singleLine = true,
                trailingIcon = { if (query.isNotEmpty()) AppIconButton(AppIcon.CLOSE, "Clear search") { query = "" } })
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(rows, key = { it.key }) { row ->
                    val indent = Modifier.padding(start = 16.dp * row.depth)
                    val playlist = row.playlist ?: return@items Row(indent.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(AppIcon.FOLDER.vector, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.label, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val contained = selected.count { it in playlist.trackIds }
                    val initial = when (contained) { 0 -> ToggleableState.Off; selected.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                    val checked = choices[playlist.id]?.let { if (it) ToggleableState.On else ToggleableState.Off } ?: initial
                    val eligible = playlist.server == null || selected.all { id -> catalog[id]?.sources?.any {
                        it.kind == SourceKind.SERVER && it.id.startsWith("server:${playlist.server.serverId}:")
                    } == true }
                    val editable = playlist.rule == null && playlist.server?.readOnly != true && eligible
                    Row(indent.fillMaxWidth().triStateToggleable(checked, enabled = editable, role = Role.Checkbox,
                        onClick = {
                            val next = checked != ToggleableState.On
                            // Toggling back to the original state is no change, so Apply stays disabled.
                            choices = if (initial == if (next) ToggleableState.On else ToggleableState.Off) choices - playlist.id
                                else choices + (playlist.id to next)
                        })
                        .semantics { contentDescription = "Playlist membership: ${playlist.name}" }
                        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CollectionArtwork(playlist.artworkPath(state), playlist.name, AppIcon.QUEUE,
                            Modifier.size(48.dp).semantics { contentDescription = "Playlist artwork: ${playlist.name}" })
                        Column(Modifier.weight(1f)) {
                            Text(playlist.title, style = MaterialTheme.typography.titleSmall)
                            Text(when {
                                playlist.rule != null -> "Smart playlist"
                                playlist.server?.readOnly == true -> "Read-only"
                                !eligible -> "Only songs from this server"
                                contained in 1 until selected.size -> "$contained of ${selected.size} selected songs"
                                else -> countLabel(playlist.trackIds.size, "song")
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TriStateCheckbox(checked, onClick = null, enabled = editable)
                    }
                }
                if (state.playlists.isEmpty()) item { Text("Create a playlist to keep these songs together.") }
                else if (rows.isEmpty()) item { Text("No playlists match \"${query.trim()}\".") }
            }
            OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcon.PLUS.vector, null); Spacer(Modifier.width(8.dp)); Text("New playlist")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f)) {
                    Icon(AppIcon.CLOSE.vector, null); Spacer(Modifier.width(8.dp)); Text("Cancel")
                }
                Button(enabled = choices.isNotEmpty(), modifier = Modifier.weight(1f), onClick = {
                    controller.managePlaylistMembership(selected, choices); dismiss()
                }) { Icon(AppIcon.CHECK.vector, null); Spacer(Modifier.width(8.dp)); Text("Apply") }
            }
        }
    }
    if (creating) CreatePlaylistDialog(controller, selected, onCreated = dismiss) { creating = false }
}

@Composable
internal fun SmartPlaylistDialog(state: AppState, controller: MusicController, playlist: Playlist?, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(playlist?.name.orEmpty()) }
    var rule by remember { mutableStateOf<LibraryQuery?>(playlist?.rule ?: LibraryQuery()) }
    val count = remember(state.tracks, rule) { rule?.let { filterTracks(state.tracks, it).size } }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (playlist == null) "New smart playlist" else "Edit rules") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (playlist == null) OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Songs update automatically as your library changes.")
                LibraryQueryFields(playlist?.rule ?: LibraryQuery()) { rule = it }
                if (count != null) Text(countLabel(count, "matching song"))
            }
        },
        confirmButton = { TextButton(enabled = rule != null && (playlist != null || name.isNotBlank()), onClick = {
            if (playlist == null) controller.createSmartPlaylist(name, requireNotNull(rule)) else controller.updatePlaylistRule(playlist.id, requireNotNull(rule))
            dismiss()
        }) { Text(if (playlist == null) "Create" else "Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun CreatePlaylistDialog(controller: MusicController, trackIds: List<String> = emptyList(), onCreated: () -> Unit = {}, dismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("New playlist") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { controller.createPlaylist(name, trackIds); onCreated(); dismiss() }) { Text("Create") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun PlaylistCreationButton(label: String, description: String, icon: AppIcon,
    modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(onClick, modifier.heightIn(min = 64.dp).semantics { contentDescription = description },
        enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon.vector, null, Modifier.size(20.dp))
            Text(label, maxLines = 1)
        }
    }
}
