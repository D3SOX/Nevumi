package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSourceLinksUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test
    fun copiesCanBeLinkedFromTheAndroidTrackMenuAfterConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val controller = app.controller
        val audio = File(app.cacheDir, "source-links-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { !controller.state.value.loading }
                val (keep, other) = runBlocking {
                    val first = controller.library.importTrack(TrackMetadata("Linking local song", "Fixture Artist", "Studio"),
                        TrackSource("local-first", SourceKind.LOCAL, audio.path))
                    val second = controller.library.importTrack(TrackMetadata("Linking other copy", "Fixture Artist", "Studio"),
                        TrackSource("local-second", SourceKind.LOCAL, audio.path))
                    controller.library.setFavorite(second.id, true)
                    controller.reload().join()
                    first to second
                }
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Linking")
                compose.onNodeWithContentDescription("Search your library").performImeAction()
                compose.onNodeWithContentDescription("Track options: Linking local song").performClick()
                compose.onNodeWithText("Song sources").performClick()
                compose.onNodeWithText("Link another copy").performClick()
                compose.onNodeWithContentDescription("Link copy: Linking other copy").performClick()
                compose.onNodeWithText("Link these songs?").assertIsDisplayed()
                assertEquals(2, controller.state.value.tracks.count { it.id == keep.id || it.id == other.id })
                compose.onNodeWithText("Link songs", substring = false).performClick()
                compose.waitUntil(5_000) { controller.state.value.tracks.none { it.id == other.id } }
                val linked = controller.state.value.tracks.single { it.id == keep.id }
                assertEquals(2, linked.sources.size)
                assertEquals(true, linked.favorite)
                compose.onNodeWithContentDescription("Track options: Linking other copy").assertDoesNotExist()
            }
        } finally { audio.delete() }
    }
}
