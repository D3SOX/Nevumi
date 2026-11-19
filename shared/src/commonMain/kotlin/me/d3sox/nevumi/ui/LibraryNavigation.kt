package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import me.d3sox.nevumi.library.LibraryTab
import org.jetbrains.compose.resources.stringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.more
import me.d3sox.nevumi.resources.sources_recent

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

    /** The widest label among [tabs] and, with [more], More, so a navigation rail can fit each of them. */
    fun widest(tabs: List<LibraryTab>, more: Boolean): Float =
        maxOf(if (more) moreWidth else 0f, tabs.maxOfOrNull { widths.getValue(it) } ?: 0f)
}

@Composable
internal fun rememberLibraryNavigationSizing(): LibraryNavigationSizing {
    val minimumWidth = with(LocalDensity.current) { (72.dp * fontScale.coerceAtLeast(1f)).toPx() }
    val padding = with(LocalDensity.current) { 8.dp.toPx() }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val labels = LibraryTab.entries.associateWith { if (it == LibraryTab.RECENT) stringResource(Res.string.sources_recent) else stringResource(it.label) }
    val more = stringResource(Res.string.more)
    return remember(labels, more, measurer, labelStyle, minimumWidth, padding) {
        fun itemWidth(label: String) = maxOf(minimumWidth,
            label.split(' ').maxOf { measurer.measure(it, labelStyle).size.width } + padding)
        LibraryNavigationSizing(labels.mapValues { itemWidth(it.value) }, itemWidth(more))
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
            val pageLabel = stringResource(page.label)
            NavigationBarItem(selected = current == page, onClick = { select(page) }, colors = itemColors,
                modifier = Modifier.semantics { contentDescription = pageLabel },
                icon = { Icon(page.icon().vector, null) }, label = { Text(if (page == LibraryTab.RECENT) stringResource(Res.string.sources_recent) else pageLabel, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) })
        } }
        if (overflow.isNotEmpty()) NavigationBarItem(selected = current !in primary, onClick = { more = true }, colors = itemColors,
            icon = { Icon(AppIcon.MORE_PAGES.vector, null) }, label = { Text(stringResource(Res.string.more)) })
    }
    if (more && overflow.isNotEmpty()) MorePagesSheet(overflow, current, { more = false }, select)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MorePagesSheet(overflow: List<LibraryTab>, current: LibraryTab, dismiss: () -> Unit, select: (LibraryTab) -> Unit) {
    AppBottomSheet(onDismissRequest = dismiss) {
        LazyColumn(Modifier.fillMaxWidth().testTag("library-more-pages")) {
            items(overflow, key = { it }) { page ->
                ListItem(modifier = Modifier.clickable { dismiss(); select(page) }.semantics { selected = page == current },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(page.label)) }, leadingContent = { Icon(page.icon().vector, null) },
                    trailingContent = { if (page == current) Icon(AppIcon.CHECK.vector, null) })
            }
        }
    }
}

/**
 * The side navigation for medium and wider windows. It shows the tabs that fit by height, the ones chosen for the bottom bar
 * first and then those in More, and lists the rest in a menu beside its More item.
 */
@Composable
internal fun LibraryNavigationRail(tabs: List<LibraryTab>, current: LibraryTab, modifier: Modifier = Modifier,
    bottomNavigationTabs: Set<LibraryTab>? = null, select: (LibraryTab) -> Unit) {
    var more by remember { mutableStateOf(false) }
    // A rail has room for more than a bottom bar, so it continues with the More tabs, in the order the tab editor lists them.
    val candidates = tabs.partition { bottomNavigationTabs == null || it in bottomNavigationTabs }.let { (chosen, rest) -> chosen + rest }
    // Measuring decides how many fit; the More menu then lists the rest.
    var fitting by remember { mutableIntStateOf(candidates.size) }
    val primary = candidates.take(fitting)
    val overflow = tabs.filterNot { it in primary }
    LaunchedEffect(overflow.isEmpty()) { if (overflow.isEmpty()) more = false }
    val sizing = rememberLibraryNavigationSizing()
    // Only the labels shown widen the rail; fewer fitting items can only narrow it, so measuring settles.
    val width = with(LocalDensity.current) { sizing.widest(primary, overflow.isNotEmpty()).toDp() }.coerceAtLeast(80.dp)
    val itemColors = NavigationRailItemDefaults.colors(
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.primary,
    )
    val label: @Composable (String) -> Unit = { text -> Text(text, maxLines = 2, textAlign = TextAlign.Center) }
    NavigationRail(modifier.width(width).fillMaxHeight().testTag("library-navigation"), windowInsets = WindowInsets(0)) {
        SubcomposeLayout(Modifier.weight(1f).fillMaxWidth()) { constraints ->
            val spacing = 4.dp.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
            val railItem: @Composable (LibraryTab?, Modifier) -> Unit = { page, itemModifier ->
                if (page == null) NavigationRailItem(selected = current !in primary, onClick = { more = true }, colors = itemColors,
                    modifier = itemModifier, icon = { Icon(AppIcon.MORE_PAGES.vector, null) }, label = { label(stringResource(Res.string.more)) })
                else {
                    val pageLabel = stringResource(page.label)
                    NavigationRailItem(selected = current == page, onClick = { select(page) }, colors = itemColors,
                        modifier = itemModifier.semantics { contentDescription = pageLabel },
                        icon = { Icon(page.icon().vector, null) },
                        label = { label(if (page == LibraryTab.RECENT) stringResource(Res.string.sources_recent) else pageLabel) })
                }
            }
            // Measuring copies stay unplaced, so they must not appear to accessibility services and tests.
            fun measure(page: LibraryTab?) = subcompose("measure:$page") { railItem(page, Modifier.clearAndSetSemantics {}) }.single().measure(loose).height
            val heights = candidates.map(::measure)
            val moreHeight = measure(null)
            val count = (candidates.size downTo 0).first { count ->
                val shown = heights.take(count).sum() + spacing * (count - 1).coerceAtLeast(0)
                count == 0 || shown + (if (count < tabs.size) spacing + moreHeight else 0) <= constraints.maxHeight
            }
            if (count != fitting) fitting = count
            val railWidth = constraints.maxWidth.toDp()
            val items = candidates.take(count).map { page -> subcompose(page) { railItem(page, Modifier) }.single().measure(loose) } +
                if (count < tabs.size) listOf(subcompose("more") {
                    Box(Modifier.width(railWidth)) {
                        railItem(null, Modifier.align(Alignment.TopCenter))
                        RailMorePagesMenu(more, overflow, current, railWidth, Modifier.align(Alignment.BottomStart), { more = false }, select)
                    }
                }.single().measure(loose)) else emptyList()
            layout(constraints.maxWidth, constraints.maxHeight) {
                var y = 0
                items.forEach { item ->
                    item.place((constraints.maxWidth - item.width) / 2, y)
                    y += item.height + spacing
                }
            }
        }
    }
}

/**
 * The pages that don't fit the rail, in a menu beside it so the pointer barely has to move. More ends a full rail, so the
 * menu usually opens upwards, and anchoring it at More's bottom keeps it level with More.
 */
@Composable
private fun RailMorePagesMenu(expanded: Boolean, overflow: List<LibraryTab>, current: LibraryTab, railWidth: Dp,
    modifier: Modifier, dismiss: () -> Unit, select: (LibraryTab) -> Unit) = Box(modifier) {
    DropdownMenu(expanded && overflow.isNotEmpty(), dismiss, Modifier.testTag("library-more-pages"), offset = DpOffset(railWidth, 0.dp)) {
        overflow.forEach { page ->
            AppMenuItem({ Text(stringResource(page.label)) }, { dismiss(); select(page) },
                Modifier.semantics { selected = page == current }, leadingIcon = { Icon(page.icon().vector, null) },
                trailingIcon = if (page == current) {{ Icon(AppIcon.CHECK.vector, null) }} else null)
        }
    }
}
