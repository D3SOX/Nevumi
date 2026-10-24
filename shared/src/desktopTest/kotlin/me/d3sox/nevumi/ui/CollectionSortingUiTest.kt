package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CollectionSortingUiTest {
    @Test fun libraryAndPlaylistCountsUseSingularForOneAndPluralForMultiple() {
        val directory = Files.createTempDirectory("library-count-labels")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = me.d3sox.nevumi.playlists.Playlists(store)
        val song = runBlocking {
            library.importTrack(TrackMetadata("Song", "Artist", "Album", genres = listOf("Genre")),
                TrackSource("local", SourceKind.LOCAL, "/music/song.mp3"))
        }
        runBlocking { playlists.create("Evening", listOf(song.id)) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Songs, 1 song").assertIsDisplayed()
                openLibraryPage("Albums")
                onNodeWithText("1 album").assertIsDisplayed()
                onNodeWithText("Album").performClick()
                onNodeWithText("Album · 1 song · 0:00").assertIsDisplayed()
                openLibraryPage("Playlists")
                onNodeWithText("1 playlist").assertIsDisplayed()
                onNodeWithText("Evening").performClick()
                onNodeWithText("Playlist · 1 song").assertIsDisplayed()
                openLibraryPage("Songs")
                runBlocking { controller.createPlaylist("Morning").join() }
                openLibraryPage("Playlists")
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("2 playlists").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun switchingFromSongsToPlaylistsKeepsASortControlVisibleThroughoutTheTransition() {
        val directory = Files.createTempDirectory("sort-navigation-flicker")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        runBlocking {
            library.importTrack(TrackMetadata("Song", "Artist"), TrackSource("local", SourceKind.LOCAL, "/music/song.mp3"))
            preferences.update { it.copy(libraryTabs = listOf(LibraryTab.SONGS, LibraryTab.PLAYLISTS, LibraryTab.STATISTICS)) }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences,
            playlists = me.d3sox.nevumi.playlists.Playlists(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Song order").assertIsDisplayed()
                mainClock.autoAdvance = false
                onNode(hasText("Playlists") and hasAnyAncestor(hasTestTag("library-navigation"))).performClick()
                repeat(40) { frame ->
                    mainClock.advanceTimeByFrame()
                    val controls = onAllNodes(hasContentDescription("Song order") or hasContentDescription("Collection order")).fetchSemanticsNodes()
                    kotlin.test.assertEquals(1, controls.size, "Sort control disappeared on navigation frame $frame")
                }
                onNodeWithContentDescription("Collection order").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun collectionOptionsInsertAllSongsNextAfterTheLastInsertionOrAtTheEnd() {
        val directory = Files.createTempDirectory("collection-queue-actions")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { LocalFiles(library).scan(java.nio.file.Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                openLibraryPage("Albums"); onNodeWithText("Fixture Album").performClick()
                onNodeWithContentDescription("Play collection").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.contextName == "Fixture Album" }
                val album = controller.state.value.queue.entries.map { it.trackId }
                for (label in listOf("Play next", "Play after", "Add to queue")) {
                    runBlocking {
                        controller.playTrack(songs[0].id, listOf(songs[0].id)).join(); controller.pause().join()
                        controller.enqueue(listOf(songs[0].id, songs[2].id), me.d3sox.nevumi.queue.QueueAction.APPEND).join()
                        controller.enqueue(songs[1].id, me.d3sox.nevumi.queue.QueueAction.PLAY_NEXT).join()
                    }
                    val before = controller.state.value.queue.upNext.map { it.trackId }
                    onNodeWithContentDescription("Collection options").performClick()
                    onNodeWithText(label, substring = false).performClick()
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.upNext.size == 6 }
                    val expected = when (label) {
                        "Play next" -> album + before
                        "Play after" -> before.take(1) + album + before.drop(1)
                        else -> before + album
                    }
                    kotlin.test.assertEquals(expected, controller.state.value.queue.upNext.map { it.trackId })
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun collectionDetailsHaveAnArtworkHeaderAndCanPlayOrManageAllTheirSongs() {
        val directory = Files.createTempDirectory("collection-hero")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val songs = runBlocking { LocalFiles(library).scan(java.nio.file.Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = me.d3sox.nevumi.playlists.Playlists(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                openLibraryPage("Albums"); onNodeWithText("Fixture Album").performClick()
                onNodeWithContentDescription("Collection artwork: Fixture Album", useUnmergedTree = true).assertIsDisplayed()
                onNodeWithContentDescription("Shuffle collection").performClick().assertIsSelected()
                onNodeWithContentDescription("Play collection").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.unshuffledEntryIds != null }
                kotlin.test.assertEquals(songs.map { it.id }.sorted(), controller.state.value.queue.entries.map { it.trackId }.sorted())
                runBlocking { controller.pause().join() }
                onNodeWithContentDescription("Collection options").performClick()
                onNodeWithText("Manage playlists").performClick()
                onNodeWithTag("playlist-membership-sheet").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun playlistsCanBeDraggedIntoACustomOrderThatIsRemembered() {
        val directory = Files.createTempDirectory("playlist-custom-order")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = me.d3sox.nevumi.playlists.Playlists(store)
        val preferences = MusicPreferences(store)
        val (alpha, bravo, charlie) = runBlocking { listOf("Alpha", "Bravo", "Charlie").map { playlists.create(it, emptyList()).id } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                openLibraryPage("Playlists")
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("List").performClick()
                onNodeWithTag("collection-reorder:$alpha", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithText("Custom order").performClick()
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithContentDescription("Ascending").assertDoesNotExist()
                onNodeWithText("Custom order").performClick()
                onNodeWithTag("collection-reorder:$alpha", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithText("Rearrange").performClick()
                onNodeWithContentDescription("Collection view").assertDoesNotExist()
                onNodeWithContentDescription("Collection order").assertDoesNotExist()
                val handle = onNodeWithTag("collection-reorder:$charlie", useUnmergedTree = true)
                val target = onNodeWithTag("collection-reorder:$alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
                val start = handle.fetchSemanticsNode().boundsInRoot.center.y
                handle.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -20f), delayMillis = 50) }
                waitForIdle()
                repeat(6) {
                    handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, (target - start + 20f) / 6), delayMillis = 50) }
                    waitForIdle()
                }
                handle.performTouchInput { up() }
                val expected = listOf(charlie, alpha, bravo)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.collectionLayouts[LibraryTab.PLAYLISTS]?.order == expected }
                val tops = listOf("Charlie", "Alpha", "Bravo").map { onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top }
                kotlin.test.assertEquals(tops.sorted(), tops)
                runBlocking { controller.createPlaylist("Aardvark").join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.size == 4 }
                assertTrue(onNodeWithText("Bravo").fetchSemanticsNode().boundsInRoot.top < onNodeWithText("Aardvark").fetchSemanticsNode().boundsInRoot.top)
                val aardvark = controller.state.value.playlists.single { it.name == "Aardvark" }.id
                onNodeWithTag("collection-reorder:$alpha", useUnmergedTree = true).performCustomAccessibilityActionWithLabel("Move up")
                waitUntil(timeoutMillis = 3_000) {
                    controller.state.value.preferences.collectionLayouts[LibraryTab.PLAYLISTS]?.order == listOf(alpha, charlie, bravo, aardvark)
                }
                onNodeWithContentDescription("Finish rearranging").performClick()
                onNodeWithTag("collection-reorder:$alpha", useUnmergedTree = true).assertDoesNotExist()
                onNodeWithContentDescription("Collection view").assertIsDisplayed()
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithText("Rearrange").performClick()
                onNodeWithContentDescription("Finish rearranging").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
                onNodeWithTag("collection-reorder:$alpha", useUnmergedTree = true).assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun collectionSortingChangesTheVisibleOrderAndRemembersItIndependentlyFromSongs() {
        val directory = Files.createTempDirectory("collection-sorting")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        runBlocking {
            listOf("Alpha" to 1, "Zulu" to 3).forEach { (album, count) -> repeat(count) { index ->
                library.importTrack(TrackMetadata("$album song $index", "An artist", album, year = if (album == "Alpha") 2000 else 2020),
                    TrackSource("$album-$index", SourceKind.LOCAL, "/music/$album-$index.flac", modifiedAt = if (album == "Alpha") 1_000 else 2_000))
            } }
        }
        val preferences = MusicPreferences(store)
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                openLibraryPage("Albums")
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("List").performClick()
                assertTrue(onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top < onNodeWithText("Zulu").fetchSemanticsNode().boundsInRoot.top)
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithText("Song count").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onNodeWithText("Zulu").fetchSemanticsNode().boundsInRoot.top < onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top
                }
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithContentDescription("Descending").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top < onNodeWithText("Zulu").fetchSemanticsNode().boundsInRoot.top
                }
                openLibraryPage("Songs")
                onNodeWithContentDescription("Song order").assertIsDisplayed()
                openLibraryPage("Albums")
                assertTrue(onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top < onNodeWithText("Zulu").fetchSemanticsNode().boundsInRoot.top)
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithText("Year").assertIsDisplayed()
                onNodeWithText("Artist").assertIsDisplayed()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
