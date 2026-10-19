package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TrackActionsUiTest {
    @Test
    fun swipeAndQuickActionChoicesUseTrackActionsInsteadOfDuplicatingThePlayTap() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        onNodeWithContentDescription("Settings").performClick()
        onNodeWithText("Library & gestures").performClick()
        for (label in listOf("Swipe left", "Swipe right", "Quick action")) {
            onNodeWithContentDescription(label).performClick()
            onNode(hasText("Play") and hasAnyAncestor(isPopup())).assertDoesNotExist()
            onNode(hasText("Download") and hasAnyAncestor(isPopup())).performScrollTo().assertIsDisplayed()
            onNode(hasText("None") and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        }
    }

    @Test
    fun downloadQuickActionsAndSwipesExplainThatLocalSongsAreAlreadyOffline() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        runBlocking { player.updatePreferences { it.copy(quickAction = me.d3sox.nevumi.settings.TrackAction.DOWNLOAD,
            swipeLeft = me.d3sox.nevumi.settings.TrackAction.DOWNLOAD) }.join() }
        onNodeWithContentDescription("Quick action: Available offline: First song").assertIsSelected().performClick()
        onNodeWithText("Already available offline").assertIsDisplayed()
        val id = player.state.value.tracks.first { it.metadata.title == "First song" }.id
        onNodeWithTag("track-row:$id").performTouchInput { down(center); moveBy(Offset(-160f, 0f), 200) }
        onNodeWithText("Available offline").assertIsDisplayed()
        onNodeWithTag("track-row:$id").performTouchInput { up() }
        assertEquals(emptyMap(), player.state.value.downloads)
        assertEquals(null, player.state.value.error)
    }

    @Test
    fun aVisibleQuickActionIsOmittedOnlyFromTheSongListMenu() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        runBlocking { player.updatePreferences { it.copy(quickAction = me.d3sox.nevumi.settings.TrackAction.FAVORITE) }.join() }
        onNodeWithContentDescription("Quick action: Favorite: First song").assertIsDisplayed()
        onNodeWithContentDescription("Track options: First song").performClick()
        onNode(hasText("Favorite") and hasAnyAncestor(hasTestTag("track-actions"))).assertDoesNotExist()
        onNodeWithText("Rate song").assertIsDisplayed()
        onNodeWithContentDescription("Close song actions").performClick()
        onNodeWithContentDescription("Quick action: Favorite: First song").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.metadata.title == "First song" }.favorite }
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Remove favorite").assertDoesNotExist()
        onNodeWithContentDescription("Close song actions").performClick()
        runBlocking { player.updatePreferences { it.copy(quickAction = me.d3sox.nevumi.settings.TrackAction.NONE) }.join() }
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Remove favorite").assertIsDisplayed()
    }

    @Test fun refreshingTheLibraryDuringATrackSwipeKeepsTheSongAndScrollAttached() {
        val directory = Files.createTempDirectory("track-swipe-refresh")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { (0 until 40).map { index ->
            val title = "Song ${index.toString().padStart(2, '0')}"
            library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL, "/music/$title.flac"))
        } }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
                val vertical = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
                onNode(hasScrollAction() and vertical and hasAnyDescendant(hasTestTag("track-row:${songs[0].id}"))).performScrollToIndex(12)
                val row = onNodeWithTag("track-row:${songs[12].id}", useUnmergedTree = true)
                val top = row.fetchSemanticsNode().boundsInRoot.top
                val title = onNode(hasText("Song 12") and hasAnyAncestor(hasTestTag("track-row:${songs[12].id}")), useUnmergedTree = true)
                val titleLeft = title.fetchSemanticsNode().boundsInRoot.left
                row.performTouchInput { down(center); moveBy(Offset(120f, 0f), 200) }
                assertTrue(title.fetchSemanticsNode().boundsInRoot.left > titleLeft + 30f)
                runBlocking {
                    library.importTrack(TrackMetadata("A new song"), TrackSource("new", SourceKind.LOCAL, "/music/new.flac"))
                    player.reload().join()
                }
                val refreshed = row.fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(refreshed.top - top) < 2f,
                    "Background library updates must preserve the visible song's scroll position")
                assertTrue(title.fetchSemanticsNode().boundsInRoot.left > titleLeft + 30f,
                    "Background library updates must preserve the held swipe")
                row.performTouchInput { moveBy(Offset(0f, 80f), 200) }
                assertTrue(kotlin.math.abs(row.fetchSemanticsNode().boundsInRoot.top - top) < 2f)
                row.performTouchInput { moveBy(Offset(-120f, -80f), 200); up() }
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun verticalMovementAfterAHorizontalTrackSwipeDoesNotScrollTheLibrary() {
        val directory = Files.createTempDirectory("track-swipe-scroll")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { (0 until 40).map { index ->
            val title = "Song ${index.toString().padStart(2, '0')}"
            library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL, java.nio.file.Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac").toString()))
        } }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
                runBlocking { player.playTrack(songs[0].id).join(); player.pause().join() }
                val vertical = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)
                onNode(hasScrollAction() and vertical and hasAnyDescendant(hasTestTag("track-row:${songs[0].id}"))).performScrollToIndex(12)
                val row = onNodeWithTag("track-row:${songs[12].id}", useUnmergedTree = true)
                for (distance in listOf(32f, 160f)) for (direction in listOf(-1f, 1f)) {
                    val before = row.fetchSemanticsNode().boundsInRoot.top
                    row.performTouchInput { down(center); moveBy(Offset(distance * direction, 8f), 200) }
                    row.performTouchInput { moveBy(Offset(-2 * distance * direction, 0f), 200) }
                    val height = row.fetchSemanticsNode().boundsInRoot.height
                    for (verticalDistance in listOf(height * 2, -height * 4, height * 2)) {
                        row.performTouchInput { moveBy(Offset(0f, verticalDistance), 200) }
                        val after = row.fetchSemanticsNode().boundsInRoot.top
                        assertTrue(kotlin.math.abs(after - before) < 2f, "The list moved during a held track swipe: $before / $after")
                    }
                    row.performTouchInput { up() }
                    mainClock.advanceTimeBy(1_000)
                    assertTrue(kotlin.math.abs(row.fetchSemanticsNode().boundsInRoot.top - before) < 2f, "The list must also stay still when the track swipe ends")
                }
                val beforeScroll = row.fetchSemanticsNode().boundsInRoot.top
                row.performTouchInput { swipe(center, center.copy(y = center.y + 80f), 250) }
                assertTrue(row.fetchSemanticsNode().boundsInRoot.top > beforeScroll + 20f, "Ordinary vertical scrolling must still work")
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun ratingStarsKeepTheHeldBackgroundAndOnlySaveAfterConfirmation() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        val id = player.state.value.tracks.first { it.metadata.title == "First song" }.id
        fun openRating() {
            onNodeWithContentDescription("Track options: First song").performClick()
            onNodeWithText("Rate song").performClick()
        }
        openRating()
        fun starBackground(): androidx.compose.ui.graphics.Color {
            val pixels = onNodeWithContentDescription("4 stars").captureToImage().toPixelMap()
            return pixels[pixels.width / 10, pixels.height / 2]
        }
        val idleBackground = starBackground()
        onNodeWithContentDescription("1 star").performTouchInput { down(center) }
        onNodeWithContentDescription("1 star").assertIsSelected()
        onNodeWithContentDescription("4 stars").performTouchInput { moveTo(center, 250) }
        onNodeWithContentDescription("4 stars").assertIsSelected()
        onNodeWithContentDescription("5 stars").assertIsNotSelected()
        onNodeWithText("4 / 5").assertIsDisplayed()
        assertNotEquals(idleBackground, starBackground(), "The held star keeps its pressed background while dragging")
        assertEquals(0, player.state.value.tracks.first { it.id == id }.rating, "Holding must only preview the rating")
        onNodeWithContentDescription("2 stars").performTouchInput { moveTo(center, 250) }
        onNodeWithContentDescription("2 stars").assertIsSelected()
        onNodeWithContentDescription("3 stars").assertIsNotSelected()
        onNodeWithContentDescription("2 stars").performTouchInput { up() }
        onNodeWithText("2 / 5").assertIsDisplayed()
        assertEquals(0, player.state.value.tracks.first { it.id == id }.rating)
        onNodeWithText("Confirm").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.id == id }.rating == 2 }
        onNodeWithContentDescription("Rating: 2 stars").assertIsDisplayed()
        openRating()
        onNodeWithContentDescription("5 stars").performTouchInput { down(center) }
        onNodeWithText("5 / 5").assertIsDisplayed()
        onNodeWithContentDescription("5 stars").performTouchInput { up() }
        onNodeWithText("Cancel").performClick()
        assertEquals(2, player.state.value.tracks.first { it.id == id }.rating)
        openRating()
        onNodeWithContentDescription("4 stars").performClick()
        onNodeWithText("4 / 5").assertIsDisplayed()
        assertEquals(2, player.state.value.tracks.first { it.id == id }.rating)
        onNodeWithText("Cancel").performClick()
        assertEquals(2, player.state.value.tracks.first { it.id == id }.rating)
    }

    @Test
    fun closingTrackActionsReturnsTheCoverBeforeRemovingTheMenu() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        fun center(node: SemanticsNodeInteraction): Offset {
            val semantics = node.fetchSemanticsNode()
            return semantics.positionOnScreen - semantics.positionInRoot + semantics.boundsInRoot.center
        }
        val origin = center(onNodeWithContentDescription("Song artwork: First song", useUnmergedTree = true))
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Track options: First song").performClick()
        mainClock.advanceTimeBy(500)
        val artwork = onNodeWithTag("track-action-artwork", useUnmergedTree = true)
        val opened = center(artwork)
        val menuTop = onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot.top
        onNodeWithContentDescription("Close song actions").performClick()
        mainClock.advanceTimeBy(96)
        val returning = center(artwork)
        assertTrue((returning - opened).getDistance() > 2f && (returning - origin).getDistance() > 2f)
        assertTrue(onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot.top > menuTop)
        mainClock.advanceTimeBy(400)
        onNodeWithTag("track-actions").assertDoesNotExist()
        assertTrue((center(onNodeWithContentDescription("Song artwork: First song", useUnmergedTree = true)) - origin).getDistance() < 2f)
        mainClock.autoAdvance = true
    }

    @Test
    fun favoriteQuickActionsToggleBothWaysWithoutDuplicatingTheSongIndicator() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        runBlocking { player.updatePreferences { it.copy(quickAction = me.d3sox.nevumi.settings.TrackAction.FAVORITE) }.join() }
        val id = player.state.value.tracks.first { it.metadata.title == "First song" }.id
        onNodeWithContentDescription("Quick action: Favorite: First song").performTouchInput { click(center) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.id == id }.favorite }
        onNodeWithContentDescription("Quick action: Remove favorite: First song").assertIsSelected().performTouchInput { click(center) }
        waitUntil(timeoutMillis = 3_000) { !player.state.value.tracks.first { it.id == id }.favorite }
        onNodeWithContentDescription("Quick action: Favorite: First song").assertIsNotSelected().performTouchInput { click(center) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.id == id }.favorite }
        onNodeWithContentDescription("Favorited", useUnmergedTree = true).assertDoesNotExist()
        runBlocking { player.updatePreferences { it.copy(quickAction = me.d3sox.nevumi.settings.TrackAction.NONE) }.join() }
        onNodeWithContentDescription("Favorited", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun optionsButtonsAreHiddenByDefaultAndLongPressMenusRemainAvailable() = withLibrary(showMoreButtons = false) { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        onNodeWithContentDescription("Track options: First song").assertDoesNotExist()
        runBlocking { player.updatePreferences { it.copy(showMoreButtons = true) }.join() }
        onNodeWithContentDescription("Track options: First song").assertIsDisplayed()
        runBlocking { player.updatePreferences { it.copy(showMoreButtons = false) }.join() }
        onNodeWithContentDescription("Track options: First song").assertDoesNotExist()
        val id = player.state.value.tracks.first { it.metadata.title == "First song" }.id
        onNodeWithTag("track-row:$id").performTouchInput { longClick() }
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("track-actions").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun openingTrackActionsMovesTheCoverFromItsRowAsTheMenuSlidesUp() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        fun center(node: SemanticsNodeInteraction): Offset {
            val semantics = node.fetchSemanticsNode()
            return semantics.positionOnScreen - semantics.positionInRoot + semantics.boundsInRoot.center
        }
        val original = center(onNodeWithContentDescription("Song artwork: First song", useUnmergedTree = true))
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Track options: First song").performClick()
        mainClock.advanceTimeByFrame()
        val cover = onNodeWithTag("track-action-artwork", useUnmergedTree = true)
        val start = center(cover)
        val startMenu = onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot
        assertTrue((start - original).getDistance() < 3f, "The menu cover must begin at the song's cover: $start / $original")
        mainClock.advanceTimeBy(96)
        val middle = center(cover)
        val middleMenu = onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot
        mainClock.advanceTimeBy(400)
        val end = center(cover)
        val endMenu = onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot
        assertTrue((start - end).getDistance() > 40f)
        assertTrue((middle - start).getDistance() > 2f && (middle - end).getDistance() > 2f)
        assertTrue(startMenu.top > middleMenu.top && middleMenu.top > endMenu.top)
        mainClock.autoAdvance = true
        onNodeWithText("Favorite").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.metadata.title == "First song" }.favorite }
        onNodeWithContentDescription("Favorited", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aConfigurableQuickActionSitsBelowTheDurationAndBeforeTheTrackMenu() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { player.state.value.libraryReady }
        fun configure(action: String) {
            onNodeWithContentDescription("Settings").performClick()
            onNodeWithText("Library & gestures").performScrollTo().performClick()
            onNodeWithContentDescription("Quick action").performScrollTo().performClick()
            onNode(hasText(action) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
            onNodeWithContentDescription("Back").performClick()
            onNodeWithContentDescription("Back").performClick()
        }
        onNodeWithContentDescription("Quick action: Favorite: First song").assertDoesNotExist()
        configure("Favorite")
        val quick = onNodeWithContentDescription("Quick action: Favorite: First song").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val menu = onNodeWithContentDescription("Track options: First song").fetchSemanticsNode().boundsInRoot
        val row = hasAnyAncestor(hasTestTag("track-row:${player.state.value.tracks.first { it.metadata.title == "First song" }.id}"))
        val duration = onNode(hasContentDescription("Duration: 1:30") and row, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(quick.right <= menu.left + 1f)
        assertTrue(duration.center.y < quick.center.y - 12f, "Duration must sit above the quick action: $duration / $quick")
        assertTrue(kotlin.math.abs(duration.center.x - quick.center.x) < 2f)
        onNodeWithContentDescription("Quick action: Favorite: First song").performClick()
        onNodeWithContentDescription("Quick action: Remove favorite: First song").assertIsDisplayed()
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Remove favorite").assertDoesNotExist()
        onNodeWithContentDescription("Close song actions").performClick()
        configure("Play next")
        onNodeWithContentDescription("Quick action: Play next: Second song").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.queue.entries.isNotEmpty() }
        assertEquals("Second song", player.state.value.tracks.first { it.id == player.state.value.queue.entries.single().trackId }.metadata.title)
        configure("None")
        onNodeWithContentDescription("Quick action: Play next: First song").assertDoesNotExist()
    }

    @Test
    fun ratingUsesStarsAndTheSavedRatingIsVisibleInTheSongListAndActionMenu() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Rate song").performClick()
        onNodeWithContentDescription("4 stars").performClick()
        onNodeWithText("Confirm").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.metadata.title == "First song" }.rating == 4 }
        onNodeWithContentDescription("Rating: 4 stars").assertIsDisplayed()
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("4 / 5").assertIsDisplayed()
        onNodeWithText("Rate song").performClick()
        onNodeWithContentDescription("4 stars").assertIsSelected()
        onNodeWithContentDescription("5 stars").assertIsNotSelected()
        onNodeWithText("Clear rating").performClick()
        onNodeWithText("Confirm").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.metadata.title == "First song" }.rating == 0 }
        onNodeWithContentDescription("Rating: 4 stars").assertDoesNotExist()
    }

    @Test
    fun deselectingAllAndCancellingBulkDialogsKeepSelectionModeOpen() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Select song").performClick()
        onNodeWithContentDescription("Select all").performClick()
        onNodeWithContentDescription("Select none").performClick()
        onNodeWithText("0 songs selected").assertIsDisplayed()
        onNodeWithContentDescription("Search").assertDoesNotExist()
        onNodeWithContentDescription("Play next").assertIsNotEnabled()
        onNodeWithContentDescription("Select all").performClick()
        onNodeWithContentDescription("Selection actions").performClick()
        onNodeWithText("Add to playlist").performClick()
        onNodeWithText("Cancel").performClick()
        onNodeWithText("2 songs selected").assertIsDisplayed()
        onNodeWithContentDescription("Select none").assertIsDisplayed()
        onNodeWithContentDescription("Clear selection").performClick()
        onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun sortingIsOnlyOfferedWhereItChangesTheVisibleSongOrder() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
        onNodeWithContentDescription("Song order").assertIsDisplayed()
        for (page in listOf("Recently added", "Recommendations", "History", "Statistics", "Playlists", "Albums", "Artists")) {
            openLibraryPage(page)
            onNodeWithContentDescription("Song order").assertDoesNotExist()
        }
        onNode(hasText("An artist") and hasClickAction()).performClick()
        onNodeWithContentDescription("Song order").assertIsDisplayed()
        openLibraryPage("Favorites")
        onNodeWithContentDescription("Song order").assertIsDisplayed()
    }

    @Test
    fun songSwipesReserveEmptySpaceAndCanFavoriteRateOrDoNothing() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
        onNodeWithTag("library-pager").performTouchInput {
            swipe(Offset(width * .8f, height * .75f), Offset(width * .2f, height * .75f), 250)
        }
        onNodeWithText("Songs").assertIsSelected()
        fun gestures() {
            onNodeWithContentDescription("Settings").performClick()
            onNodeWithText("Library & gestures").performScrollTo().performClick()
        }
        fun library() {
            onNodeWithContentDescription("Back").performClick()
            onNodeWithContentDescription("Back").performClick()
        }
        gestures()
        onNodeWithContentDescription("Swipe right").performClick()
        onNodeWithText("Favorite").performScrollTo().performClick()
        onNodeWithContentDescription("Swipe left").performClick()
        onNodeWithText("Rate song").performScrollTo().performClick()
        library()
        val id = player.state.value.tracks.first { it.metadata.title == "First song" }.id
        onNodeWithTag("track-row:$id").performTouchInput { down(center); moveBy(Offset(160f, 0f)) }
        onNodeWithText("Favorite").assertIsDisplayed()
        onNodeWithTag("track-row:$id").performTouchInput { up() }
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.id == id }.favorite }
        onNodeWithTag("track-row:$id").performTouchInput { swipe(center, center.copy(x = center.x - 200f), 200) }
        onNodeWithContentDescription("4 stars").performClick()
        onNodeWithText("Confirm").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.first { it.id == id }.rating == 4 }
        gestures()
        onNodeWithContentDescription("Swipe right").performClick()
        onNode(hasText("None") and hasAnyAncestor(isPopup())).performClick()
        onNodeWithContentDescription("Swipe left").performClick()
        onNode(hasText("None") and hasAnyAncestor(isPopup())).performClick()
        library()
        onNodeWithTag("track-row:$id").performTouchInput { swipe(center, center.copy(x = center.x + 200f), 200) }
        onNodeWithTag("track-row:$id").performTouchInput { swipe(center, center.copy(x = center.x - 200f), 200) }
        assertEquals(true, player.state.value.tracks.first { it.id == id }.favorite)
        assertEquals(4, player.state.value.tracks.first { it.id == id }.rating)
        assertEquals(0, player.state.value.queue.entries.size)
        onNodeWithText("Songs").assertIsSelected()
    }

    @Test
    fun confirmationsCanOpenTheQueueAndFavoritesWithoutStartingPlayback() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Add to queue").performClick()
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Added to queue").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Open queue").performClick()
        onNodeWithTag("queue-header").assertIsDisplayed()
        assertEquals(false, player.state.value.playback.playing)
        onNodeWithContentDescription("Now playing").performClick()
        onNodeWithContentDescription("Collapse").performClick()
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Favorite").performClick()
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Added to favorites").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("Open favorites").performClick()
        onNodeWithContentDescription("Track options: First song").assertIsDisplayed()
        onNodeWithContentDescription("Track options: Second song").assertDoesNotExist()
    }

    @Test
    fun compactTrackActionsStayCurrentAndCanOpenRelatedCollections() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithTag("track-actions").assertIsDisplayed()
        onNodeWithText("Favorite").performClick()
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Remove favorite").assertIsDisplayed()
        onNodeWithText("Rate song").performClick()
        onNodeWithContentDescription("4 stars").performClick()
        onNodeWithText("Confirm").performClick()
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Rate song").performClick()
        onNodeWithContentDescription("4 stars").assertIsSelected()
        onNodeWithText("Cancel").performClick()
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Go to artist").performClick()
        onNodeWithText("Artists").assertIsSelected()
        onNode(hasText("An artist") and !hasClickAction()).assertIsDisplayed()
        onNodeWithContentDescription("Track options: First song").assertIsDisplayed()
        onNodeWithContentDescription("Back").performClick()
        onNodeWithContentDescription("Collection view").assertIsDisplayed()
    }

    @Test
    fun selectingSongsReplacesTheBrandedHeaderWithIconControls() = withLibrary { player ->
        setContent { MusicPlayerApp(player) }
        waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
        onNodeWithContentDescription("Nevumi logo").assertIsDisplayed()
        onNodeWithContentDescription("Nevumi logo").assertIsDisplayed()
        onNodeWithContentDescription("Track options: First song").performClick()
        onNodeWithText("Select song").performClick()
        onNodeWithText("1 song selected").assertIsDisplayed()
        onNodeWithContentDescription("Search").assertDoesNotExist()
        onNodeWithContentDescription("Settings").assertDoesNotExist()
        onNodeWithText("Nevumi").assertDoesNotExist()
        onNodeWithContentDescription("Select all").performClick()
        onNodeWithText("2 songs selected").assertIsDisplayed()
        onNodeWithContentDescription("Selection actions").performClick()
        onNodeWithText("Play after").performClick()
        waitUntil(timeoutMillis = 3_000) { player.state.value.queue.entries.size == 2 }
        assertEquals(listOf("First song", "Second song"), player.state.value.queue.entries.map { entry ->
            player.state.value.tracks.first { it.id == entry.trackId }.metadata.title
        })
        onNodeWithContentDescription("Nevumi logo").assertIsDisplayed()
    }

    private fun withLibrary(showMoreButtons: Boolean = true, test: ComposeUiTest.(MusicController) -> Unit) {
        val directory = Files.createTempDirectory("nevumi-track-actions")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        runBlocking { listOf("First song", "Second song").forEach { title ->
            library.importTrack(TrackMetadata(title, "An artist", "An album", durationMs = 90_000, genres = listOf("Ambient"), folder = "/music"),
                TrackSource(title, SourceKind.LOCAL, "/music/$title.flac"))
        } }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).also { preferences ->
            if (showMoreButtons) preferences.withOptionsButtons()
        })
        try { runComposeUiTest { test(player) } }
        finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
