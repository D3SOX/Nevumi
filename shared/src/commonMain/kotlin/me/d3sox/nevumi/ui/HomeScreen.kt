package me.d3sox.nevumi.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.QueueOrigin
import kotlin.random.Random
import me.d3sox.nevumi.settings.HomeSectionId
import org.jetbrains.compose.resources.stringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.customize_home
import me.d3sox.nevumi.resources.library_collection_options_for
import me.d3sox.nevumi.resources.library_music_settings
import me.d3sox.nevumi.resources.library_no_music_available
import me.d3sox.nevumi.resources.library_no_music_available_hint
import me.d3sox.nevumi.resources.library_no_music_matches
import me.d3sox.nevumi.resources.library_no_music_matches_hint
import me.d3sox.nevumi.resources.library_no_music_yet
import me.d3sox.nevumi.resources.library_no_music_yet_hint
import me.d3sox.nevumi.resources.library_open_album
import me.d3sox.nevumi.resources.library_open_artist
import me.d3sox.nevumi.resources.library_open_mix
import me.d3sox.nevumi.resources.library_open_playlist
import me.d3sox.nevumi.resources.library_play_mix
import me.d3sox.nevumi.resources.library_refresh_mixes
import me.d3sox.nevumi.resources.library_see_all
import me.d3sox.nevumi.resources.library_see_all_section
import me.d3sox.nevumi.resources.library_select_saved_queue
import me.d3sox.nevumi.resources.library_track_options
import me.d3sox.nevumi.resources.library_unknown_album
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.unknown_artist

@Composable
internal fun HomeScreen(state: AppState, controller: MusicController, tracks: List<Track>,
    destination: LibraryDestination?, backEnabled: Boolean, settings: () -> Unit) {
    val now = rememberLibraryTime()
    var seed by rememberSaveable { mutableIntStateOf(Random.nextInt()) }
    val unknownAlbum = stringResource(Res.string.library_unknown_album)
    val unknownArtist = stringResource(Res.string.unknown_artist)
    val feed = rememberDerived("home-feed", listOf(seed, unknownAlbum, unknownArtist), listOf(tracks, state.listens, now), initial = { HomeFeed() }) {
        // The minute clock can predate a listen that has just arrived.
        homeFeed(tracks, state.listens, kotlin.time.Clock.System.now().toEpochMilliseconds(), seed, unknownAlbum, unknownArtist)
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var handled by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(destination) {
        if (destination?.tab == LibraryTab.HOME && destination.revision != handled) {
            selected = destination.collectionId; handled = destination.revision
        }
    }
    val navigate = LocalLibraryNavigate.current
    val homeQueues = remember(state.savedQueues) { state.savedQueues.filter { it.kind != me.d3sox.nevumi.queue.SavedQueueKind.RECOVERY } }
    val catalog = rememberDerived("home-catalog", emptyList(), listOf(tracks)) { tracks.associateBy { it.id } }
    val playlistLayout = state.preferences.collectionLayout(LibraryTab.PLAYLISTS)
    val playlists = remember(state.playlists, state.tracks, playlistLayout) {
        homePlaylists(state.playlists, playlistLayout, state.tracks.associate { it.id to it.recentAt })
    }
    AnimatedNavigation(selected, null, canGoBack = backEnabled && selected != null,
        onBack = { selected = null }, depth = { if (it == null) 0 else 1 }) { mixId ->
        val mix = feed.mixes.firstOrNull { it.id == mixId }
        if (mixId == "customize-home") HomeCustomization(state.preferences.homeSections, controller) { selected = null }
        else if (mix != null) Column(Modifier.fillMaxSize()) {
            val mixTitle = stringResource(mix.title)
            CollectionHeader(mixTitle) { selected = null }
            CompositionLocalProvider(LocalLibraryOrigin provides QueueOrigin(LibraryTab.HOME, mix.id),
                LocalLibraryScrollKey provides "home:mix:${mix.id}") {
                SongList(mix.tracks, controller, Modifier.weight(1f), contextName = mixTitle,
                    shuffleContext = true, header = {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(mix.description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PageAction(stringResource(Res.string.library_play_mix), AppIcon.PLAY) {
                                controller.playTrack(mix.tracks.first().id, mix.tracks.map { it.id }, 0, mixTitle,
                                    shuffleContext = true, origin = QueueOrigin(LibraryTab.HOME, mix.id))
                            }
                        }
                    })
            }
        } else LazyColumn(Modifier.fillMaxSize().testTag("home-page"),
            state = rememberLibraryListState(controller, "home"), contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item(key = "intro") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(countLabel(tracks.size, Res.plurals.songs), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (feed.mixes.isNotEmpty()) TextButton(onClick = { seed = Random.nextInt() }) {
                        Icon(AppIcon.SHUFFLE.vector, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.library_refresh_mixes))
                    }
                    AppIconButton(AppIcon.TABS, stringResource(Res.string.customize_home)) { selected = "customize-home" }
                }
            }
            if (feed.added.isEmpty()) item(key = "empty") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(AppIcon.LIBRARY.vector, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(when { state.tracks.isEmpty() -> Res.string.library_no_music_yet; tracks.isEmpty() -> Res.string.library_no_music_matches
                            else -> Res.string.library_no_music_available }),
                            style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(when {
                            state.tracks.isEmpty() -> Res.string.library_no_music_yet_hint
                            tracks.isEmpty() -> Res.string.library_no_music_matches_hint
                            else -> Res.string.library_no_music_available_hint
                        }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PageAction(stringResource(Res.string.library_music_settings), AppIcon.SETTINGS, onClick = settings)
                    }
                }
            }
            state.preferences.homeSections.filterNot { it in state.preferences.disabledHomeSections }.forEach { section ->
                when (section) {
                    HomeSectionId.MIXES -> if (feed.mixes.isNotEmpty()) item(key = "mixes") {
                HomeSection(stringResource(section.label)) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(feed.mixes, key = { it.id }) { entry ->
                            val title = stringResource(entry.title)
                            HomeCollectionCard(title, stringResource(entry.description), entry.tracks, state, controller, LibraryTab.HOME, entry.id, AppIcon.SHUFFLE,
                                stringResource(Res.string.library_open_mix, title)) { selected = entry.id }
                        }
                    }
                }
            }
                    HomeSectionId.RECENT -> if (feed.recent.isNotEmpty()) item(key = "recent") {
                HomeSongs(stringResource(section.label), feed.recent, state, controller) { navigate(LibraryTab.HISTORY, null) }
            }
                    HomeSectionId.TOP -> if (feed.top.isNotEmpty()) item(key = "top") {
                HomeSongs(stringResource(section.label), feed.top, state, controller) { navigate(LibraryTab.STATISTICS, null) }
            }
                    HomeSectionId.RECENT_ALBUMS -> if (feed.recentAlbums.isNotEmpty()) item(key = "recent-albums") {
                HomeCollections(stringResource(section.label), feed.recentAlbums, LibraryTab.ALBUMS, state, controller, navigate)
            }
                    HomeSectionId.RECENT_ARTISTS -> if (feed.recentArtists.isNotEmpty()) item(key = "recent-artists") {
                HomeCollections(stringResource(section.label), feed.recentArtists, LibraryTab.ARTISTS, state, controller, navigate)
            }
                    HomeSectionId.TOP_ALBUMS -> if (feed.topAlbums.isNotEmpty()) item(key = "top-albums") {
                HomeCollections(stringResource(section.label), feed.topAlbums, LibraryTab.ALBUMS, state, controller, navigate)
            }
                    HomeSectionId.TOP_ARTISTS -> if (feed.topArtists.isNotEmpty()) item(key = "top-artists") {
                HomeCollections(stringResource(section.label), feed.topArtists, LibraryTab.ARTISTS, state, controller, navigate)
            }
                    HomeSectionId.REDISCOVER -> if (feed.rediscover.isNotEmpty()) item(key = "rediscover") {
                HomeSongs(stringResource(section.label), feed.rediscover, state, controller)
            }
                    HomeSectionId.PLAYLISTS -> if (state.playlists.isNotEmpty()) item(key = "playlists") {
                HomeSection(stringResource(section.label), { navigate(LibraryTab.PLAYLISTS, null) }) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            HomeCollectionCard(playlist.name, countLabel(playlist.trackIds.size, Res.plurals.songs), playlist.trackIds.mapNotNull(catalog::get), state, controller, LibraryTab.PLAYLISTS, playlist.id,
                                AppIcon.QUEUE, stringResource(Res.string.library_open_playlist, playlist.name), artwork = playlist.artworkPath(state)) {
                                navigate(LibraryTab.PLAYLISTS, playlist.id)
                            }
                        }
                    }
                }
            }
                    HomeSectionId.SAVED_QUEUES -> if (homeQueues.isNotEmpty()) item(key = "saved-queues") {
                HomeSection(stringResource(section.label)) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(homeQueues.take(20), key = { it.id }) { queue ->
                            HomeCollectionCard(queue.name, countLabel(queue.snapshot.entries.size, Res.plurals.songs),
                                queue.snapshot.entries.mapNotNull { catalog[it.trackId] }, state, controller, LibraryTab.HOME, queue.id, AppIcon.QUEUE,
                                stringResource(Res.string.library_select_saved_queue, queue.name)) { controller.loadSavedQueue(queue.id) }
                        }
                    }
                }
            }
                    HomeSectionId.ADDED -> if (feed.added.isNotEmpty()) item(key = "added") {
                HomeSongs(stringResource(section.label), feed.added, state, controller) { navigate(LibraryTab.RECENT, null) }
            }
                }
            }
        }
    }
}

@Composable
private fun HomeSection(title: String, all: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(Modifier.testTag("home-section:$title"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            val seeAllDescription = stringResource(Res.string.library_see_all_section, title)
            if (all != null) TextButton(all, Modifier.semantics { contentDescription = seeAllDescription }) { Text(stringResource(Res.string.library_see_all)) }
        }
        content()
    }
}

@Composable
private fun HomeSongs(title: String, songs: List<Track>, state: AppState, controller: MusicController, all: (() -> Unit)? = null) {
    val ids = remember(songs) { songs.map { it.id } }
    HomeSection(title, all) {
        LazyRow(Modifier.testTag("home-songs:$title"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(songs, key = { _, track -> track.id }) { index, track ->
                var bounds by remember(track.id) { mutableStateOf<Rect?>(null) }
                SongActionHost(track, state, controller, ids, index, contextName = title, artworkOrigin = { bounds }) { _, _, menu, menuOpen ->
                    Card(Modifier.width(152.dp).testTag("home-song:$title:${track.id}").clip(RoundedCornerShape(16.dp))
                        .combinedClickable(onClick = { controller.playTrack(track.id, ids, index, title, origin = QueueOrigin(LibraryTab.HOME), preserveManualQueue = true) }, onLongClick = menu)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CollectionArtwork(state.artwork[track.id], track.metadata.title, AppIcon.MUSIC,
                                Modifier.fillMaxWidth().aspectRatio(1f).onGloballyPositioned { bounds = Rect(it.positionOnScreen(), it.size.toSize()) }
                                    .graphicsLayer { alpha = if (menuOpen) 0f else 1f })
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(track.metadata.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (state.preferences.showMoreButtons) AppIconButton(AppIcon.MORE, stringResource(Res.string.library_track_options, track.metadata.title), onClick = menu)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCollections(title: String, collections: List<TrackCollection>, tab: LibraryTab, state: AppState, controller: MusicController, navigate: (LibraryTab, String?) -> Unit) {
    HomeSection(title, { navigate(tab, null) }) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(collections, key = { it.id }) { collection ->
                HomeCollectionCard(collection.title, if (tab == LibraryTab.ALBUMS) collection.subtitle else countLabel(collection.tracks.size, Res.plurals.songs),
                    collection.tracks, state, controller, tab, collection.id, tab.icon(),
                    stringResource(if (tab == LibraryTab.ALBUMS) Res.string.library_open_album else Res.string.library_open_artist, collection.title)) {
                    navigate(tab, collection.id)
                }
            }
        }
    }
}

@Composable
private fun HomeCollectionCard(title: String, subtitle: String, tracks: List<Track>, state: AppState, controller: MusicController, tab: LibraryTab, id: String, icon: AppIcon,
    label: String, artwork: String? = null, open: () -> Unit) {
    val entry = CollectionItem(id, title, subtitle, artwork, icon, tracks.size, trackIds = tracks.map { it.id })
    CollectionActionHost(entry, tab, state, controller, open) { menu ->
    Card(Modifier.width(168.dp).clip(MaterialTheme.shapes.medium)
        .combinedClickable(onClick = open, onLongClick = menu).semantics { contentDescription = label }) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val covers = tracks.distinctBy { albumCollectionId(it.metadata) }.take(4)
            if (artwork != null || covers.size < 4) CollectionArtwork(artwork ?: tracks.firstNotNullOfOrNull { state.artwork[it.id] },
                title, icon, Modifier.fillMaxWidth().aspectRatio(1f))
            else Column(Modifier.fillMaxWidth().aspectRatio(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                covers.chunked(2).forEach { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        row.forEach { track -> CollectionArtwork(state.artwork[track.id], title, icon, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.preferences.showMoreButtons) AppIconButton(AppIcon.MORE, stringResource(Res.string.library_collection_options_for, title), onClick = menu)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    }
}
