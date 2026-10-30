package me.d3sox.nevumi

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPlayerMotionAndScrollTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun scrollbarPreviewsAndPlayerMotionWorkWithNativeTouchAndPredictiveBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "motion-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture-cover.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val cover = org.jaudiotagger.audio.AudioFileIO.read(audio).tag.firstArtwork.binaryData
        val tracks = runBlocking { (0 until 208).map { index ->
            val track = app.controller.library.importTrack(TrackMetadata("${'A' + index / 8}${index.toString().padStart(3, '0')}",
                artist = "${'A' + index / 8} artist", album = "Album ${index / 8}"),
                TrackSource("motion-$index", SourceKind.LOCAL, audio.path, modifiedAt = 1_577_836_800_000L + index * 86_400_000L))
            if (index in 99..101) ArtworkLibrary(File(app.filesDir, "artwork").path).save(track.id, cover)
            track
        } }
        fun capture(name: String) {
            compose.waitForIdle()
            val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
            val file = File(output, "ui-verification/$name.png").apply { parentFile!!.mkdirs() }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            runBlocking {
                app.controller.updatePreferences { it.copy(themeMode = ThemeMode.DARK, amoledBlack = true, libraryTabs = LibraryTab.entries.toList()) }.join()
                app.controller.refresh()
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { !app.controller.state.value.loading }
                val scrollbar = compose.onNodeWithContentDescription("Scroll library")
                scrollbar.performTouchInput { down(center.copy(y = height * .4f)); moveTo(center.copy(y = height * .55f), 200) }
                assertTrue(compose.onNodeWithTag("fast-scroll-label").fetchSemanticsNode().config[SemanticsProperties.Text].single().text.single() in 'J'..'Q')
                capture("scroll-letter")
                scrollbar.performTouchInput { up() }
                compose.onNodeWithContentDescription("Song order").performClick()
                compose.onNodeWithText("Recently added").performClick()
                compose.waitUntil(5_000) { app.controller.state.value.preferences.songSort == LibrarySort.RECENT }
                scrollbar.performTouchInput { down(center.copy(y = height * .4f)); moveTo(center.copy(y = height * .55f), 200) }
                assertTrue(compose.onNodeWithTag("fast-scroll-label").fetchSemanticsNode().config[SemanticsProperties.Text].single().text.endsWith("2020"))
                capture("scroll-date")
                scrollbar.performTouchInput { up() }
                runBlocking { app.controller.playTrack(tracks[100].id, tracks.map { it.id }, 100).join(); app.controller.pause().join() }
                compose.waitUntil(5_000) { app.controller.state.value.playback.durationMs == 3_000L }
                val entry = app.controller.state.value.queue.currentEntryId
                val mini = compose.onNodeWithTag("mini-player")
                mini.performTouchInput { down(center); moveTo(center.copy(x = width * .15f), 200) }
                compose.onNodeWithContentDescription("Next track preview: ${tracks[101].metadata.title}", useUnmergedTree = true).assertIsDisplayed()
                capture("mini-swipe-preview")
                mini.performTouchInput { cancel() }
                compose.waitForIdle()
                assertEquals(entry, app.controller.state.value.queue.currentEntryId)
                val miniCover = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                mini.performClick()
                listOf("Shuffle", "Repeat").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
                val fullCover = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(fullCover.width > miniCover.width * 3)
                capture("now-playing-controls")
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .5f, BackEventCompat.EDGE_LEFT))
                }
                compose.waitForIdle()
                val shrinkingCover = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(shrinkingCover.width in (miniCover.width + 1)..(fullCover.width - 1))
                capture("playing-back-progress")
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.onNodeWithContentDescription("Queue").performClick()
                listOf("Shuffle songs", "Shuffle albums", "Repeat", "Repeat count").forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
                compose.onNodeWithTag("queue-mini-player").assertIsDisplayed()
                val queueCover = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(queueCover.width > miniCover.width && queueCover.width < fullCover.width)
                capture("queue-current-track")
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .4f, BackEventCompat.EDGE_LEFT))
                }
                compose.waitForIdle()
                val transitioningCover = compose.onNodeWithTag("player-artwork", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(transitioningCover.width > queueCover.width)
                val title = compose.onNode(hasText(tracks[100].metadata.title) and !hasTestTag("queue-entry:$entry") and
                    !hasAnyAncestor(hasTestTag("queue-entry:$entry"))).fetchSemanticsNode().boundsInRoot
                assertTrue("The moving title must stay clear of the artwork", title.left >= transitioningCover.right || title.top >= transitioningCover.bottom)
                capture("queue-back-progress")
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.onNodeWithContentDescription("Now playing").performClick()
                compose.onNodeWithContentDescription("Collapse").performClick()
                compose.onNodeWithTag("mini-player").assertIsDisplayed()
                assertEquals(entry, app.controller.state.value.queue.currentEntryId)
                assertFalse(app.controller.state.value.playback.playing)
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }
}
