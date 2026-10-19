package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidTrackActionsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test fun playingLibrarySongsKeepsManualRequestsAndDoesNotRebuildAnExhaustedContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "play-swipe.flac")
        instrumentation.context.assets.open("waveform-long.flac").use { input -> audio.outputStream().use(input::copyTo) }
        val original = app.controller.state.value.preferences
        try {
            val songs = runBlocking { (0 until 8).map { index ->
                app.controller.library.importTrack(TrackMetadata("Swipe Song %02d".format(index), durationMs = 600_000),
                    TrackSource("play-swipe:$index", SourceKind.LOCAL, audio.toURI().toString()))
            }.also {
                app.controller.updatePreferences { it.copy(precomputeWaveforms = false) }.join()
                app.controller.reload().join()
            } }
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking {
                    app.controller.playTrack(songs[0].id, songs.map { it.id }).join(); app.controller.pause().join()
                    app.controller.clearQueue().join()
                    app.controller.enqueue(listOf(songs[1].id, songs[1].id, songs[2].id), me.d3sox.nevumi.queue.QueueAction.APPEND).join()
                }
                val requested = app.controller.state.value.queue.upNext
                fun play(index: Int) {
                    compose.onNodeWithTag("track-row:${songs[index].id}", useUnmergedTree = true).performTouchInput {
                        click(center)
                    }
                    compose.waitUntil(5_000) { app.controller.state.value.queue.currentTrackId == songs[index].id }
                }
                play(2)
                assertEquals(requested, app.controller.state.value.queue.upNext)
                assertEquals(emptyList<me.d3sox.nevumi.queue.QueueEntry>(), app.controller.state.value.queue.context)
                runBlocking { app.controller.pause().join(); app.controller.clearQueue().join() }
                play(0)
                assertEquals(emptyList<me.d3sox.nevumi.queue.QueueEntry>(), app.controller.state.value.queue.context)
                assertEquals(emptyList<me.d3sox.nevumi.queue.QueueEntry>(), app.controller.state.value.queue.upNext)
                assertEquals(me.d3sox.nevumi.queue.QueueSection.MANUAL,
                    app.controller.state.value.queue.entries[app.controller.state.value.queue.currentIndex].section)
            }
        } finally { runBlocking { app.controller.pause().join(); app.controller.updatePreferences { original }.join() }; audio.delete() }
    }

    @Test
    fun playingAndQueueActionsWorkWithNativeTouchWithoutResumingPausedPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "native-actions.flac")
        instrumentation.context.assets.open("fixture-cover.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val original = app.controller.state.value.preferences
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(app.filesDir, "actions-ui/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { !app.controller.state.value.loading }
                val cover = org.jaudiotagger.audio.AudioFileIO.read(audio).tag.firstArtwork.binaryData
                val songs = runBlocking {
                    app.controller.updatePreferences { it.copy(themeMode = ThemeMode.DARK, amoledBlack = true) }.join()
                    listOf("First fixture", "Second fixture", "Third fixture").map { title ->
                        val track = app.controller.library.importTrack(TrackMetadata(title, "Fixture artist", "Fixture album", durationMs = 3_000),
                            TrackSource(title, SourceKind.LOCAL, audio.path))
                        ArtworkLibrary(File(app.filesDir, "artwork").path).save(track.id, cover)
                        track
                    }
                }
                runBlocking { app.controller.reload().join(); app.controller.playTrack(songs[0].id, songs.map { it.id }).join(); app.controller.pause().join() }
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithContentDescription("Track options: First fixture").performClick()
                capture("song-actions")
                compose.onNodeWithText("File actions").performClick()
                compose.onNodeWithText("Edit tags").assertIsDisplayed()
                capture("file-actions")
                compose.onNodeWithContentDescription("Close song actions").performClick()
                compose.onNodeWithContentDescription("Favorite").performClick()
                compose.waitUntil(5_000) { app.controller.state.value.tracks.first { it.id == songs[0].id }.favorite }
                compose.onNodeWithContentDescription("Remove favorite").assertIsDisplayed()
                compose.onNodeWithContentDescription("Elapsed time").performClick()
                compose.waitUntil(5_000) { app.controller.state.value.preferences.showRemainingTime }
                compose.onNodeWithContentDescription("Remaining time").assertIsDisplayed()
                capture("now-playing")
                compose.onNodeWithTag("player-artwork", useUnmergedTree = true).performTouchInput { swipe(center, center.copy(x = center.x - 260f), 300) }
                compose.waitUntil(5_000) { app.controller.state.value.queue.currentIndex == 1 }
                compose.onNodeWithTag("player-artwork", useUnmergedTree = true).performTouchInput { swipe(center, center.copy(x = center.x + 260f), 300) }
                compose.waitUntil(5_000) { app.controller.state.value.queue.currentIndex == 0 }
                compose.onNodeWithContentDescription("Queue").performClick()
                val entries = app.controller.state.value.queue.entries
                compose.onNodeWithContentDescription("Track options: Second fixture").performClick()
                compose.onNodeWithText("Rate song").performClick()
                compose.onNodeWithContentDescription("4 stars").performClick()
                compose.onNodeWithText("Confirm").performClick()
                compose.waitUntil(5_000) { app.controller.state.value.tracks.first { it.id == songs[1].id }.rating == 4 }
                compose.onNodeWithTag("queue-entry:${entries[1].id}").performTouchInput { swipe(center, center.copy(x = center.x - 260f), 300) }
                compose.waitUntil(5_000) { app.controller.state.value.queue.entries.size == 2 }
                capture("queue")
                compose.onNodeWithTag("queue-entry:${entries[0].id}").performTouchInput { swipe(center, center.copy(y = center.y + 500f), 400) }
                compose.onNodeWithContentDescription("Collapse").assertIsDisplayed()
                assertEquals(false, app.controller.state.value.playback.playing)
                assertEquals(entries[0].id, app.controller.state.value.queue.currentEntryId)
                compose.onNodeWithContentDescription("Collapse").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Saved queues").performScrollTo().performClick()
                capture("saved-queues")
                compose.onNodeWithText("Done").assertIsDisplayed()
            }
        } finally { runBlocking { app.controller.pause().join(); app.controller.updatePreferences { original }.join() }; audio.delete() }
    }
}
