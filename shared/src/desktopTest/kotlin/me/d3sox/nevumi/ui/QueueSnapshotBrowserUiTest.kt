package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.*
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.ColorMode
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class QueueSnapshotBrowserUiTest {
    @Test fun fixedCopiesCanBePreviewedDuplicatedConvertedRestoredAndExportedFromTheBrowser() {
        val directory = Files.createTempDirectory("queue-snapshot-browser")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val queue = PlaybackQueue(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val tracks = runBlocking { (1..3).map { library.importTrack(TrackMetadata("Song $it", "Artist", durationMs = 3_000), TrackSource("source:$it", SourceKind.LOCAL, audio)) } }
        val controller = MusicController(library, queue, MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store))
        var document: Pair<String, String>? = null
        val files = object : PlaylistFiles {
            override suspend fun open(): PlaylistDocument? = null
            override suspend fun save(name: String, text: String): Boolean { document = name to text; return true }
        }
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, playlistFiles = files) }
                waitForLibrary(controller)
                runBlocking {
                    controller.playTrack(tracks[1].id, listOf(tracks[0].id, tracks[1].id, tracks[1].id), 2).join(); controller.pause().join()
                    controller.enqueue(listOf(tracks[2].id, tracks[2].id), QueueAction.APPEND).join(); controller.seek(1_000).join()
                }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.playback.positionMs in 950..1_050 }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Saved queues").performScrollTo().performClick()
                onNodeWithText("Save current queue").performClick()
                onNodeWithContentDescription("Queue name").performTextReplacement("Checkpoint")
                onNodeWithContentDescription("Save as").performClick()
                onNode(hasText("Fixed snapshot") and hasAnyAncestor(isPopup())).performClick()
                onNodeWithText("Save queue").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.savedQueues.size == 1 }
                val fixed = controller.state.value.savedQueues.single()
                assertEquals(SavedQueueKind.SNAPSHOT, fixed.kind)
                assertNull(controller.state.value.queue.selectedSavedQueueId)
                val dismissals = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                if (dismissals.fetchSemanticsNodes().isNotEmpty()) dismissals[0].performSemanticsAction(SemanticsActions.Dismiss) { it() }
                captureUiSnapshot("queue-snapshot-browser")
                onNode(hasText("Checkpoint") and hasClickAction()).performClick()
                onNodeWithText("Previously played").assertIsDisplayed()
                onNodeWithText("Now playing").assertIsDisplayed()
                onNodeWithText("Up next").assertIsDisplayed()
                captureUiSnapshot("queue-snapshot-preview")
                val appearance = controller.state.value.preferences
                for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) for (seed in listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF)) {
                    runBlocking { controller.updatePreferences { it.copy(themeMode = theme, colors = ColorMode.CUSTOM, customColor = seed) }.join() }
                    val label = "Snapshot song: ${fixed.snapshot.currentEntryId}"
                    val pixels = onNodeWithContentDescription(label).captureToImage().toPixelMap()
                    val background = pixels[pixels.width / 2, pixels.height / 2].luminance()
                    for (text in listOf("Song 2", "Artist")) {
                        val layouts = mutableListOf<TextLayoutResult>()
                        onNode(hasText(text) and hasAnyAncestor(hasContentDescription(label)), useUnmergedTree = true)
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        val foreground = layouts.single().layoutInput.style.color.luminance()
                        val contrast = (maxOf(background, foreground) + .05f) / (minOf(background, foreground) + .05f)
                        assertTrue(contrast >= 4.5f, "The highlighted snapshot $text must remain readable: $contrast for $theme / $seed")
                    }
                }
                runBlocking { controller.updatePreferences { it.copy(themeMode = appearance.themeMode, colors = appearance.colors, customColor = appearance.customColor) }.join() }
                onNodeWithContentDescription("Saved queue options: Checkpoint").performClick()
                onNodeWithText("Duplicate").performClick()
                onNode(hasSetTextAction() and hasText("Name")).performTextReplacement("Live copy")
                onNodeWithText("Duplicate", substring = false).performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.savedQueues.size == 2 }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Saved queue options: Live copy").performClick()
                onNodeWithText("Convert to live queue").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.savedQueues.first { it.name == "Live copy" }.kind == SavedQueueKind.LIVE }
                onNodeWithContentDescription("Live queues").assertIsSelected()
                onNodeWithContentDescription("Load queue: Live copy").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.selectedSavedQueueId != null }
                runBlocking { controller.enqueue(tracks[0].id, QueueAction.APPEND).join() }
                assertEquals(fixed.snapshot, runBlocking { queue.saved().first { it.id == fixed.id }.snapshot })
                onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss))[0]
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
                onNodeWithText("Saved queues").performScrollTo().performClick()
                onNodeWithContentDescription("Snapshots").performClick()
                onNode(hasText("Checkpoint") and hasClickAction()).performClick()
                onNodeWithText("Export queue").performClick()
                onNodeWithContentDescription("Include").performClick()
                onNode(hasText("Up next") and hasAnyAncestor(isPopup())).performClick()
                onNodeWithText("Save as playlist").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.playlists.any { it.name == "Checkpoint" } }
                assertEquals(listOf(tracks[2].id, tracks[2].id), controller.state.value.playlists.single().trackIds)
                onNodeWithText("Export queue").performClick()
                onNodeWithText("Export M3U").performClick()
                waitUntil(timeoutMillis = 5_000) { document != null }
                assertEquals("Checkpoint.m3u", document!!.first)
                assertEquals(5, document!!.second.lineSequence().count { it.isNotBlank() && !it.startsWith('#') })
                onNodeWithText("Restore snapshot").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.queue.currentEntryId == fixed.snapshot.currentEntryId && controller.state.value.queue.selectedSavedQueueId == null }
                assertEquals(fixed.snapshot.entries, controller.state.value.queue.entries)
                assertTrue(controller.state.value.playback.positionMs in 950..1_050)
                assertNull(controller.state.value.error)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
