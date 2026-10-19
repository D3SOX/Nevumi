package me.d3sox.nevumi

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidTrackInteractionUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test fun localDownloadActionsExplainOfflineStatusAndQuickActionsDoNotRepeatInTheMenu() = withTracks(2) { app, songs ->
        val song = songs.first()
        runBlocking { app.controller.updatePreferences { it.copy(
            quickAction = me.d3sox.nevumi.settings.TrackAction.DOWNLOAD,
            swipeLeft = me.d3sox.nevumi.settings.TrackAction.DOWNLOAD) }.join() }
        compose.onNodeWithContentDescription("Quick action: Available offline: ${song.metadata.title}").assertIsSelected().performClick()
        compose.onNodeWithText("Already available offline").assertIsDisplayed()
        val row = compose.onNodeWithTag("track-row:${song.id}", useUnmergedTree = true)
        row.performTouchInput { down(center); moveBy(Offset(-180f, 0f), 200) }
        compose.onNodeWithText("Available offline").assertIsDisplayed()
        row.performTouchInput { up() }
        compose.onNodeWithContentDescription("Track options: ${song.metadata.title}").performClick()
        compose.onNode(hasText("Available offline") and hasAnyAncestor(hasTestTag("track-actions"))).assertDoesNotExist()
        compose.onNodeWithText("Rate song").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close song actions").performClick()
        assertTrue(app.controller.state.value.downloads.isEmpty())
        assertNull(app.controller.state.value.error)
        runBlocking { app.controller.updatePreferences { it.copy(quickAction = me.d3sox.nevumi.settings.TrackAction.FAVORITE) }.join() }
        compose.onNodeWithContentDescription("Quick action: Favorite: ${song.metadata.title}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Track options: ${song.metadata.title}").performClick()
        compose.onNode(hasText("Favorite") and hasAnyAncestor(hasTestTag("track-actions"))).assertDoesNotExist()
        compose.onNodeWithContentDescription("Close song actions").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Library & gestures").performClick()
        for (label in listOf("Swipe left", "Swipe right", "Quick action")) {
            compose.onNodeWithContentDescription(label).performScrollTo().performClick()
            compose.onNode(hasText("Play") and hasAnyAncestor(isPopup())).assertDoesNotExist()
            compose.onNode(hasText("Download") and hasAnyAncestor(isPopup())).performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("None") and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        }
    }

    @Test fun brandedConnectionButtonsAreInTheListeningCategoryAndBackReturnsThere() = withTracks(1) { app, _ ->
        runBlocking { app.controller.updatePreferences { it.copy(themeMode = me.d3sox.nevumi.settings.ThemeMode.DARK, amoledBlack = true) }.join() }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
        val listenBrainz = compose.onNodeWithText("Connect ListenBrainz").performScrollTo().assertIsDisplayed()
        val lastFm = compose.onNodeWithText("Connect Last.fm").performScrollTo().assertIsDisplayed()
        assertTrue(listenBrainz.fetchSemanticsNode().boundsInRoot.width > 250f)
        assertTrue(lastFm.fetchSemanticsNode().boundsInRoot.width > 250f)
        fun capture(name: String) {
            compose.waitForIdle()
            val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(app.filesDir, "polish-ui/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
        capture("listening-services-dark")
        runBlocking { app.controller.updatePreferences { it.copy(themeMode = me.d3sox.nevumi.settings.ThemeMode.LIGHT) }.join() }
        capture("listening-services-light")
        listenBrainz.performClick()
        compose.onNodeWithText("User token").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Record listening history").assertIsDisplayed()
        compose.onNodeWithText("Connect Last.fm").performScrollTo().performClick()
        compose.onNodeWithText("Application API key").assertIsDisplayed()
        compose.onNodeWithText("Start sign-in").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Record listening history").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
    }

    @Test fun heldRatingStarsKeepTheirBackgroundAndOnlySaveAfterConfirmation() = withTracks(1) { app, songs ->
        val song = songs.single()
        fun openRating() {
            compose.onNodeWithContentDescription("Track options: ${song.metadata.title}").performClick()
            compose.onNodeWithText("Rate song").performClick()
        }
        openRating()
        fun starBackground(): androidx.compose.ui.graphics.Color {
            val pixels = compose.onNodeWithContentDescription("4 stars").captureToImage().toPixelMap()
            return pixels[pixels.width / 10, pixels.height / 2]
        }
        val idleBackground = starBackground()
        compose.onNodeWithContentDescription("1 star").performTouchInput { down(center) }
        compose.onNodeWithContentDescription("1 star").assertIsSelected()
        compose.onNodeWithContentDescription("4 stars").performTouchInput { moveTo(center, 250) }
        compose.onNodeWithContentDescription("4 stars").assertIsSelected()
        compose.onNodeWithContentDescription("5 stars").assertIsNotSelected()
        compose.onNodeWithText("4 / 5").assertIsDisplayed()
        assertNotEquals("The held star keeps its pressed background while dragging", idleBackground, starBackground())
        assertEquals(0, app.controller.state.value.tracks.first { it.id == song.id }.rating)
        compose.onNodeWithContentDescription("2 stars").performTouchInput { moveTo(center, 250); up() }
        compose.onNodeWithText("2 / 5").assertIsDisplayed()
        assertEquals(0, app.controller.state.value.tracks.first { it.id == song.id }.rating)
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(5_000) { app.controller.state.value.tracks.first { it.id == song.id }.rating == 2 }
        compose.onNodeWithContentDescription("Rating: 2 stars").assertIsDisplayed()
        openRating()
        compose.onNodeWithContentDescription("5 stars").performTouchInput { down(center) }
        compose.onNodeWithText("5 / 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("5 stars").performTouchInput { cancel() }
        compose.onNodeWithText("2 / 5").assertIsDisplayed()
        compose.onNodeWithContentDescription("5 stars").assertIsNotSelected()
        assertEquals(2, app.controller.state.value.tracks.first { it.id == song.id }.rating)
        compose.onNodeWithText("Cancel").performClick()
        openRating()
        compose.onNodeWithContentDescription("4 stars").performClick()
        compose.onNodeWithText("4 / 5").assertIsDisplayed()
        assertEquals(2, app.controller.state.value.tracks.first { it.id == song.id }.rating)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, app.controller.state.value.tracks.first { it.id == song.id }.rating)
    }

    @Test fun horizontalTrackSwipesKeepVerticalMovementOutOfTheLibraryScroll() = withTracks(40) { app, songs ->
        runBlocking { app.controller.playTrack(songs[0].id).join(); app.controller.pause().join() }
        val vertical = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        compose.onNode(hasScrollAction() and vertical and hasAnyDescendant(hasTestTag("track-row:${songs[0].id}"))).performScrollToIndex(12)
        val row = compose.onNodeWithTag("track-row:${songs[12].id}", useUnmergedTree = true)
        for (distance in listOf(32f, 160f)) for (direction in listOf(-1f, 1f)) {
            val before = row.fetchSemanticsNode().boundsInRoot.top
            row.performTouchInput { down(center); moveBy(Offset(distance * direction, 8f), 200) }
            row.performTouchInput { moveBy(Offset(-2 * distance * direction, 0f), 200) }
            val height = row.fetchSemanticsNode().boundsInRoot.height
            for (verticalDistance in listOf(height * 2, -height * 4, height * 2)) {
                row.performTouchInput { moveBy(Offset(0f, verticalDistance), 200) }
                val after = row.fetchSemanticsNode().boundsInRoot.top
                assertTrue("The library moved during a held track swipe: $before / $after", kotlin.math.abs(after - before) < 2f)
            }
            row.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(1_000)
            assertTrue("The library must also stay still when the track swipe ends", kotlin.math.abs(row.fetchSemanticsNode().boundsInRoot.top - before) < 2f)
        }
        val beforeScroll = row.fetchSemanticsNode().boundsInRoot.top
        row.performTouchInput { swipe(center, center.copy(y = center.y + 80f), 250) }
        assertTrue("Ordinary vertical scrolling must still work", row.fetchSemanticsNode().boundsInRoot.top > beforeScroll + 20f)
    }

    @Test fun aLibraryRefreshPreservesTheVisibleSongAndItsHeldSwipe() = withTracks(40) { app, songs ->
        val vertical = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        compose.onNode(hasScrollAction() and vertical and hasAnyDescendant(hasTestTag("track-row:${songs[0].id}"))).performScrollToIndex(12)
        val row = compose.onNodeWithTag("track-row:${songs[12].id}", useUnmergedTree = true)
        val title = compose.onNode(hasText("Song 12") and hasAnyAncestor(hasTestTag("track-row:${songs[12].id}")), useUnmergedTree = true)
        val top = row.fetchSemanticsNode().boundsInRoot.top
        val titleLeft = title.fetchSemanticsNode().boundsInRoot.left
        row.performTouchInput { down(center); moveBy(Offset(160f, 0f), 200) }
        assertTrue(title.fetchSemanticsNode().boundsInRoot.left > titleLeft + 30f)
        runBlocking {
            app.controller.library.importTrack(TrackMetadata("A new song"),
                TrackSource("new", SourceKind.LOCAL, songs[0].sources.first().location))
            app.controller.reload().join()
        }
        assertTrue("Library refresh must preserve the scroll position", kotlin.math.abs(row.fetchSemanticsNode().boundsInRoot.top - top) < 2f)
        assertTrue("Library refresh must preserve the held swipe", title.fetchSemanticsNode().boundsInRoot.left > titleLeft + 30f)
        row.performTouchInput { moveBy(Offset(0f, 90f), 200) }
        assertTrue(kotlin.math.abs(row.fetchSemanticsNode().boundsInRoot.top - top) < 2f)
        row.performTouchInput { moveBy(Offset(-160f, -90f), 200); up() }
    }

    private fun withTracks(count: Int, test: (MusicApplication, List<Track>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "track-interaction.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                val tracks = runBlocking { (0 until count).map { index ->
                    val title = "Song ${index.toString().padStart(2, '0')}"
                    app.controller.library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL, audio.path))
                }.also { app.controller.reload().join() } }
                test(app, tracks)
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }
}
