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

internal fun CollectionSort.label() = when (this) {
    CollectionSort.NAME -> "Name"
    CollectionSort.ARTIST -> "Artist"
    CollectionSort.YEAR -> "Year"
    CollectionSort.SONG_COUNT -> "Song count"
    CollectionSort.RECENT -> "Recently added music"
    CollectionSort.CUSTOM -> "Custom order"
}

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
        val current = if (layout.sort == CollectionSort.CUSTOM) layout.sort.label()
            else "${layout.sort.label()}, ${if (layout.descending) "Descending" else "Ascending"}"
        SortIconButton(layout.sort.icon(), "Collection order", current) { expanded = true }
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
                val label = if (layout.descending) "Descending" else "Ascending"
                AppMenuItem(text = { Text(label) }, leadingIcon = { Icon((if (layout.descending) AppIcon.DESCENDING else AppIcon.ASCENDING).vector, null) },
                    modifier = Modifier.semantics { contentDescription = label },
                    onClick = { update { it.copy(descending = !it.descending) }; expanded = false })
            }
            if (rearrange != null) {
                HorizontalDivider()
                AppMenuItem(text = { Text("Rearrange") }, leadingIcon = { Icon(AppIcon.DRAG.vector, null) }, onClick = {
                    update { it.copy(sort = CollectionSort.CUSTOM, descending = false) }
                    rearrange(); expanded = false
                })
            }
        }
    }
}
