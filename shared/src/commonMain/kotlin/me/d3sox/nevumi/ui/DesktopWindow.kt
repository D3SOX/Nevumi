package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import me.d3sox.nevumi.app.MusicController

/** Whether Nevumi runs in a desktop window, whose closing and tray icon have settings. */
internal expect val desktopWindowSettings: Boolean

/** Takes music dropped onto [content]: folders join the library and songs play. */
@Composable
internal expect fun MusicDropTarget(controller: MusicController, content: @Composable () -> Unit)
