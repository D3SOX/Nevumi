package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import me.d3sox.nevumi.app.MusicController

internal actual val desktopWindowSettings = false
internal actual val headphoneResumeSetting = true
internal actual val bitPerfectSetting = android.os.Build.VERSION.SDK_INT >= 34

@Composable
internal actual fun MusicDropTarget(controller: MusicController, content: @Composable () -> Unit) = content()
