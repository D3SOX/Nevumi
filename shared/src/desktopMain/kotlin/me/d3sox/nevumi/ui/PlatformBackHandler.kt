package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun PlatformBackHandler(onBack: () -> Unit) = Unit

@Composable
internal actual fun PlatformPredictiveBackHandler(enabled: Boolean, onStarted: (fromRightEdge: Boolean) -> Unit,
    onProgress: (Float) -> Unit, onCancelled: () -> Unit, onBack: () -> Unit) = Unit
