package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.AndroidAudioWaveforms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidWaveformSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun usersCanPrepareTheirLibraryAndOpenCachedWaveformsAfterRecreatingTheActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.filesDir, "prepared-audio").apply { mkdirs() }
        val tracks = runBlocking {
            app.controller.updatePreferences { it.copy(precomputeWaveforms = false) }.join()
            listOf("wav", "mp3", "flac").map { format ->
                val file = File(audio, "waveform.$format")
                instrumentation.context.assets.open("waveform.$format").use { input -> file.outputStream().use { input.copyTo(it) } }
                app.controller.library.importTrack(TrackMetadata("Prepared $format", artist = "Waveform fixture", durationMs = 800),
                    TrackSource("prepared:$format", SourceKind.LOCAL, file.toURI().toString(), modifiedAt = file.lastModified(), indexedSize = file.length()))
            }.also { list ->
                ArtworkLibrary(File(app.filesDir, "artwork").path).save(list.first().id,
                    instrumentation.context.assets.open("cover.png").use { it.readBytes() })
                app.controller.reload().join()
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Waveforms").assertDoesNotExist()
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithText("Waveforms").performScrollTo().performClick()
                compose.onNodeWithText("Prepare all waveforms").performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("3 of 3 prepared").fetchSemanticsNodes().isNotEmpty() }
                val reader = AndroidAudioWaveforms(app)
                runBlocking { tracks.forEach { assertEquals(128, reader.cached(it.sources.first())?.size) } }
                saveScreenshot(app, "waveform-settings.png")
                activity.recreate()
                compose.waitUntil(5_000) { compose.onAllNodesWithText("3 of 3 prepared").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Playback speed").assertExists()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                runBlocking { app.controller.playTrack(tracks.first().id, tracks.map { it.id }).join(); app.controller.pause().join() }
                compose.waitUntil(5_000) { app.controller.state.value.playback.durationMs > 0 }
                compose.onNodeWithTag("mini-player").performClick()
                compose.waitUntil(1_000) { compose.onAllNodesWithTag("audio-waveform").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("audio-waveform").assertIsDisplayed()
                compose.onNodeWithContentDescription("Collapse").performClick()
                compose.mainClock.advanceTimeBy(1_000)
                compose.mainClock.autoAdvance = false
                try {
                    // Approach the full portrait player while keeping the pointer held.
                    val distance = compose.onRoot().fetchSemanticsNode().boundsInRoot.height * .7f
                    compose.onNodeWithTag("mini-player").performTouchInput {
                        down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -distance), 500)
                    }
                    compose.mainClock.advanceTimeBy(350)
                    compose.onNodeWithTag("audio-waveform").assertIsDisplayed()
                    compose.onRoot().performTouchInput { up() }
                } finally { compose.mainClock.autoAdvance = true }
                compose.mainClock.advanceTimeBy(1_000)
                compose.onNodeWithContentDescription("Playback position").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(600f) }
                compose.waitUntil(5_000) { app.controller.state.value.playback.positionMs in 550..700 }
                assertFalse(app.controller.state.value.playback.playing)
                saveScreenshot(app, "prepared-waveform-playing.png")
                compose.onNodeWithContentDescription("Next track").performClick()
                compose.waitUntil(3_000) { app.controller.state.value.playback.trackId == tracks[1].id }
                compose.onNodeWithTag("audio-waveform").assertIsDisplayed()
                compose.onNodeWithContentDescription("Previous track").performClick()
                compose.waitUntil(3_000) { app.controller.state.value.playback.trackId == tracks[0].id }
                compose.onNodeWithTag("audio-waveform").assertIsDisplayed()
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.deleteRecursively() }
    }

    private fun saveScreenshot(app: MusicApplication, name: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(app.filesDir, "native-test-output/$name").apply {
                parentFile?.mkdirs(); outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }
}
