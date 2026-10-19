package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryLayoutUiTest {
    @Test
    fun defaultGridsFitTheWindowAndAnExplicitColumnChoiceStillWins() {
        val directory = Files.createTempDirectory("adaptive-grid")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { ('A'..'H').forEach { letter -> library.importTrack(TrackMetadata("Song $letter", album = "Album $letter"),
            TrackSource("local-$letter", SourceKind.LOCAL, "/music/$letter.flac")) } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = me.d3sox.nevumi.settings.MusicPreferences(store))
        var width by androidx.compose.runtime.mutableStateOf(360)
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(width.dp, 740.dp)) {
                    MusicPlayerApp(controller)
                } }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                onNodeWithText("Albums").performClick()
                fun top(label: String) = onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top
                assertTrue(kotlin.math.abs(top("Album A") - top("Album B")) < 2)
                assertTrue(top("Album C") > top("Album A") + 20)
                runOnIdle { width = 460 }
                assertTrue(kotlin.math.abs(top("Album A") - top("Album C")) < 2,
                    "Automatic should fit three columns at the Pixel 8 Pro's 460 dp width")
                assertTrue(top("Album D") > top("Album A") + 20)
                runOnIdle { width = 1000 }
                assertTrue(kotlin.math.abs(top("Album A") - top("Album D")) < 2,
                    "A laptop window should fit at least four cards instead of two oversized cards")
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("Automatic").assertIsSelected()
                onNodeWithText("3 columns").performClick()
                assertTrue(kotlin.math.abs(top("Album A") - top("Album C")) < 2)
                assertTrue(top("Album D") > top("Album A") + 20)
                runOnIdle { width = 460 }
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("2 columns").performClick()
                assertTrue(kotlin.math.abs(top("Album A") - top("Album B")) < 2)
                assertTrue(top("Album C") > top("Album A") + 20)
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("Automatic").performClick()
                assertTrue(kotlin.math.abs(top("Album A") - top("Album C")) < 2)
                assertTrue(top("Album D") > top("Album A") + 20)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun collectionGridColumnsAreIndependentAndSurviveRestartWhileSongsRemainAList() {
        val directory = Files.createTempDirectory("collection-grid-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { ('A'..'F').forEach { letter -> library.importTrack(TrackMetadata("Song $letter", artist = "Artist $letter", album = "Album $letter",
            albumArtist = "Album artist $letter", genres = listOf("Genre $letter"), folder = "Music/$letter"),
            TrackSource("local-$letter", SourceKind.LOCAL, "/music/$letter.flac")) } }
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        runBlocking { preferences.update { it.copy(libraryTabs = it.libraryTabs + LibraryTab.ALBUM_ARTISTS) } }
        val playlists = me.d3sox.nevumi.playlists.Playlists(store)
        runBlocking { listOf("Mix A", "Mix B").forEach { playlists.create(it, emptyList()) } }
        fun controller() = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences.withOptionsButtons(), playlists = playlists)
        var active by androidx.compose.runtime.mutableStateOf(controller())
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(360.dp, 740.dp)) { androidx.compose.runtime.key(active) { MusicPlayerApp(active) } } }
                waitUntil(timeoutMillis = 3_000) { !active.state.value.loading }
                onNodeWithContentDescription("Collection view").assertDoesNotExist()
                onNodeWithText("Albums").performClick()
                fun bounds(label: String) = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(bounds("Album A").top - bounds("Album B").top) < 2)
                assertTrue(bounds("Album C").top > bounds("Album A").bottom)
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("1 column").assertDoesNotExist()
                onNodeWithText("Automatic").assertIsSelected()
                onNodeWithText("3 columns").performClick()
                assertTrue(kotlin.math.abs(bounds("Album A").top - bounds("Album C").top) < 2)
                assertTrue(bounds("Album D").top > bounds("Album A").bottom)
                onNodeWithText("Album A").performClick()
                onNodeWithContentDescription("Track options: Song A").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Artists").performClick()
                assertTrue(bounds("Artist B").top >= bounds("Artist A").bottom)
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithText("Name").performClick()
                assertTrue(bounds("Artist B").top >= bounds("Artist A").bottom,
                    "Changing the sort should retain the default list view")
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("List").assertIsSelected()
                onNodeWithText("Grid").performClick()
                assertTrue(kotlin.math.abs(bounds("Artist A").top - bounds("Artist B").top) < 2)
                for ((page, first, second) in listOf(Triple("Album artists", "Album artist A", "Album artist B"),
                    Triple("Genres", "Genre A", "Genre B"), Triple("Folders", "Music/A", "Music/B"))) {
                    openLibraryPage(page)
                    assertTrue(bounds(second).top >= bounds(first).bottom, "$page defaults to a list")
                }
                openLibraryPage("Albums")
                assertTrue(kotlin.math.abs(bounds("Album A").top - bounds("Album C").top) < 2)
                runBlocking { active.closeAndJoin() }
                runOnIdle { active = controller() }
                waitUntil(timeoutMillis = 3_000) { !active.state.value.loading }
                onNodeWithText("Artists").performClick()
                assertTrue(kotlin.math.abs(bounds("Artist A").top - bounds("Artist B").top) < 2,
                    "An explicit grid choice should survive restart")
                onNodeWithText("Albums").performClick()
                assertTrue(kotlin.math.abs(bounds("Album A").top - bounds("Album C").top) < 2)
                onNodeWithContentDescription("Collection view").performClick()
                onNodeWithText("List").performClick()
                assertTrue(bounds("Album B").top >= bounds("Album A").bottom)
                onNodeWithText("Songs").performClick()
                onNodeWithContentDescription("Collection view").assertDoesNotExist()
                onNodeWithText("More").performClick()
                onNodeWithText("Playlists").performScrollTo().performClick()
                assertTrue(kotlin.math.abs(bounds("Mix A").top - bounds("Mix B").top) < 2)
                onNodeWithText("Mix A").performClick()
                onNodeWithText("Back to playlists").assertDoesNotExist()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Mix B").assertIsDisplayed()
            }
        } finally { runBlocking { active.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun compactBottomNavigationKeepsTheOtherLibraryPagesReachableThroughMore() {
        val directory = Files.createTempDirectory("bottom-navigation-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking { library.importTrack(TrackMetadata("A song", folder = "Music/Weekend"), TrackSource("local", SourceKind.LOCAL, "/music/song.flac")) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { Box(Modifier.size(360.dp, 740.dp)) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val navigation = onNodeWithTag("library-navigation").fetchSemanticsNode().boundsInRoot
                assertTrue(navigation.top > onRoot().fetchSemanticsNode().boundsInRoot.height * .8f)
                onNodeWithText("Folders").assertDoesNotExist()
                onNodeWithText("More").performClick()
                onNodeWithText("Folders").performScrollTo().performClick()
                onNodeWithText("Music/Weekend").assertIsDisplayed()
                onNodeWithText("More").assertIsSelected()
                onNodeWithText("Songs").performClick()
                onNodeWithContentDescription("Track options: A song").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun sortDirectionNamesDescribeTheVisibleOrderAndCanBeToggled() {
        val directory = Files.createTempDirectory("song-direction-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking {
            listOf("Alpha", "Zulu").forEach { library.importTrack(TrackMetadata(it), TrackSource(it, SourceKind.LOCAL, "/music/$it.flac")) }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = me.d3sox.nevumi.settings.MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Song order").performClick()
                onNodeWithText("Ascending").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.songOrderReversed }
                assertTrue(onNodeWithText("Zulu").fetchSemanticsNode().boundsInRoot.top < onNodeWithText("Alpha").fetchSemanticsNode().boundsInRoot.top)
                onNodeWithContentDescription("Song order").performClick()
                onNodeWithText("Descending").assertIsDisplayed()
                onNodeWithText("Descending").performClick()
                runBlocking { controller.updatePreferences { it.copy(songSort = LibrarySort.RECENT, songOrderReversed = false) }.join() }
                onNodeWithContentDescription("Song order").performClick()
                onNodeWithText("Descending").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pickedFoldersAreSavedCancellationDoesNothingAndRulesCanBeRemoved() {
        val directory = Files.createTempDirectory("picked-folder-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        val answers = java.util.ArrayDeque<String>().apply { add("/music"); add("/music/Podcasts") }
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, folderPicker = FolderPicker { answers.pollFirst() }) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Local music").performClick()
                onNodeWithContentDescription("Add included folder").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.includeFolders == listOf("/music") }
                onNodeWithContentDescription("Add excluded folder").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.excludeFolders == listOf("/music/Podcasts") }
                onNodeWithContentDescription("Add included folder").performScrollTo().performClick()
                waitForIdle()
                kotlin.test.assertEquals(listOf("/music"), controller.state.value.preferences.includeFolders)
                onNodeWithContentDescription("Remove included folder: /music").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.includeFolders.isEmpty() }
                onNodeWithContentDescription("Remove excluded folder: /music/Podcasts").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.excludeFolders.isEmpty() }
                kotlin.test.assertEquals(emptyList(), runBlocking { preferences.load() }.includeFolders)
                kotlin.test.assertEquals(emptyList(), runBlocking { preferences.load() }.excludeFolders)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun settingsAreDedicatedCategorizedPagesAndLocalSetupIsReachedFromThem() {
        val directory = Files.createTempDirectory("settings-pages-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        var addMusicRequests = 0
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, onAddMusic = { addMusicRequests++ }) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithTag("settings-page").assertIsDisplayed()
                onNodeWithTag("library-pager").assertDoesNotExist()
                onNodeWithText("Appearance").performClick()
                onNodeWithContentDescription("Theme").performClick()
                onNodeWithText("Dark").performClick()
                onNodeWithContentDescription("AMOLED black").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.amoledBlack }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Local music").performClick()
                onNodeWithText("Scan music").performClick()
                kotlin.test.assertEquals(1, addMusicRequests)
                onNodeWithText("Include folders").assertIsDisplayed()
                onNodeWithText("Exclude folders").assertIsDisplayed()
                onNodeWithContentDescription("Add included folder").assertIsDisplayed()
                onNodeWithContentDescription("Add excluded folder").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("library-pager").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun theLibraryKeepsSongsNearTheHeaderAndOpensSearchOnlyWhenRequested() {
        val directory = Files.createTempDirectory("compact-library-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking {
            listOf("First song", "Second song").forEach {
                library.importTrack(TrackMetadata(it), TrackSource(it, SourceKind.LOCAL, "/music/$it.flac"))
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, onAddMusic = {}) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Search your library").assertDoesNotExist()
                onNodeWithText("Scan music").assertDoesNotExist()
                onNodeWithText("Music sources").assertDoesNotExist()
                val firstSong = onNodeWithText("First song").fetchSemanticsNode().boundsInRoot
                assertTrue(firstSong.top < onRoot().fetchSemanticsNode().boundsInRoot.height * .25f)
                onNodeWithContentDescription("Search").performClick()
                onNodeWithText("Your music").assertDoesNotExist()
                onNodeWithContentDescription("Settings").assertDoesNotExist()
                onNodeWithContentDescription("Search your library").performTextInput("Second")
                onNodeWithContentDescription("Track options: First song").assertDoesNotExist()
                onNodeWithContentDescription("Track options: Second song").assertIsDisplayed()
                onNodeWithContentDescription("Close search").performClick()
                onNodeWithContentDescription("Search your library").assertDoesNotExist()
                onNodeWithContentDescription("Track options: First song").assertIsDisplayed()
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Device").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
