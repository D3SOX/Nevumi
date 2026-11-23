package me.d3sox.nevumi

import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playlists.playlistName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidBackNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    /** Scaling to 90% around the page's center moves a node by at most 5% of the width, so only a slide passes. */
    private fun Rect.slidFrom(original: Rect) = left - original.left > compose.onRoot().fetchSemanticsNode().size.width * .1f

    @Test
    fun returningToTheEdgeBeforeCancellingKeepsSettingsStationary() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun cancelAtTheEdge() {
                val page = compose.onNodeWithTag("settings-page")
                val original = page.fetchSemanticsNode().boundsInRoot
                compose.mainClock.autoAdvance = false
                try {
                    scenario.onActivity {
                        it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                        it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(450f, 800f, .6f, BackEventCompat.EDGE_LEFT))
                    }
                    compose.mainClock.advanceTimeBy(64)
                    compose.waitForIdle()
                    assertTrue("Settings follow the finger", page.fetchSemanticsNode().boundsInRoot.previews(original))
                    listOf(.3f, .1f, 0f).forEach { progress ->
                        scenario.onActivity {
                            it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(750f * progress, 800f, progress, BackEventCompat.EDGE_LEFT))
                        }
                        compose.mainClock.advanceTimeBy(64)
                        compose.waitForIdle()
                    }
                    assertTrue("Returning to the edge restores the page", page.fetchSemanticsNode().boundsInRoot.matches(original))
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                    repeat(24) { frame ->
                        compose.mainClock.advanceTimeByFrame()
                        compose.waitForIdle()
                        val bounds = page.fetchSemanticsNode().boundsInRoot
                        assertTrue("Cancelling at the edge must not restart the animation: frame=$frame, bounds=$bounds, original=$original",
                            bounds.matches(original))
                    }
                } finally { compose.mainClock.autoAdvance = true }
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            cancelAtTheEdge()
            compose.onNodeWithText("Appearance").performClick()
            cancelAtTheEdge()
            compose.onNodeWithContentDescription("Theme").assertIsDisplayed()
        }
    }

    @Test
    fun aBackGestureFromTheRightEdgeSlidesThePageLeft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("Settings").performClick()
            val page = compose.onNodeWithTag("settings-page")
            val original = page.fetchSemanticsNode().boundsInRoot
            val originalX = page.fetchSemanticsNode().positionInRoot.x
            val width = compose.onRoot().fetchSemanticsNode().size.width
            scenario.onActivity {
                it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(width.toFloat(), 800f, 0f, BackEventCompat.EDGE_RIGHT))
                it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(width - 300f, 800f, .4f, BackEventCompat.EDGE_RIGHT))
            }
            // Scaling to 90% moves the page's left edge right, and the clipped bounds stop at the screen's edge, so only the
            // unclipped position of a page sliding left passes.
            compose.waitUntil("The page follows the finger to the left", 5_000) {
                page.fetchSemanticsNode().positionInRoot.x - originalX < -width * .1f
            }
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            compose.waitForIdle()
            assertTrue(page.fetchSemanticsNode().boundsInRoot.matches(original))
        }
    }

    @Test
    fun aRightEdgeGestureDuringALeftEdgeRollbackSlidesThePageLeft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription("Settings").performClick()
            val page = compose.onNodeWithTag("settings-page")
            val originalX = page.fetchSemanticsNode().positionInRoot.x
            val width = compose.onRoot().fetchSemanticsNode().size.width
            compose.mainClock.autoAdvance = false
            try {
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(450f, 800f, .6f, BackEventCompat.EDGE_LEFT))
                }
                compose.mainClock.advanceTimeBy(64)
                compose.waitForIdle()
                // The next gesture continues the transition that the cancelled one started rolling back.
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(width.toFloat(), 800f, 0f, BackEventCompat.EDGE_RIGHT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(width - 300f, 800f, .4f, BackEventCompat.EDGE_RIGHT))
                }
                // The rollback cancels the first seeks, so keep moving the finger as a real gesture does.
                listOf(.3f, .4f, .45f).forEach { progress ->
                    compose.mainClock.advanceTimeBy(32)
                    compose.waitForIdle()
                    scenario.onActivity {
                        it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(width - 750f * progress, 800f, progress, BackEventCompat.EDGE_RIGHT))
                    }
                }
                compose.mainClock.advanceTimeBy(64)
                compose.waitForIdle()
                // Scaling down alone moves the page's left edge right, so only a slide to the left puts it left of its origin.
                val slid = page.fetchSemanticsNode().positionInRoot.x - originalX
                assertTrue("The page follows the finger to the left: slid=$slid", slid < 0)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            } finally { compose.mainClock.autoAdvance = true }
        }
    }

    @Test
    fun settingsBackPreviewsItsParentCancelsAndCommitsAtEachLevel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun previewAndCancel() {
                val original = compose.onNodeWithTag("settings-page").fetchSemanticsNode().boundsInRoot
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .4f, BackEventCompat.EDGE_LEFT))
                }
                // Sliding alone keeps the height, so a shorter page shows that it scales with the gesture.
                compose.waitUntil("Settings shrink with the predictive back gesture", 5_000) {
                    compose.onNodeWithTag("settings-page").fetchSemanticsNode().boundsInRoot.height < original.height - 10
                }
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.waitForIdle()
                assertTrue(compose.onNodeWithTag("settings-page").fetchSemanticsNode().boundsInRoot.matches(original))
            }
            fun commit() {
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(500f, 800f, .7f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.onBackPressed()
                }
                compose.waitForIdle()
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            previewAndCancel()
            compose.onNodeWithText("Appearance").performClick()
            previewAndCancel()
            compose.onNodeWithContentDescription("Theme").assertIsDisplayed()
            commit()
            compose.onNodeWithContentDescription("Theme").assertDoesNotExist()
            compose.onNodeWithText("Music server").performClick()
            previewAndCancel()
            commit()
            compose.onNodeWithText("Appearance").assertExists()
            compose.onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
            compose.onNodeWithText("Connect ListenBrainz").performScrollTo().performClick()
            previewAndCancel()
            commit()
            compose.onNodeWithText("Connect Last.fm").performScrollTo().performClick()
            previewAndCancel()
            commit()
            compose.onNodeWithText("No pending listens").performScrollTo().assertIsDisplayed()
            commit()
            compose.onNodeWithText("Playback").performClick()
            compose.onNodeWithText("Audio effects").performScrollTo().performClick()
            compose.onNodeWithText("Headphone profiles").performClick()
            previewAndCancel()
            commit()
            compose.onNodeWithText("Enable equalizer").assertIsDisplayed()
            commit()
            commit()
            commit()
            compose.onNodeWithTag("library-pager").assertIsDisplayed()
            compose.onNodeWithTag("settings-page").assertDoesNotExist()
        }
    }

    @Test
    fun collectionBackPreviewsCancelsAndReturnsToTheList(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        app.controller.updatePreferences { it.copy(libraryTabs = listOf(LibraryTab.ALBUMS, LibraryTab.ARTISTS,
            LibraryTab.GENRES, LibraryTab.FOLDERS, LibraryTab.ALBUM_ARTISTS)) }.join()
        app.controller.library.importTrack(TrackMetadata("Back test song", artist = "Artist", albumArtist = "Album artist",
            album = "Album", genres = listOf("Trance"), folder = "Music/Back test"),
            TrackSource("back-test", SourceKind.LOCAL, "/missing.flac"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(5_000) { !app.controller.state.value.loading }
            listOf("Albums" to "Album", "Artists" to "Artist", "Genres" to "Trance",
                "Folders" to "Music/Back test", "Album artists" to "Album artist").forEach { (tab, group) ->
                if (compose.onAllNodes(hasText(tab) and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithText("More").performClick()
                    compose.onNodeWithTag("library-more-pages").performScrollToNode(hasText(tab))
                }
                compose.onNodeWithText(tab).performClick()
                compose.onNodeWithText(group).performClick()
                compose.onNodeWithContentDescription("Track options: Back test song").assertIsDisplayed()
                compose.onNodeWithText("Back to ${tab.lowercase()}").assertDoesNotExist()
                val heading = compose.onNode(hasText(group) and !hasClickAction())
                val original = heading.fetchSemanticsNode().boundsInRoot
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .4f, BackEventCompat.EDGE_LEFT))
                }
                compose.waitUntil("The collection follows the back gesture", 5_000) { heading.fetchSemanticsNode().boundsInRoot.slidFrom(original) }
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.waitForIdle()
                assertTrue(heading.fetchSemanticsNode().boundsInRoot.matches(original))
                compose.onNodeWithContentDescription("Back").assertIsDisplayed()
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(500f, 800f, .7f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.onBackPressed()
                }
                compose.onNodeWithContentDescription("Track options: Back test song").assertDoesNotExist()
                compose.onNodeWithText(group).assertIsDisplayed()
                compose.onNodeWithContentDescription("Back").assertDoesNotExist()
            }
        }
    }

    @Test
    fun aRestoredPlaylistFolderStaysAboveItsParentDuringTheBackGesture(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val folder = "Back folder ${UUID.randomUUID().toString().take(6)}"
        listOf("One", "Two").forEach { app.controller.createPlaylist(playlistName(listOf(folder), it)).join() }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForLibrary(app.controller)
            compose.openLibraryPage("Playlists")
            compose.onNodeWithText(folder).performClick()
            // Recreating the activity restores the folder as the first page its navigation shows, like returning to the tab.
            scenario.recreate()
            compose.onNodeWithText("One").assertIsDisplayed()
            val original = compose.onRoot().captureToImage().asAndroidBitmap()
            fun changedShare(): Float {
                compose.waitForIdle()
                val shown = compose.onRoot().captureToImage().asAndroidBitmap()
                var differing = 0
                for (y in 0 until original.height step 4) for (x in 0 until original.width step 4) if (original.getPixel(x, y) != shown.getPixel(x, y)) differing++
                return differing / ((original.width / 4f) * (original.height / 4f))
            }
            fun assertUnchanged(message: String) = changedShare().let { assertTrue("$message: $it of the screen differs", it < .02f) }
            fun send(progress: Float) {
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(750f * progress, 800f, progress, BackEventCompat.EDGE_LEFT))
                }
                compose.mainClock.advanceTimeByFrame()
            }
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT)) }
            (1..10).forEach { send(it / 10f * .9f) }
            changedShare().let { assertTrue("The folder follows the back gesture: only $it of the screen changed", it > .2f) }
            (10 downTo 0).forEach { send(it / 10f * .9f) }
            // The parent waits behind the folder until the system cancels the gesture.
            assertUnchanged("The parent shows over the folder with the finger back at the edge")
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            assertUnchanged("Cancelling leaves the parent visible")
        }
    }

    @Test
    fun searchCollectionBackPreviewsCancelsAndReturnsToTheResults(): Unit = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val track = app.controller.library.importTrack(TrackMetadata("Search back song", album = "Search back album"),
            TrackSource("search-back", SourceKind.LOCAL, "/missing.flac"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForLibrary(app.controller)
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNodeWithContentDescription("Search your library").performTextInput("Search back")
            compose.onNodeWithTag("search-type:ALBUMS").performClick()
            compose.onNodeWithContentDescription("Open album: Search back album").performClick()
            val row = compose.onNodeWithTag("track-row:${track.id}")
            val original = row.fetchSemanticsNode().boundsInRoot
            scenario.onActivity {
                it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .4f, BackEventCompat.EDGE_LEFT))
            }
            compose.waitUntil("The opened album follows the back gesture", 5_000) { row.fetchSemanticsNode().boundsInRoot.slidFrom(original) }
            scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
            compose.waitForIdle()
            assertTrue(row.fetchSemanticsNode().boundsInRoot.matches(original))
            scenario.onActivity {
                it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(500f, 800f, .7f, BackEventCompat.EDGE_LEFT))
                it.onBackPressedDispatcher.onBackPressed()
            }
            compose.onNodeWithTag("track-row:${track.id}").assertDoesNotExist()
            compose.onNodeWithContentDescription("Open album: Search back album").assertIsDisplayed()
        }
    }

    @Test
    fun playerBackFollowsGestureProgressAndCancellationRestoresThePanel(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val file = File(app.cacheDir, "predictive-back-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        val track = app.controller.library.importTrack(TrackMetadata("Predictive back"), TrackSource("predictive", SourceKind.LOCAL, file.path))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                app.controller.refresh()
                app.controller.playTrack(track.id, listOf(track.id)).join()
                compose.waitUntil(15_000) { app.controller.state.value.playback.playing }
                app.controller.pause().join()
                compose.onNodeWithTag("mini-player").performClick()
                val original = compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot.top
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .4f, BackEventCompat.EDGE_LEFT))
                }
                compose.waitForIdle()
                assertTrue("Now playing must follow the predictive back gesture", compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot.top > original + 50)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.onNodeWithTag("now-playing").assertIsDisplayed()
                assertTrue(kotlin.math.abs(compose.onNodeWithTag("now-playing").fetchSemanticsNode().boundsInRoot.top - original) < 2)
                compose.onNodeWithContentDescription("Queue").performClick()
                val queueTop = compose.onNodeWithTag("queue-header").fetchSemanticsNode().boundsInRoot.top
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .4f, BackEventCompat.EDGE_LEFT))
                }
                compose.waitForIdle()
                assertTrue("Queue must follow the predictive back gesture", compose.onNodeWithTag("queue-header").fetchSemanticsNode().boundsInRoot.top > queueTop + 50)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.onNodeWithTag("queue-header").assertIsDisplayed()
                assertTrue(kotlin.math.abs(compose.onNodeWithTag("queue-header").fetchSemanticsNode().boundsInRoot.top - queueTop) < 2)
                // Dispatch Back to the activity: a key event would close a system dialog that has taken focus on a slow emulator.
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("queue-header").assertDoesNotExist()
                compose.onNodeWithTag("now-playing").assertIsDisplayed()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("mini-player").assertIsDisplayed()
            }
        } finally { app.controller.pause().join(); file.delete() }
    }

    @Test
    fun backOverAPageOpenedBeforeThePlayerExpandedClosesOnlyThePlayer(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val file = File(app.cacheDir, "player-over-page-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        val track = app.controller.library.importTrack(TrackMetadata("Overlay song", album = "Overlay album"),
            TrackSource("player-over-page", SourceKind.LOCAL, file.path))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitForLibrary(app.controller)
                app.controller.playTrack(track.id, listOf(track.id)).join()
                compose.waitUntil(15_000) { app.controller.state.value.playback.playing }
                app.controller.pause().join()
                compose.onNodeWithTag("mini-player").assertIsDisplayed()
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Overlay")
                compose.onNodeWithTag("search-type:ALBUMS").performClick()
                // The opened album registers its back handler after the player panel did.
                compose.onNodeWithContentDescription("Open album: Overlay album").performClick()
                compose.onNodeWithTag("track-row:${track.id}").assertIsDisplayed()
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithTag("now-playing").assertIsDisplayed()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithTag("mini-player").assertIsDisplayed()
                compose.onNodeWithTag("track-row:${track.id}").assertIsDisplayed()
            }
        } finally { app.controller.pause().join(); file.delete() }
    }
}

/** Depending on how far its transition has run, a back preview slides the page or shrinks it. */
private fun Rect.previews(original: Rect) =
    kotlin.math.abs(left - original.left) > 50 || kotlin.math.abs(width - original.width) > 50

private fun Rect.matches(original: Rect) = kotlin.math.abs(left - original.left) < 2 && kotlin.math.abs(top - original.top) < 2 &&
    kotlin.math.abs(width - original.width) < 2 && kotlin.math.abs(height - original.height) < 2
