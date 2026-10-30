package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPlayerAppearanceUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun compactLibraryAndCoveredPlayerKeepTheCurrentQueueEntryVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val original = runBlocking { MusicPreferences(app.store).load() }
        val audio = File(app.cacheDir, "appearance-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture-cover.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val library = MusicLibrary(app.store)
        val cover = org.jaudiotagger.audio.AudioFileIO.read(audio).tag.firstArtwork.binaryData
        val tracks = runBlocking {
            (1..40).map { number ->
                val track = library.importTrack(TrackMetadata("A song %02d".format(number), "Fixture Artist", "Fixture Album ${'A' + number % 6}"),
                    TrackSource("appearance-$number", SourceKind.LOCAL, audio.path))
                ArtworkLibrary(File(app.filesDir, "artwork").path).save(track.id, cover)
                track
            }
        }
        fun assertCover(description: String) {
            compose.waitUntil(5_000) {
                val node = if (description.startsWith("Album artwork:"))
                    compose.onNode(hasContentDescription(description) and hasAnyAncestor(hasTestTag("player-artwork")), useUnmergedTree = true)
                    else compose.onNodeWithContentDescription(description, useUnmergedTree = true)
                // A capture can land mid-layout on slow emulators and throw, so retry it.
                runCatching { node.captureToImage().toPixelMap() }.getOrNull()?.let {
                    it[it.width / 2, it.height / 2] == Color(0xFFE05040)
                } == true
            }
        }
        fun capture(name: String) {
            compose.waitForIdle()
            val output = requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir"))
            val destination = File(output, "ui-verification/$name.png").apply { parentFile!!.mkdirs() }
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            destination.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            runBlocking {
                app.controller.updatePreferences { it.copy(themeMode = ThemeMode.DARK, amoledBlack = true, colors = ColorMode.ARTWORK,
                    libraryTabs = LibraryTab.entries.toList(), includeFolders = emptyList(), excludeFolders = emptyList(), songSort = LibrarySort.TITLE,
                    songOrderReversed = false) }.join()
                app.controller.refresh()
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithContentDescription("Search your library").assertDoesNotExist()
                val row = compose.onNodeWithText("A song 01").fetchSemanticsNode().boundsInRoot
                assertTrue(row.top < compose.onRoot().fetchSemanticsNode().boundsInRoot.height * .25f)
                capture("library")
                runBlocking {
                    app.controller.playTrack(tracks[30].id, tracks.map { it.id }, 30).join()
                    app.controller.pause().join()
                }
                compose.waitUntil(5_000) { app.controller.state.value.playback.trackId == tracks[30].id }
                assertCover("Mini-player artwork: A song 31")
                capture("mini-player")
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithTag("mini-player").assertDoesNotExist()
                assertCover("Album artwork: A song 31")
                compose.onNodeWithContentDescription("Previous track").assertIsDisplayed()
                compose.onNodeWithText("Previous").assertDoesNotExist()
                compose.waitUntil(5_000) { app.controller.state.value.playback.audioInfo != null }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("playing-audio-info").fetchSemanticsNodes().isNotEmpty() }
                val audioInfo = compose.onNodeWithTag("playing-audio-info")
                if (runCatching { audioInfo.assertIsDisplayed() }.isFailure) audioInfo.performScrollTo()
                audioInfo.assertIsDisplayed()
                capture("now-playing")
                compose.onNodeWithContentDescription("Audio effects").performClick()
                compose.onNodeWithText("Audio effects").assertIsDisplayed()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Previous track").assertIsDisplayed()
                compose.onNodeWithContentDescription("Queue").performClick()
                compose.onNodeWithTag("queue-entry:${app.controller.state.value.queue.currentEntryId}").assertIsDisplayed()
                assertCover("Queue artwork: ${app.controller.state.value.queue.currentEntryId}")
                capture("queue")
                compose.onNodeWithContentDescription("Now playing").performClick()
                compose.onNodeWithContentDescription("Collapse").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithTag("library-pager").assertDoesNotExist()
                capture("settings")
                compose.onNodeWithText("Local music").performClick()
                compose.onNodeWithText("Include folders").assertIsDisplayed()
                compose.onNodeWithText("Exclude folders").assertIsDisplayed()
                capture("local-music")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Albums").performClick()
                compose.onNodeWithContentDescription("Collection view").performClick()
                compose.onNodeWithText("Grid").performClick()
                compose.onNodeWithContentDescription("Collection view").performClick()
                compose.onNodeWithText("3 columns").performClick()
                val first = compose.onNodeWithText("Fixture Album A").fetchSemanticsNode().boundsInRoot
                val third = compose.onNodeWithText("Fixture Album C").fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(first.top - third.top) < 2)
                capture("albums-grid")
                compose.onNodeWithText("Fixture Album A").performClick()
                compose.onNodeWithContentDescription("Back").assertIsDisplayed()
                capture("album-songs")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("More").performClick()
                capture("more-pages")
            }
        } finally {
            runBlocking { app.controller.pause().join(); app.controller.updatePreferences { original }.join() }
            audio.delete()
        }
    }
}
