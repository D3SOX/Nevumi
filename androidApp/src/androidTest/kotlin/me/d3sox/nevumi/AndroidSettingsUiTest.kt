package me.d3sox.nevumi

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import android.view.inputmethod.InputMethodManager
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun androidSettingsApplyBlackThemeSourcePriorityAndPlayAfterSwipes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val preferences = MusicPreferences(app.store)
        val original = runBlocking { preferences.load() }
        val audio = File(app.filesDir, "settings-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val library = MusicLibrary(app.store)
        val tracks = runBlocking {
            listOf("Settings First", "Settings Second").map { title ->
                library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL, audio.toURI().toString()))
            }
        }
        runBlocking { app.controller.refresh() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Appearance").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Theme").performClick()
                compose.onNodeWithText("Dark").performClick()
                compose.onNodeWithContentDescription("AMOLED black").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Library & gestures").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Swipe right").performClick()
                compose.onNodeWithText("Play after").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Downloads & cache").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Source priority").performClick()
                compose.onNodeWithText("Downloads → Local → Server").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Playback speed").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
                compose.onNodeWithContentDescription("Playback pitch").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitForIdle()
                assertEquals(Color.Black, compose.onRoot().captureToImage().toPixelMap()[5, 5])
                assertEquals(listOf(SourceKind.DOWNLOAD, SourceKind.LOCAL, SourceKind.SERVER), runBlocking { preferences.load() }.sourcePriority)
                assertEquals(1.5, runBlocking { preferences.load() }.speed, 0.001)
                assertEquals(2.0, runBlocking { preferences.load() }.pitch, 0.001)
                compose.waitUntil(5_000) { app.controller.state.value.playback.speed in 1.49..1.51 }
                compose.waitUntil(5_000) { app.controller.state.value.playback.pitch in 1.99..2.01 }
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Settings First")
                scenario.onActivity { activity ->
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                runBlocking {
                    app.controller.playTrack(tracks[0].id, listOf(tracks[0].id)).join()
                }
                compose.waitUntil(5_000) { app.controller.state.value.playback.playing }
                runBlocking {
                    app.controller.togglePlayback().join()
                    app.controller.enqueue(tracks[1].id, QueueAction.APPEND).join()
                }
                assertEquals(2, app.controller.state.value.queue.entries.size)
                compose.onNodeWithTag("track-row:${tracks[0].id}").performScrollTo().performTouchInput {
                    swipeRight(durationMillis = 300)
                }
                try { compose.waitUntil(5_000) { app.controller.state.value.queue.entries.size == 3 } }
                catch (failure: Throwable) {
                    throw AssertionError("Swipe result: ${app.controller.state.value.queue}; settings: ${app.controller.state.value.preferences}; error: ${app.controller.state.value.error}", failure)
                }
                assertEquals(listOf(tracks[0].id, tracks[1].id, tracks[0].id), app.controller.state.value.queue.entries.map { it.trackId })
            }
        } finally { runBlocking {
            app.controller.setSpeed(original.speed).join()
            app.controller.setPitch(original.pitch).join()
            app.controller.updatePreferences { original }.join()
        } }
    }
}
