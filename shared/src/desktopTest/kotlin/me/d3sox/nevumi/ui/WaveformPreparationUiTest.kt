package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.*
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WaveformPreparationUiTest {
    @Test fun aPreparedWaveformIsVisibleDuringExpansionBeforeTheFingerIsReleased() {
        val directory = Files.createTempDirectory("waveform-during-drag")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "waveform-long.flac").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("Prepared song", durationMs = 600_000),
            TrackSource("prepared", SourceKind.LOCAL, audio)) }
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(precomputeWaveforms = false) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        val reader = CachedAudioWaveforms(directory.resolve("waves").toString()) { _, _ -> List(128) { .5f } }
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, audioWaveforms = reader) }
                waitForLibrary(controller)
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithTag("audio-waveform").assertIsDisplayed()
                onNodeWithContentDescription("Collapse").performClick()
                mainClock.advanceTimeBy(1_000)
                mainClock.autoAdvance = false
                onNodeWithTag("mini-player").performTouchInput {
                    down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -350f), 500)
                }
                mainClock.advanceTimeBy(350)
                onNodeWithTag("audio-waveform").assertIsDisplayed()
                onRoot().performTouchInput { up() }
                mainClock.autoAdvance = true
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun waveformsAreAPlaybackSubpageAndBackReturnsToPlayback() {
        val directory = Files.createTempDirectory("waveform-settings-navigation")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForLibrary(controller)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Waveforms").assertDoesNotExist()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithText("Waveforms").performScrollTo().performClick()
                onNodeWithText("Prepare all waveforms").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Playback speed").assertExists()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Appearance").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun usersCanPrepareAllWaveformsWithAutomaticPreparationOffAndReuseThemAfterRestart() {
        val directory = Files.createTempDirectory("waveform-settings")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "waveform-long.flac").toString()
        val tracks = runBlocking { (0 until 3).map { index ->
            library.importTrack(TrackMetadata("Song $index", durationMs = 600_000), TrackSource("source:$index", SourceKind.LOCAL, audio))
        } }
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(precomputeWaveforms = false) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        val decodes = AtomicInteger()
        fun reader() = CachedAudioWaveforms(directory.resolve("waves").toString()) { _, _ ->
            kotlinx.coroutines.delay(50); decodes.incrementAndGet(); List(128) { .5f }
        }
        try {
            repeat(2) { restart ->
                runComposeUiTest {
                    setContent { MusicPlayerApp(controller, audioWaveforms = reader()) }
                    waitForLibrary(controller)
                    onNodeWithContentDescription("Settings").performClick()
                    onNodeWithText("Playback").performScrollTo().performClick()
                    onNodeWithText("Waveforms").performScrollTo().performClick()
                    onNodeWithText("Prepare all waveforms").assertIsDisplayed().performClick()
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("3 of 3 prepared").fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(3, decodes.get(), "Restart $restart must reuse cached waveforms")
                    onNodeWithContentDescription("Back").performClick()
                    onNodeWithContentDescription("Back").performClick()
                    onNodeWithContentDescription("Back").performClick()
                    runBlocking { controller.playTrack(tracks[0].id, tracks.map { it.id }).join() }
                    onNodeWithTag("mini-player").performClick()
                    onNodeWithTag("audio-waveform").assertIsDisplayed()
                    assertEquals(3, decodes.get())
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun preparationContinuesDuringPlaybackAndPreparedTracksOpenWithoutDecodingAgain() {
        val directory = Files.createTempDirectory("waveforms-during-playback")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "waveform-long.flac").toString()
        val tracks = runBlocking { (0 until 3).map { index ->
            library.importTrack(TrackMetadata("Song $index", durationMs = 600_000), TrackSource("source:$index", SourceKind.LOCAL, audio))
        } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        val decodes = AtomicInteger()
        val reader = CachedAudioWaveforms(directory.resolve("waves").toString()) { _, _ -> decodes.incrementAndGet(); List(128) { .5f } }
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, audioWaveforms = reader) }
                waitForLibrary(controller)
                runBlocking { controller.playTrack(tracks[0].id, tracks.map { it.id }).join() }
                waitUntil(timeoutMillis = 2_000) { controller.state.value.playback.playing }
                waitUntil(timeoutMillis = 6_000) { decodes.get() == 3 }
                onNodeWithTag("mini-player").performClick()
                onNodeWithTag("audio-waveform").assertIsDisplayed()
                assertEquals(3, decodes.get(), "The currently playing track should already have a prepared waveform")
                assertEquals(true, controller.state.value.playback.playing)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
