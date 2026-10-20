package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab

@Composable
internal fun LibraryTabsDialog(initial: List<LibraryTab>, controller: MusicController, dismiss: () -> Unit) {
    var tabs by remember { mutableStateOf(initial) }
    val ordered = tabs + LibraryTab.entries.filterNot { it in tabs }
    fun move(tab: LibraryTab, offset: Int) {
        val index = tabs.indexOf(tab)
        tabs = tabs.toMutableList().apply { add(index + offset, removeAt(index)) }
    }
    val scroll = rememberLazyListState()
    val reorderable = rememberReorderableLazyListState(scroll) { from, to ->
        val source = tabs.indexOfFirst { it.name == from.key }
        val destination = tabs.indexOfFirst { it.name == to.key }
        if (source >= 0 && destination >= 0) tabs = tabs.toMutableList().apply { add(destination, removeAt(source)) }
    }
    SettingsScreen(onDismissRequest = dismiss, title = { Text("Library tabs") }, text = {
        LazyColumn(state = scroll, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(ordered, key = { it.name }) { tab ->
                ReorderableItem(reorderable, tab.name) { _ ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(tab.icon().vector, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Checkbox(tab in tabs, enabled = tabs.size > 1 || tab !in tabs, onCheckedChange = { visible ->
                        tabs = if (visible) tabs + tab else tabs - tab
                    }, modifier = Modifier.semantics { contentDescription = "Show ${tab.label}" })
                    Text(tab.label, Modifier.weight(1f))
                    if (tab in tabs) {
                        Box(Modifier.size(48.dp).draggableHandle().semantics {
                            contentDescription = "Reorder tab: ${tab.label}"
                            customActions = listOf(CustomAccessibilityAction("Move up") {
                                if (tabs.indexOf(tab) > 0) { move(tab, -1); true } else false
                            }, CustomAccessibilityAction("Move down") {
                                if (tabs.indexOf(tab) < tabs.lastIndex) { move(tab, 1); true } else false
                            })
                        }, contentAlignment = Alignment.Center) { Icon(AppIcon.DRAG.vector, null) }
                    }
                }
                }
            }
            }
        }
    }, actions = { ActionButton("Reset", AppIcon.RESET, style = ActionStyle.OUTLINED) {
        tabs = me.d3sox.nevumi.settings.MusicSettings().libraryTabs
    } }, confirmButton = { PageAction("Save tabs", onClick = { controller.updatePreferences { it.copy(libraryTabs = tabs, startupTab = it.startupTab?.takeIf { tab -> tab in tabs }) }; dismiss() }) },
        dismissButton = { PageAction("Cancel", AppIcon.CLOSE, secondary = true, onClick = dismiss) })
}
