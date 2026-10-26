package me.d3sox.nevumi.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import kotlinx.coroutines.flow.MutableSharedFlow
import me.d3sox.nevumi.app.MusicController

/**
 * Keyboard shortcuts for the whole window. Hand [onKeyEvent] to the window, which offers it only the keys the focused
 * component left alone, so typing in a text field never controls playback.
 */
class AppShortcuts(private val controller: MusicController, private val quit: (() -> Unit)? = null) {
    internal val searches = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    // Text fields take a space from its typed event and let the key down pass, so Space toggles playback on the typed event,
    // and only after its key down also reached the window. A held key repeats both; playback toggles once per press.
    private var spacePressed = false
    private var spaceHandled = false

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.isAltPressed || event.isMetaPressed) return false
        val ctrl = event.isCtrlPressed
        val shift = event.isShiftPressed
        if (event.key == Key.Spacebar && event.type != KeyEventType.Unknown) {
            spacePressed = event.type == KeyEventType.KeyDown && !ctrl && !shift
            if (event.type == KeyEventType.KeyUp) spaceHandled = false
            return false
        }
        if (event.type == KeyEventType.Unknown && event.utf16CodePoint == ' '.code) {
            if (!spacePressed) return false
            if (!spaceHandled) controller.togglePlayback()
            spaceHandled = true
            return true
        }
        if (event.type != KeyEventType.KeyDown) return false
        when {
            ctrl && !shift && event.key == Key.DirectionRight -> controller.skip(1)
            ctrl && !shift && event.key == Key.DirectionLeft -> controller.skip(-1)
            shift && !ctrl && event.key == Key.DirectionRight -> seekBy(SEEK_STEP_MS)
            shift && !ctrl && event.key == Key.DirectionLeft -> seekBy(-SEEK_STEP_MS)
            ctrl && !shift && event.key == Key.F -> searches.tryEmit(Unit)
            ctrl && !shift && event.key == Key.Q && quit != null -> quit()
            else -> return false
        }
        return true
    }

    private fun seekBy(offsetMs: Long) {
        val playback = controller.state.value.playback
        val entryId = playback.entryId ?: return
        val destination = (playback.positionMs + offsetMs).coerceAtLeast(0)
        if (playback.durationMs in 1..<destination) controller.skip(1) else controller.seekCurrentEntry(entryId, destination)
    }

    private companion object { const val SEEK_STEP_MS = 5_000L }
}
