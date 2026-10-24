package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidHomeUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mixesPlayLocalMusicAndTheLastOpenedHomePageIsRestored() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val file = File(app.cacheDir, "home-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { source -> file.outputStream().use(source::copyTo) }
        val tracks = runBlocking { (1..4).map { number ->
            controller.library.importTrack(TrackMetadata("Home song $number", "Home artist", "Home album", durationMs = 3_000, trackNumber = number),
                TrackSource("home-source:$number", SourceKind.LOCAL, file.path))
        } }
        runBlocking { controller.setFavorite(tracks[0].id, true).join() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.waitUntil(5_000) { controller.state.value.libraryReady && controller.state.value.tracks.any { it.id == tracks[0].id && it.favorite } }
                compose.onNodeWithContentDescription("Songs").assertIsSelected()
                compose.onNodeWithContentDescription("Home").performClick()
                compose.onNodeWithText("Mixes").assertIsDisplayed()
                compose.onNodeWithContentDescription("Open mix: Favorites mix").performClick()
                compose.onNodeWithText("Play mix").performClick()
                compose.waitUntil(5_000) { controller.state.value.playback.trackId == tracks[0].id }
                assertEquals(listOf(tracks[0].id), controller.state.value.queue.entries.map { it.trackId })
                compose.waitUntil(10_000) { controller.state.value.listens.any { it.trackId == tracks[0].id && it.qualified } }
                runBlocking { controller.pause().join() }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithTag("home-page").performScrollToNode(hasTestTag("home-section:Recent listens"))
                compose.onNodeWithText("Recent listens").assertIsDisplayed()
                compose.onNodeWithTag("home-page").performScrollToNode(hasTestTag("home-section:Recent albums"))
                compose.onNode(hasContentDescription("Open album: Home album") and hasAnyAncestor(hasTestTag("home-section:Recent albums"))).performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("track-row:${tracks[3].id}"))
                compose.onNodeWithText("Home song 4").assertExists()
                compose.onNodeWithContentDescription("Home").performClick()
                compose.waitUntil(5_000) { controller.state.value.preferences.lastLibraryTab == LibraryTab.HOME }
                assertNull(runBlocking { MusicPreferences(app.store).load() }.startupTab)
                scenario.recreate()
                compose.onNode(hasContentDescription("Home") and hasAnyAncestor(hasTestTag("library-navigation"))).assertIsSelected()
                compose.onNodeWithTag("home-page").assertIsDisplayed()
            }
        } finally { runBlocking { controller.pause().join() }; file.delete() }
    }
}
