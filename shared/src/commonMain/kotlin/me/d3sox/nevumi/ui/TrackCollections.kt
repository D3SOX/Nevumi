package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.recentAt
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.library_disc_track
import me.d3sox.nevumi.resources.songs
import org.jetbrains.compose.resources.stringResource

internal data class TrackCollection(val id: String, val title: String, val tracks: List<Track>, val subtitle: String = "")

@Composable
internal fun TrackCollections(collections: List<TrackCollection>, tab: LibraryTab, state: AppState,
    controller: MusicController, statistics: Map<String, me.d3sox.nevumi.history.ListeningStatistics>, backEnabled: Boolean,
    destination: LibraryDestination? = null) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var handledDestination by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(destination) {
        if (destination?.tab == tab && destination.revision != handledDestination) {
            selected = destination.collectionId; handledDestination = destination.revision
        }
    }
    OnLibraryTabReselected { selected = null }
    val selecting = LocalSongSelection.current?.active == true
    val entries = rememberDerived("collections:${tab.name}", emptyList(), listOf(collections, state.artwork)) {
        collections.map { entry ->
            CollectionItem(entry.id, entry.title, entry.subtitle,
                entry.tracks.firstNotNullOfOrNull { state.artwork[it.id] }, tab.icon(),
                songCount = entry.tracks.size, artist = entry.tracks.firstOrNull()?.metadata?.albumArtist.orEmpty(),
                year = entry.tracks.maxOfOrNull { it.metadata.year } ?: 0, recentAt = entry.tracks.maxOfOrNull { it.recentAt } ?: 0,
                trackIds = entry.tracks.map { it.id }, describe = {
                    listOf(entry.subtitle, countLabel(entry.tracks.size, Res.plurals.songs)).filter { it.isNotBlank() }.joinToString(" · ")
                })
        }
    }
    AnimatedNavigation(selected, null, canGoBack = backEnabled && selected != null && !selecting,
        onBack = { selected = null }, depth = { if (it == null) 0 else 1 }) { id ->
        val collection = collections.firstOrNull { it.id == id }
        if (collection == null) CollectionBrowser(entries, tab, state, controller) { selected = it }
        else Column(Modifier.fillMaxSize()) {
            // The scroll indicator is read outside composition, so resolve each album track's position up front.
            val positions = if (tab == LibraryTab.ALBUMS) collection.tracks.map { track ->
                track.metadata.takeIf { it.trackNumber > 0 }?.let { stringResource(Res.string.library_disc_track, it.discNumber.coerceAtLeast(1), it.trackNumber) }
            } else emptyList()
            CompositionLocalProvider(LocalLibraryScrollKey provides "${LocalLibraryScrollKey.current}:collection:${collection.id}",
                LocalLibraryOrigin provides me.d3sox.nevumi.queue.QueueOrigin(tab, collection.id)) {
            SongList(collection.tracks, controller, Modifier.weight(1f), statistics = statistics, scrollSort = state.preferences.songSort,
                scrollIndicator = if (tab == LibraryTab.ALBUMS) { index ->
                    positions.getOrNull(index) ?: "${index + 1} / ${collection.tracks.size}"
                } else null, contextName = collection.title, headerBack = { selected = null },
                header = if (!selecting) ({ CollectionHero(collection, tab, state, controller) }) else null)
            }
        }
    }
}

@Composable
internal fun CollectionArtwork(path: String?, title: String, icon: AppIcon, modifier: Modifier, covers: List<String?> = emptyList(), badge: AppIcon? = null) {
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        // Four covers make a 2×2 mosaic; a cell without artwork shows the music placeholder.
        if (covers.size >= 4) Column {
            covers.take(4).chunked(2).forEach { row ->
                Row(Modifier.weight(1f)) { row.forEach { cover -> Box(Modifier.weight(1f).fillMaxHeight()) { MusicArtwork(cover, title) } } }
            }
        }
        else if (path != null) MusicArtwork(path, title)
        else ArtworkPlaceholder(LocalArtworkPending.current, icon, null, Modifier.size(28.dp))
        // Artwork hides the collection's own icon, so a corner badge keeps it recognizable, such as a folder among playlists.
        // The top start cell always has a cover because covers come first, so the badge never hides a placeholder.
        if (badge != null && (path != null || covers.size >= 4)) BoxWithConstraints(Modifier.fillMaxSize()) {
            val size = (maxWidth * .3f).coerceIn(18.dp, 36.dp)
            Surface(Modifier.align(Alignment.TopStart).padding(maxWidth * .05f).size(size), shape = RoundedCornerShape(size / 3),
                color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(badge.vector, null, Modifier.padding(size * .2f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
