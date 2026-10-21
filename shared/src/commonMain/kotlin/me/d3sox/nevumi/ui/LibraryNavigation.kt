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

internal class LibraryNavigationSizing(private val widths: Map<LibraryTab, Float>, private val moreWidth: Float) {
    fun primary(tabs: List<LibraryTab>, preferred: Set<LibraryTab>?, width: Int): List<LibraryTab> {
        val candidates = tabs.filter { preferred == null || it in preferred }
        val count = (candidates.size downTo 0).firstOrNull { count ->
            val overflow = count < tabs.size
            val itemWidth = maxOf(candidates.take(count).maxOfOrNull { widths.getValue(it) } ?: 0f,
                if (overflow) moreWidth else 0f)
            width >= (count + if (overflow) 1 else 0) * itemWidth
        } ?: 0
        return candidates.take(count)
    }
}

@Composable
internal fun rememberLibraryNavigationSizing(): LibraryNavigationSizing {
    val minimumWidth = with(LocalDensity.current) { (72.dp * fontScale.coerceAtLeast(1f)).toPx() }
    val padding = with(LocalDensity.current) { 8.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    return remember(measurer, labelStyle, minimumWidth, padding) {
        fun itemWidth(label: String) = maxOf(minimumWidth,
            label.split(' ').maxOf { measurer.measure(it, labelStyle).size.width } + padding)
        LibraryNavigationSizing(LibraryTab.entries.associateWith {
            itemWidth(if (it == LibraryTab.RECENT) "Recent" else it.label)
        }, itemWidth("More"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryNavigation(tabs: List<LibraryTab>, current: LibraryTab, modifier: Modifier = Modifier,
    bottomNavigationTabs: Set<LibraryTab>? = null, select: (LibraryTab) -> Unit) {
    var more by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val sizing = rememberLibraryNavigationSizing()
    val primary = sizing.primary(tabs, bottomNavigationTabs, width)
    val overflow = tabs.filterNot { it in primary }
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
