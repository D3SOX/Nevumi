package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.SemanticsProperties
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.PlaylistServer
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PlaylistMembershipUiTest {
    @Test
    fun nowPlayingCanManageMembershipBesideNextAndShowsWhenTheSongBelongsToAPlaylist() {
        val directory = Files.createTempDirectory("player-playlists")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val song = runBlocking { LocalFiles(library).scan(java.nio.file.Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }.first()
        runBlocking { playlists.create("Evening", emptyList()) }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                runBlocking { player.playTrack(song.id).join(); player.pause().join() }
                onNodeWithTag("mini-player").performClick()
                val button = onNodeWithContentDescription("Manage playlists")
                button.assertIsDisplayed().assertIsNotSelected()
                kotlin.test.assertTrue(button.fetchSemanticsNode().boundsInRoot.left >
                    onNodeWithContentDescription("Next track").fetchSemanticsNode().boundsInRoot.left)
                button.performClick()
                onNodeWithTag("playlist-membership-sheet").assertIsDisplayed()
                onNodeWithContentDescription("Playlist membership: Evening").assertIsOff().performClick()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.single().trackIds == listOf(song.id) }
                button.assertIsSelected().performClick()
                onNodeWithContentDescription("Playlist membership: Evening").assertIsOn()
                onNodeWithTag("playlist-membership-sheet").performTouchInput {
                    swipe(topCenter.copy(y = top + 20f), bottomCenter, 300)
                }
                waitUntil(timeoutMillis = 1_000) { onAllNodesWithTag("playlist-membership-sheet").fetchSemanticsNodes().isEmpty() }
                assertEquals(false, player.state.value.playback.playing)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun bulkMembershipShowsMixedChecksAndArtworkAndOnlyAppliesConfirmedChanges() {
        val directory = Files.createTempDirectory("playlist-membership")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val songs = runBlocking {
            listOf("First song", "Second song").map { title ->
                library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL, "/music/$title.flac"))
            }.also {
                playlists.create("Evening", listOf(it[0].id, it[0].id))
                playlists.create("Morning", emptyList())
                playlists.createSmart("All music", LibraryQuery())
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Track options: First song").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Select all").performClick()
                fun manage() {
                    onNodeWithContentDescription("Selection actions").performClick()
                    onNodeWithText("Add to playlist").performClick()
                }
                fun membership(name: String) = onNodeWithContentDescription("Playlist membership: $name")
                manage()
                membership("Evening").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate))
                onNodeWithContentDescription("Playlist artwork: Evening", useUnmergedTree = true).assertIsDisplayed()
                membership("All music").assertIsNotEnabled().assertIsOn()
                membership("Evening").performClick()
                membership("Morning").performClick()
                onNodeWithText("Cancel").performClick()
                assertEquals(listOf(songs[0].id, songs[0].id), runBlocking { playlists.all().single { it.name == "Evening" }.trackIds })
                onNodeWithText("2 songs selected").assertIsDisplayed()
                manage()
                membership("Evening").performClick()
                membership("Morning").performClick()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.single { it.name == "Morning" }.trackIds.size == 2 }
                assertEquals(listOf(songs[0].id, songs[0].id, songs[1].id), runBlocking { playlists.all().single { it.name == "Evening" }.trackIds })
                manage()
                membership("Evening").assertIsOn().performClick()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.single { it.name == "Evening" }.trackIds.isEmpty() }
                assertEquals(songs.map { it.id }, runBlocking { playlists.all().single { it.name == "Morning" }.trackIds })
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun searchFiltersPlaylistsAndKeepsChoicesMadeBeforeFiltering() {
        val directory = Files.createTempDirectory("playlist-membership-search")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val song = runBlocking {
            library.importTrack(TrackMetadata("Song"), TrackSource("song", SourceKind.LOCAL, "/music/song.flac")).also {
                playlists.create("Evening", emptyList())
                playlists.create("Morning", emptyList())
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Track options: Song").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Add to playlist").performClick()
                onNodeWithContentDescription("Playlist membership: Evening").performClick()
                onNodeWithText("Search playlists").performTextInput("morn")
                onAllNodesWithContentDescription("Playlist membership: Evening").assertCountEquals(0)
                onNodeWithContentDescription("Playlist membership: Morning").performClick()
                onNodeWithText("Search playlists").performTextReplacement("x")
                onNodeWithText("No playlists match \"x\".").assertIsDisplayed()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.all { it.trackIds == listOf(song.id) } }
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pickerListsPlaylistsHoldingTheSongInATopSectionThenSortsByTheChosenOrderAndRemembersIt() {
        val directory = Files.createTempDirectory("playlist-membership-sort")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        var now = 0L
        val playlists = Playlists(store, clock = { now })
        val song = runBlocking {
            library.importTrack(TrackMetadata("Song"), TrackSource("song", SourceKind.LOCAL,
                Files.createFile(directory.resolve("song.flac")).toString())).also { song ->
                val alpha = listOf("Alpha", "Beta", "Gamma").map { now++; playlists.create(it, emptyList()) }.first()
                now++; playlists.append(alpha.id, song.id)
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists,
            preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Track options: Song").performClick()
                onNodeWithText("Select song").performClick()
                fun open() {
                    onNodeWithContentDescription("Selection actions").performClick()
                    onNodeWithText("Add to playlist").performClick()
                }
                fun order(vararg names: String) = names.sortedBy {
                    onNodeWithContentDescription("Playlist membership: $it").fetchSemanticsNode().boundsInRoot.top
                }
                // Preferences save in the background, so the new order can take a moment to appear.
                fun assertOrder(vararg names: String) {
                    runCatching { waitUntil(timeoutMillis = 3_000) { order(*names) == names.toList() } }
                    assertEquals(names.toList(), order(*names))
                }
                fun choose(sort: String) {
                    onNodeWithContentDescription("Sort by").performClick()
                    onNodeWithText(sort).performClick()
                }
                open()
                onNodeWithText("Recently updated").assertIsDisplayed()
                assertOrder("Alpha", "Gamma", "Beta")
                choose("Recently added")
                assertOrder("Alpha", "Gamma", "Beta")
                // Pending choices don't reorder the list.
                onNodeWithContentDescription("Playlist membership: Alpha").performClick()
                assertOrder("Alpha", "Gamma", "Beta")
                onNodeWithContentDescription("Playlist membership: Alpha").performClick()
                choose("Alphabetical")
                assertOrder("Alpha", "Beta", "Gamma")
                val beta = player.state.value.playlists.single { it.name == "Beta" }
                now++; runBlocking { player.playTrack(song.id, listOf(song.id), 0, "Beta",
                    origin = me.d3sox.nevumi.queue.QueueOrigin(me.d3sox.nevumi.library.LibraryTab.PLAYLISTS, beta.id)).join(); player.pause().join() }
                choose("Recently played")
                waitUntil(timeoutMillis = 3_000) { player.state.value.playlists.single { it.id == beta.id }.playedAt == now }
                assertOrder("Alpha", "Beta", "Gamma")
                val ids = player.state.value.playlists.associate { it.name to it.id }
                runBlocking { player.updatePreferences { it.copy(collectionLayouts = mapOf(me.d3sox.nevumi.library.LibraryTab.PLAYLISTS to
                    me.d3sox.nevumi.settings.CollectionLayout(sort = me.d3sox.nevumi.settings.CollectionSort.CUSTOM,
                        order = listOf(ids.getValue("Gamma"), ids.getValue("Alpha"), ids.getValue("Beta"))))) }.join() }
                choose("Same as Playlists tab")
                assertOrder("Alpha", "Gamma", "Beta")
                onNodeWithText("Cancel").performClick()
                open()
                onNodeWithText("Same as Playlists tab").assertIsDisplayed()
                assertOrder("Alpha", "Gamma", "Beta")
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pickerGroupsPlaylistsByFolderAndRevertedChoicesLeaveApplyDisabled() {
        val directory = Files.createTempDirectory("playlist-membership-folders")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        runBlocking {
            library.importTrack(TrackMetadata("Song"), TrackSource("song", SourceKind.LOCAL, "/music/song.flac"))
            playlists.create("Road ▸ Drive", emptyList())
            playlists.create("Morning", emptyList())
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Track options: Song").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Add to playlist").performClick()
                onNodeWithText("Road").assertIsDisplayed()
                onNodeWithText("Drive", useUnmergedTree = true).assertIsDisplayed()
                onAllNodesWithText("Road ▸ Drive", useUnmergedTree = true).assertCountEquals(0)
                kotlin.test.assertTrue(onNodeWithText("Road").fetchSemanticsNode().boundsInRoot.top >
                    onNodeWithContentDescription("Playlist membership: Morning").fetchSemanticsNode().boundsInRoot.top)
                kotlin.test.assertTrue(onNodeWithContentDescription("Playlist membership: Road ▸ Drive").fetchSemanticsNode().boundsInRoot.top >
                    onNodeWithText("Road").fetchSemanticsNode().boundsInRoot.top)
                onNodeWithText("Apply").assertIsNotEnabled()
                onNodeWithContentDescription("Playlist membership: Road ▸ Drive").performClick()
                onNodeWithText("Apply").assertIsEnabled()
                onNodeWithContentDescription("Playlist membership: Road ▸ Drive").performClick()
                onNodeWithText("Apply").assertIsNotEnabled()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun pickerListsPlaylistsHoldingTheSongTogetherAboveFoldersUntilApplied() {
        val directory = Files.createTempDirectory("playlist-membership-section")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        runBlocking {
            val song = library.importTrack(TrackMetadata("Song"), TrackSource("song", SourceKind.LOCAL, "/music/song.flac"))
            playlists.create("Road ▸ Drive", listOf(song.id))
            playlists.create("Road ▸ Night", emptyList())
            playlists.create("Evening", emptyList())
            playlists.create("Morning", listOf(song.id))
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Track options: Song").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Add to playlist").performClick()
                onNodeWithContentDescription("Sort by").performClick()
                onNodeWithText("Alphabetical").performClick()
                fun top(text: String) = onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
                fun assertOrder() {
                    val order = listOf("Contains this song", "Morning", "Road ▸ Drive", "Other playlists", "Evening", "Road", "Night")
                    runCatching { waitUntil(timeoutMillis = 3_000) { order.sortedBy(::top) == order } }
                    assertEquals(order, order.sortedBy(::top))
                }
                assertOrder()
                // Pending choices don't move playlists between sections.
                onNodeWithContentDescription("Playlist membership: Road ▸ Drive").performClick()
                onNodeWithContentDescription("Playlist membership: Evening").performClick()
                assertOrder()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSongUploadedToTheServerCanJoinItsPlaylistsFromTheSameSongRow() {
        val directory = Files.createTempDirectory("playlist-membership-upload")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val song = runBlocking {
            val playlist = playlists.create("Mix", emptyList())
            store.put("playlist:${playlist.id}", "playlists", playlist.copy(server = PlaylistServer("server", "mix", "Mix", emptyList(), "owner")))
            library.importTrack(TrackMetadata("Upload"), TrackSource("upload", SourceKind.LOCAL, "/music/upload.flac"))
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                openLibraryPage("Songs")
                val quickAction = onNodeWithContentDescription("Quick action: Add to playlist: Upload")
                quickAction.performClick()
                onNodeWithText("Only songs from this server").assertIsDisplayed()
                onNodeWithText("Cancel").performClick()
                runBlocking { library.importTrack(TrackMetadata("Upload"), TrackSource("server:server:upload", SourceKind.SERVER, "upload"), linkTo = song.id) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.tracks.single().sources.size == 2 }
                quickAction.performClick()
                onNodeWithText("Only songs from this server").assertDoesNotExist()
                onNodeWithContentDescription("Playlist membership: Mix").assertIsEnabled()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
