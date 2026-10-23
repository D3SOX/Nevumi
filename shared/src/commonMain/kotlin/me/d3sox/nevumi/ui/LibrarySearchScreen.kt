package me.d3sox.nevumi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.QueueOrigin

@Composable
internal fun LibrarySearchScreen(state: AppState, controller: MusicController, bottomSpace: Dp, dismiss: (() -> Unit)?, onSettings: (() -> Unit)? = null) {
    var search by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val query = search.text
    var type by rememberSaveable { mutableStateOf(SearchType.ALL) }
    var openedType by rememberSaveable { mutableStateOf<SearchType?>(null) }
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selection = remember { SongSelection() }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun stopTyping() { keyboard?.hide(); focusManager.clearFocus() }
    val visible = remember(state.tracks, state.preferences.includeFolders, state.preferences.excludeFolders) {
        val folders = LocalFolderRules(state.preferences.includeFolders, state.preferences.excludeFolders)
        state.tracks.filter { track -> track.sources.any { it.available } && (track.sources.any { it.kind != SourceKind.LOCAL } ||
            folders.allows(track.metadata.folder, track.sources.firstOrNull { it.kind == SourceKind.LOCAL }?.filePath?.substringBeforeLast('/'))) }
    }
    // Grouping the library is the expensive part; it happens once per library change, off the main thread for large ones.
    val index = rememberDerived(null, emptyList(), listOf(visible, state.playlists)) { LibrarySearchIndex(visible, state.playlists) }
    val results = remember(index, query) { index.search(query) }
    val songIds = remember(results.songs) { results.songs.map { it.id } }
    val opened = results.collections[openedType].orEmpty().firstOrNull { it.id == openedId }
    val active = LocalNavigationActive.current
    LaunchedEffect(active) { if (!active) selection.clear() }
    if (active && (selection.active || opened != null)) PlatformBackHandler {
        if (selection.active) selection.clear() else openedId = null
    }
    CompositionLocalProvider(LocalSongSelection provides selection, LocalLibraryOrigin provides QueueOrigin(LibraryTab.SONGS),
        LocalLibraryScrollKey provides "global-search:${type.name}") {
        Column(Modifier.fillMaxSize().imePadding().testTag("library-search-page")) {
            if (selection.active) SongSelectionHeader(selection, controller)
            else Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (opened == null && dismiss == null) 16.dp else 0.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (opened != null || dismiss != null) AppIconButton(AppIcon.BACK, "Close search") { stopTyping(); if (opened != null) openedId = null else dismiss?.invoke() }
                TextField(search, { if (it.text != query) openedId = null; search = it }, placeholder = { Text("Search your library") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { stopTyping() }),
                    trailingIcon = { if (query.isNotEmpty()) AppIconButton(AppIcon.CLOSE, "Clear search") { search = TextFieldValue(); openedId = null } },
                    modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Search your library" })
                if (onSettings != null) AppIconButton(AppIcon.SETTINGS, "Settings") { stopTyping(); onSettings() }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
            if (opened == null) LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchType.entries.filter { it != SearchType.SOUNDCLOUD || state.soundCloud != null }, key = { it }) { category ->
                    FilterChip(type == category, { type = category; selection.clear(); stopTyping() }, label = { Text(category.label) },
                        modifier = Modifier.testTag("search-type:${category.name}"))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (opened != null) CompositionLocalProvider(LocalLibraryOrigin provides QueueOrigin(requireNotNull(openedType?.tab), opened.id),
                    LocalLibraryScrollKey provides "search-detail:${openedType?.name}:${opened.id}") {
                    SongList(opened.tracks, controller, playlistId = opened.id.takeIf { openedType == SearchType.PLAYLISTS }, contextName = opened.title,
                        header = { CollectionHero(opened, requireNotNull(openedType?.tab), state, controller) { openedId = null } })
                } else if (query.isBlank()) {
                    if (type == SearchType.SOUNDCLOUD) SearchPrompt(AppIcon.CLOUD, "Search SoundCloud for songs to download", Modifier.align(Alignment.Center))
                    else SearchPrompt(AppIcon.MUSIC, "Enter a search term", Modifier.align(Alignment.Center))
                } else if (type == SearchType.SOUNDCLOUD) SoundCloudSearchResults(query, state, controller)
                else if (type == SearchType.SONGS) SongList(results.songs, controller, contextName = "Search")
                else {
                    val categories = if (type == SearchType.ALL) SearchType.entries.filter { it != SearchType.ALL && it != SearchType.SOUNDCLOUD } else listOf(type)
                    val count = if (type == SearchType.ALL) results.count else results.collections[type].orEmpty().size
                    LazyColumn(Modifier.fillMaxSize().testTag("search-results")) {
                        if (count == 0) item { Text("No results for “${query.trim()}”", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        categories.forEach { category ->
                            val collections = results.collections[category].orEmpty()
                            val total = if (category == SearchType.SONGS) results.songs.size else collections.size
                            if (total > 0) item(key = "heading:${category.name}") {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${category.label} · $total", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                    if (type == SearchType.ALL) TextButton(onClick = { type = category; stopTyping() }) { Text("See all") }
                                }
                            }
                            if (category == SearchType.SONGS) itemsIndexed(results.songs.take(20), key = { _, song -> "song:${song.id}" }) { index, song ->
                                TrackRow(song, controller, songIds, index, selectionMode = selection.active, selected = song.id in selection.selectedIds,
                                    onSelect = { selection.toggle(song.id, songIds) }, contextName = "Search")
                            } else items(if (type == SearchType.ALL) collections.take(10) else collections, key = { "${category.name}:${it.id}" }) { collection ->
                                val tab = requireNotNull(category.tab)
                                val entry = CollectionItem(collection.id, collection.title, collection.subtitle,
                                    collection.tracks.firstNotNullOfOrNull { state.artwork[it.id] }, tab.icon(), collection.tracks.size,
                                    trackIds = collection.tracks.map { it.id })
                                val open = { openedType = category; openedId = collection.id; stopTyping() }
                                CollectionActionHost(entry, tab, state, controller, open) { menu ->
                                ListItem(modifier = Modifier.combinedClickable(onClick = open, onLongClick = menu)
                                    .semantics { contentDescription = "Open ${category.label.lowercase().removeSuffix("s")}: ${collection.title}" },
                                    headlineContent = { Text(collection.title) },
                                    supportingContent = { Text(listOf(collection.subtitle, countLabel(collection.tracks.size, "song")).filter { it.isNotBlank() }.joinToString(" · ")) },
                                    leadingContent = { CollectionArtwork(entry.artwork, collection.title, tab.icon(), Modifier.size(48.dp)) },
                                    trailingContent = { if (state.preferences.showMoreButtons) AppIconButton(AppIcon.MORE, "${category.label.removeSuffix("s")} options: ${collection.title}", onClick = menu) })
                                }
                            }
                        }
                        if (type == SearchType.ALL && state.soundCloud != null) item(key = "search-soundcloud") {
                            SearchSoundCloudRow(query) { type = SearchType.SOUNDCLOUD; stopTyping() }
                        }
                    }
                }
            }
            Spacer(Modifier.height(bottomSpace))
        }
    }
}

@Composable
private fun SearchPrompt(icon: AppIcon, label: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(96.dp)) {
            Box(Modifier.size(80.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(icon.vector, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Box(Modifier.align(Alignment.BottomEnd).size(48.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(AppIcon.SEARCH.vector, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
