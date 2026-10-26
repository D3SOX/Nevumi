package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import me.d3sox.nevumi.app.MusicController

internal actual val desktopWindowSettings = false

@Composable
internal actual fun MusicDropTarget(controller: MusicController, content: @Composable () -> Unit) = content()
