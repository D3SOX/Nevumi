package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryMaintenanceUiTest {
    @Test fun clearingRatingsCanBeCancelledAndUndoneIncludingFilteredSongs() = withLibrary { controller, library ->
        runBlocking { controller.updatePreferences { it.copy(libraryFilter = LibraryQuery(genre = "Ambient")) }.join() }
        runComposeUiTest {
            setContent { MusicPlayerApp(controller) }
            waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
            onNodeWithContentDescription("Settings").performClick()
            onNodeWithText("Library & gestures").performClick()
            onNodeWithText("Clear ratings").performScrollTo().performClick()
            onNodeWithText("Cancel").performClick()
            assertEquals(listOf(2, 5), runBlocking { library.tracks().map { it.rating }.filter { it > 0 }.sorted() })
            onNodeWithText("Clear ratings").performClick()
            onNodeWithText("Clear").performClick()
            waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.all { it.rating == 0 } }
            onNodeWithText("Clear ratings").assertIsNotEnabled()
            assertTrue(runBlocking { library.tracks().all { it.rating == 0 } })
            onNodeWithText("Undo").performClick()
            waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.count { it.rating > 0 } == 2 }
            assertEquals(listOf(2, 5), runBlocking { library.tracks().map { it.rating }.filter { it > 0 }.sorted() })
            onNodeWithContentDescription("Remember scroll positions").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3_000) { !controller.state.value.preferences.rememberScrollPositions }
        }
    }

    @Test fun scrollMemoryRestoresRecreatedListsAndGridsOnlyWhenEnabled() = withLibrary { controller, _ ->
        runComposeUiTest {
            var mounted by mutableStateOf(true)
            var registry by mutableStateOf(SaveableStateRegistry(null) { true })
            lateinit var list: LazyListState
            lateinit var grid: LazyGridState
            setContent {
                if (mounted) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    Column {
                        list = rememberLibraryListState(controller, "test-list")
                        grid = rememberLibraryGridState(controller, "test-grid")
                        LazyColumn(Modifier.height(100.dp), state = list) {
                            items(40) { Text("Song $it", Modifier.height(48.dp)) }
                        }
                        LazyVerticalGrid(GridCells.Fixed(2), Modifier.height(100.dp), state = grid) {
                            items(40) { Text("Album $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
            runBlocking { list.scrollToItem(15); grid.scrollToItem(20) }
            waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.libraryPositions.values.map { it.index }.containsAll(listOf(15, 20)) }
            val saved = runOnIdle { registry.performSave() }
            runOnIdle { mounted = false }
            waitForIdle()
            // Recreation must restore the latest UI state even when persistence is behind.
            runBlocking { controller.updatePreferences { it.copy(libraryPositions = emptyMap()) }.join() }
            runOnIdle { registry = SaveableStateRegistry(saved) { true }; mounted = true }
            waitForIdle()
            assertEquals(15, list.firstVisibleItemIndex)
            assertEquals(20, grid.firstVisibleItemIndex)
            runBlocking { controller.updatePreferences { it.copy(rememberScrollPositions = false) }.join() }
            waitForIdle()
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemIndex)
            runBlocking { list.scrollToItem(12); grid.scrollToItem(18) }
            val disabledSaved = runOnIdle { registry.performSave() }
            runOnIdle { mounted = false }
            waitForIdle()
            runOnIdle { registry = SaveableStateRegistry(disabledSaved) { true }; mounted = true }
            waitForIdle()
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemIndex)
            assertTrue(controller.state.value.preferences.libraryPositions.isEmpty())
            runBlocking { controller.updatePreferences { it.copy(rememberScrollPositions = true) }.join() }
            waitForIdle()
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, grid.firstVisibleItemIndex)
        }
    }

    @Test fun resettingTabsIsADraftUntilSaved() = withLibrary { controller, _ ->
        val custom = listOf(LibraryTab.FAVORITES, LibraryTab.SONGS)
        runBlocking { controller.updatePreferences { it.copy(libraryTabs = custom) }.join() }
        runComposeUiTest {
            setContent { MusicPlayerApp(controller) }
            waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
            onNodeWithContentDescription("Settings").performClick()
            onNodeWithText("Library & gestures").performClick()
            onNodeWithText("Library tabs").performScrollTo().performClick()
            onNodeWithText("Reset").performClick()
            onNode(hasScrollToIndexAction()).performScrollToIndex(0)
            onNodeWithContentDescription("Show Home").assertIsOn()
            assertEquals(custom, controller.state.value.preferences.libraryTabs)
            onNodeWithText("Cancel").performClick()
            assertEquals(custom, controller.state.value.preferences.libraryTabs)
            onNodeWithText("Library tabs").performScrollTo().performClick()
            onNodeWithText("Reset").performClick()
            onNodeWithText("Save tabs").performClick()
            waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.libraryTabs == MusicSettings().libraryTabs }
        }
    }

    private fun withLibrary(test: (MusicController, MusicLibrary) -> Unit) {
        val directory = Files.createTempDirectory("library-maintenance")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        runBlocking {
            listOf(2, 5, 0).forEachIndexed { index, rating ->
                val track = library.importTrack(TrackMetadata("Song $index", genres = listOf(if (index == 0) "Ambient" else "Rock")),
                    TrackSource("local:$index", SourceKind.LOCAL, "/music/$index.flac"))
                library.setRating(track.id, rating)
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try { test(controller, library) }
        finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
