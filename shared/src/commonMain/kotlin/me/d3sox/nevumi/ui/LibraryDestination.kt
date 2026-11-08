package me.d3sox.nevumi.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.TrackMetadata

internal data class LibraryDestination(val tab: LibraryTab, val collectionId: String? = null, val revision: Int = 0, val trackId: String? = null)
internal val LocalLibraryNavigate = compositionLocalOf<(LibraryTab, String?) -> Unit> { { _, _ -> } }
internal val LocalLibraryOrigin = compositionLocalOf { me.d3sox.nevumi.queue.QueueOrigin() }
internal val LocalLibraryFocus = compositionLocalOf<LibraryDestination?> { null }
internal val LocalLibraryFocusHandled = compositionLocalOf<() -> Unit> { {} }
internal val LocalPlayingContextNavigate = compositionLocalOf<(me.d3sox.nevumi.queue.QueueOrigin, String) -> Unit> { { _, _ -> } }
internal fun albumCollectionId(metadata: TrackMetadata): String {
    val artist = metadata.albumArtist.ifBlank { metadata.artist }
    return "${metadata.album.length}:${metadata.album}$artist"
}

/** Emits a tab when its navigation item is tapped while it is already open. */
internal val LocalLibraryReselects = staticCompositionLocalOf<Flow<LibraryTab>> { emptyFlow() }

/** Returns the page to its top level when the user taps its tab again. */
@Composable
internal fun OnLibraryTabReselected(action: () -> Unit) {
    val reselects = LocalLibraryReselects.current
    val tab = LocalLibraryOrigin.current.tab
    val current by rememberUpdatedState(action)
    LaunchedEffect(reselects, tab) { reselects.collect { if (it == tab) current() } }
}
