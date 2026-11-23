package me.d3sox.nevumi.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CancellationException

@Composable
internal actual fun PlatformBackHandler(onBack: () -> Unit) { BackHandler(enabled = LocalNavigationActive.current, onBack = onBack) }

@Composable
internal actual fun PlatformPredictiveBackHandler(enabled: Boolean, onStarted: (fromRightEdge: Boolean) -> Unit,
    onProgress: (Float) -> Unit, onCancelled: () -> Unit, onBack: () -> Unit) {
    PredictiveBackHandler(enabled = enabled) { events ->
        // The flow leaves out the start event, so the swipe edge arrives with the first progress event.
        var started = false
        try {
            events.collect {
                if (!started) { started = true; onStarted(it.swipeEdge == BackEventCompat.EDGE_RIGHT) }
                onProgress(it.progress)
            }
        }
        catch (cancelled: CancellationException) { onCancelled(); throw cancelled }
        onBack()
    }
}
