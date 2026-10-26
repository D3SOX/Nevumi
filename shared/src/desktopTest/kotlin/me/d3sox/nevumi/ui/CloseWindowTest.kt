package me.d3sox.nevumi.ui

import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.settings.MusicSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class CloseWindowTest {
    private fun hides(trayIcon: Boolean, keepPlaying: Boolean, playing: Boolean, trayAvailable: Boolean = true, mediaControlsAvailable: Boolean = true) = AppState(
        preferences = MusicSettings(trayIcon = trayIcon, keepPlayingInBackground = keepPlaying),
        playback = PlaybackStatus(playing = playing)).closeHidesWindow(trayAvailable, mediaControlsAvailable)

    @Test fun closingQuitsWhenNeitherRestoreControlRegistered() {
        assertEquals(false, hides(trayIcon = true, keepPlaying = true, playing = true,
            trayAvailable = false, mediaControlsAvailable = false))
        assertEquals(false, hides(trayIcon = false, keepPlaying = true, playing = true,
            trayAvailable = false, mediaControlsAvailable = false))
        assertEquals(true, hides(trayIcon = true, keepPlaying = false, playing = false,
            trayAvailable = true, mediaControlsAvailable = false))
        assertEquals(true, hides(trayIcon = false, keepPlaying = true, playing = true,
            trayAvailable = false, mediaControlsAvailable = true))
    }

    @Test fun theTrayKeepsNevumiRunningWhilePlaybackOnlyKeepsItRunningWhilePlaying() {
        assertEquals(false, hides(trayIcon = false, keepPlaying = false, playing = true))
        assertEquals(false, hides(trayIcon = false, keepPlaying = true, playing = false))
        assertEquals(true, hides(trayIcon = false, keepPlaying = true, playing = true))
        assertEquals(true, hides(trayIcon = true, keepPlaying = false, playing = false))
        assertEquals(true, hides(trayIcon = true, keepPlaying = true, playing = false))
    }
}
