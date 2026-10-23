package me.d3sox.nevumi.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_song_actions
import org.jetbrains.compose.resources.stringResource

private class MenuLayer(val render: @Composable () -> Unit, val dismiss: () -> Unit)
private val LocalMenuLayer = staticCompositionLocalOf<MutableState<MenuLayer?>> { error("A track menu needs the app host") }

/** Hiding the original and drawing the moving cover share the activity's rendering surface. */
@Composable
internal actual fun TrackMenuHost(content: @Composable () -> Unit) {
    val layer = remember { mutableStateOf<MenuLayer?>(null) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalMenuLayer provides layer) { content() }
        layer.value?.let { menu ->
            val focus = remember { FocusRequester() }
            BackHandler(onBack = menu.dismiss)
            LaunchedEffect(menu) { focus.requestFocus() }
            val songActions = stringResource(Res.string.sources_song_actions)
            Box(Modifier.fillMaxSize().semantics { paneTitle = songActions; isTraversalGroup = true }.focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) { menu.dismiss(); true } else false
                }.focusable()) { menu.render() }
        }
    }
}

@Composable
internal actual fun TrackMenuWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalMenuLayer.current
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    val menu = remember { MenuLayer({ currentContent() }, { currentDismiss() }) }
    DisposableEffect(menu) {
        host.value = menu
        onDispose { if (host.value === menu) host.value = null }
    }
}
