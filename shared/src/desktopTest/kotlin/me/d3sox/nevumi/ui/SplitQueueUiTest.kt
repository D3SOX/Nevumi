package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.*
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.geometry.Offset

@OptIn(ExperimentalTestApi::class)
class SplitQueueUiTest {
    @Test fun expandingPreviousTracksOnlyChangesTheirVisibility() {
        val directory = Files.createTempDirectory("queue-history-visibility")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val tracks = runBlocking { (0..9).map { library.importTrack(TrackMetadata("History song $it"),
            TrackSource("history:$it", SourceKind.LOCAL, audio)) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = me.d3sox.nevumi.settings.MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                runBlocking {
                    controller.updatePreferences { it.copy(previousQueueTrackLimit = 2) }.join()
                    controller.playTrack(tracks[8].id, tracks.map { it.id }, 8).join(); controller.pause().join()
                    controller.saveQueue("Full history").join()
                }
                val before = controller.state.value.queue
                onNodeWithTag("mini-player").performClick(); onNodeWithContentDescription("Queue").performClick()
                onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                onNodeWithTag("queue-entry:${before.entries[0].id}").assertDoesNotExist()
                onNodeWithText("Show all 8").performClick()
                onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                onNodeWithTag("queue-entry:${before.entries[0].id}").assertIsDisplayed()
                onNodeWithText("Show less").performClick()
                onNodeWithTag("queue-entry:${before.entries[0].id}").assertDoesNotExist()
                assertEquals(before.entries, controller.state.value.queue.entries)
                assertEquals(before.currentEntryId, controller.state.value.queue.currentEntryId)
                assertEquals(before.entries, controller.state.value.savedQueues.single().snapshot.entries)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun aRepeatedQueueSongMovesItsOwnCoverToTheMenuAndBack() {
        val directory = Files.createTempDirectory("queue-cover-menu")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking {
                    controller.playTrack(tracks[0].id, tracks.map { it.id }).join(); controller.pause().join()
                    controller.enqueue(listOf(tracks[1].id, tracks[1].id), QueueAction.APPEND).join()
                }
                onNodeWithTag("mini-player").performClick(); onNodeWithContentDescription("Queue").performClick()
                val entry = controller.state.value.queue.upNext.last()
                val row = hasTestTag("queue-entry:${entry.id}")
                onNode(hasScrollAction() and hasAnyDescendant(hasText("Up next"))).performScrollToNode(row)
                fun screenCenter(node: SemanticsNodeInteraction): Offset {
                    val semantics = node.fetchSemanticsNode()
                    return semantics.positionOnScreen - semantics.positionInRoot + semantics.boundsInRoot.center
                }
                val cover = onNodeWithContentDescription("Queue artwork: ${entry.id}", useUnmergedTree = true)
                val origin = screenCenter(cover)
                mainClock.autoAdvance = false
                onNode(hasContentDescription("Track options: ${tracks[1].metadata.title}") and hasAnyAncestor(row)).performClick()
                mainClock.advanceTimeByFrame()
                val moving = onNodeWithTag("track-action-artwork", useUnmergedTree = true)
                assertTrue((screenCenter(moving) - origin).getDistance() < 3f, "The selected queue occurrence must supply its cover")
                mainClock.advanceTimeBy(400)
                val opened = screenCenter(moving)
                assertTrue((opened - origin).getDistance() > 20f)
                onNodeWithContentDescription("Close song actions").performClick()
                mainClock.advanceTimeBy(96)
                val returning = screenCenter(moving)
                assertTrue((returning - opened).getDistance() > 2f && (returning - origin).getDistance() > 2f)
                mainClock.advanceTimeBy(400)
                onNodeWithTag("track-actions").assertDoesNotExist()
                assertTrue((screenCenter(cover) - origin).getDistance() < 2f)
                assertEquals(entry.id, controller.state.value.queue.upNext.last().id)
                assertEquals(false, controller.state.value.playback.playing)
                mainClock.autoAdvance = true
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun onlyUpcomingQueueSongsHaveReorderControls() {
        val directory = Files.createTempDirectory("queue-reorder-controls")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = me.d3sox.nevumi.settings.MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking {
                    controller.updatePreferences { it.copy(showPreviousQueueTracks = true, previousQueueTrackLimit = 10) }.join()
                    controller.playTrack(tracks[1].id, tracks.map { it.id }, 1).join(); controller.pause().join()
                }
                onNodeWithTag("mini-player").performClick(); onNodeWithContentDescription("Queue").performClick()
                onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                onNodeWithContentDescription("Reorder: ${tracks[0].metadata.title}", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithContentDescription("Reorder: ${tracks[1].metadata.title}", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithContentDescription("Reorder: ${tracks[2].metadata.title}", useUnmergedTree = true).assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun playingALibrarySongDoesNotRecreateContextWhenTheLastManualRequestIsCurrent() {
        val directory = Files.createTempDirectory("play-swipe-last-request")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val requested = tracks.maxBy { it.metadata.title.lowercase() }
        val selected = tracks.minBy { it.metadata.title.lowercase() }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = me.d3sox.nevumi.settings.MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking {
                    controller.enqueue(requested.id, QueueAction.APPEND).join()
                    controller.selectQueueEntry(controller.state.value.queue.currentEntryId!!).join(); controller.pause().join()
                }
                assertEquals(emptyList(), controller.state.value.queue.upNext)
                assertEquals(QueueSection.MANUAL, controller.state.value.queue.entries.single().section)
                onNodeWithTag("track-row:${selected.id}", useUnmergedTree = true).performTouchInput {
                    click(center)
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentTrackId == selected.id }
                assertEquals(emptyList(), controller.state.value.queue.context, "Playing from a manual queue must not recreate a library context")
                assertEquals(emptyList(), controller.state.value.queue.upNext)
                assertEquals(QueueSection.MANUAL, controller.state.value.queue.entries[controller.state.value.queue.currentIndex].section)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun playingDifferentLibrarySongsPreservesEveryManualRequest() {
        val directory = Files.createTempDirectory("play-swipe-manual-queue")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking {
                    controller.playTrack(tracks[0].id, tracks.map { it.id }).join(); controller.pause().join()
                    controller.clearQueue().join()
                    controller.enqueue(listOf(tracks[1].id, tracks[1].id, tracks[2].id), QueueAction.APPEND).join()
                }
                val requested = controller.state.value.queue.upNext
                for (index in listOf(2, 1)) {
                    val row = onNodeWithTag("track-row:${tracks[index].id}", useUnmergedTree = true)
                    row.performTouchInput { click(center) }
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentTrackId == tracks[index].id }
                    assertEquals(requested, controller.state.value.queue.upNext, "Playing a song must retain every requested occurrence")
                    assertEquals(emptyList(), controller.state.value.queue.context, "Playing a song must not recreate a cleared context")
                    runBlocking { controller.pause().join() }
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun tappingALibrarySongPreservesManualRequestsAndDoesNotRecreateAClearedContext() {
        val directory = Files.createTempDirectory("keep-manual-queue")
        val database = directory.resolve("music.db").toString()
        val store = openMusicStore(database)
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking {
                    controller.playTrack(tracks[0].id, tracks.map { it.id }).join(); controller.pause().join()
                    controller.clearQueue().join()
                    controller.enqueue(listOf(tracks[1].id, tracks[1].id, tracks[2].id), QueueAction.APPEND).join()
                }
                val requested = controller.state.value.queue.upNext
                onNodeWithText(tracks[2].metadata.title, substring = false).performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.currentTrackId == tracks[2].id }
                assertEquals(requested, controller.state.value.queue.upNext, "Playing a song must retain every queued occurrence")
                assertEquals(emptyList(), controller.state.value.queue.context, "A cleared context must stay empty")
                runBlocking { controller.pause().join() }
                val restored = runBlocking { PlaybackQueue(store).snapshot() }
                assertEquals(requested, restored.upNext)
                assertEquals(emptyList(), restored.context)
                openLibraryPage("Albums"); onNodeWithText("Fixture Album").performClick()
                onNodeWithContentDescription("Play collection").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.contextName == "Fixture Album" }
                assertEquals(emptyList(), controller.state.value.queue.upNext, "An explicit album Play starts its context")
                assertEquals(2, controller.state.value.queue.context.size)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun clearingAScrolledQueueKeepsTheCurrentSongAndUpdatesTheScrollbarSafely() {
        val directory = Files.createTempDirectory("clear-scrolled-queue")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val tracks = runBlocking { (0 until 40).map { index ->
            library.importTrack(TrackMetadata("Song $index", durationMs = 3_000), TrackSource("source:$index", SourceKind.LOCAL, audio))
        } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(tracks[0].id, tracks.map { it.id }).join(); controller.pause().join() }
                val current = controller.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick(); onNodeWithContentDescription("Queue").performClick()
                onNodeWithContentDescription("Scroll queue").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(25f) }
                waitForIdle()
                onNodeWithContentDescription("Clear queue").performClick(); onNodeWithText("All").performClick()
                onNodeWithText("Clear", substring = false).performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == 1 }
                onNodeWithTag("queue-entry:$current").assertIsDisplayed()
                assertEquals(current, controller.state.value.queue.currentEntryId)
                onNodeWithText("Undo").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == 40 }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun jumpingPastTheNextSongRequiresConfirmationButCanBeDisabled() {
        val directory = Files.createTempDirectory("queue-jump-confirmation")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
                runBlocking { player.playTrack(tracks[0].id, tracks.map { it.id }).join(); player.pause().join() }
                val entries = player.state.value.queue.entries
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithTag("queue-entry:${entries[2].id}").performClick()
                onNodeWithText("Play this song now?").assertIsDisplayed()
                assertEquals(entries[0].id, player.state.value.queue.currentEntryId)
                onNodeWithText("Cancel").performClick()
                onNodeWithTag("queue-entry:${entries[1].id}").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.currentEntryId == entries[1].id }
                onNodeWithText("Play this song now?").assertDoesNotExist()
                runBlocking { player.selectQueueEntry(entries[0].id).join(); player.pause().join() }
                onNodeWithTag("queue-entry:${entries[2].id}").performClick()
                onNodeWithText("Play now").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.currentEntryId == entries[2].id }
                runBlocking { player.selectQueueEntry(entries[0].id).join(); player.pause().join() }
                // The setting must be reachable and persistent, not just a controller flag.
                onNodeWithContentDescription("Now playing").performClick()
                onNodeWithContentDescription("Collapse").performClick()
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithContentDescription("Confirm queue jumps").performScrollTo().performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithTag("queue-entry:${entries[2].id}").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.currentEntryId == entries[2].id }
                onNodeWithText("Play this song now?").assertDoesNotExist()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun swipingAQueueSongAwayContinuesOutwardBeforeTheGapCloses() {
        val directory = Files.createTempDirectory("queue-swipe-motion")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
                runBlocking { player.playTrack(songs[0].id, songs.map { it.id }).join(); player.pause().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                val removed = player.state.value.queue.entries[1].id
                val row = onNodeWithTag("queue-entry:$removed", useUnmergedTree = true)
                waitForIdle()
                mainClock.autoAdvance = false
                row.performTouchInput { swipe(center, center - androidx.compose.ui.geometry.Offset(180f, 0f), durationMillis = 180) }
                val released = row.fetchSemanticsNode().positionInRoot.x
                mainClock.advanceTimeBy(80)
                waitForIdle()
                val flying = row.fetchSemanticsNode().positionInRoot.x
                kotlin.test.assertTrue(flying < released - 5, "The released song must continue flying out, rather than returning to its resting position: released=$released flying=$flying")
                mainClock.advanceTimeBy(300)
                mainClock.autoAdvance = true
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.entries.none { it.id == removed } }
                row.assertDoesNotExist()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun theFirstContextSongStaysUnderTheFingerAndDropsIntoUpNext() {
        val directory = Files.createTempDirectory("first-context-drag")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }.sortedBy { it.metadata.title }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking { player.playTrack(tracks[0].id, tracks.map { it.id }).join(); player.pause().join() }
                val moved = player.state.value.queue.context.first()
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                fun handle() = onNodeWithContentDescription("Reorder: ${tracks[1].metadata.title}", useUnmergedTree = true)
                val start = handle().fetchSemanticsNode().boundsInRoot.center
                val minimumCenter = onNodeWithTag("queue:manual").fetchSemanticsNode().boundsInRoot.bottom +
                    onNodeWithTag("queue-entry:${moved.id}").fetchSemanticsNode().boundsInRoot.height / 2
                handle().performTouchInput { down(center); advanceEventTime(600); moveBy(androidx.compose.ui.geometry.Offset(0f, -20f), delayMillis = 50) }
                waitForIdle()
                var pointer = start.copy(y = start.y - 20)
                val grabOffset = pointer.y - handle().fetchSemanticsNode().boundsInRoot.center.y
                val target = onNodeWithTag("queue:manual").fetchSemanticsNode().boundsInRoot.center.y
                val stepDistance = (target + grabOffset - pointer.y) / 9
                repeat(9) { step ->
                    handle().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, stepDistance), delayMillis = 50) }
                    pointer = pointer.copy(y = pointer.y + stepDistance)
                    waitForIdle()
                        val handleCenter = handle().fetchSemanticsNode().boundsInRoot.center
                        kotlin.test.assertTrue(kotlin.math.abs((pointer.y - grabOffset).coerceAtLeast(minimumCenter) - handleCenter.y) < 4,
                            "First context track must stay under the finger: step=$step, pointer=$pointer, handle=$handleCenter")
                }
                handle().performTouchInput { up() }
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.upNext.singleOrNull()?.id == moved.id }
                assertEquals(tracks[0].id, player.state.value.queue.currentTrackId)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun draggingAContextSongStopsBelowTheCurrentTrack() {
        val directory = Files.createTempDirectory("current-section-drag")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }.sortedBy { it.metadata.title }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking { player.playTrack(tracks[0].id, tracks.map { it.id }).join(); player.pause().join() }
                val moved = player.state.value.queue.context.first()
                val current = player.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                fun handle() = onNodeWithContentDescription("Reorder: ${tracks[1].metadata.title}", useUnmergedTree = true)
                val currentBounds = onNodeWithTag("queue-entry:$current").fetchSemanticsNode().boundsInRoot
                val start = handle().fetchSemanticsNode().boundsInRoot.center
                handle().performTouchInput { down(center); advanceEventTime(600); moveBy(androidx.compose.ui.geometry.Offset(0f, -20f), delayMillis = 50) }
                waitForIdle()
                var pointer = start.copy(y = start.y - 20)
                val grabOffset = pointer.y - handle().fetchSemanticsNode().boundsInRoot.center.y
                val target = onNodeWithText("Now playing", substring = false).fetchSemanticsNode().boundsInRoot.center.y - 100
                val stepDistance = (target + grabOffset - pointer.y) / 9
                repeat(9) { step ->
                    handle().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, stepDistance), delayMillis = 50) }
                    pointer = pointer.copy(y = pointer.y + stepDistance)
                    waitForIdle()
                    val bounds = onNodeWithTag("queue-entry:${moved.id}").fetchSemanticsNode().boundsInRoot
                    kotlin.test.assertTrue(bounds.top >= currentBounds.bottom - 1f,
                        "Dragged song must stop below Now playing: step=$step song=$bounds current=$currentBounds")
                    assertEquals(currentBounds, onNodeWithTag("queue-entry:$current").fetchSemanticsNode().boundsInRoot)
                }
                handle().performTouchInput { up() }
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.upNext.singleOrNull()?.id == moved.id }
                assertEquals(tracks[0].id, player.state.value.queue.currentTrackId)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aFastInitialDragCannotCrossNowPlaying() {
        val directory = Files.createTempDirectory("current-section-drag")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }.sortedBy { it.metadata.title }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking { player.playTrack(tracks[0].id, tracks.map { it.id }).join(); player.pause().join() }
                val moved = player.state.value.queue.context.first()
                val current = player.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                fun handle() = onNodeWithContentDescription("Reorder: ${tracks[1].metadata.title}", useUnmergedTree = true)
                val currentBounds = onNodeWithTag("queue-entry:$current").fetchSemanticsNode().boundsInRoot
                val start = handle().fetchSemanticsNode().boundsInRoot.center
                val target = onNodeWithTag("queue-mini-player").fetchSemanticsNode().boundsInRoot.center.y
                handle().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, target - start.y), delayMillis = 50) }
                waitForIdle()
                val bounds = onNodeWithTag("queue-entry:${moved.id}").fetchSemanticsNode().boundsInRoot
                kotlin.test.assertTrue(bounds.top >= currentBounds.bottom - 1f,
                    "A fast initial drag must stop below Now playing: song=$bounds current=$currentBounds")
                val manualHeading = onNodeWithTag("queue:manual").fetchSemanticsNode().boundsInRoot
                kotlin.test.assertTrue(bounds.top >= manualHeading.bottom,
                    "Upcoming songs must stay below Up next, outside the Now playing section: song=$bounds heading=$manualHeading")
                handle().performTouchInput { up() }
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.upNext.singleOrNull()?.id == moved.id }
                assertEquals(tracks[0].id, player.state.value.queue.currentTrackId)
                assertEquals(3, player.state.value.queue.entries.size)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun holdingAnUpcomingSongAtTheTopDuringAutoScrollCannotCrossNowPlaying() {
        val directory = Files.createTempDirectory("queue-drag-auto-scroll")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val tracks = runBlocking { (0 until 40).map { index ->
            library.importTrack(TrackMetadata("Song %02d".format(index), durationMs = 3_000), TrackSource("source:$index", SourceKind.LOCAL, audio))
        } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = me.d3sox.nevumi.settings.MusicPreferences(store))
        try {
            runComposeUiTest {
                runBlocking { controller.updatePreferences { it.copy(showPreviousQueueTracks = true, previousQueueTrackLimit = 20) }.join() }
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(tracks[20].id, tracks.map { it.id }).join(); controller.pause().join() }
                val current = controller.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick(); onNodeWithContentDescription("Queue").performClick()
                val moved = controller.state.value.queue.entries[21].id
                fun handle() = onNodeWithContentDescription("Reorder: Song 21", useUnmergedTree = true)
                val start = handle().fetchSemanticsNode().boundsInRoot.center
                val target = onNodeWithTag("queue-header").fetchSemanticsNode().boundsInRoot.center.y
                handle().performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -20f), delayMillis = 50) }
                mainClock.autoAdvance = false
                repeat(8) {
                    handle().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, (target - start.y + 20f) / 8), delayMillis = 50) }
                    mainClock.advanceTimeBy(100)
                }
                repeat(15) { step ->
                    mainClock.advanceTimeBy(100)
                    val currentRows = onAllNodesWithTag("queue-entry:$current").fetchSemanticsNodes()
                    if (currentRows.isNotEmpty()) {
                        val dragged = onNodeWithTag("queue-entry:$moved").fetchSemanticsNode().boundsInRoot
                        val playing = currentRows.single().boundsInRoot
                        kotlin.test.assertTrue(dragged.top >= playing.bottom - 1f,
                            "Holding at the scroll edge must not cross Now playing: step=$step dragged=$dragged playing=$playing")
                    }
                }
                handle().performTouchInput { up() }
                mainClock.autoAdvance = true
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.upNext.any { it.id == moved } }
                assertEquals(current, controller.state.value.queue.currentEntryId)
                assertEquals(40, controller.state.value.queue.entries.size)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun manualAndContextSongsHaveSeparateSectionsAndClearingRequiresConfirmation() {
        val directory = Files.createTempDirectory("split-queue-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }.sortedBy { it.metadata.title }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking {
                    player.playTrack(tracks[0].id, tracks.map { it.id }).join()
                    player.pause().join()
                    player.enqueue(tracks[2].id, QueueAction.APPEND).join()
                }
                val current = player.state.value.queue.currentEntryId
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithText("Up next").assertIsDisplayed()
                onNodeWithText("Playing from Library").assertIsDisplayed()
                onNodeWithContentDescription("Manual queue time remaining").assertTextEquals("0:03 remaining")
                val requested = player.state.value.queue.upNext.single()
                fun options() { onNode(hasContentDescription("Track options: ${tracks[2].metadata.title}") and
                    hasAnyAncestor(hasTestTag("queue-entry:${requested.id}"))).performClick() }
                options()
                onNodeWithText("Move to context").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.upNext.isEmpty() }
                options()
                onNodeWithText("Move to Up next").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.upNext.singleOrNull()?.id == requested.id }
                onNodeWithContentDescription("Clear queue").performClick()
                onNodeWithText("All").performClick()
                onNodeWithText("Clear all?").assertIsDisplayed()
                onNodeWithText("Cancel").performClick()
                assertEquals(4, player.state.value.queue.entries.size)
                onNodeWithContentDescription("Clear queue").performClick()
                onNodeWithText("All").performClick()
                onNodeWithText("Clear", substring = false).performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.entries.size == 1 }
                assertEquals(current, player.state.value.queue.currentEntryId)
                assertEquals(false, player.state.value.playback.playing)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
    @Test
    fun shuffleInNowPlayingOnlyShufflesTheContextAndLeavesManualRequestsAlone() {
        val directory = Files.createTempDirectory("context-shuffle-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking { player.playTrack(tracks[0].id, tracks.map { it.id }).join(); player.pause().join(); player.enqueue(tracks[2].id, QueueAction.APPEND).join() }
                val manual = player.state.value.queue.upNext
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Shuffle songs").assertDoesNotExist()
                onNodeWithContentDescription("Shuffle albums").assertDoesNotExist()
                onNodeWithContentDescription("Repeat").assertIsDisplayed()
                onNodeWithContentDescription("Shuffle").assertIsDisplayed().performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.unshuffledEntryIds != null }
                assertEquals(manual, player.state.value.queue.upNext)
                onNodeWithContentDescription("Shuffle").assertIsSelected().performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.unshuffledEntryIds == null }
                onNodeWithContentDescription("Shuffle albums").assertDoesNotExist()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithContentDescription("Shuffle").assertDoesNotExist()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun draggingIntoAnEmptyManualSectionMovesASongOutOfTheContext() {
        val directory = Files.createTempDirectory("queue-cross-section-drag")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }.sortedBy { it.metadata.title }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking { player.playTrack(tracks[0].id, tracks.map { it.id }).join(); player.pause().join() }
                val current = player.state.value.queue.currentEntryId
                val moved = player.state.value.queue.context.last()
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                val header = onNodeWithTag("queue:manual").fetchSemanticsNode().boundsInRoot
                val handle = onNodeWithContentDescription("Reorder: ${tracks[2].metadata.title}", useUnmergedTree = true)
                val distance = header.center.y - handle.fetchSemanticsNode().boundsInRoot.center.y
                handle.performTouchInput {
                    down(center); advanceEventTime(600)
                    moveTo(center.copy(y = center.y - 20), delayMillis = 16)
                    moveTo(center.copy(y = center.y + distance), delayMillis = 300)
                }
                waitForIdle()
                onNodeWithContentDescription("Reorder: ${tracks[2].metadata.title}", useUnmergedTree = true).performTouchInput { up() }
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.upNext.singleOrNull()?.id == moved.id }
                assertEquals(current, player.state.value.queue.currentEntryId)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playingFromAnAlbumNamesThatContextInTheQueue() {
        val directory = Files.createTempDirectory("queue-context-name")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                openLibraryPage("Albums")
                onNodeWithText("Fixture Album").performClick()
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.queue.currentTrackId != null }
                runBlocking { player.pause().join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithText("Playing from Fixture Album").assertIsDisplayed()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun previousSongsCanBeHiddenAndShownWithAPersistedLimit() {
        val directory = Files.createTempDirectory("queue-history-limit")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }.sortedBy { it.metadata.title }
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking {
                    player.updatePreferences { it.copy(showPreviousQueueTracks = false) }.join()
                    player.playTrack(songs[2].id, songs.map { it.id }, 2).join(); player.pause().join()
                }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithText("Previously played").assertDoesNotExist()
                onNodeWithTag("queue-entry:${player.state.value.queue.entries[0].id}").assertDoesNotExist()
                onNodeWithContentDescription("Now playing").performClick()
                onNodeWithContentDescription("Collapse").performClick()
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performClick()
                onNodeWithContentDescription("Show previous tracks in queue").performScrollTo().performClick()
                onNodeWithContentDescription("Previous track limit").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1f) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.preferences.previousQueueTrackLimit == 1 }
                assertEquals(true, runBlocking { preferences.load().showPreviousQueueTracks })
                assertEquals(1, runBlocking { preferences.load().previousQueueTrackLimit })
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Queue").performClick()
                onNodeWithText("Previously played").assertIsDisplayed()
                onNodeWithTag("queue-entry:${player.state.value.queue.entries[0].id}").assertDoesNotExist()
                onNodeWithTag("queue-entry:${player.state.value.queue.entries[1].id}").assertIsDisplayed()
                runBlocking { player.skip(-1, restartPrevious = false).join() }
                assertEquals(songs[1].id, player.state.value.queue.currentTrackId)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

}
