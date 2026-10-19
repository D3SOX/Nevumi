package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidLibraryUpdatesUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test
    fun searchMembershipRatingsAndSplitQueueWorkWithNativeTouch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val audio = File(app.filesDir, "library-updates.flac")
        instrumentation.context.assets.open("fixture-cover.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(app.filesDir, "library-updates/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { !controller.state.value.loading }
                val songs = runBlocking {
                    val cover = org.jaudiotagger.audio.AudioFileIO.read(audio).tag.firstArtwork.binaryData
                    val tracks = (1..50).map { index ->
                        val song = controller.library.importTrack(TrackMetadata("Song ${index.toString().padStart(2, '0')}",
                            "Fixture artist", "Fixture album", durationMs = 3_000), TrackSource("fixture-$index", SourceKind.LOCAL, audio.path))
                        ArtworkLibrary(File(app.filesDir, "artwork").path).save(song.id, cover)
                        song
                    }
                    controller.reload().join()
                    controller.updatePreferences { it.copy(themeMode = ThemeMode.DARK, amoledBlack = true) }.join()
                    controller.createPlaylist("Evening").join()
                    tracks
                }
                compose.onNodeWithContentDescription("Scroll library").assertIsDisplayed()
                val menu = compose.onNodeWithContentDescription("Track options: Song 01")
                val scrollbar = compose.onNodeWithContentDescription("Scroll library").fetchSemanticsNode().boundsInRoot
                assertTrue(menu.fetchSemanticsNode().boundsInRoot.right <= scrollbar.left + 1)
                menu.performTouchInput { click(center) }
                compose.onNodeWithText("Rate song").performClick()
                compose.onNodeWithContentDescription("4 stars").performClick()
                compose.onNodeWithText("Confirm").performClick()
                compose.waitUntil(5_000) { controller.state.value.tracks.first { it.id == songs[0].id }.rating == 4 }
                compose.onNodeWithContentDescription("Rating: 4 stars").assertIsDisplayed()
                menu.performClick()
                compose.onNodeWithText("4 / 5").assertIsDisplayed()
                compose.onNodeWithText("Select song").performClick()
                compose.onNodeWithContentDescription("Select all").performClick()
                compose.onNodeWithContentDescription("Select none").performClick()
                compose.onNodeWithText("0 songs selected").assertIsDisplayed()
                compose.onNodeWithContentDescription("Clear selection").performClick()
                capture("library")
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithContentDescription("Search settings").performClick()
                compose.onNodeWithContentDescription("Search settings query").performTextInput("crossfade")
                capture("settings-search")
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss)).onLast()
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
                compose.onNodeWithText("Crossfade").performClick()
                compose.onNodeWithContentDescription("Crossfade").performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                runBlocking {
                    controller.playTrack(songs[0].id, songs.take(3).map { it.id }, contextName = "Fixture album").join()
                    controller.pause().join()
                }
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithContentDescription("Shuffle songs").assertDoesNotExist()
                compose.onNodeWithContentDescription("Shuffle albums").assertDoesNotExist()
                compose.onNodeWithContentDescription("Manage playlists").assertIsNotSelected().performClick()
                compose.onNodeWithContentDescription("Playlist artwork: Evening").assertIsDisplayed()
                compose.onNodeWithContentDescription("Playlist membership: Evening").assertIsOff().performClick()
                capture("playlist-membership")
                compose.onNodeWithText("Apply").performClick()
                compose.waitUntil(5_000) { controller.state.value.playlists.single().trackIds == listOf(songs[0].id) }
                compose.onNodeWithContentDescription("Manage playlists").assertIsSelected()
                compose.onNodeWithContentDescription("Favorite").performClick()
                compose.waitUntil(5_000) { controller.state.value.tracks.first { it.id == songs[0].id }.favorite }
                capture("now-playing")
                compose.onNodeWithContentDescription("Queue").performClick()
                compose.onNodeWithText("Playing from Fixture album").assertIsDisplayed()
                compose.onNodeWithContentDescription("Shuffle").assertDoesNotExist()
                val current = controller.state.value.queue.currentEntryId
                val moving = controller.state.value.queue.context.first()
                val header = compose.onNodeWithTag("queue:manual").fetchSemanticsNode().boundsInRoot
                val minimumCenter = header.bottom + compose.onNodeWithTag("queue-entry:${moving.id}").fetchSemanticsNode().boundsInRoot.height / 2
                fun handle() = compose.onNodeWithContentDescription("Reorder: Song 02", useUnmergedTree = true)
                val start = handle().fetchSemanticsNode().boundsInRoot.center
                val originalHeadingTop = compose.onNodeWithText("Playing from Fixture album").fetchSemanticsNode().boundsInRoot.top
                capture("queue-before-drag")
                handle().performTouchInput {
                    down(center); advanceEventTime(600)
                    moveBy(androidx.compose.ui.geometry.Offset(0f, -64f), delayMillis = 50)
                }
                compose.waitForIdle()
                var pointerY = start.y - 64
                val grabOffset = pointerY - handle().fetchSemanticsNode().boundsInRoot.center.y
                val stepDistance = (header.center.y + grabOffset - pointerY) / 9
                repeat(9) { step ->
                    handle().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, stepDistance), delayMillis = 50) }
                    pointerY += stepDistance
                    compose.waitForIdle()
                    val centerY = handle().fetchSemanticsNode().boundsInRoot.center.y
                    assertTrue("The first context song must stay attached: step=$step, pointer=$pointerY, handle=$centerY",
                        kotlin.math.abs((pointerY - grabOffset).coerceAtLeast(minimumCenter) - centerY) < 8)
                }
                assertTrue("Section headings move aside during the drag",
                    compose.onNodeWithText("Playing from Fixture album").fetchSemanticsNode().boundsInRoot.top > originalHeadingTop + 20)
                capture("queue-during-drag")
                handle().performTouchInput { up() }
                compose.waitUntil(5_000) { controller.state.value.queue.upNext.singleOrNull()?.id == moving.id }
                compose.onNodeWithContentDescription("Manual queue time remaining").assertTextEquals("0:03 remaining")
                compose.onNodeWithContentDescription("Now playing").performClick()
                compose.onNodeWithContentDescription("Shuffle").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.unshuffledEntryIds != null }
                assertEquals(moving.id, controller.state.value.queue.upNext.single().id)
                compose.onNodeWithContentDescription("Queue").performClick()
                capture("split-queue")
                compose.onNodeWithContentDescription("Clear queue").performClick()
                compose.onNodeWithText("All").performClick()
                compose.onNodeWithText("Cancel").performClick()
                assertEquals(3, controller.state.value.queue.entries.size)
                compose.onNodeWithContentDescription("Clear queue").performClick()
                compose.onNodeWithText("All").performClick()
                compose.onNodeWithText("Clear").performClick()
                compose.waitUntil(5_000) { controller.state.value.queue.entries.size == 1 }
                assertEquals(current, controller.state.value.queue.currentEntryId)
                compose.onNodeWithContentDescription("Now playing").performClick()
                compose.onNodeWithContentDescription("Collapse").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithContentDescription("Search settings").performClick()
                compose.onNodeWithContentDescription("Search settings query").performTextInput("launch")
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss)).onLast()
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
                compose.onNodeWithText("On launch").performClick()
                compose.onNodeWithContentDescription("On launch").performClick()
                compose.onNodeWithText("Albums").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.startupTab == LibraryTab.ALBUMS }
                capture("startup-setting")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                runBlocking {
                    controller.updatePreferences { it.copy(showPreviousQueueTracks = false) }.join()
                    controller.playTrack(songs[2].id, songs.take(3).map { it.id }, 2).join()
                    controller.pause().join()
                }
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithContentDescription("Queue").performClick()
                compose.onNodeWithText("Previously played").assertDoesNotExist()
                compose.onNodeWithContentDescription("Now playing").performClick()
                compose.onNodeWithContentDescription("Collapse").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithContentDescription("Search settings").performClick()
                compose.onNodeWithContentDescription("Search settings query").performTextInput("previous tracks")
                compose.onNodeWithText("Show previous tracks in queue").performClick()
                compose.onNodeWithContentDescription("Show previous tracks in queue").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Previous track limit").performScrollTo()
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1f) }
                compose.waitUntil(5_000) { controller.state.value.preferences.previousQueueTrackLimit == 1 }
                capture("previous-track-setting")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithContentDescription("Queue").performClick()
                compose.onNodeWithText("Previously played").assertIsDisplayed()
                val entries = controller.state.value.queue.entries
                compose.onNodeWithTag("queue-entry:${entries[0].id}").assertDoesNotExist()
                compose.onNodeWithTag("queue-entry:${entries[1].id}").assertIsDisplayed()
                runBlocking { controller.skip(-1, restartPrevious = false).join() }
                assertEquals(songs[1].id, controller.state.value.queue.currentTrackId)
            }
        } finally { runBlocking { controller.closeAndJoin() }; audio.delete() }
    }
}
