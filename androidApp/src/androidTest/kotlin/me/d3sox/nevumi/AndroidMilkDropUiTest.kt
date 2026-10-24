package me.d3sox.nevumi

import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.visualizer.VisualizerPcm
import me.d3sox.nevumi.visualizer.milkDropLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidMilkDropUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun presetsCanBeSelectedAndTheVisualizerStopsInTheBackgroundAndWhenClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val file = File(app.filesDir, "milkdrop-ui.wav")
        instrumentation.context.assets.open("fixture.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        val track = runBlocking {
            app.controller.updatePreferences { it.copy(precomputeWaveforms = false,
                milkDrop = me.d3sox.nevumi.visualizer.MilkDropSettings()) }.join()
            app.controller.library.importTrack(TrackMetadata("MilkDrop fixture", artist = "Nevumi", durationMs = 2000),
                TrackSource("milkdrop:ui", SourceKind.LOCAL, file.toURI().toString())).also { app.controller.reload().join() }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                runBlocking { app.controller.playTrack(track.id, listOf(track.id)).join(); app.controller.pause().join() }
                compose.waitUntil(5_000) { app.controller.state.value.playback.durationMs > 0 }
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithContentDescription("MilkDrop visualizer").performClick()
                compose.waitUntil(60_000) { app.controller.state.value.preferences.milkDrop.selectedPresetId != null }
                compose.onNodeWithContentDescription("MilkDrop visualization").assertIsDisplayed()
                assertFalse("Paused playback must not tap audio", VisualizerPcm.enabled)
                Thread.sleep(1_000)
                val bounds = compose.onNodeWithContentDescription("MilkDrop visualization").fetchSemanticsNode().boundsInRoot
                fun frame(): android.graphics.Bitmap {
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    return android.graphics.Bitmap.createBitmap(screenshot, bounds.left.toInt() + 8, bounds.top.toInt() + 8,
                        bounds.width.toInt() - 16, bounds.height.toInt() - 16).also { screenshot.recycle() }
                }
                val before = frame()
                Thread.sleep(1_000)
                val after = frame()
                assertTrue("The visualizer must freeze while playback is paused", before.sameAs(after))
                before.recycle(); after.recycle()
                compose.onNodeWithContentDescription("Change presets automatically").performClick()
                compose.onNodeWithText("Unlock preset").assertExists()
                runBlocking { app.controller.setQueueRepeat(me.d3sox.nevumi.playback.RepeatMode.ONE).join(); app.controller.togglePlayback().join() }
                compose.onNodeWithContentDescription("Next preset").performClick()
                val presets = runBlocking { milkDropLibrary(File(app.filesDir, "milkdrop")) }.presets
                compose.onNodeWithText(presets[1].name).assertExists()
                compose.onNodeWithContentDescription("Choose MilkDrop preset").performClick()
                compose.onNodeWithText("Search presets").performTextInput("postcard from Fractopia")
                val preset = compose.onNodeWithText("Flexi + Rovastar - postcard from Fractopia")
                // The dialog moves above the keyboard as it opens, so tap the preset once it stops moving.
                val dialog = (preset.fetchSemanticsNode().root as ViewRootForTest).view
                compose.waitUntil(5_000) { ViewCompat.getRootWindowInsets(dialog)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                var presetBounds = preset.fetchSemanticsNode().boundsInRoot
                var stableSince = android.os.SystemClock.uptimeMillis()
                compose.waitUntil(5_000) {
                    val next = preset.fetchSemanticsNode().boundsInRoot
                    if (next != presetBounds) { presetBounds = next; stableSince = android.os.SystemClock.uptimeMillis() }
                    android.os.SystemClock.uptimeMillis() - stableSince >= 300
                }
                preset.performClick()
                compose.onNodeWithText("Flexi + Rovastar - postcard from Fractopia").assertExists()
                compose.waitForIdle()
                requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "milkdrop-ui-${android.os.Build.VERSION.SDK_INT}.png")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NevumiTests")
                    }
                    val uri = requireNotNull(app.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                    requireNotNull(app.contentResolver.openOutputStream(uri)).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
                shell("input keyevent 3")
                compose.waitUntil(15_000) { !VisualizerPcm.enabled }
                shell("am start --user 0 -W --activity-single-top --activity-clear-top -n me.d3sox.nevumi/.MainActivity")
                compose.waitUntil(15_000) { VisualizerPcm.enabled }
                compose.waitForIdle()
                compose.onNodeWithContentDescription("MilkDrop visualization").assertIsDisplayed()
                compose.onNodeWithText("Close visualizer").performClick()
                compose.onNodeWithContentDescription("MilkDrop visualization").assertDoesNotExist()
                compose.waitUntil(15_000) { !VisualizerPcm.enabled }
                compose.onNodeWithContentDescription("MilkDrop visualizer").performClick()
                // Reopening loads the preset library again, just as the first open does. The
                // audio tap cannot start until that finishes on the CI emulator's slow storage.
                compose.waitUntil(60_000) {
                    compose.onAllNodesWithContentDescription("MilkDrop visualization").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithContentDescription("MilkDrop visualization").assertIsDisplayed()
                compose.waitUntil(15_000) { VisualizerPcm.enabled }
                compose.onNodeWithText("Flexi + Rovastar - postcard from Fractopia").assertExists()
                compose.onNodeWithText("Unlock preset").assertExists()
                runBlocking { app.controller.updatePreferences { settings ->
                    settings.copy(milkDrop = settings.milkDrop.copy(favoritesOnly = true, favorites = emptySet()))
                }.join() }
                compose.waitUntil(15_000) { !VisualizerPcm.enabled }
                compose.onNodeWithText("Favorite a preset to start the visualizer.").assertExists()
                compose.onNodeWithText("Close visualizer").performClick()
                compose.onNodeWithContentDescription("MilkDrop visualization").assertDoesNotExist()
                compose.waitUntil(15_000) { !VisualizerPcm.enabled }
            }
        } finally { runBlocking { app.controller.pause().join() }; file.delete() }
    }

    @Test fun theVisualizerCanAnimateBehindTheNowPlayingScreenOnlyWhileItIsVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val file = File(app.filesDir, "milkdrop-background.wav")
        instrumentation.context.assets.open("fixture.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        val preset = runBlocking { milkDropLibrary(File(app.filesDir, "milkdrop")) }.presets
            .single { it.name == "Flexi + Rovastar - postcard from Fractopia" }
        val track = runBlocking {
            app.controller.updatePreferences { it.copy(precomputeWaveforms = false, milkDrop = me.d3sox.nevumi.visualizer.MilkDropSettings(
                selectedPresetId = preset.info.id, automatic = false, background = true, backgroundDim = 0f)) }.join()
            app.controller.library.importTrack(TrackMetadata("MilkDrop background", artist = "Nevumi", durationMs = 2000),
                TrackSource("milkdrop:background", SourceKind.LOCAL, file.toURI().toString())).also { app.controller.reload().join() }
        }
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                runBlocking {
                    app.controller.playTrack(track.id, listOf(track.id)).join()
                    app.controller.setQueueRepeat(me.d3sox.nevumi.playback.RepeatMode.ONE).join()
                }
                compose.onNodeWithTag("mini-player").performClick()
                compose.waitUntil(60_000) { VisualizerPcm.enabled }
                // The left margin beside the cover shows only the background.
                val bounds = compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot
                fun patch(): android.graphics.Bitmap {
                    val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    return android.graphics.Bitmap.createBitmap(screenshot, bounds.left.toInt() + 4,
                        (bounds.top + bounds.height / 2).toInt(), 12, 120).also { screenshot.recycle() }
                }
                Thread.sleep(2_000)
                val first = patch()
                Thread.sleep(1_000)
                val second = patch()
                assertFalse("The background must animate while music plays", first.sameAs(second))
                first.recycle(); second.recycle()
                runBlocking { app.controller.updatePreferences { it.copy(milkDrop = it.milkDrop.copy(backgroundDim = 1f)) }.join() }
                compose.waitForIdle()
                Thread.sleep(500)
                patch().let { dimmed ->
                    val pixels = IntArray(dimmed.width * dimmed.height).also { dimmed.getPixels(it, 0, dimmed.width, 0, 0, dimmed.width, dimmed.height) }
                    assertEquals("Full dimming must hide the background", 1, pixels.distinct().size)
                    dimmed.recycle()
                }
                shell("input keyevent 3")
                compose.waitUntil(15_000) { !VisualizerPcm.enabled }
                shell("am start --user 0 -W --activity-single-top --activity-clear-top -n me.d3sox.nevumi/.MainActivity")
                compose.waitUntil(15_000) { VisualizerPcm.enabled }
                compose.onNodeWithContentDescription("Collapse").performClick()
                compose.waitUntil(15_000) { !VisualizerPcm.enabled }
            }
        } finally {
            runBlocking { app.controller.pause().join(); app.controller.updatePreferences { it.copy(
                milkDrop = me.d3sox.nevumi.visualizer.MilkDropSettings()) }.join() }
            file.delete()
        }
    }
}
