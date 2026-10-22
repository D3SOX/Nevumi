package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import me.d3sox.nevumi.settings.ColorMode
import me.d3sox.nevumi.settings.ThemeMode
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.*
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class QueueDesktopUiTest {
    private fun withPlayer(test: ComposeUiTest.(MusicController, List<Track>) -> Unit) {
        val directory = Files.createTempDirectory("desktop-queue-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val tracks = runBlocking { (0 until 40).map { library.importTrack(TrackMetadata("Track $it", "Artist $it", durationMs = 3_000), TrackSource("source:$it", SourceKind.LOCAL, audio)) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(1000.dp, 740.dp)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.updatePreferences { it.copy(showPreviousQueueTracks = true, previousQueueTrackLimit = 5) }.join(); controller.playTrack(tracks[1].id, tracks.map { it.id }, 1).join(); controller.pause().join() }
                onNodeWithTag("mini-player").performClick(); onNodeWithContentDescription("Queue").performClick()
                test(controller, tracks)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun theRepeatCountFieldUsesTheAvailableDialogWidth() = withPlayer { _, _ ->
        onNodeWithContentDescription("Now playing").performClick()
        onNodeWithContentDescription("Repeat").performClick()
        onNodeWithText("Repeat count").performClick()
        val field = onNode(hasSetTextAction() and hasText("Total plays")).fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(field.width - with(density) { 512.dp.toPx() }) < 2f,
            "The input should fill the dialog body: field=${field.width}")
        onNodeWithText("Cancel").performClick()
    }

    @Test fun rightClickingAQueuedTrackOpensItsActionsWithoutChangingPlayback() = withPlayer { controller, tracks ->
        val before = controller.state.value.queue
        val entry = before.entries.first { it.trackId == tracks[2].id }
        onNodeWithTag("queue-entry:${entry.id}").performMouseInput { click(button = MouseButton.Secondary) }
        onNodeWithTag("track-actions").assertIsDisplayed()
        onNodeWithText("Remove from queue").assertExists()
        assertEquals(before, controller.state.value.queue)
        onNodeWithText("Add to queue").performClick()
        waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == before.entries.size + 1 }
    }

    @Test fun theMiniPlayerVolumeIsBeforePlaybackControlsAndDraggingDoesNotSkipOrExpand() = withPlayer { controller, _ ->
        onNodeWithContentDescription("Now playing").performClick()
        onNodeWithContentDescription("Collapse").performClick()
        val before = controller.state.value.queue.currentEntryId
        val volume = onNodeWithContentDescription("App volume")
        assertTrue(volume.fetchSemanticsNode().boundsInRoot.right < onNodeWithContentDescription("Previous track").fetchSemanticsNode().boundsInRoot.left)
        volume.performTouchInput { swipe(centerLeft, centerRight, durationMillis = 500) }
        waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.volume > .9 }
        assertEquals(before, controller.state.value.queue.currentEntryId)
        onNodeWithTag("mini-player").assertIsDisplayed()
        onNodeWithContentDescription("Collapse").assertDoesNotExist()
    }

    @Test fun highlightedTrackMetadataStaysReadableAcrossLightAndDarkAccentColors() = withPlayer { controller, _ ->
        for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (seed in listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF)) {
            runBlocking { controller.updatePreferences { it.copy(themeMode = mode, colors = ColorMode.CUSTOM, customColor = seed) }.join() }
            val tag = "queue-entry:${controller.state.value.queue.currentEntryId}"
            val row = onNodeWithTag(tag)
            val pixels = row.captureToImage().toPixelMap()
            val background = pixels[pixels.width / 2, pixels.height / 2].luminance()
            for (label in listOf("Track 1", "Artist 1", "0:03")) {
                val layouts = mutableListOf<TextLayoutResult>()
                onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val foreground = layouts.single().layoutInput.style.color.luminance()
                val contrast = (maxOf(background, foreground) + .05f) / (minOf(background, foreground) + .05f)
                assertTrue(contrast >= 4.5f, "$label has $contrast contrast for $mode / $seed")
            }
        }
    }

    @Test fun theAppVolumeSliderControlsPlaybackInTheQueueAndNowPlaying() = withPlayer { controller, _ ->
        val slider = onNodeWithContentDescription("App volume")
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.25f) }
        waitUntil(timeoutMillis = 3_000) { kotlin.math.abs(controller.engine.status.value.volume - .25) < .01 }
        assertEquals(false, controller.engine.status.value.playWhenReady)
        onNodeWithContentDescription("Now playing").performClick()
        onNodeWithContentDescription("App volume").assertIsDisplayed()
            .assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.25f, 0f..1f))
        runBlocking { controller.setVolume(.6).join() }
        waitUntil(timeoutMillis = 3_000) { kotlin.math.abs(controller.state.value.playback.volume - .6) < .01 }
        onNodeWithContentDescription("App volume").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(.6f, 0f..1f))
    }

    @Test fun playbackSlidersUseThePurpleAccentForTheirInactiveTrack() = withPlayer { _, _ ->
        onNodeWithContentDescription("Now playing").performClick()
        onNodeWithContentDescription("Collapse").performClick()
        onNodeWithContentDescription("Settings").performClick()
        onNodeWithText("Playback").performClick()
        listOf("Playback speed", "Playback pitch", "Crossfade", "Previous track limit").forEach { label ->
            val pixels = onNodeWithContentDescription(label).performScrollTo().captureToImage().toPixelMap()
            val color = pixels[pixels.width * 4 / 5, pixels.height / 2 + 2]
            assertTrue(color.blue > color.green + .02f, "$label uses the purple playback accent: $color")
        }
    }

    @Test fun theCurrentQueueSongUsesThePurpleAccentInsteadOfAContrastingGreen() = withPlayer { controller, _ ->
        val current = onNodeWithTag("queue-entry:${controller.state.value.queue.currentEntryId}")
        val pixels = current.captureToImage().toPixelMap()
        val color = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(color.blue > color.green + .02f, "The selected queue song uses the purple playback accent: $color")
    }

    @Test fun scrollingTheMouseWheelAtTheTopDoesNotPullOrResizeThePlayer() = withPlayer { _, _ ->
        val list = onNode(hasScrollToIndexAction())
        list.performScrollToIndex(0)
        val header = onNodeWithTag("queue-mini-player")
        val bounds = header.fetchSemanticsNode().boundsInRoot
        mainClock.autoAdvance = false
        list.performMouseInput { moveTo(center); scroll(-300f) }
        mainClock.advanceTimeBy(80)
        assertEquals(bounds, header.fetchSemanticsNode().boundsInRoot, "Mouse wheel scrolling must not pull the player panel")
        onNodeWithTag("queue:current").assertIsDisplayed()
        mainClock.autoAdvance = true
    }

    @Test fun openingTheQueueAnchorsTheCurrentSongAndKeepsHistoryScrollable() = withPlayer { controller, tracks ->
        val list = onNode(hasScrollToIndexAction())
        val heading = onNodeWithTag("queue:current").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(heading.top - list.fetchSemanticsNode().boundsInRoot.top) < 2f)
        assertTrue(onAllNodesWithText("Previously played").fetchSemanticsNodes().isEmpty())
        runBlocking { controller.skip(1).join() }
        val next = onNodeWithTag("queue:current").fetchSemanticsNode().boundsInRoot
        assertTrue(kotlin.math.abs(next.top - list.fetchSemanticsNode().boundsInRoot.top) < 2f)
        list.performScrollToIndex(0)
        onNodeWithText("Previously played").assertIsDisplayed()
        onNodeWithText(tracks[0].metadata.title).assertIsDisplayed()
    }

    @Test fun removingAPreviousTrackKeepsTheExpandedHistoryScrollPosition() = withPlayer { controller, tracks ->
        runBlocking { controller.skip(19).join() }
        val list = onNode(hasScrollToIndexAction())
        list.performScrollToIndex(0)
        onNodeWithText("Show all 20").performClick()
        list.performScrollToIndex(0)
        onNodeWithText(tracks[0].metadata.title).assertIsDisplayed()
        val removed = controller.state.value.queue.entries.first { it.trackId == tracks[1].id }
        runBlocking { controller.removeQueueEntry(removed.id).join() }
        waitForIdle()
        onNodeWithText("Previously played").assertIsDisplayed()
        onNodeWithText(tracks[0].metadata.title).assertIsDisplayed()
    }

    @Test fun replacingTheQueueDropsTheOldRowsInsteadOfFadingThemOverTheNewOnes() = withPlayer { controller, tracks ->
        // Fading rows have no semantics, so compare what the list draws against its settled state.
        val list = onNode(hasScrollToIndexAction())
        waitForIdle()
        mainClock.autoAdvance = false
        runBlocking { controller.playTrack(tracks[3].id, tracks.map { it.id }, 3).join() }
        repeat(6) { mainClock.advanceTimeByFrame() }
        val switching = list.captureToImage().toPixelMap()
        mainClock.autoAdvance = true
        waitForIdle()
        val settled = list.captureToImage().toPixelMap()
        assertEquals(switching.width to switching.height, settled.width to settled.height)
        val changed = (0 until settled.width).sumOf { x -> (0 until settled.height).count { y -> switching[x, y] != settled[x, y] } }
        assertEquals(0, changed, "Rows from the replaced queue must not stay drawn over the new queue")
    }

    @Test fun anEmptyManualQueueHasNoHeadingOrRemainingTime()= withPlayer { controller, tracks ->
        onNodeWithText("Up next").assertDoesNotExist()
        onNodeWithContentDescription("Manual queue time remaining").assertDoesNotExist()
        runBlocking { controller.enqueue(tracks[0].id, QueueAction.APPEND).join() }
        onNodeWithText("Up next").assertIsDisplayed()
        onNodeWithContentDescription("Manual queue time remaining").assertIsDisplayed()
    }

    @Test fun queueTracksOpenTheirActionsOnLongPressAndToastsMoveDuringThePlayerTransition() = withPlayer { controller, tracks ->
        val before = controller.state.value.queue
        onNodeWithTag("queue-entry:${before.currentEntryId}").performTouchInput { longClick() }
        onNodeWithText("Remove from queue").assertIsDisplayed()
        assertEquals(before.currentEntryId, controller.state.value.queue.currentEntryId)
        onNodeWithContentDescription("Close song actions").performClick()
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("track-actions").fetchSemanticsNodes().isEmpty() }
        onNodeWithContentDescription("Now playing").performClick()
        onNodeWithContentDescription("Collapse").performClick()
        runBlocking { controller.setFavorite(tracks[1].id, true).join() }
        onNodeWithText("Undo").assertIsDisplayed()
        val bottom = onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
        mainClock.autoAdvance = false
        onNodeWithTag("mini-player").performClick()
        mainClock.advanceTimeBy(100)
        val between = onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
        mainClock.advanceTimeBy(500)
        val top = onNodeWithTag("action-snackbar").fetchSemanticsNode().boundsInRoot.top
        assertTrue(between < bottom - 10f && between > top + 10f, "The toast should move through intermediate positions: $bottom / $between / $top")
        mainClock.autoAdvance = true
        onNodeWithText("Undo").performClick()
        onNodeWithText("Redo").assertIsDisplayed().performClick()
        onNodeWithText("Undo").assertIsDisplayed()
        assertTrue(controller.state.value.tracks.first { it.id == tracks[1].id }.favorite)
    }
}
