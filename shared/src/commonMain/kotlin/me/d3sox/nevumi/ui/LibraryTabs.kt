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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab

@Composable
internal fun LibraryTabsDialog(initial: List<LibraryTab>, controller: MusicController,
    initialBottomNavigationTabs: Set<LibraryTab>? = null, dismiss: () -> Unit) {
    LibraryTabsEditor(initial, initialBottomNavigationTabs, onSave = { tabs, bottomTabs ->
        controller.updatePreferences { it.copy(libraryTabs = tabs, bottomNavigationTabs = bottomTabs,
            startupTab = it.startupTab?.takeIf { tab -> tab in tabs }) }
        dismiss()
    }, dismiss = dismiss)
}

@Composable
internal fun LibraryTabsEditor(initial: List<LibraryTab>, initialBottomNavigationTabs: Set<LibraryTab>?,
    onSave: (List<LibraryTab>, Set<LibraryTab>?) -> Unit, dismiss: () -> Unit) {
    var tabs by remember { mutableStateOf(initial) }
    var bottomTabs by remember { mutableStateOf(initialBottomNavigationTabs) }
    var width by remember { mutableIntStateOf(0) }
    val sizing = rememberLibraryNavigationSizing()
    val primary = sizing.primary(tabs, bottomTabs, width)
    val overflow = tabs.filterNot { it in primary }
    val disabled = LibraryTab.entries.filterNot { it in tabs }
    fun group(tab: LibraryTab) = if (tab in primary) primary else tabs.filterNot { it in primary }
    fun move(tab: LibraryTab, offset: Int): Boolean {
        val section = group(tab)
        val destination = section.indexOf(tab) + offset
        if (destination !in section.indices) return false
        val index = tabs.indexOf(tab)
        val target = tabs.indexOf(section[destination])
        tabs = tabs.toMutableList().apply { add(target, removeAt(index)) }
        return true
    }
    val scroll = rememberLazyListState()
    val currentPrimary by rememberUpdatedState(primary)
    fun slot(tab: LibraryTab) = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tab.name }?.offset?.toFloat()
    // A dragged tab stops at the first and last rows of its section; an edge outside the viewport doesn't limit it.
    fun edge(tab: LibraryTab, last: Boolean) = tabs.filter { (it in currentPrimary) == (tab in currentPrimary) }
        .let { slot(if (last) it.last() else it.first()) }
    val reorderable = rememberReorderableLazyListState(scroll) { from, to ->
        val source = tabs.indexOfFirst { it.name == from.key }
        val destination = tabs.indexOfFirst { it.name == to.key }
        if (source >= 0 && destination >= 0 && (tabs[source] in primary) == (tabs[destination] in primary)) {
            tabs = tabs.toMutableList().apply { add(destination, removeAt(source)) }
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width }) {
        SettingsScreen(onDismissRequest = dismiss, title = { Text("Library tabs") }, text = {
            LazyColumn(Modifier.testTag("library-tabs-editor"), state = scroll, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item("help") {
                    Text("Use + and − to choose bottom navigation tabs. Drag to reorder within each section. Uncheck a tab to hide it entirely.",
                        Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                listOf("Bottom navigation" to primary, "More" to overflow, "Disabled" to disabled).forEach { (title, section) ->
                    item(title) {
                        Text(title, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
                        if (title == "Bottom navigation") Text(
                            "${primary.size} tabs shown. Available space depends on screen width and text size.",
                            Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (title == "More" || title == "Disabled") Text(
                            if (title == "More") "Open these tabs from More in the bottom navigation."
                            else "Hidden from navigation. Check a tab to enable it.",
                            Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(section, key = { it.name }) { tab ->
                        val boundary = remember(tab, scroll) { DragBoundary({ slot(tab) ?: 0f },
                            { edge(tab, last = false) ?: Float.NEGATIVE_INFINITY }, { edge(tab, last = true) ?: Float.POSITIVE_INFINITY }) }
                        ReorderableItem(reorderable, tab.name) { _ ->
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(tab.icon().vector, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Checkbox(tab in tabs, enabled = tabs.size > 1 || tab !in tabs, onCheckedChange = { visible ->
                                        bottomTabs = bottomTabs?.minus(tab)
                                        tabs = if (visible) tabs + tab else tabs - tab
                                    }, modifier = Modifier.semantics { contentDescription = "Show ${tab.label}" })
                                    Text(tab.label, Modifier.weight(1f))
                                    val candidateTabs = if (tab in tabs) tabs else tabs + tab
                                    val configuredBottom = bottomTabs ?: primary.toSet()
                                    val isConfigured = tab in configuredBottom
                                    val candidateBottom = configuredBottom + tab
                                    val canAdd = sizing.primary(candidateTabs, candidateBottom, width).size == candidateBottom.size
                                    IconButton(onClick = {
                                        bottomTabs = if (isConfigured) configuredBottom - tab else candidateBottom
                                        if (tab !in tabs) tabs = tabs + tab
                                    }, enabled = isConfigured || canAdd) {
                                        Icon(if (isConfigured) AppIcon.MINUS.vector else AppIcon.PLUS.vector,
                                            when {
                                                tab in primary -> "Move ${tab.label} to More"
                                                isConfigured -> "Remove ${tab.label} from bottom navigation"
                                                else -> "Add ${tab.label} to bottom navigation"
                                            })
                                    }
                                    if (tab in tabs) {
                                        Box(Modifier.size(48.dp).draggableHandle(dragGestureDetector = boundary).semantics {
                                            contentDescription = "Reorder tab: ${tab.label}"
                                            customActions = listOf(CustomAccessibilityAction("Move up") { move(tab, -1) },
                                                CustomAccessibilityAction("Move down") { move(tab, 1) })
                                        }, contentAlignment = Alignment.Center) { Icon(AppIcon.DRAG.vector, null) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, actions = { ActionButton("Reset", AppIcon.RESET, style = ActionStyle.OUTLINED) {
            tabs = me.d3sox.nevumi.settings.MusicSettings().libraryTabs
            bottomTabs = null
        } }, confirmButton = { PageAction("Save tabs", onClick = { onSave(tabs, bottomTabs) }) },
            dismissButton = { PageAction("Cancel", AppIcon.CLOSE, secondary = true, onClick = dismiss) })
    }
}
