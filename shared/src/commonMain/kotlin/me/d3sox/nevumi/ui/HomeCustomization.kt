package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.HomeSectionId
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import org.jetbrains.compose.resources.stringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.customize_home
import me.d3sox.nevumi.resources.settings_home_description
import me.d3sox.nevumi.resources.settings_move_section_down
import me.d3sox.nevumi.resources.settings_move_section_up
import me.d3sox.nevumi.resources.settings_reorder_home_section
import me.d3sox.nevumi.resources.settings_reset
import me.d3sox.nevumi.resources.settings_save_order
import me.d3sox.nevumi.resources.settings_show_home_section

@Composable
internal fun HomeCustomization(initial: List<HomeSectionId>, controller: MusicController, dismiss: () -> Unit) {
    var sections by remember { mutableStateOf(initial) }
    var disabled by remember { mutableStateOf(controller.state.value.preferences.disabledHomeSections) }
    val scroll = rememberLazyListState()
    fun move(from: Int, to: Int) {
        if (from in sections.indices && to in sections.indices) sections = sections.toMutableList().apply { add(to, removeAt(from)) }
    }
    val reorderable = rememberReorderableLazyListState(scroll) { from, to ->
        move(sections.indexOfFirst { it.name == from.key }, sections.indexOfFirst { it.name == to.key })
    }
    SettingsScreen(dismiss, title = { Text(stringResource(Res.string.customize_home)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.settings_home_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).testTag("home-section-order"), state = scroll,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sections, key = { it.name }) { section ->
                    ReorderableItem(reorderable, section.name) { _ ->
                        val sectionLabel = stringResource(section.label)
                        val showDescription = stringResource(Res.string.settings_show_home_section, sectionLabel)
                        val reorderDescription = stringResource(Res.string.settings_reorder_home_section, sectionLabel)
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(section.icon().vector, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(sectionLabel, Modifier.weight(1f).padding(horizontal = 12.dp))
                                Switch(section !in disabled, { enabled ->
                                    disabled = if (enabled) disabled - section else disabled + section
                                }, Modifier.semantics { contentDescription = showDescription })
                            }
                            Row(Modifier.fillMaxWidth().padding(end = 4.dp), horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically) {
                                AppIconButton(AppIcon.UP, stringResource(Res.string.settings_move_section_up, sectionLabel), enabled = sections.first() != section) {
                                    move(sections.indexOf(section), sections.indexOf(section) - 1)
                                }
                                AppIconButton(AppIcon.DOWN, stringResource(Res.string.settings_move_section_down, sectionLabel), enabled = sections.last() != section) {
                                    move(sections.indexOf(section), sections.indexOf(section) + 1)
                                }
                                Box(Modifier.size(48.dp).draggableHandle().testTag("home-reorder:${section.name}")
                                    .semantics { contentDescription = reorderDescription }, contentAlignment = Alignment.Center) {
                                    Icon(AppIcon.DRAG.vector, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
    }, actions = { ActionButton(stringResource(Res.string.settings_reset), AppIcon.RESET, style = ActionStyle.OUTLINED) { sections = HomeSectionId.entries; disabled = emptySet() } },
        confirmButton = { PageAction(stringResource(Res.string.settings_save_order), onClick = {
            controller.updatePreferences { it.copy(homeSections = sections, disabledHomeSections = disabled) }; dismiss()
        }) },
        dismissButton = { PageAction(stringResource(Res.string.cancel), AppIcon.CLOSE, secondary = true, onClick = dismiss) })
}

private fun HomeSectionId.icon(): AppIcon = when (this) {
    HomeSectionId.MIXES, HomeSectionId.REDISCOVER -> AppIcon.SHUFFLE
    HomeSectionId.RECENT -> AppIcon.HISTORY
    HomeSectionId.TOP -> AppIcon.STATISTICS
    HomeSectionId.RECENT_ALBUMS, HomeSectionId.TOP_ALBUMS -> AppIcon.ALBUM
    HomeSectionId.RECENT_ARTISTS, HomeSectionId.TOP_ARTISTS -> AppIcon.ARTIST
    HomeSectionId.PLAYLISTS, HomeSectionId.SAVED_QUEUES -> AppIcon.QUEUE
    HomeSectionId.ADDED -> AppIcon.RECENT
}
