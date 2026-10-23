package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playlists.Playlist
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.library_reorder_position
import me.d3sox.nevumi.resources.library_scroll_playlist
import org.jetbrains.compose.resources.stringResource

private data class PlaylistDragRow(val key: Int, val trackId: String)

@Composable
internal fun PlaylistRearrangeList(playlist: Playlist, state: AppState, controller: MusicController,
    modifier: Modifier, header: @Composable () -> Unit) {
    var rows by remember(playlist.id) { mutableStateOf(playlist.trackIds.mapIndexed { index, id -> PlaylistDragRow(index, id) }) }
    var nextKey by remember(playlist.id) { mutableIntStateOf(rows.size) }
    var from by remember { mutableIntStateOf(-1) }
    var dragging by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    val catalog = remember(state.tracks) { state.tracks.associateBy { it.id } }
    val ids = remember(rows) { rows.map { it.trackId } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(playlist.trackIds, dragging, committing) {
        if (!dragging && !committing && rows.map { it.trackId } != playlist.trackIds) {
            val existing = rows.groupBy { it.trackId }.mapValues { it.value.toMutableList() }
            rows = playlist.trackIds.map { id -> existing[id]?.removeFirstOrNull() ?: PlaylistDragRow(nextKey++, id) }
        }
    }
    val scroll = rememberLazyListState()
    val reorderable = rememberReorderableLazyListState(scroll) { origin, destination ->
        val source = rows.indexOfFirst { it.key == origin.key }
        val target = rows.indexOfFirst { it.key == destination.key }
        if (source >= 0 && target >= 0) rows = rows.toMutableList().apply { add(target, removeAt(source)) }
    }
    Box(modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), state = scroll) {
        item("playlist-header") { header() }
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val track = catalog[row.trackId] ?: return@itemsIndexed
            val reorderDescription = stringResource(Res.string.library_reorder_position, track.metadata.title, index + 1)
            ReorderableItem(reorderable, row.key) { _ ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(start = 8.dp).size(48.dp).testTag("playlist-reorder:${row.key}")
                        .semantics { contentDescription = reorderDescription }
                        .draggableHandle(enabled = !committing, onDragStarted = { from = index; dragging = true },
                            onDragStopped = {
                                val destination = rows.indexOfFirst { it.key == row.key }
                                committing = true; dragging = false
                                scope.launch {
                                    try { if (from >= 0 && destination >= 0) controller.movePlaylistTrack(playlist.id, from, destination).join() }
                                    finally { committing = false }
                                }
                            }), contentAlignment = Alignment.Center) { Icon(AppIcon.DRAG.vector, null) }
                    Box(Modifier.weight(1f)) {
                        TrackRow(track, controller, ids, index, playlist.id,
                            contextName = playlist.name, playlistPosition = index, shuffleContext = playlist.shuffle)
                    }
                }
            }
        }
    }
    val scrollRows = rows
    LibraryScrollbar(scroll, scrollRows.size, stringResource(Res.string.library_scroll_playlist), headerItems = 1) { index ->
        scrollRows.getOrNull(index)?.trackId?.let { catalog[it]?.metadata?.title }.orEmpty()
    }
    }
}
