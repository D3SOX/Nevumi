package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.settings.CollectionLayout
import me.d3sox.nevumi.settings.CollectionView
import me.d3sox.nevumi.settings.CollectionSort
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import org.jetbrains.compose.resources.stringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.album
import me.d3sox.nevumi.resources.album_artist
import me.d3sox.nevumi.resources.album_artists
import me.d3sox.nevumi.resources.albums
import me.d3sox.nevumi.resources.artist
import me.d3sox.nevumi.resources.artists
import me.d3sox.nevumi.resources.folders
import me.d3sox.nevumi.resources.genres
import me.d3sox.nevumi.resources.library_album_artist_options
import me.d3sox.nevumi.resources.library_album_options
import me.d3sox.nevumi.resources.library_artist_options
import me.d3sox.nevumi.resources.library_automatic
import me.d3sox.nevumi.resources.library_collection_view
import me.d3sox.nevumi.resources.library_column_count
import me.d3sox.nevumi.resources.library_columns
import me.d3sox.nevumi.resources.library_finish_rearranging
import me.d3sox.nevumi.resources.library_folder
import me.d3sox.nevumi.resources.library_folder_options
import me.d3sox.nevumi.resources.library_genre
import me.d3sox.nevumi.resources.library_genre_options
import me.d3sox.nevumi.resources.library_grid
import me.d3sox.nevumi.resources.library_list
import me.d3sox.nevumi.resources.library_no_songs
import me.d3sox.nevumi.resources.library_playlist
import me.d3sox.nevumi.resources.library_playlist_options_for
import me.d3sox.nevumi.resources.library_reorder
import me.d3sox.nevumi.resources.library_scroll_album_artists
import me.d3sox.nevumi.resources.library_scroll_albums
import me.d3sox.nevumi.resources.library_scroll_artists
import me.d3sox.nevumi.resources.library_scroll_folders
import me.d3sox.nevumi.resources.library_scroll_genres
import me.d3sox.nevumi.resources.library_scroll_playlists
import me.d3sox.nevumi.resources.library_unknown_year
import me.d3sox.nevumi.resources.move_down
import me.d3sox.nevumi.resources.move_up
import me.d3sox.nevumi.resources.playlists
import me.d3sox.nevumi.resources.songs
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.PluralStringResource

/**
 * A collection row or card. [describe] replaces [subtitle] when set, so lists built outside a composition
 * can show counted text such as "3 songs" that is resolved when the row is shown.
 */
internal data class CollectionItem(val id: String, val title: String, val subtitle: String,
    val artwork: String?, val icon: AppIcon, val songCount: Int = 0, val artist: String = "", val year: Int = 0, val recentAt: Long = 0,
    val trackIds: List<String> = emptyList(), val describe: (@Composable () -> String)? = null)

/** How a collection tab names one of its collections and counts them, without deriving singulars from the tab label. */
internal class CollectionNouns(val kind: StringResource, val count: PluralStringResource, val options: StringResource, val scroll: StringResource)

/** Only collection tabs reach the collection browser and hero; the playlists wording covers any other tab. */
internal fun LibraryTab.collectionNouns() = when (this) {
    LibraryTab.ALBUMS -> CollectionNouns(Res.string.album, Res.plurals.albums, Res.string.library_album_options, Res.string.library_scroll_albums)
    LibraryTab.ARTISTS -> CollectionNouns(Res.string.artist, Res.plurals.artists, Res.string.library_artist_options, Res.string.library_scroll_artists)
    LibraryTab.ALBUM_ARTISTS -> CollectionNouns(Res.string.album_artist, Res.plurals.album_artists,
        Res.string.library_album_artist_options, Res.string.library_scroll_album_artists)
    LibraryTab.GENRES -> CollectionNouns(Res.string.library_genre, Res.plurals.genres, Res.string.library_genre_options, Res.string.library_scroll_genres)
    LibraryTab.FOLDERS -> CollectionNouns(Res.string.library_folder, Res.plurals.folders, Res.string.library_folder_options, Res.string.library_scroll_folders)
    else -> CollectionNouns(Res.string.library_playlist, Res.plurals.playlists, Res.string.library_playlist_options_for, Res.string.library_scroll_playlists)
}

internal fun CollectionLayout.comparator(): Comparator<CollectionItem> {
    val positions = order.withIndex().associate { it.value to it.index }
    val comparator = when (sort) {
        CollectionSort.NAME -> compareBy<CollectionItem> { it.title.lowercase() }
        CollectionSort.ARTIST -> compareBy { it.artist.lowercase() }
        CollectionSort.YEAR -> compareBy { it.year }
        CollectionSort.SONG_COUNT -> compareBy { it.songCount }
        CollectionSort.RECENT -> compareBy { it.recentAt }
        CollectionSort.CUSTOM -> compareBy { positions[it.id] ?: Int.MAX_VALUE }
    }.thenBy { it.title.lowercase() }
    return if (descending) comparator.reversed() else comparator
}

@Composable
internal fun CollectionBrowser(entries: List<CollectionItem>, tab: LibraryTab, state: AppState, controller: MusicController,
    modifier: Modifier = Modifier, total: Int = entries.size, rearranging: Boolean = false, finishRearranging: () -> Unit = {}, open: (String) -> Unit) {
    val nouns = tab.collectionNouns()
    val listScroll = rememberLibraryListState(controller, "collections:${tab.name}:list")
    val gridScroll = rememberLibraryGridState(controller, "collections:${tab.name}:grid")
    val layout = state.preferences.collectionLayout(tab)
    val sorted = remember(entries, layout.sort, layout.descending, layout.order) { entries.sortedWith(layout.comparator()) }
    // Library updates can replace the entries during a drag or before its order is saved, so the dragged order is kept by ID until then.
    var dragged by remember(layout.sort, layout.descending, layout.order) { mutableStateOf<List<String>?>(null) }
    val sortedEntries = remember(sorted, dragged) {
        dragged?.let { ids ->
            val rows = sorted.associateBy { it.id }
            val kept = ids.toSet()
            ids.mapNotNull(rows::get) + sorted.filter { it.id !in kept }
        } ?: sorted
    }
    fun move(from: Any, to: Any) {
        val rows = sortedEntries.map { it.id }.toMutableList()
        val source = rows.indexOf(from)
        val target = rows.indexOf(to)
        if (source >= 0 && target >= 0) dragged = rows.apply { add(target, removeAt(source)) }
    }
    val rearrangeScroll = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(rearrangeScroll) { from, to -> move(from.key, to.key) }
    val gridCells = layout.columns?.let { GridCells.Fixed(it.coerceIn(2, 6)) } ?: GridCells.Adaptive(128.dp)
    var menu by remember { mutableStateOf(false) }
    fun update(change: (CollectionLayout) -> CollectionLayout) {
        controller.updatePreferences { settings ->
            settings.copy(collectionLayouts = settings.collectionLayouts + (tab to change(settings.collectionLayout(tab))))
        }
    }
    // Playlist folders are ordered separately, so keep the positions of entries this page does not show.
    fun saveOrder(ids: List<String>) = update { layout -> layout.copy(order = ids + (layout.order - ids.toSet())) }
    @Composable
    fun ReorderableCollectionItemScope.DragHandle(entry: CollectionItem) {
        fun moveBy(offset: Int): Boolean {
            val rows = sortedEntries.map { it.id }.toMutableList()
            val from = rows.indexOf(entry.id)
            if (from + offset !in rows.indices) return false
            rows.add(from + offset, rows.removeAt(from))
            dragged = rows
            saveOrder(rows)
            return true
        }
        val reorderDescription = stringResource(Res.string.library_reorder, entry.title)
        val moveUp = stringResource(Res.string.move_up)
        val moveDown = stringResource(Res.string.move_down)
        Box(Modifier.size(48.dp).testTag("collection-reorder:${entry.id}")
            .semantics {
                contentDescription = reorderDescription
                customActions = listOf(CustomAccessibilityAction(moveUp) { moveBy(-1) }, CustomAccessibilityAction(moveDown) { moveBy(1) })
            }
            .draggableHandle(onDragStopped = { dragged?.let(::saveOrder) }),
            contentAlignment = Alignment.Center) { Icon(AppIcon.DRAG.vector, null) }
    }
    val navigationActive = LocalNavigationActive.current
    Column(modifier.fillMaxSize().onKeyEvent {
        if (navigationActive && rearranging && it.key == Key.Escape && it.type == KeyEventType.KeyUp) { finishRearranging(); true } else false
    }) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(countLabel(total, nouns.count), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rearranging) AppIconButton(AppIcon.CHECK, stringResource(Res.string.library_finish_rearranging), onClick = finishRearranging)
            else Box {
                AppIconButton(if (layout.view == CollectionView.GRID) AppIcon.GRID else AppIcon.LIST, stringResource(Res.string.library_collection_view)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    CollectionView.entries.forEach { view ->
                        AppMenuItem(text = { Text(stringResource(if (view == CollectionView.LIST) Res.string.library_list else Res.string.library_grid)) },
                            leadingIcon = { Icon((if (view == CollectionView.LIST) AppIcon.LIST else AppIcon.GRID).vector, null) },
                            trailingIcon = { if (layout.view == view) Icon(AppIcon.CHECK.vector, null) },
                            modifier = Modifier.semantics { selected = layout.view == view },
                            onClick = { update { it.copy(view = view) }; menu = false })
                    }
                    if (layout.view == CollectionView.GRID) {
                        HorizontalDivider()
                        Text(stringResource(Res.string.library_columns), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                        AppMenuItem(text = { Text(stringResource(Res.string.library_automatic)) },
                            trailingIcon = { if (layout.columns == null) Icon(AppIcon.CHECK.vector, null) },
                            modifier = Modifier.semantics { selected = layout.columns == null },
                            onClick = { update { it.copy(columns = null) }; menu = false })
                        (2..6).forEach { columns ->
                            AppMenuItem(text = { Text(pluralStringResource(Res.plurals.library_column_count, columns, columns)) },
                                trailingIcon = { if (layout.columns == columns) Icon(AppIcon.CHECK.vector, null) },
                                modifier = Modifier.semantics { selected = layout.columns == columns },
                                onClick = { update { it.copy(columns = columns) }; menu = false })
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (rearranging) LazyColumn(Modifier.fillMaxSize(), state = rearrangeScroll) {
            items(sortedEntries, key = { it.id }) { entry ->
                ReorderableItem(reorder, entry.id) { _ ->
                    ListItem(headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { CollectionArtwork(entry.artwork, entry.title, entry.icon, Modifier.size(40.dp)) },
                        trailingContent = { DragHandle(entry) })
                }
            }
        } else if (layout.view == CollectionView.LIST) LazyColumn(Modifier.fillMaxSize(), state = listScroll) {
            items(sortedEntries, key = { it.id }) { entry ->
                CollectionActionHost(entry, tab, state, controller, { open(entry.id) }) { menu ->
                ListItem(modifier = Modifier.combinedClickable(onClick = { open(entry.id) }, onLongClick = menu),
                    headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { CollectionArtwork(entry.artwork, entry.title, entry.icon, Modifier.size(48.dp)) },
                    supportingContent = { Text(entry.describe?.invoke() ?: entry.subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { if (state.preferences.showMoreButtons) AppIconButton(AppIcon.MORE, stringResource(nouns.options, entry.title), onClick = menu) })
                }
            }
        } else LazyVerticalGrid(gridCells, Modifier.fillMaxSize(), state = gridScroll,
            contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sortedEntries, key = { it.id }) { entry ->
                CollectionActionHost(entry, tab, state, controller, { open(entry.id) }) { menu ->
                ElevatedCard(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .combinedClickable(onClick = { open(entry.id) }, onLongClick = menu)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CollectionArtwork(entry.artwork, entry.title, entry.icon, Modifier.fillMaxWidth().aspectRatio(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (state.preferences.showMoreButtons) AppIconButton(AppIcon.MORE, stringResource(nouns.options, entry.title), onClick = menu)
                        }
                        Text(entry.describe?.invoke() ?: entry.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                }
            }
        }
        val unknownYear = stringResource(Res.string.library_unknown_year)
        val noSongs = stringResource(Res.string.library_no_songs)
        // Scrollbar labels are read outside composition, so resolve each distinct song count up front.
        val songCounts = if (layout.sort != CollectionSort.SONG_COUNT) emptyMap() else
            remember(sortedEntries) { sortedEntries.map { it.songCount }.distinct() }.associateWith { key(it) { countLabel(it, Res.plurals.songs) } }
        fun indicator(index: Int): String = sortedEntries[index].let { entry -> when (layout.sort) {
            CollectionSort.NAME -> alphabetIndicator(entry.title)
            CollectionSort.ARTIST -> alphabetIndicator(entry.artist)
            CollectionSort.YEAR -> if (entry.year == 0) unknownYear else "${entry.year}"
            CollectionSort.SONG_COUNT -> songCounts[entry.songCount].orEmpty()
            CollectionSort.RECENT -> if (entry.recentAt == 0L) noSongs else formatScrollDate(entry.recentAt)
            CollectionSort.CUSTOM -> "${index + 1}"
        } }
        val scrollDescription = stringResource(nouns.scroll)
        if (rearranging) LibraryScrollbar(rearrangeScroll, entries.size, scrollDescription, label = ::indicator)
        else if (layout.view == CollectionView.LIST) LibraryScrollbar(listScroll, entries.size, scrollDescription, label = ::indicator)
        else LibraryScrollbar(gridScroll, entries.size, scrollDescription, label = ::indicator)
        }
    }
}
