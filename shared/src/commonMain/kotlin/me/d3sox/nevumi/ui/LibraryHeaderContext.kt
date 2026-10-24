package me.d3sox.nevumi.ui

import androidx.compose.runtime.*
import me.d3sox.nevumi.library.LibraryTab

internal data class LibraryHeaderContext(val title: String?, val songCount: Int?)

internal val LocalLibraryHeaders = compositionLocalOf<MutableMap<LibraryTab, LibraryHeaderContext>?> { null }

/** Only the active navigation page contributes to the library header during transitions. */
@Composable
internal fun ReportLibraryHeader(title: String? = null, songCount: Int? = null) {
    val headers = LocalLibraryHeaders.current
    val tab = LocalLibraryOrigin.current.tab
    val active = LocalNavigationActive.current
    val context = remember(title, songCount) { LibraryHeaderContext(title, songCount) }
    DisposableEffect(headers, tab, active, context) {
        if (active) headers?.set(tab, context)
        onDispose { if (active && headers?.get(tab) === context) headers.remove(tab) }
    }
}
