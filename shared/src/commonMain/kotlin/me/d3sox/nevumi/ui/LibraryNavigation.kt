package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import me.d3sox.nevumi.library.LibraryTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryNavigation(tabs: List<LibraryTab>, current: LibraryTab, modifier: Modifier = Modifier, select: (LibraryTab) -> Unit) {
    var more by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val minimumWidth = with(LocalDensity.current) { (72.dp * fontScale.coerceAtLeast(1f)).toPx() }
    val padding = with(LocalDensity.current) { 8.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val itemWidths = remember(tabs, measurer, labelStyle, minimumWidth, padding) {
        tabs.map { page ->
            val label = if (page == LibraryTab.RECENT) "Recent" else page.label
            maxOf(minimumWidth, label.split(' ').maxOf { measurer.measure(it, labelStyle).size.width } + padding)
        }
    }
    val capacity = ((width / minimumWidth).toInt().coerceAtMost(tabs.size).coerceAtLeast(2) downTo 2)
        .firstOrNull { count ->
            val directCount = if (tabs.size <= count) tabs.size else count - 1
            width >= count * (itemWidths.take(directCount).maxOrNull() ?: minimumWidth)
        } ?: 2
    val primary = if (tabs.size <= capacity) tabs else tabs.take(capacity - 1)
    val overflow = tabs.drop(primary.size)
    LaunchedEffect(overflow.isEmpty()) { if (overflow.isEmpty()) more = false }
    val itemColors = NavigationBarItemDefaults.colors(
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.primary,
    )
    NavigationBar(modifier.fillMaxWidth().onSizeChanged { width = it.width }.testTag("library-navigation"), windowInsets = WindowInsets(0)) {
        primary.forEach { page -> key(page) {
            NavigationBarItem(selected = current == page, onClick = { select(page) }, colors = itemColors,
                modifier = Modifier.semantics { contentDescription = page.label },
                icon = { Icon(page.icon().vector, null) }, label = { Text(if (page == LibraryTab.RECENT) "Recent" else page.label, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) })
        } }
        if (overflow.isNotEmpty()) NavigationBarItem(selected = current !in primary, onClick = { more = true }, colors = itemColors,
            icon = { Icon(AppIcon.MORE_PAGES.vector, null) }, label = { Text("More") })
    }
    if (more && overflow.isNotEmpty()) AppBottomSheet(onDismissRequest = { more = false }) {
        LazyColumn(Modifier.fillMaxWidth().testTag("library-more-pages")) {
            items(overflow, key = { it }) { page ->
                ListItem(modifier = Modifier.clickable { more = false; select(page) }.semantics { selected = page == current },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(page.label) }, leadingContent = { Icon(page.icon().vector, null) },
                    trailingContent = { if (page == current) Icon(AppIcon.CHECK.vector, null) })
            }
        }
    }
}
