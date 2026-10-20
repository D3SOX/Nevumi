package me.d3sox.nevumi.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.LibraryPosition
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

internal val LocalLibraryScrollKey = staticCompositionLocalOf { "library" }

@Composable
internal fun rememberLibraryListState(controller: MusicController, suffix: String): LazyListState {
    val key = "${LocalLibraryScrollKey.current}:$suffix"
    val appState by controller.state.collectAsState()
    val enabled = appState.preferences.rememberScrollPositions
    val initial = remember(key, enabled) { if (enabled) controller.state.value.preferences.libraryPositions[key] ?: LibraryPosition() else LibraryPosition() }
    val state = key(key, enabled) {
        if (enabled) rememberLazyListState(initial.index, initial.offset) else remember { LazyListState() }
    }
    if (enabled) RememberPosition(controller, key, { state.isScrollInProgress }) { LibraryPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    return state
}

@Composable
internal fun rememberLibraryGridState(controller: MusicController, suffix: String): LazyGridState {
    val key = "${LocalLibraryScrollKey.current}:$suffix"
    val appState by controller.state.collectAsState()
    val enabled = appState.preferences.rememberScrollPositions
    val initial = remember(key, enabled) { if (enabled) controller.state.value.preferences.libraryPositions[key] ?: LibraryPosition() else LibraryPosition() }
    val state = key(key, enabled) {
        if (enabled) rememberLazyGridState(initial.index, initial.offset) else remember { LazyGridState() }
    }
    if (enabled) RememberPosition(controller, key, { state.isScrollInProgress }) { LibraryPosition(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    return state
}

@Composable
private fun RememberPosition(controller: MusicController, key: String, scrolling: () -> Boolean, position: () -> LibraryPosition) {
    key(controller, key) {
    val latest by rememberUpdatedState(position)
    val moving by rememberUpdatedState(scrolling)
    LaunchedEffect(controller, key) {
        snapshotFlow { if (moving()) null else latest() }.filterNotNull().distinctUntilChanged().collect { controller.rememberLibraryPosition(key, it).join() }
    }
    OnLibraryStopped { controller.rememberLibraryPosition(key, latest()) }
    DisposableEffect(controller, key) {
        onDispose { controller.rememberLibraryPosition(key, latest()) }
    }
    }
}

@Composable internal expect fun OnLibraryStopped(onStop: () -> Unit)
