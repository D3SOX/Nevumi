package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.LibrarySort
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.filterTracks
import me.d3sox.nevumi.library.recentAt
import me.d3sox.nevumi.library.LocalFolderRules
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.queue.QueueAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import me.d3sox.nevumi.history.listeningStatistics
import me.d3sox.nevumi.history.ListeningStatistics
import me.d3sox.nevumi.resources.nevumi_logo

@Composable
internal fun LibraryScreens(state: AppState, controller: MusicController, modifier: Modifier = Modifier,
    destination: LibraryDestination? = null, backEnabled: Boolean = true, miniPlayerSpace: androidx.compose.ui.unit.Dp = 0.dp,
    onNavigationHeight: (Int) -> Unit = {}, initialTab: LibraryTab? = null, onTabSelected: (LibraryTab) -> Unit = {}, onSettings: () -> Unit = {}, onSearch: () -> Unit = {}) {
    val selection = remember { SongSelection() }
    val songPages = remember { SongGesturePages() }
    val tabs = remember(state.preferences.libraryTabs, destination) {
        (state.preferences.libraryTabs + listOfNotNull(destination?.tab)).distinct()
    }
    var handledDestination by rememberSaveable { mutableIntStateOf(-1) }
    val incoming = destination?.takeIf { it.revision != handledDestination }
    val initial = incoming?.tab ?: initialTab ?: state.preferences.startupTab ?: state.preferences.lastLibraryTab
    val pager = rememberPagerState(initialPage = tabs.indexOf(initial).coerceAtLeast(0)) { tabs.size }
    LaunchedEffect(pager.settledPage, tabs) {
        tabs.getOrNull(pager.settledPage)?.let { tab ->
            onTabSelected(tab)
            controller.rememberLibraryTab(tab)
        }
    }
    var previousTabs by remember { mutableStateOf(tabs) }
    LaunchedEffect(tabs) {
        if (previousTabs != tabs) {
            val selected = previousTabs.getOrNull(pager.currentPage)
            pager.scrollToPage(tabs.indexOf(selected).coerceAtLeast(0))
            previousTabs = tabs
        }
    }
    val currentIndex = pager.targetPage.coerceIn(0, tabs.lastIndex)
    var rearrangingPlaylists by remember(tabs[currentIndex]) { mutableStateOf(false) }
    LaunchedEffect(destination) {
        if (destination != null && destination.revision != handledDestination) {
            selection.clear(); pager.scrollToPage(tabs.indexOf(destination.tab).coerceAtLeast(0))
            handledDestination = destination.revision
        }
    }
    val scope = rememberCoroutineScope()
    var sourceMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    val order = state.preferences.songSort
    val reversed = state.preferences.songOrderReversed
    val descending = reversed != (order in setOf(LibrarySort.RECENT, LibrarySort.RATING, LibrarySort.PLAY_COUNT, LibrarySort.LAST_PLAYED))
    val sources = state.preferences.sourceFilter
    val now = rememberLibraryTime()
    val statistics = remember(state.listens) { listeningStatistics(state.listens) }
    val filter = state.preferences.libraryFilter
    // Only time-window filters depend on the clock; everything else must not re-sort the library every minute.
    val filterTime = if (filter.lastPlayedWithinDays != null || filter.notPlayedWithinDays != null) now else 0L
    val tracks = remember(state.tracks, statistics, order, reversed, sources, filter, state.preferences.includeFolders, state.preferences.excludeFolders, filterTime) {
        val folders = LocalFolderRules(state.preferences.includeFolders, state.preferences.excludeFolders)
        val visible = state.tracks.filter { track ->
            val localAllowed = folders.allows(track.metadata.folder, track.sources.firstOrNull { it.kind == SourceKind.LOCAL }?.filePath?.substringBeforeLast('/'))
            localAllowed || sources != SourceKind.LOCAL && track.sources.any { it.kind != SourceKind.LOCAL }
        }
        filterTracks(visible, state.preferences.libraryFilter.copy(sort = order, descending = reversed, sourceKind = sources), statistics, now)
    }
    if (selection.active && backEnabled) PlatformBackHandler(selection::clear)
    CompositionLocalProvider(LocalSongSelection provides selection, LocalSongGesturePages provides songPages) {
    Column(modifier.fillMaxWidth()) {
        if (selection.active) SongSelectionHeader(selection, controller)
        else Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.Image(org.jetbrains.compose.resources.painterResource(me.d3sox.nevumi.resources.Res.drawable.nevumi_logo),
                    "Nevumi logo", Modifier.size(40.dp))
                Text(if (tabs[currentIndex] == LibraryTab.HOME) "Home" else countLabel(tracks.size, "song"), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIconButton(AppIcon.SEARCH, "Search", onClick = onSearch)
            val currentTab = tabs[currentIndex]
            val activeSongLists = songPages.activeLists[currentTab] ?: 0
            val sortable = currentTab in setOf(LibraryTab.SONGS, LibraryTab.FAVORITES) ||
                activeSongLists > 0 && currentTab in setOf(LibraryTab.ARTISTS, LibraryTab.ALBUM_ARTISTS, LibraryTab.GENRES, LibraryTab.FOLDERS)
            if (sortable) Box {
                SortIconButton(order.icon(), "Song order", "${order.name}, ${if (descending) "Descending" else "Ascending"}") { sortMenu = true }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf(LibrarySort.TITLE to "Title", LibrarySort.ARTIST to "Artist", LibrarySort.ALBUM to "Album",
                        LibrarySort.RECENT to "Recently added", LibrarySort.RATING to "Rating",
                        LibrarySort.PLAY_COUNT to "Most played", LibrarySort.LAST_PLAYED to "Last played").forEach { (value, name) ->
                        AppMenuItem(modifier = Modifier.semantics { selected = value == order }, text = { Text(name) },
                            leadingIcon = { Icon(value.icon().vector, null) },
                            trailingIcon = { if (value == order) Icon(AppIcon.CHECK.vector, null) },
                            onClick = { controller.updatePreferences { it.copy(songSort = value) }; sortMenu = false })
                    }
                    HorizontalDivider()
                    AppMenuItem(modifier = Modifier.semantics { contentDescription = if (descending) "Descending" else "Ascending" }, text = { Text(if (descending) "Descending" else "Ascending") }, leadingIcon = { Icon((if (descending) AppIcon.DESCENDING else AppIcon.ASCENDING).vector, null) },
                        onClick = { controller.updatePreferences { it.copy(songOrderReversed = !reversed) }; sortMenu = false })
                }
            }
            if (activeSongLists == 0 && !rearrangingPlaylists && currentTab in setOf(LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.ALBUM_ARTISTS,
                    LibraryTab.GENRES, LibraryTab.FOLDERS, LibraryTab.PLAYLISTS))
                CollectionOrder(currentTab, state.preferences.collectionLayouts[currentTab] ?: me.d3sox.nevumi.settings.CollectionLayout(), controller,
                    rearrange = if (currentTab == LibraryTab.PLAYLISTS) ({ rearrangingPlaylists = true }) else null)
            AppIconButton(AppIcon.SETTINGS, "Settings", onClick = onSettings)
            Box {
                AppIconButton(AppIcon.MENU, "Music sources") { sourceMenu = true }
                DropdownMenu(sourceMenu, { sourceMenu = false }) {
                    listOf<Pair<SourceKind?, String>>(null to "All sources", SourceKind.LOCAL to "Device",
                        SourceKind.SERVER to "Server", SourceKind.DOWNLOAD to "Downloads").forEach { (value, name) ->
                        AppMenuItem(modifier = Modifier.semantics { selected = value == sources }, text = { Text(name) }, leadingIcon = { Icon(when {
                            value == SourceKind.SERVER -> AppIcon.CLOUD.vector
                            value == SourceKind.DOWNLOAD -> AppIcon.DOWNLOAD.vector
                            value == SourceKind.LOCAL -> AppIcon.DEVICE.vector
                            else -> AppIcon.LIBRARY.vector
                        }, null) }, trailingIcon = { if (value == sources) Icon(AppIcon.CHECK.vector, null) }, onClick = { controller.updatePreferences { it.copy(sourceFilter = value) }; sourceMenu = false })
                    }
                    HorizontalDivider()
                    AppMenuItem(text = { Text("Filters") }, leadingIcon = { Icon(AppIcon.FILTER.vector, null) },
                        onClick = { filters = true; sourceMenu = false })
                }
            }
        }
        HorizontalPager(pager, userScrollEnabled = false, key = { tabs[it] }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("library-pager")) { index ->
            val active = LocalNavigationActive.current && pager.currentPage == index
            CompositionLocalProvider(LocalNavigationActive provides active,
                LocalLibraryOrigin provides me.d3sox.nevumi.queue.QueueOrigin(tabs[index]),
                LocalLibraryFocus provides destination?.takeIf { it.tab == tabs[index] },
                LocalLibraryScrollKey provides "library:${tabs[index].name}") {
            val handleBack = backEnabled && pager.currentPage == index
            Box(Modifier.fillMaxSize().then(if (pager.currentPage == index) Modifier else Modifier.clearAndSetSemantics {})) {
            when (tabs[index]) {
                LibraryTab.HOME -> HomeScreen(state, controller, tracks, destination, handleBack, onSettings)
                LibraryTab.SONGS -> SongList(tracks, controller, statistics = statistics, scrollSort = order)
                LibraryTab.ALBUMS -> {
                    val albums = remember(tracks) { tracks.groupBy { it.metadata.album to it.metadata.albumArtist.ifBlank { it.metadata.artist } }
                        .entries.sortedBy { it.key.first.lowercase() }.map { (key, songs) ->
                            TrackCollection("${key.first.length}:${key.first}${key.second}", key.first.ifBlank { "Unknown album" },
                                songs.sortedWith(compareBy<Track> { it.metadata.discNumber }.thenBy { it.metadata.trackNumber }.thenBy { it.metadata.title }), key.second)
                        } }
                    TrackCollections(albums, LibraryTab.ALBUMS, state, controller, handleBack, destination)
                }
                LibraryTab.ARTISTS -> GroupedSongs(remember(tracks) { tracks.groupBy { it.metadata.artist } }, LibraryTab.ARTISTS, state, controller, handleBack, destination)
                LibraryTab.ALBUM_ARTISTS -> GroupedSongs(remember(tracks) { tracks.groupBy { it.metadata.albumArtist.ifBlank { it.metadata.artist } } }, LibraryTab.ALBUM_ARTISTS, state, controller, handleBack, destination)
                LibraryTab.GENRES -> GroupedSongs(remember(tracks) { tracks.flatMap { track -> track.metadata.genres.ifEmpty { listOf("") }
                    .map { it to track } }.groupBy({ it.first }, { it.second }) }, LibraryTab.GENRES, state, controller, handleBack, destination)
                LibraryTab.FOLDERS -> GroupedSongs(remember(tracks) { tracks.groupBy { it.metadata.folder } }, LibraryTab.FOLDERS, state, controller, handleBack, destination)
                LibraryTab.FAVORITES -> SongList(remember(tracks) { tracks.filter { it.favorite } }, controller, statistics = statistics, scrollSort = order, contextName = "Favorites")
                LibraryTab.RECENT -> SongList(remember(tracks) { tracks.sortedByDescending { it.recentAt } }, controller, scrollSort = LibrarySort.RECENT, contextName = "Recently added")
                LibraryTab.PLAYLISTS -> PlaylistsScreen(state, controller, backEnabled = handleBack, destination = destination,
                    rearranging = rearrangingPlaylists, finishRearranging = { rearrangingPlaylists = false })
                LibraryTab.HISTORY -> ListeningHistoryScreen(state, controller, tracks)
                LibraryTab.STATISTICS -> ListeningStatisticsScreen(state, controller, tracks, handleBack)
                LibraryTab.RECOMMENDATIONS -> ListeningRecommendationsScreen(state, controller, tracks)
            }
            }
        }
        }
        Spacer(Modifier.height(miniPlayerSpace))
        LibraryNavigation(tabs, tabs[currentIndex], Modifier.onSizeChanged { onNavigationHeight(it.height) }) { page -> selection.clear(); scope.launch { pager.animateScrollToPage(tabs.indexOf(page)) } }
    }
    }
    if (filters) LibraryFiltersDialog(state, controller) { filters = false }
}

@Composable
private fun GroupedSongs(groups: Map<String, List<Track>>, tab: LibraryTab, state: AppState, controller: MusicController, backEnabled: Boolean, destination: LibraryDestination?) {
    val collections = remember(groups) { groups.entries.sortedBy { it.key.lowercase() }.map { (key, tracks) ->
        TrackCollection(key, key.ifBlank { "Unknown ${tab.label.lowercase().removeSuffix("s")}" }, tracks)
    } }
    TrackCollections(collections, tab, state, controller, backEnabled, destination)
}

@Composable
internal fun SongList(tracks: List<me.d3sox.nevumi.library.Track>, controller: MusicController,
    modifier: Modifier = Modifier, playlistId: String? = null, statistics: Map<String, ListeningStatistics>? = null,
    extraInfo: Map<String, String> = emptyMap(), entryInfo: ((Int) -> String)? = null, scrollSort: LibrarySort = LibrarySort.TITLE, scrollIndicator: ((Int) -> String)? = null, header: (@Composable () -> Unit)? = null, contextName: String = "Library", playlistPositions: List<Int>? = null,
    shuffleContext: Boolean = false) {
    val songPages = LocalSongGesturePages.current
    val active = LocalNavigationActive.current
    val tab = LocalLibraryOrigin.current.tab
    DisposableEffect(songPages, active, tab) {
        if (active && songPages != null) songPages.activeLists[tab] = (songPages.activeLists[tab] ?: 0) + 1
        onDispose { if (active && songPages != null) {
            val remaining = (songPages.activeLists[tab] ?: 1) - 1
            if (remaining == 0) songPages.activeLists.remove(tab) else songPages.activeLists[tab] = remaining
        } }
    }
    val scroll = rememberLibraryListState(controller, "songs:${playlistId ?: contextName}")
    val ids = remember(tracks) { tracks.map { it.id } }
    val rowKeys = remember(ids) {
        val occurrences = mutableMapOf<String, Int>()
        ids.map { id ->
            val occurrence = occurrences.getOrElse(id) { 0 }
            occurrences[id] = occurrence + 1
            "$occurrence:$id"
        }
    }
    val focus = LocalLibraryFocus.current
    val focusHandled = LocalLibraryFocusHandled.current
    var appliedFocus by rememberSaveable(LocalLibraryScrollKey.current, playlistId, contextName) { mutableIntStateOf(-1) }
    LaunchedEffect(focus, active, ids) {
        val index = ids.indexOf(focus?.trackId)
        if (active && focus != null && index >= 0 && appliedFocus != focus.revision) {
            scroll.scrollToItem(index + if (header == null) 0 else 1)
            appliedFocus = focus.revision
            focusHandled()
        }
    }
    val sharedSelection = LocalSongSelection.current
    val selection = sharedSelection ?: remember { SongSelection() }
    val selectedIds = selection.selectedIds
    fun toggle(id: String) { selection.toggle(id, ids) }
    Column(modifier.fillMaxSize()) {
        if (sharedSelection == null && selection.active) SongSelectionHeader(selection, controller)
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll) {
            if (header != null) item(key = "song-list-header") { header() }
            itemsIndexed(tracks, key = { index, _ -> rowKeys[index] }) { index, track ->
                TrackRow(track, controller, ids, index, playlistId, selection.active, track.id in selectedIds,
                    { toggle(track.id) }, statistics?.get(track.id), entryInfo?.invoke(index) ?: extraInfo[track.id], contextName,
                    playlistPosition = playlistPositions?.getOrNull(index), shuffleContext = shuffleContext)
            }
            if (tracks.isEmpty()) item { Text("No songs here yet", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        LibraryScrollbar(scroll, tracks.size, "Scroll library", if (header == null) 0 else 1) { scrollIndicator?.invoke(it) ?: tracks[it].scrollLabel(scrollSort, statistics) }
        }
    }
}
