package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidLibraryControlsTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun sourceAndOrderControlsSetTheAndroidPlaybackQueueAndSurviveActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val original = runBlocking { MusicPreferences(app.store).load() }
        val audio = File(app.cacheDir, "library-controls-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { !controller.state.value.loading }
                val tracks = runBlocking {
                    listOf("Zeta", "Alpha", "Mu").mapIndexed { index, artist ->
                        controller.library.importTrack(TrackMetadata("Library control ${'A' + index}", artist),
                            TrackSource("control-${UUID.randomUUID()}", SourceKind.LOCAL, audio.path))
                    }
                }
                val server = runBlocking {
                    controller.library.importTrack(TrackMetadata("Library control server"),
                        TrackSource("control-server-${UUID.randomUUID()}", SourceKind.SERVER, "cached-server-song"))
                }
                runBlocking { controller.reload().join() }
                compose.onNodeWithContentDescription("Music sources").performClick()
                compose.onNodeWithText("Device").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.sourceFilter == SourceKind.LOCAL }
                compose.onNodeWithTag("track-row:${server.id}").assertDoesNotExist()
                compose.onNodeWithContentDescription("Song order").performClick()
                compose.onNodeWithText("Artist").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.songSort == LibrarySort.ARTIST }
                compose.onNodeWithTag("track-row:${tracks[1].id}").performScrollTo().performClick()
                try {
                    compose.waitUntil(5_000) { controller.state.value.playback.playing }
                } catch (error: Throwable) {
                    throw AssertionError("Native library play failed: ${controller.state.value.playback}; queue=${controller.state.value.queue}", error)
                }
                assertEquals(listOf(tracks[1].id, tracks[2].id, tracks[0].id), controller.state.value.queue.entries.map { it.trackId })
                runBlocking { controller.pause().join() }
                compose.onNodeWithContentDescription("Song order").performClick()
                compose.onNodeWithText("Ascending").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.songOrderReversed }
                compose.onNodeWithContentDescription("Music sources").performClick()
                compose.onNodeWithText("Server").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.sourceFilter == SourceKind.SERVER }
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Library control")
                compose.onNodeWithContentDescription("Search your library").performImeAction()
                scenario.recreate()
                compose.onNodeWithContentDescription("Search your library").assertTextContains("Library control")
                compose.onNodeWithContentDescription("Close search").performClick()
                compose.onNodeWithContentDescription("Music sources").performClick()
                // The source chip under the header also reads "Server", so pick the menu item.
                compose.onNode(hasText("Server") and hasAnyAncestor(isPopup())).assertIsSelected().performClick()
                compose.onNodeWithContentDescription("Song order").performClick()
                compose.onNodeWithText("Artist").assertIsSelected().performClick()
                compose.onNodeWithContentDescription("Song order").performClick()
                compose.onNodeWithText("Descending").assertIsDisplayed()
                compose.onNodeWithTag("track-row:${server.id}").assertIsDisplayed()
                compose.onNodeWithTag("track-row:${tracks[0].id}").assertDoesNotExist()
            }
        } finally {
            runBlocking { controller.pause().join(); controller.updatePreferences { original }.join() }
            audio.delete()
        }
    }
}
