package me.d3sox.nevumi

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@ColdStartupCheck
@RunWith(AndroidJUnit4::class)
class AndroidStartupTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun seedCachedLibraryAndQueueForColdRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val player = app.controller
        withTimeout(10_000) { player.state.first { !it.loading } }
        val audio = File(app.filesDir, "startup-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        val songs = (0 until 600).map { index ->
            player.library.importTrack(TrackMetadata("Song ${index.toString().padStart(3, '0')}",
                "Artist ${(index % 60).toString().padStart(2, '0')}", "Album ${index / 10}", durationMs = 3_000),
                TrackSource("startup-$index", SourceKind.LOCAL, audio.path))
        }
        player.queue.replace(songs.map { it.id }, 300)
        player.queue.setPosition(1_000)
        player.updatePreferences { it.copy(lastLibraryTab = LibraryTab.ARTISTS, startupTab = null,
            themeMode = ThemeMode.DARK, amoledBlack = true) }.join()
        assertEquals(600, player.library.tracks().size)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(24)
            compose.onNode(hasText("Artist 24") and hasAnyAncestor(hasTestTag("library-pager"))).assertIsDisplayed()
            compose.waitUntil(5_000) { player.libraryPositions.values.any { it.index == 24 } }
        }
    }

    @Test
    fun coldOpenShowsTheCachedArtistsPageWithoutASongsFrame() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val started = SystemClock.elapsedRealtime()
        compose.mainClock.autoAdvance = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                var firstLibraryFrame: Int? = null
                for (frame in 0 until 240) {
                    compose.mainClock.advanceTimeByFrame()
                    compose.waitForIdle()
                    val tabs = compose.onAllNodes(hasText("Artists") and hasClickAction()).fetchSemanticsNodes()
                    if (tabs.isNotEmpty()) {
                        firstLibraryFrame = frame
                        assertTrue("The first library frame must already show Artists, not Songs (frame=$frame)",
                            tabs.single().config[SemanticsProperties.Selected])
                        compose.onNode(hasText("Artist 24") and hasAnyAncestor(hasTestTag("library-pager"))).assertIsDisplayed()
                        compose.onNode(hasText("Artist 00") and hasAnyAncestor(hasTestTag("library-pager"))).assertDoesNotExist()
                        break
                    }
                }
                assertNotNull("Cached library must become visible", firstLibraryFrame)
                println("STARTUP_CACHE_VISIBLE_MS=${SystemClock.elapsedRealtime() - started}")
                assertEquals(600, app.controller.state.value.tracks.size)
                compose.mainClock.autoAdvance = true
                compose.onNodeWithText("Albums").performClick()
                compose.waitUntil(10_000) { app.controller.state.value.preferences.lastLibraryTab == LibraryTab.ALBUMS }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Albums").assertIsSelected()
            }
        } finally { compose.mainClock.autoAdvance = true }
    }
}
