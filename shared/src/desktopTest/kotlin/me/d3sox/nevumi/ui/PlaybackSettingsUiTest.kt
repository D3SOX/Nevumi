package me.d3sox.nevumi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.visualizer.MilkDropSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class PlaybackSettingsUiTest {
    @Test fun quickControlsAdjustCrossfadePreciselyAndSaveGaplessAndVisualizerChoices() {
        val directory = Files.createTempDirectory("nevumi-playback-settings")
        val database = directory.resolve("music.db").toString()
        val store = openMusicStore(database)
        val preferences = MusicPreferences(store)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        var openedEffects = false
        val visualizer = MilkDropSettings("Original/locked.milk", automatic = false, favorites = setOf("Original/locked.milk"),
            favoritesOnly = true, collection = "Original MilkDrop", category = "Waveform")
        try {
            runComposeUiTest {
                setContent { MaterialTheme { PlaybackQuickSettings(controller.state.collectAsState().value,
                    controller, { openedEffects = true }, {}) } }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Increase Crossfade").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.preferences.crossfadeDurationMs == 1_000L }
                onNodeWithContentDescription("Decrease Crossfade").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.preferences.crossfadeDurationMs == 0L }
                onNodeWithContentDescription("Decrease Crossfade").assertIsNotEnabled()
                onNodeWithContentDescription("Gapless playback").performClick()
                waitUntil(timeoutMillis = 5_000) { !controller.state.value.preferences.gaplessPlayback }
                onNodeWithText("15 min").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.sleepTimerEndsAt != null }
                onNodeWithText("Cancel timer").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.sleepTimerEndsAt == null }
                onNodeWithText("Audio effects").performClick()
                assertTrue(openedEffects)
                runBlocking { controller.updatePreferences { it.copy(milkDrop = visualizer) }.join() }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close() }
        val restored = openMusicStore(database)
        try {
            val saved = runBlocking { MusicPreferences(restored).load() }
            assertFalse(saved.gaplessPlayback)
            assertEquals(visualizer, saved.milkDrop)
        } finally { restored.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun previousTrackLimitButtonsChangeTheLimitByOneTrack() {
        val directory = Files.createTempDirectory("nevumi-previous-limit")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(showPreviousQueueTracks = true, previousQueueTrackLimit = 51) } }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithContentDescription("Decrease Previous track limit").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.preferences.previousQueueTrackLimit == 50 }
                onNodeWithContentDescription("Increase Previous track limit").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.preferences.previousQueueTrackLimit == 51 }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun linuxWindowSettingsKeepMusicPlayingAndShowTheTrayIconWhenChosen() {
        val directory = Files.createTempDirectory("nevumi-window-settings")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                assertFalse(controller.state.value.preferences.keepPlayingInBackground)
                assertFalse(controller.state.value.preferences.trayIcon)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithContentDescription("Keep playing after closing").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.preferences.keepPlayingInBackground }
                onNodeWithContentDescription("Tray icon").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.preferences.trayIcon }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextInput("tray")
                onNodeWithText("Tray icon").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
