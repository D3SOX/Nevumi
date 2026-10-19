package me.d3sox.nevumi.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryFastScrollUiTest {
    @Test
    fun draggingTheLibraryScrollbarShowsTheSortLetterOrModificationDateAndReachesBothEnds() {
        val directory = Files.createTempDirectory("library-fast-scroll")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = runBlocking { (0 until 208).map { index ->
            library.importTrack(TrackMetadata("${'A' + index / 8}${index.toString().padStart(3, '0')}", artist = "${'A' + index / 8} artist", genres = listOf("Trance")),
                TrackSource("local-$index", SourceKind.LOCAL, "/music/$index.flac", modifiedAt = 1_577_836_800_000L + index * 86_400_000L))
        } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                fun label() = onNodeWithTag("fast-scroll-label").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                val scrollbar = onNodeWithContentDescription("Scroll library")
                scrollbar.assertIsDisplayed()
                scrollbar.performTouchInput { down(center.copy(y = height * .45f)); moveTo(center.copy(y = height * .6f), 150) }
                val letter = label()
                assertTrue(letter.single() in 'J'..'Q')
                assertTrue(onAllNodes(hasContentDescription("Track options: $letter", substring = true)).fetchSemanticsNodes().isNotEmpty())
                assertTrue(controller.state.value.queue.entries.isEmpty())
                scrollbar.performTouchInput { moveTo(center.copy(y = height - 1f), 150); up() }
                onNodeWithContentDescription("Track options: Z207").assertIsDisplayed()
                scrollbar.performTouchInput { down(center.copy(y = 1f)); moveTo(center.copy(y = 2f), 50); up() }
                onNodeWithContentDescription("Track options: A000").assertIsDisplayed()
                onNodeWithContentDescription("Song order").performClick()
                onNodeWithText("Recently added").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.songSort == LibrarySort.RECENT }
                scrollbar.performTouchInput { down(center.copy(y = height * .4f)); moveTo(center.copy(y = height * .55f), 150) }
                val date = label()
                val formatter = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH).withZone(ZoneId.systemDefault())
                val track = tracks.first { formatter.format(Instant.ofEpochMilli(it.recentAt)) == date }
                onNodeWithContentDescription("Track options: ${track.metadata.title}").assertIsDisplayed()
                scrollbar.performTouchInput { up() }
                onNodeWithTag("fast-scroll-label").assertDoesNotExist()
                onNodeWithText("Artists").performClick()
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("Grid").performClick()
                val collections = onNodeWithContentDescription("Scroll artists")
                collections.performTouchInput { down(center.copy(y = height * .6f)); moveTo(center.copy(y = height - 1f), 150) }
                assertEquals("Z", label())
                onNodeWithText("Z artist").assertIsDisplayed()
                collections.performTouchInput { up() }
                openLibraryPage("Genres")
                onNodeWithText("Trance").performClick()
                val genreSongs = onNodeWithContentDescription("Scroll library")
                genreSongs.performTouchInput { down(center.copy(y = height * .4f)); moveTo(center.copy(y = height * .55f), 150) }
                assertTrue(label().endsWith("2020"), "Collection songs must keep the indicator for the active sort")
                genreSongs.performTouchInput { up() }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
