package me.d3sox.nevumi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.history.ListeningStatistics
import me.d3sox.nevumi.library.LibrarySort
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.recentAt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.library_never_played
import me.d3sox.nevumi.resources.plays
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.stringResource

internal fun alphabetIndicator(text: String): String = text.trim().firstOrNull()?.uppercaseChar()?.let {
    if (it.isLetter()) it.toString() else "#"
} ?: "#"

internal expect fun formatScrollDate(epochMillis: Long): String

/** Text the scrollbar shows for listening statistics, resolved in composition because the scrollbar reads labels outside it. */
internal class ScrollStatisticsLabels(val plays: Map<Int, String>, val neverPlayed: String)

@Composable
internal fun rememberScrollStatisticsLabels(statistics: Map<String, ListeningStatistics>?): ScrollStatisticsLabels {
    val neverPlayed = stringResource(Res.string.library_never_played)
    val plays by produceState(emptyMap<Int, String>(), statistics) {
        value = (statistics?.values?.map { it.playCount }.orEmpty() + 0).distinct().associateWith { getPluralString(Res.plurals.plays, it, it) }
    }
    return ScrollStatisticsLabels(plays, neverPlayed)
}

internal fun Track.scrollLabel(sort: LibrarySort, statistics: Map<String, ListeningStatistics>?, labels: ScrollStatisticsLabels): String = when (sort) {
    LibrarySort.TITLE -> alphabetIndicator(metadata.title)
    LibrarySort.ARTIST -> alphabetIndicator(metadata.artist)
    LibrarySort.ALBUM -> alphabetIndicator(metadata.album)
    LibrarySort.RECENT -> formatScrollDate(recentAt)
    LibrarySort.RATING -> "$rating ★"
    LibrarySort.PLAY_COUNT -> (statistics?.get(id)?.playCount ?: 0).let { labels.plays[it] ?: "$it" }
    LibrarySort.LAST_PLAYED -> statistics?.get(id)?.lastPlayedAt?.let(::formatScrollDate) ?: labels.neverPlayed
}

@Composable
internal fun BoxScope.LibraryScrollbar(state: LazyListState, count: Int, description: String,
    headerItems: Int = 0, label: (Int) -> String) {
    FastScrollbar(count, (state.firstVisibleItemIndex - headerItems).coerceAtLeast(0),
        state.layoutInfo.visibleItemsInfo.size, state.canScrollForward || state.canScrollBackward,
        description, { state.scrollToItem(it + headerItems) }, label)
}

@Composable
internal fun BoxScope.LibraryScrollbar(state: LazyGridState, count: Int, description: String, label: (Int) -> String) {
    FastScrollbar(count, state.firstVisibleItemIndex, state.layoutInfo.visibleItemsInfo.size,
        state.canScrollForward || state.canScrollBackward, description, { state.scrollToItem(it) }, label)
}

@Composable
private fun BoxScope.FastScrollbar(count: Int, first: Int, visible: Int, scrollable: Boolean, description: String,
    scrollTo: suspend (Int) -> Unit, label: (Int) -> String) {
    if (count < 2 || !scrollable) return
    val scope = rememberCoroutineScope()
    val scroll by rememberUpdatedState(scrollTo)
    val describe by rememberUpdatedState(label)
    // Semantics rerun as soon as the label changes, before this lambda is replaced, so read the size and position
    // from the same snapshot instead of this composition's values. Otherwise a shrinking list indexes past its end.
    val size by rememberUpdatedState(count)
    val position by rememberUpdatedState(first)
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var target by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(0) }
    val thumb = with(LocalDensity.current) { 48.dp.toPx() }
    val travel = (height - thumb).coerceAtLeast(1f)
    val fraction = if (dragging) ((cursor - thumb / 2) / travel).coerceIn(0f, 1f)
        else (first.toFloat() / (count - visible.coerceAtLeast(1)).coerceAtLeast(1)).coerceIn(0f, 1f)
    fun move(y: Float) {
        cursor = y
        val index = (((y - thumb / 2) / travel).coerceIn(0f, 1f) * (count - 1)).roundToInt()
        if (index != target || !dragging) {
            target = index
            scrollJob?.cancel()
            scrollJob = scope.launch { scroll(index) }
        }
    }
    Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(16.dp).onSizeChanged { height = it.height }
        .semantics {
            contentDescription = description
            stateDescription = describe(position.coerceIn(0, size - 1))
            progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..(size - 1).toFloat())
            setProgress { value -> scope.launch { scroll(value.roundToInt().coerceIn(0, size - 1)) }; true }
        }.pointerInput(count) { detectTapGestures { move(it.y) } }.pointerInput(count) {
            detectVerticalDragGestures(onDragStart = { move(it.y); dragging = true },
                onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                onVerticalDrag = { change, _ -> change.consume(); move(change.position.y) })
        }) {
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight(.98f)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .12f), RoundedCornerShape(1.dp)))
        Box(Modifier.align(Alignment.TopCenter).offset { IntOffset(0, (fraction * travel).roundToInt()) }
            .width(if (dragging) 8.dp else 5.dp).height(48.dp)
            .background(if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f), RoundedCornerShape(4.dp)))
    }
    if (dragging) Surface(Modifier.align(Alignment.TopEnd).padding(end = 24.dp)
        .offset { IntOffset(0, (fraction * travel).roundToInt()) }, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Text(describe(target.coerceIn(0, count - 1)), Modifier.testTag("fast-scroll-label").padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleMedium)
    }
}
