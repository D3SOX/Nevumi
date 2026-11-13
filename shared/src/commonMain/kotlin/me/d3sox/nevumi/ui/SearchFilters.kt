package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.library
import me.d3sox.nevumi.resources.more
import org.jetbrains.compose.resources.stringResource

/** Where a search looks: the library, or SoundCloud or YouTube, which [SearchType] names for their results. */
internal enum class SearchSource(val type: SearchType?, val icon: AppIcon) {
    LIBRARY(null, AppIcon.LIBRARY), YOUTUBE(SearchType.YOUTUBE, AppIcon.YOUTUBE), SOUNDCLOUD(SearchType.SOUNDCLOUD, AppIcon.CLOUD)
}

/**
 * Picks the source to search; it shows only when there is more than the library to pick from. Each source shows its icon and name,
 * or on a narrow screen only its name, or only its icon.
 */
@Composable
internal fun SearchSourcePicker(sources: List<SearchSource>, selected: SearchSource, modifier: Modifier = Modifier, select: (SearchSource) -> Unit) {
    if (sources.size < 2) return
    val labels = sources.map { stringResource(it.type?.label ?: Res.string.library) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // The buttons share the width equally, so the longest name decides, leaving room for the button's padding.
        val text = with(density) { labels.maxOf { measurer.measure(it, style).size.width }.toDp() }
        val each = maxWidth / sources.size - 16.dp
        val icons = text + SegmentedButtonDefaults.IconSize + 8.dp <= each
        val names = icons || text <= each
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sources.forEachIndexed { index, source ->
                SegmentedButton(source == selected, { select(source) }, SegmentedButtonDefaults.itemShape(index, sources.size),
                    Modifier.testTag("search-source:${source.name}").semantics { contentDescription = labels[index] },
                    // Each source keeps its own icon; the filled button marks the chosen one.
                    icon = { if (icons) Icon(source.icon.vector, null, Modifier.size(SegmentedButtonDefaults.IconSize)) },
                    label = {
                        if (names) Text(labels[index], maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Icon(source.icon.vector, null, Modifier.size(SegmentedButtonDefaults.IconSize))
                    })
            }
        }
    }
}

/**
 * Filter chips in one row without scrolling: the chips that fit, then a More chip with the rest.
 * A chosen chip from the menu takes the last place in the row, so the chosen one always shows.
 */
@Composable
internal fun <T> OverflowChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, tag: (T) -> String, modifier: Modifier = Modifier,
    select: (T) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val labels = options.map { label(it) }
    val moreLabel = stringResource(Res.string.more)
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val spacing = 8.dp.roundToPx()
        // Measured unseen, only to learn how many chips fit.
        val widths = options.indices.map { index ->
            subcompose("measure:$index") { FilterChip(true, {}, label = { Text(labels[index]) }, modifier = Modifier.clearAndSetSemantics {}) }
                .single().measure(loose).width
        }
        val moreWidth = subcompose("measure:more") {
            FilterChip(false, {}, label = { Text(moreLabel) }, trailingIcon = { Icon(AppIcon.DOWN.vector, null) }, modifier = Modifier.clearAndSetSemantics {})
        }.single().measure(loose).width
        fun fits(chips: List<Int>, overflow: Boolean) =
            chips.sumOf { widths[it] } + spacing * chips.size + (if (overflow) moreWidth else -spacing) <= constraints.maxWidth
        val chosen = options.indexOf(selected)
        val shown = if (fits(options.indices.toList(), false)) options.indices.toList() else {
            // The leading chips that fit beside the chosen one and More.
            val others = (0 until options.size).filter { it != chosen }
            // Narrower than the chosen chip and More, the row still shows both rather than nothing.
            val leading = (others.size downTo 0).firstOrNull { count -> fits(others.take(count) + listOfNotNull(chosen.takeIf { it >= 0 }), true) } ?: 0
            (others.take(leading) + listOfNotNull(chosen.takeIf { it >= 0 })).sorted()
        }
        val hidden = options.indices - shown.toSet()
        val row = subcompose("row") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { index ->
                    FilterChip(options[index] == selected, { select(options[index]) }, label = { Text(labels[index]) },
                        modifier = Modifier.testTag(tag(options[index])))
                }
                if (hidden.isNotEmpty()) Box {
                    FilterChip(false, { menu = true }, label = { Text(moreLabel) }, trailingIcon = { Icon(AppIcon.DOWN.vector, null) },
                        modifier = Modifier.testTag("search-filters-more"))
                    DropdownMenu(menu, { menu = false }) {
                        hidden.forEach { index ->
                            AppMenuItem({ Text(labels[index]) }, { menu = false; select(options[index]) }, modifier = Modifier.testTag(tag(options[index])))
                        }
                    }
                }
            }
        }.single().measure(loose)
        layout(constraints.maxWidth, row.height) { row.place(0, 0) }
    }
}
