package me.d3sox.nevumi.ui

import androidx.compose.runtime.*
import me.d3sox.nevumi.library.LibraryTab

/** [page] stays the same while one page is open, also when its title or song count changes. */
internal data class LibraryHeaderContext(val title: String?, val songCount: Int?, val onBack: (() -> Unit)?, val page: Any)

internal val LocalLibraryHeaders = compositionLocalOf<MutableMap<LibraryTab, LibraryHeaderContext>?> { null }

/** Only the active navigation page contributes to the library header during transitions. */
@Composable
internal fun ReportLibraryHeader(title: String? = null, songCount: Int? = null, onBack: (() -> Unit)? = null) {
    val headers = LocalLibraryHeaders.current
    val tab = LocalLibraryOrigin.current.tab
    val active = LocalNavigationActive.current
    val currentBack by rememberUpdatedState(onBack)
    val page = remember { Any() }
    val context = remember(title, songCount, onBack != null) {
        LibraryHeaderContext(title, songCount, if (onBack == null) null else ({ currentBack?.invoke() }), page)
    }
    DisposableEffect(headers, tab, active, context) {
        if (active) headers?.set(tab, context)
        onDispose { if (active && headers?.get(tab) === context) headers.remove(tab) }
    }
}
