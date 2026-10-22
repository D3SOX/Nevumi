package me.d3sox.nevumi

import androidx.activity.BackEventCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
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

    @Test
    fun returningToTheEdgeBeforeCancellingKeepsSettingsStationary() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun cancelAtTheEdge() {
                val page = compose.onNodeWithTag("settings-page")
                val original = page.fetchSemanticsNode().boundsInRoot.left
                compose.mainClock.autoAdvance = false
                try {
                    scenario.onActivity {
                        it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                        it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(450f, 800f, .6f, BackEventCompat.EDGE_LEFT))
                    }
                    compose.mainClock.advanceTimeBy(64)
                    compose.waitForIdle()
                    assertTrue("Settings follow the finger inward", page.fetchSemanticsNode().boundsInRoot.left > original + 50)
                    listOf(.3f, .1f, 0f).forEach { progress ->
                        scenario.onActivity {
                            it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(750f * progress, 800f, progress, BackEventCompat.EDGE_LEFT))
                        }
                        compose.mainClock.advanceTimeBy(64)
                        compose.waitForIdle()
                    }
                    assertTrue("Returning to the edge restores the page", kotlin.math.abs(page.fetchSemanticsNode().boundsInRoot.left - original) < 2)
                    scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                    repeat(24) { frame ->
                        compose.mainClock.advanceTimeByFrame()
                        compose.waitForIdle()
                        val left = page.fetchSemanticsNode().boundsInRoot.left
                        assertTrue("Cancelling at the edge must not restart the animation: frame=$frame, left=$left, original=$original",
                            kotlin.math.abs(left - original) < 2)
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
    fun settingsBackPreviewsItsParentCancelsAndCommitsAtEachLevel() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun previewAndCancel() {
                val original = compose.onNodeWithTag("settings-page").fetchSemanticsNode().boundsInRoot.left
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .4f, BackEventCompat.EDGE_LEFT))
                }
                compose.waitForIdle()
                assertTrue("Settings must move with the predictive back gesture",
                    compose.onNodeWithTag("settings-page").fetchSemanticsNode().boundsInRoot.left > original + 50)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.waitForIdle()
                assertTrue(kotlin.math.abs(compose.onNodeWithTag("settings-page").fetchSemanticsNode().boundsInRoot.left - original) < 2)
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
                val original = heading.fetchSemanticsNode().boundsInRoot.left
                scenario.onActivity {
                    it.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 800f, 0f, BackEventCompat.EDGE_LEFT))
                    it.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(300f, 800f, .4f, BackEventCompat.EDGE_LEFT))
                }
                compose.waitForIdle()
                assertTrue("The collection must follow the back gesture",
                    heading.fetchSemanticsNode().boundsInRoot.left > original + 50)
                scenario.onActivity { it.onBackPressedDispatcher.dispatchOnBackCancelled() }
                compose.waitForIdle()
                assertTrue(kotlin.math.abs(heading.fetchSemanticsNode().boundsInRoot.left - original) < 2)
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
}
