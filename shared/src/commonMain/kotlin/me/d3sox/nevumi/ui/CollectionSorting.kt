package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.settings.CollectionLayout
import me.d3sox.nevumi.settings.CollectionSort
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.artist
import me.d3sox.nevumi.resources.ascending
import me.d3sox.nevumi.resources.descending
import me.d3sox.nevumi.resources.library_collection_order
import me.d3sox.nevumi.resources.library_custom_order
import me.d3sox.nevumi.resources.library_rearrange
import me.d3sox.nevumi.resources.library_recently_added_music
import me.d3sox.nevumi.resources.library_song_count
import me.d3sox.nevumi.resources.library_sort_state
import me.d3sox.nevumi.resources.name
import me.d3sox.nevumi.resources.year
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CollectionSort.label() = stringResource(when (this) {
    CollectionSort.NAME -> Res.string.name
    CollectionSort.ARTIST -> Res.string.artist
    CollectionSort.YEAR -> Res.string.year
    CollectionSort.SONG_COUNT -> Res.string.library_song_count
    CollectionSort.RECENT -> Res.string.library_recently_added_music
    CollectionSort.CUSTOM -> Res.string.library_custom_order
})

internal fun CollectionSort.icon() = when (this) {
    CollectionSort.NAME -> AppIcon.TITLE
    CollectionSort.ARTIST -> AppIcon.ARTIST
    CollectionSort.YEAR -> AppIcon.YEAR
    CollectionSort.SONG_COUNT -> AppIcon.REPEAT_COUNT
    CollectionSort.RECENT -> AppIcon.RECENT
    CollectionSort.CUSTOM -> AppIcon.DRAG
}

@Composable
internal fun SortIconButton(option: AppIcon, description: String, current: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.semantics { contentDescription = description; stateDescription = current }) {
        Box(Modifier.size(32.dp)) {
            Icon(AppIcon.SORT.vector, null, Modifier.align(Alignment.CenterStart).size(24.dp))
            Surface(Modifier.align(Alignment.BottomEnd).size(18.dp), shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Box(contentAlignment = Alignment.Center) { Icon(option.vector, null, Modifier.size(12.dp)) }
            }
        }
    }
}

@Composable
internal fun CollectionOrder(tab: LibraryTab, layout: CollectionLayout, controller: MusicController, rearrange: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    fun update(change: (CollectionLayout) -> CollectionLayout) {
        controller.updatePreferences { settings -> settings.copy(collectionLayouts = settings.collectionLayouts +
            (tab to change(settings.collectionLayout(tab)))) }
    }
    Box {
        val direction = stringResource(if (layout.descending) Res.string.descending else Res.string.ascending)
        val current = if (layout.sort == CollectionSort.CUSTOM) layout.sort.label()
            else stringResource(Res.string.library_sort_state, layout.sort.label(), direction)
        SortIconButton(layout.sort.icon(), stringResource(Res.string.library_collection_order), current) { expanded = true }
        DropdownMenu(expanded, { expanded = false }) {
            val choices = listOf(CollectionSort.NAME, CollectionSort.SONG_COUNT, CollectionSort.RECENT) +
                when (tab) {
                    LibraryTab.ALBUMS -> listOf(CollectionSort.ARTIST, CollectionSort.YEAR)
                    LibraryTab.PLAYLISTS -> listOf(CollectionSort.CUSTOM)
                    else -> emptyList()
                }
            choices.forEach { sort ->
                AppMenuItem(text = { Text(sort.label()) }, leadingIcon = { Icon(sort.icon().vector, null) },
                    trailingIcon = { if (sort == layout.sort) Icon(AppIcon.CHECK.vector, null) },
                    modifier = Modifier.semantics { selected = sort == layout.sort }, onClick = {
                        update { it.copy(sort = sort, descending = sort in listOf(CollectionSort.SONG_COUNT, CollectionSort.RECENT)) }
                        expanded = false
                    })
            }
            if (layout.sort != CollectionSort.CUSTOM) {
                HorizontalDivider()
                val label = direction
                AppMenuItem(text = { Text(label) }, leadingIcon = { Icon((if (layout.descending) AppIcon.DESCENDING else AppIcon.ASCENDING).vector, null) },
                    modifier = Modifier.semantics { contentDescription = label },
                    onClick = { update { it.copy(descending = !it.descending) }; expanded = false })
            }
            if (rearrange != null) {
                HorizontalDivider()
                AppMenuItem(text = { Text(stringResource(Res.string.library_rearrange)) }, leadingIcon = { Icon(AppIcon.DRAG.vector, null) }, onClick = {
                    update { it.copy(sort = CollectionSort.CUSTOM, descending = false) }
                    rearrange(); expanded = false
                })
            }
        }
    }
}
