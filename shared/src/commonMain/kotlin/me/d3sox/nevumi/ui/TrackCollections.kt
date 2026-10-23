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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.recentAt

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
    val selecting = LocalSongSelection.current?.active == true
    val entries = rememberDerived("collections:${tab.name}", emptyList(), listOf(collections, state.artwork)) {
        collections.map { entry ->
            CollectionItem(entry.id, entry.title, listOf(entry.subtitle, countLabel(entry.tracks.size, "song")).filter { it.isNotBlank() }.joinToString(" · "),
                entry.tracks.firstNotNullOfOrNull { state.artwork[it.id] }, tab.icon(),
                songCount = entry.tracks.size, artist = entry.tracks.firstOrNull()?.metadata?.albumArtist.orEmpty(),
                year = entry.tracks.maxOfOrNull { it.metadata.year } ?: 0, recentAt = entry.tracks.maxOfOrNull { it.recentAt } ?: 0,
                trackIds = entry.tracks.map { it.id })
        }
    }
    AnimatedNavigation(selected, null, canGoBack = backEnabled && selected != null && !selecting,
        onBack = { selected = null }, depth = { if (it == null) 0 else 1 }) { id ->
        val collection = collections.firstOrNull { it.id == id }
        if (collection == null) CollectionBrowser(entries, tab, state, controller) { selected = it }
        else Column(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalLibraryScrollKey provides "${LocalLibraryScrollKey.current}:collection:${collection.id}",
                LocalLibraryOrigin provides me.d3sox.nevumi.queue.QueueOrigin(tab, collection.id)) {
            SongList(collection.tracks, controller, Modifier.weight(1f), statistics = statistics, scrollSort = state.preferences.songSort,
                scrollIndicator = if (tab == LibraryTab.ALBUMS) { index ->
                    val metadata = collection.tracks[index].metadata
                    if (metadata.trackNumber > 0) "Disc ${metadata.discNumber.coerceAtLeast(1)} · Track ${metadata.trackNumber}"
                    else "${index + 1} / ${collection.tracks.size}"
                } else null, contextName = collection.title,
                header = if (!selecting) ({ CollectionHero(collection, tab, state, controller) { selected = null } }) else null)
            }
        }
    }
}

@Composable
internal fun CollectionHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIconButton(AppIcon.BACK, "Back", onClick = back)
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun CollectionArtwork(path: String?, title: String, icon: AppIcon, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        if (path != null) MusicArtwork(path, title)
        else Box(contentAlignment = Alignment.Center) { Icon(icon.vector, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}
