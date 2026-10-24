package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
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
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.library.LibrarySort
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.playlists.playlistedTrackIds
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
import me.d3sox.nevumi.history.listeningStatistics
import me.d3sox.nevumi.history.ListeningStatistics
import me.d3sox.nevumi.resources.nevumi_logo
import org.jetbrains.compose.resources.stringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.album
import me.d3sox.nevumi.resources.artist
import me.d3sox.nevumi.resources.ascending
import me.d3sox.nevumi.resources.descending
import me.d3sox.nevumi.resources.library_filters
import me.d3sox.nevumi.resources.library_last_played
import me.d3sox.nevumi.resources.library_nevumi_logo
import me.d3sox.nevumi.resources.library_no_songs_yet
import me.d3sox.nevumi.resources.library_rating
import me.d3sox.nevumi.resources.library_scroll_library
import me.d3sox.nevumi.resources.library_song_order
import me.d3sox.nevumi.resources.library_sort_state
import me.d3sox.nevumi.resources.library_unknown_album
import me.d3sox.nevumi.resources.library_unknown_album_artist
import me.d3sox.nevumi.resources.library_unknown_folder
import me.d3sox.nevumi.resources.library_unknown_genre
import me.d3sox.nevumi.resources.most_played
import me.d3sox.nevumi.resources.music_sources
import me.d3sox.nevumi.resources.recently_added
import me.d3sox.nevumi.resources.search
import me.d3sox.nevumi.resources.settings
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.songs_of_total
import org.jetbrains.compose.resources.pluralStringResource
import me.d3sox.nevumi.resources.title
import me.d3sox.nevumi.resources.unknown_artist

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
    var selectedTab by rememberSaveable { mutableStateOf(initial.takeIf { it in tabs } ?: tabs.first()) }
    val currentIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    val transition = updateTransition(tabs[currentIndex], label = "Library tab")
    val pageStates = rememberSaveableStateHolder()
    LaunchedEffect(transition.currentState, transition.isRunning) {
        if (!transition.isRunning) {
            onTabSelected(transition.currentState)
            controller.rememberLibraryTab(transition.currentState)
        }
    }
    LaunchedEffect(tabs) {
        if (selectedTab !in tabs) selectedTab = tabs.first()
    }
    var searchReturnTab by rememberSaveable { mutableStateOf(tabs.firstOrNull { it != LibraryTab.SEARCH }) }
    fun closeSearch() {
        selectedTab = searchReturnTab?.takeIf { it in tabs && it != LibraryTab.SEARCH }
            ?: tabs.firstOrNull { it != LibraryTab.SEARCH } ?: tabs.first()
    }
    if (tabs[currentIndex] == LibraryTab.SEARCH && backEnabled && tabs.size > 1) PlatformBackHandler { closeSearch() }
    var rearrangingPlaylists by remember(tabs[currentIndex]) { mutableStateOf(false) }
    LaunchedEffect(destination) {
        if (destination != null && destination.revision != handledDestination) {
            selection.clear(); selectedTab = destination.tab
            handledDestination = destination.revision
        }
    }
    // The keyboard covers the navigation, so pages that pad for the keyboard must not leave room for it too.
    var navigationHeight by remember { mutableStateOf(0.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var sourceMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(false) }
    val order = state.preferences.songSort
    val reversed = state.preferences.songOrderReversed
    val descending = reversed != (order in setOf(LibrarySort.RECENT, LibrarySort.RATING, LibrarySort.PLAY_COUNT, LibrarySort.LAST_PLAYED))
    val sources = state.preferences.sourceFilter
    val now = rememberLibraryTime()
    val derived = remember { DerivedResults() }
    val statistics = rememberDerived(null, emptyList(), listOf(state.listens)) { listeningStatistics(state.listens) }
    val filter = state.preferences.libraryFilter
    val unknownAlbum = stringResource(Res.string.library_unknown_album)
    // Only time-window filters depend on the clock; everything else must not re-sort the library every minute.
    val filterTime = if (filter.lastPlayedWithinDays != null || filter.notPlayedWithinDays != null) now else 0L
    val ranked = order == LibrarySort.PLAY_COUNT || order == LibrarySort.LAST_PLAYED || filter.playCountFrom != null ||
        filter.playCountTo != null || filter.lastPlayedWithinDays != null || filter.notPlayedWithinDays != null
    // Playback adds listened time every few seconds, which never changes the order; play counts and play times can.
    val rankedStatistics = rememberDerived(null, listOf(ranked), listOf(statistics)) { if (ranked) statistics.mapValues { (_, it) -> it.playCount to it.lastPlayedAt } else null }
    // Playlist edits only matter while the "Not in any playlist" filter is on.
    val playlisted = remember(filter.notInPlaylist, state.playlists) {
        if (filter.notInPlaylist) playlistedTrackIds(state.playlists) else emptySet()
    }
    // Sort and filter choices answer at once; a library change, such as one song's favorite, re-sorts in the background.
    val trackChoices = listOf(order, reversed, sources, filter, state.preferences.includeFolders, state.preferences.excludeFolders)
    // The unfiltered count is what the library shows without its source and filters, so the header can say "4 of 12 songs".
    val (tracks, unfilteredCount) = rememberDerived(null, trackChoices, listOf(state.tracks, playlisted, rankedStatistics, filterTime)) {
        val folders = LocalFolderRules(state.preferences.includeFolders, state.preferences.excludeFolders)
        fun localAllowed(track: Track) =
            folders.allows(track.metadata.folder, track.sources.firstOrNull { it.kind == SourceKind.LOCAL }?.filePath?.substringBeforeLast('/'))
        val unfiltered = state.tracks.filter { track -> localAllowed(track) || track.sources.any { it.kind != SourceKind.LOCAL } }
        val visible = if (sources == SourceKind.LOCAL) unfiltered.filter(::localAllowed) else unfiltered
        filterTracks(visible, state.preferences.libraryFilter.copy(sort = order, descending = reversed, sourceKind = sources), statistics, now, playlisted) to
            unfiltered.size
    }
    val displayedCount = remember(tracks, tabs[currentIndex]) {
        if (tabs[currentIndex] == LibraryTab.FAVORITES) tracks.count { it.favorite } else tracks.size
    }
    if (selection.active && backEnabled) PlatformBackHandler(selection::clear)
    CompositionLocalProvider(LocalSongSelection provides selection, LocalSongGesturePages provides songPages, LocalDerivedResults provides derived) {
    Column(modifier.fillMaxWidth()) {
        // Search ignores the source and library filters, and Playlists only follow the source.
        val libraryFiltersApply = tabs[currentIndex] !in setOf(LibraryTab.SEARCH, LibraryTab.PLAYLISTS)
        if (selection.active) SongSelectionHeader(selection, controller)
        else if (tabs[currentIndex] != LibraryTab.SEARCH) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.Image(org.jetbrains.compose.resources.painterResource(me.d3sox.nevumi.resources.Res.drawable.nevumi_logo),
                    stringResource(Res.string.library_nevumi_logo), Modifier.size(40.dp))
                if (libraryFiltersApply && tabs[currentIndex] != LibraryTab.HOME && (sources != null || filter.hasActiveFilters())) Row(Modifier.clickable(onClickLabel = stringResource(Res.string.library_filters)) { filters = true },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(AppIcon.FILTER.vector, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(pluralStringResource(Res.plurals.songs_of_total, unfilteredCount, displayedCount, unfilteredCount),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                } else Text(if (tabs[currentIndex] == LibraryTab.HOME) stringResource(LibraryTab.HOME.label) else countLabel(displayedCount, Res.plurals.songs), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (LibraryTab.SEARCH !in tabs) AppIconButton(AppIcon.SEARCH, stringResource(Res.string.search), onClick = onSearch)
            val currentTab = tabs[currentIndex]
            val activeSongLists = songPages.activeLists[currentTab] ?: 0
            val sortable = currentTab in setOf(LibraryTab.SONGS, LibraryTab.FAVORITES) ||
                activeSongLists > 0 && currentTab in setOf(LibraryTab.ARTISTS, LibraryTab.ALBUM_ARTISTS, LibraryTab.GENRES, LibraryTab.FOLDERS)
            val direction = stringResource(if (descending) Res.string.descending else Res.string.ascending)
            if (sortable) Box {
                SortIconButton(order.icon(), stringResource(Res.string.library_song_order),
                    stringResource(Res.string.library_sort_state, order.name, direction)) { sortMenu = true }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    listOf(LibrarySort.TITLE to Res.string.title, LibrarySort.ARTIST to Res.string.artist, LibrarySort.ALBUM to Res.string.album,
                        LibrarySort.RECENT to Res.string.recently_added, LibrarySort.RATING to Res.string.library_rating,
                        LibrarySort.PLAY_COUNT to Res.string.most_played, LibrarySort.LAST_PLAYED to Res.string.library_last_played).forEach { (value, name) ->
                        AppMenuItem(modifier = Modifier.semantics { selected = value == order }, text = { Text(stringResource(name)) },
                            leadingIcon = { Icon(value.icon().vector, null) },
                            trailingIcon = { if (value == order) Icon(AppIcon.CHECK.vector, null) },
                            onClick = { controller.updatePreferences { it.copy(songSort = value) }; sortMenu = false })
                    }
                    HorizontalDivider()
                    AppMenuItem(modifier = Modifier.semantics { contentDescription = direction }, text = { Text(direction) }, leadingIcon = { Icon((if (descending) AppIcon.DESCENDING else AppIcon.ASCENDING).vector, null) },
                        onClick = { controller.updatePreferences { it.copy(songOrderReversed = !reversed) }; sortMenu = false })
                }
            }
            if (activeSongLists == 0 && !rearrangingPlaylists && currentTab in setOf(LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.ALBUM_ARTISTS,
                    LibraryTab.GENRES, LibraryTab.FOLDERS, LibraryTab.PLAYLISTS))
                CollectionOrder(currentTab, state.preferences.collectionLayout(currentTab), controller,
                    rearrange = if (currentTab == LibraryTab.PLAYLISTS) ({ rearrangingPlaylists = true }) else null)
            AppIconButton(AppIcon.SETTINGS, stringResource(Res.string.settings), onClick = onSettings)
            Box {
                AppIconButton(AppIcon.MENU, stringResource(Res.string.music_sources)) { sourceMenu = true }
                DropdownMenu(sourceMenu, { sourceMenu = false }) {
                    listOf(null, SourceKind.LOCAL, SourceKind.SERVER, SourceKind.DOWNLOAD).forEach { value ->
                        AppMenuItem(modifier = Modifier.semantics { selected = value == sources }, text = { Text(stringResource(value.sourceName())) },
                            leadingIcon = { Icon(value.sourceIcon().vector, null) }, trailingIcon = { if (value == sources) Icon(AppIcon.CHECK.vector, null) }, onClick = { controller.updatePreferences { it.copy(sourceFilter = value) }; sourceMenu = false })
                    }
                    HorizontalDivider()
                    AppMenuItem(text = { Text(stringResource(Res.string.library_filters)) }, leadingIcon = { Icon(AppIcon.FILTER.vector, null) },
                        onClick = { filters = true; sourceMenu = false })
                }
            }
        }
        // The selection header replaces the source menu that the source chip opens.
        if (!selection.active && tabs[currentIndex] != LibraryTab.SEARCH) ActiveLibraryFilters(if (libraryFiltersApply) filter else LibraryQuery(), sources, edit = { filters = true },
            editSource = { sourceMenu = true }, change = { value -> controller.updatePreferences { it.copy(libraryFilter = value) } },
            changeSource = { value -> controller.updatePreferences { it.copy(sourceFilter = value) } })
        // Like Namida, slide the incoming tab over the stationary previous page, skipping intermediate tabs.
        transition.AnimatedContent(Modifier.weight(1f).fillMaxWidth().clipToBounds()
            .consumeWindowInsets(PaddingValues(bottom = navigationHeight)).testTag("library-pager"), transitionSpec = {
            val direction = if (tabs.indexOf(targetState) > tabs.indexOf(initialState))
                AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
            (slideIntoContainer(direction, tween(400, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))) togetherWith
                ExitTransition.KeepUntilTransitionsFinished).using(null)
        }) { tab ->
            pageStates.SaveableStateProvider(tab.name) {
            val active = LocalNavigationActive.current && tab == tabs[currentIndex]
            CompositionLocalProvider(LocalNavigationActive provides active,
                LocalLibraryOrigin provides me.d3sox.nevumi.queue.QueueOrigin(tab),
                LocalLibraryFocus provides destination?.takeIf { it.tab == tab },
                LocalLibraryScrollKey provides "library:${tab.name}") {
            val handleBack = backEnabled && active
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("library-tab:${tab.name}").then(if (active) Modifier else Modifier.clearAndSetSemantics {})) {
            when (tab) {
                LibraryTab.SEARCH -> LibrarySearchScreen(state, controller, 0.dp, dismiss = null, onSettings = onSettings)
                LibraryTab.HOME -> HomeScreen(state, controller, tracks, destination, handleBack, onSettings)
                LibraryTab.SONGS -> SongList(tracks, controller, statistics = statistics, scrollSort = order)
                LibraryTab.ALBUMS -> {
                    val albums = rememberDerived("albums", trackChoices + unknownAlbum, listOf(tracks)) { tracks.groupBy { it.metadata.album to it.metadata.albumArtist.ifBlank { it.metadata.artist } }
                        .entries.sortedBy { it.key.first.lowercase() }.map { (key, songs) ->
                            TrackCollection("${key.first.length}:${key.first}${key.second}", key.first.ifBlank { unknownAlbum },
                                songs.sortedWith(compareBy<Track> { it.metadata.discNumber }.thenBy { it.metadata.trackNumber }.thenBy { it.metadata.title }), key.second)
                        } }
                    TrackCollections(albums, LibraryTab.ALBUMS, state, controller, statistics, handleBack, destination)
                }
                LibraryTab.ARTISTS, LibraryTab.ALBUM_ARTISTS, LibraryTab.GENRES, LibraryTab.FOLDERS -> {
                    val unknownName = stringResource(when (tab) {
                        LibraryTab.ARTISTS -> Res.string.unknown_artist
                        LibraryTab.ALBUM_ARTISTS -> Res.string.library_unknown_album_artist
                        LibraryTab.GENRES -> Res.string.library_unknown_genre
                        else -> Res.string.library_unknown_folder
                    })
                    val collections = rememberDerived(tab.name, trackChoices + unknownName, listOf(tracks)) {
                        groupedCollections(tracks, unknownName) { track ->
                            when (tab) {
                                LibraryTab.ARTISTS -> listOf(track.metadata.artist)
                                LibraryTab.ALBUM_ARTISTS -> listOf(track.metadata.albumArtist.ifBlank { track.metadata.artist })
                                LibraryTab.GENRES -> track.metadata.genres.ifEmpty { listOf("") }
                                else -> listOf(track.metadata.folder)
                            }
                        }
                    }
                    TrackCollections(collections, tab, state, controller, statistics, handleBack, destination)
                }
                LibraryTab.FAVORITES -> SongList(rememberDerived("favorites", trackChoices, listOf(tracks)) { tracks.filter { it.favorite } }, controller,
                    statistics = statistics, scrollSort = order, contextName = "Favorites")
                LibraryTab.RECENT -> SongList(rememberDerived("recent", trackChoices, listOf(tracks)) {
                    tracks.map { it to it.recentAt }.sortedByDescending { it.second }.map { it.first }
                }, controller, scrollSort = LibrarySort.RECENT, contextName = "Recently added")
                LibraryTab.PLAYLISTS -> PlaylistsScreen(state, controller, backEnabled = handleBack, destination = destination,
                    rearranging = rearrangingPlaylists, finishRearranging = { rearrangingPlaylists = false })
                LibraryTab.HISTORY -> ListeningHistoryScreen(state, controller, tracks)
                LibraryTab.STATISTICS -> ListeningStatisticsScreen(state, controller, tracks, handleBack)
                LibraryTab.RECOMMENDATIONS -> ListeningRecommendationsScreen(state, controller, tracks)
            }
            }
        }
        }
        }
        Spacer(Modifier.height(miniPlayerSpace))
        LibraryNavigation(tabs, tabs[currentIndex], Modifier.onSizeChanged {
            navigationHeight = with(density) { it.height.toDp() }
            onNavigationHeight(it.height)
        }, state.preferences.bottomNavigationTabs) { page ->
            if (selectedTab != LibraryTab.SEARCH) searchReturnTab = selectedTab
            selection.clear(); selectedTab = page
        }
    }
    }
    if (filters) LibraryFiltersDialog(state, controller) { filters = false }
}

private fun groupedCollections(tracks: List<Track>, unknownName: String, keys: (Track) -> List<String>): List<TrackCollection> {
    val groups = linkedMapOf<String, MutableList<Track>>()
    tracks.forEach { track -> keys(track).forEach { groups.getOrPut(it) { mutableListOf() } += track } }
    return groups.entries.map { it.key.lowercase() to it }.sortedBy { it.first }.map { (_, group) ->
        TrackCollection(group.key, group.key.ifBlank { unknownName }, group.value)
    }
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
        CompositionLocalProvider(LocalTrackRowStrings provides trackRowStrings()) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll) {
            if (header != null) item(key = "song-list-header") { header() }
            itemsIndexed(tracks, key = { index, _ -> rowKeys[index] }) { index, track ->
                TrackRow(track, controller, ids, index, playlistId, selection.active, track.id in selectedIds,
                    { toggle(track.id) }, statistics?.get(track.id), entryInfo?.invoke(index) ?: extraInfo[track.id], contextName,
                    playlistPosition = playlistPositions?.getOrNull(index), shuffleContext = shuffleContext)
            }
            if (tracks.isEmpty()) item { Text(stringResource(Res.string.library_no_songs_yet), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        }
        val statisticsLabels = rememberScrollStatisticsLabels(statistics)
        LibraryScrollbar(scroll, tracks.size, stringResource(Res.string.library_scroll_library), if (header == null) 0 else 1) { scrollIndicator?.invoke(it) ?: tracks[it].scrollLabel(scrollSort, statistics, statisticsLabels) }
        }
    }
}
