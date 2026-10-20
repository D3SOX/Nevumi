package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import me.d3sox.nevumi.settings.MusicPreferences
import org.jetbrains.compose.resources.getString

@OptIn(ExperimentalTestApi::class)
class LibraryUiTest {
    @Test fun clearingFavoritesIncludesFilteredSongsAndCanBeCancelledAndUndone() {
        val directory = Files.createTempDirectory("clear-favorites")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        val songs = runBlocking {
            preferences.update { it.copy(libraryTabs = listOf(LibraryTab.FAVORITES), startupTab = LibraryTab.FAVORITES,
                libraryFilter = LibraryQuery(genre = "Ambient")) }
            listOf("Ambient", "Rock", "Jazz").mapIndexed { index, genre ->
                library.importTrack(TrackMetadata("Song $index", genres = listOf(genre)),
                    TrackSource("local:$index", SourceKind.LOCAL, "/music/$index.flac")).also {
                    if (index < 2) library.setFavorite(it.id, true)
                }
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                onNodeWithText("Clear favorites").assertDoesNotExist()
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Library & gestures").performClick()
                onNodeWithText("Clear favorites").performScrollTo().performClick()
                onNodeWithText("Cancel").performClick()
                assertEquals(2, runBlocking { library.tracks().count { it.favorite } })
                onNodeWithText("Clear favorites").performScrollTo().performClick()
                onNodeWithText("Clear").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.none { it.favorite } }
                onNodeWithText("Clear favorites").assertIsNotEnabled()
                assertEquals(3, runBlocking { library.tracks().size })
                assertTrue(runBlocking { library.tracks().none { it.favorite } })
                onNodeWithText("Undo").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.count { it.favorite } == 2 }
                assertEquals(songs.take(2).map { it.id }.toSet(),
                    runBlocking { library.tracks().filter { it.favorite }.map { it.id }.toSet() })
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun tabTransitionsSkipIntermediatePagesAndIgnoreHorizontalDrags() {
        val directory = Files.createTempDirectory("library-navigation-header")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(libraryTabs = listOf(LibraryTab.RECENT, LibraryTab.PLAYLISTS, LibraryTab.STATISTICS), startupTab = LibraryTab.RECENT) } }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                onNodeWithTag("library-pager").performTouchInput { swipeLeft() }
                onNodeWithContentDescription("Recently added").assertIsSelected()
                val pageBounds = onNodeWithTag("library-tab:RECENT").fetchSemanticsNode().boundsInRoot
                mainClock.autoAdvance = false
                onNodeWithContentDescription("Statistics").performClick()
                mainClock.advanceTimeBy(112)
                val entering = onNodeWithTag("library-tab:STATISTICS").fetchSemanticsNode().boundsInRoot
                assertTrue(entering.left > pageBounds.left && entering.left < pageBounds.right)
                assertEquals(pageBounds, onNodeWithTag("library-tab:RECENT", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                repeat(30) {
                    mainClock.advanceTimeBy(16)
                    onNodeWithContentDescription("Song order").assertDoesNotExist()
                    onNodeWithContentDescription("Collection order").assertDoesNotExist()
                    onNodeWithTag("library-tab:PLAYLISTS", useUnmergedTree = true).assertDoesNotExist()
                }
                onNodeWithContentDescription("Recently added").performClick()
                mainClock.advanceTimeBy(112)
                val returning = onNodeWithTag("library-tab:RECENT").fetchSemanticsNode().boundsInRoot
                assertTrue(returning.right > pageBounds.left && returning.right < pageBounds.right)
                assertEquals(pageBounds, onNodeWithTag("library-tab:STATISTICS", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
                onNodeWithContentDescription("Statistics").performClick()
                mainClock.autoAdvance = true
                onNodeWithContentDescription("Statistics").assertIsSelected()
                onNodeWithTag("library-tab:STATISTICS").assertIsDisplayed()
                onNodeWithTag("library-tab:RECENT", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithTag("library-pager").performTouchInput { swipeRight() }
                onNodeWithContentDescription("Statistics").assertIsSelected()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSongsRatingCanBeSetAndClearedFromItsTrackMenuAndIsSaved() {
        val directory = Files.createTempDirectory("rating-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val track = runBlocking { library.importTrack(TrackMetadata("Northern Light"),
            TrackSource("local", SourceKind.LOCAL, "/music/song.flac")) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Rate song").performClick()
                onNodeWithContentDescription("4 stars").performClick()
                onNodeWithText("Confirm").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.single().rating == 4 }
                onNodeWithContentDescription("Rating: 4 stars").assertIsDisplayed()
                assertEquals(4, runBlocking { library.findTrack(track.id)!!.rating })
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Rate song").performClick()
                onNodeWithText("Clear rating").performClick()
                onNodeWithText("Confirm").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.single().rating == 0 }
                onNodeWithContentDescription("Rating: 4 stars").assertDoesNotExist()
                assertEquals(0, runBlocking { library.findTrack(track.id)!!.rating })
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun libraryTabsCanBeHiddenAndReorderedAndTheConfigurationIsSaved() {
        val directory = Files.createTempDirectory("library-tabs-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Library & gestures").performScrollTo().performClick()
                onNodeWithText("Library tabs").performScrollTo().performClick()
                onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Show Album artists"))
                onNodeWithContentDescription("Show Album artists").assertIsOff()
                onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                val handle = onNodeWithContentDescription("Reorder tab: Artists", useUnmergedTree = true)
                val target = onNodeWithContentDescription("Reorder tab: Albums", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
                val start = handle.fetchSemanticsNode().boundsInRoot.center.y
                handle.performTouchInput { down(center); advanceEventTime(600); moveBy(androidx.compose.ui.geometry.Offset(0f, -20f), delayMillis = 50) }
                waitForIdle()
                repeat(6) {
                    handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, (target - start - 20f) / 6), delayMillis = 50) }
                    waitForIdle()
                }
                handle.performTouchInput { up() }
                onNodeWithText("Save tabs").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.libraryTabs == listOf(LibraryTab.HOME, LibraryTab.SONGS, LibraryTab.ARTISTS, LibraryTab.ALBUMS) +
                    LibraryTab.entries.filter { it !in setOf(LibraryTab.HOME, LibraryTab.SONGS, LibraryTab.ARTISTS, LibraryTab.ALBUMS, LibraryTab.ALBUM_ARTISTS, LibraryTab.SEARCH) } }
                onNodeWithText("Album artists").assertDoesNotExist()
                assertEquals(listOf("Home", "Songs", "Artists", "Albums"), runBlocking { preferences.load().libraryTabs.take(4).map { getString(it.label) } })
                onNodeWithText("Artists").performClick()
                onNodeWithText("Artists").assertIsSelected()
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun theSelectedSongsCanAllBeAddedToFavorites() {
        val directory = Files.createTempDirectory("bulk-favorites-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { listOf("Song a", "Song b").forEach { title ->
            library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL, "/music/$title.flac"))
        } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Song a").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Select all").performClick()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Add to favorites").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.all { it.favorite } }
                assertEquals(2, runBlocking { library.tracks(LibraryQuery(favoritesOnly = true)).size })
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun selectedSongsCanBePlayedNextInVisibleOrderWithoutMovingThePausedPlayhead() {
        val directory = Files.createTempDirectory("bulk-queue-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val tracks = runBlocking { listOf("Playing", "Selected a", "Selected b").mapIndexed { index, title ->
            library.importTrack(TrackMetadata(title), TrackSource("local:$index", SourceKind.LOCAL, audio))
        } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking {
                    controller.playTrack(tracks[0].id, listOf(tracks[0].id)).join()
                    controller.pause().join(); controller.seek(1_000).join()
                }
                onNodeWithContentDescription("Search").performClick()
                onNodeWithContentDescription("Search your library").performTextInput("Selected")
                onNodeWithContentDescription("Track options: Selected a").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Select all").performClick()
                onNodeWithContentDescription("Play next").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == 3 }
                assertEquals(tracks.map { it.id }, controller.state.value.queue.entries.map { it.trackId })
                assertEquals(tracks[0].id, controller.state.value.playback.trackId)
                assertEquals(false, controller.state.value.playback.playing)
                kotlin.test.assertTrue(controller.state.value.playback.positionMs in 990..1100)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun libraryFiltersRestrictTheDisplayedSongsAndPlaybackQueueAndCanBeCleared() {
        val directory = Files.createTempDirectory("library-filter-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val expected = runBlocking {
            val first = library.importTrack(TrackMetadata("New ambient", genres = listOf("Ambient"), year = 2024), TrackSource("new", SourceKind.LOCAL, audio))
            library.importTrack(TrackMetadata("Old ambient", genres = listOf("Ambient"), year = 1990), TrackSource("old", SourceKind.LOCAL, audio))
            library.importTrack(TrackMetadata("New rock", genres = listOf("Rock"), year = 2024), TrackSource("rock", SourceKind.LOCAL, audio))
            first.id
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Filters").performClick()
                onNode(hasText("Genre") and hasSetTextAction()).performTextInput("Ambient")
                onNodeWithText("Year from").performScrollTo().performTextInput("2020")
                onNodeWithText("Apply filters").performClick()
                onNodeWithText("New ambient").assertIsDisplayed()
                onNodeWithText("Old ambient").assertDoesNotExist()
                onNodeWithText("New rock").assertDoesNotExist()
                onNodeWithText("New ambient").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                assertEquals(listOf(expected), controller.state.value.queue.entries.map { it.trackId })
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Filters").performClick()
                onNodeWithText("Genre").assertTextContains("Ambient")
                onNodeWithText("Year from").assertTextContains("2020")
                onNodeWithText("Clear filters").performClick()
                onNodeWithText("Old ambient").assertIsDisplayed()
                onNodeWithText("New rock").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun theSourcesDialogLinksCopiesOnlyAfterConfirmationAndKeepsThePausedQueue() {
        val directory = Files.createTempDirectory("link-sources-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val local = runBlocking { library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Local album"), TrackSource("local:song", SourceKind.LOCAL, audio)) }
        val remote = runBlocking { library.importTrack(TrackMetadata("Server copy", "Fixture Artist", "Server album"), TrackSource("server:song", SourceKind.SERVER, "song")) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking {
                    controller.playTrack(local.id, listOf(local.id, remote.id, local.id)).join()
                    controller.pause().join()
                    controller.seek(1_000).join()
                }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.playback.playing && controller.state.value.playback.positionMs in 990..1100 }
                val before = controller.state.value.queue
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Song sources").performClick()
                onNodeWithText("Link another copy").performClick()
                onNodeWithContentDescription("Link copy: Server copy").performClick()
                onNodeWithText("Link these songs?").assertIsDisplayed()
                assertEquals(2, controller.state.value.tracks.size)
                onNodeWithText("Cancel").performClick()
                assertEquals(2, controller.state.value.tracks.size)
                onNodeWithContentDescription("Link copy: Server copy").performClick()
                onNodeWithText("Link songs", substring = false).performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.tracks.size == 1 }
                assertEquals(local.id, controller.state.value.tracks.single().id)
                assertEquals(List(3) { local.id }, controller.state.value.queue.entries.map { it.trackId })
                assertEquals(before.entries.map { it.id }, controller.state.value.queue.entries.map { it.id })
                assertEquals(before.currentEntryId, controller.state.value.playback.entryId)
                assertEquals(false, controller.state.value.playback.playing)
                kotlin.test.assertTrue(controller.state.value.playback.positionMs in 990..1100)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun sourceFiltersIncludeMixedTracksAndStartAQueueFromOnlyTheVisibleSongs() {
        val directory = Files.createTempDirectory("library-source-filter")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val localIds = runBlocking {
            val local = library.importTrack(TrackMetadata("Device song"), TrackSource("local", SourceKind.LOCAL, audio))
            library.importTrack(TrackMetadata("Server song"), TrackSource("server", SourceKind.SERVER, "remote"))
            val mixed = library.importTrack(TrackMetadata("Mixed song"), TrackSource("mixed", SourceKind.LOCAL, audio))
            library.importTrack(TrackMetadata("Mixed song"), TrackSource("mixed-server", SourceKind.SERVER, "remote-mixed"), linkTo = mixed.id)
            library.importTrack(TrackMetadata("Downloaded song"), TrackSource("downloaded", SourceKind.DOWNLOAD, audio))
            listOf(local.id, mixed.id)
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Server").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.sourceFilter == SourceKind.SERVER }
                onNodeWithText("Server song").assertIsDisplayed()
                onNodeWithText("Mixed song").assertIsDisplayed()
                onNodeWithText("Device song").assertDoesNotExist()
                onNodeWithText("Downloaded song").assertDoesNotExist()
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Downloads").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.sourceFilter == SourceKind.DOWNLOAD }
                onNodeWithText("Downloaded song").assertIsDisplayed()
                onNodeWithText("Mixed song").assertDoesNotExist()
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Device").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.sourceFilter == SourceKind.LOCAL }
                onNodeWithTag("track-row:${localIds[1]}").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                assertEquals(localIds, controller.state.value.queue.entries.map { it.trackId })
                onNodeWithText("Server song").assertDoesNotExist()
                onNodeWithText("Downloaded song").assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun songOrderControlsChangeThePlayedQueueAndKeepTheChoiceAfterRestart() {
        val directory = Files.createTempDirectory("library-sort-ui")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val ids = runBlocking {
            listOf(Triple("a", "A song", "Zeta"), Triple("b", "B song", "Alpha"), Triple("c", "C song", "Mu")).associate { (id, title, artist) ->
                id to library.importTrack(TrackMetadata(title, artist), TrackSource(id, SourceKind.LOCAL, audio)).id
            }
        }
        var controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            val initial = controller
            runComposeUiTest {
                setContent { MusicPlayerApp(initial) }
                waitUntil(timeoutMillis = 3_000) { !initial.state.value.loading }
                onNodeWithContentDescription("Song order").performClick()
                onNodeWithText("Artist").performClick()
                waitUntil(timeoutMillis = 3_000) { initial.state.value.preferences.songSort == LibrarySort.ARTIST }
                onNodeWithTag("track-row:${ids.getValue("b")}").performClick()
                waitUntil(timeoutMillis = 3_000) { initial.state.value.playback.playing }
                assertEquals(listOf("b", "c", "a").map(ids::getValue), initial.state.value.queue.entries.map { it.trackId })
                runBlocking { initial.pause().join() }
                onNodeWithContentDescription("Song order").performClick()
                onNodeWithText("Ascending").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    initial.state.value.preferences.songOrderReversed
                }
            }
            runBlocking { initial.closeAndJoin() }
            store.close()
            store = openMusicStore(database)
            controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
            val restored = controller
            runComposeUiTest {
                setContent { MusicPlayerApp(restored) }
                waitUntil(timeoutMillis = 3_000) { !restored.state.value.loading }
                onNodeWithContentDescription("Song order").performClick()
                onNodeWithText("Descending").assertIsDisplayed()
                onNodeWithText("Artist").performClick()
                onNodeWithTag("track-row:${ids.getValue("a")}").performClick()
                waitUntil(timeoutMillis = 3_000) { restored.state.value.playback.playing }
                assertEquals(listOf("a", "c", "b").map(ids::getValue), restored.state.value.queue.entries.map { it.trackId })
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun artistGenreAndFavoriteViewsBrowseTheSameUnifiedLibrary() {
        val directory = Files.createTempDirectory("browse-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Favorite").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.any { it.metadata.title == "Northern Light" && it.favorite } }
                onNodeWithText("Artists").performClick()
                onNodeWithText("Fixture Artist").performClick()
                onNodeWithText("Northern Light").assertIsDisplayed()
                openLibraryPage("Genres")
                onNodeWithText("Ambient").performClick()
                onNodeWithText("Night Walk").assertIsDisplayed()
                openLibraryPage("Favorites")
                onNodeWithText("Northern Light").assertIsDisplayed()
                onNodeWithText("Night Walk").assertDoesNotExist()
                onNodeWithText("Long Way Home").assertDoesNotExist()
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anAlbumOpensItsSongsAndStartsAQueueContainingOnlyThatAlbum() {
        val directory = Files.createTempDirectory("album-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking {
            val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString())
            library.importTrack(TrackMetadata("Another song", "Other Artist", "Another Album"),
                TrackSource("extra", SourceKind.LOCAL, tracks.first().sources.first().location))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Albums").performClick()
                onNodeWithText("Fixture Album").performClick()
                onNodeWithText("Northern Light").assertIsDisplayed()
                onNodeWithText("Another song").assertDoesNotExist()
                onNodeWithText("Night Walk").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                assertEquals(3, controller.state.value.queue.entries.size)
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
