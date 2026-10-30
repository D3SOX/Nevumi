package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.security.SecretServiceVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlin.uuid.Uuid
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond

@OptIn(ExperimentalTestApi::class)
class PlaylistsUiTest {
    @Test
    fun anOwnedServerPlaylistWithReadOnlyTracksStillOffersEditingAndDeletion() {
        val directory = Files.createTempDirectory("owned-read-only-playlist-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val profile = ServerProfile("https://music.example.test", "fixture")
        val http = HttpClient(MockEngine { error("No server request is needed to edit cached metadata") })
        val vault = object : me.d3sox.nevumi.security.CredentialVault {
            override suspend fun save(key: String, secret: String) = Unit
            override suspend fun read(key: String): String? = null
            override suspend fun delete(key: String) = Unit
        }
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        runBlocking {
            store.put("server:navidrome", "servers", profile)
            val song = library.importTrack(TrackMetadata("Server song"), TrackSource("server:${profile.serverId}:song", SourceKind.SERVER, "song"))
            val playlist = playlists.create("Evening", listOf(song.id, song.id))
            store.put("playlist:${playlist.id}", "playlists", playlist.copy(server =
                me.d3sox.nevumi.playlists.PlaylistServer(profile.serverId, "mix", playlist.name, listOf("song", "song"), profile.username, readOnly = true)))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            NavidromeConnection(store, http, vault), playlists = playlists)
        try {
            androidx.compose.ui.test.v2.runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(420f, 900f)) {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isNotEmpty() && controller.state.value.server != null }
                openLibraryPage("Playlists")
                onNodeWithText("Evening").performClick()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Edit playlist").assertIsDisplayed()
                onNodeWithText("Delete playlist").assertIsDisplayed()
                onNodeWithText("Export M3U").assertIsDisplayed()
                onNodeWithText("Rearrange").assertDoesNotExist()
                captureUiSnapshot("owned-server-playlist-options", onNode(isPopup()))
                onNodeWithText("Edit playlist").performClick()
                onNodeWithContentDescription("Playlist title").performTextReplacement("After hours")
                onNodeWithContentDescription("Playlist description").performTextInput("Music for late evenings")
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().name == "After hours" }
                onNodeWithText("Music for late evenings").assertIsDisplayed()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Delete playlist").performClick()
                onNodeWithText("Delete", substring = false).performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isEmpty() }
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSmartPlaylistCanUseAValidatedRatingRangeAndUpdateWhenASongsRatingChanges() {
        val directory = Files.createTempDirectory("rating-rule-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val songs = runBlocking {
            val tracks = listOf("Alpha", "Bravo").map { title -> library.importTrack(TrackMetadata(title),
                TrackSource("local:$title", SourceKind.LOCAL, "/music/$title.flac")) }
            library.setRating(tracks[0].id, 3); library.setRating(tracks[1].id, 5)
            tracks
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                openLibraryPage("Playlists")
                onNodeWithContentDescription("New smart playlist").performClick()
                onNodeWithText("Playlist name").performTextInput("Top rated")
                onNodeWithText("Minimum rating").performScrollTo().performTextInput("6")
                onNodeWithText("Create").assertIsNotEnabled()
                onNodeWithText("Minimum rating").performTextReplacement("4")
                onNodeWithText("Maximum rating").performScrollTo().performTextInput("5")
                onNodeWithText("Title").performScrollTo().performClick()
                onNodeWithText("Rating").performClick()
                onNodeWithText("Create").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.singleOrNull()?.trackIds == listOf(songs[1].id) }
                onNodeWithText("Top rated").performClick()
                controller.setRating(songs[0].id, 5)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().trackIds == songs.map { it.id } }
                onNodeWithText("Alpha").assertIsDisplayed()
                assertEquals(LibrarySort.RATING, runBlocking { playlists.all().single().rule!!.sort })
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aReadOnlyPlaylistOffersPlaybackDownloadsAndExportWithoutEditingActions() {
        val directory = Files.createTempDirectory("read-only-playlist-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(MockEngine { request ->
            respond(if (request.url.encodedPath.endsWith("/getPlaylists.view"))
                """{"subsonic-response":{"status":"ok","playlists":{"playlist":[{"id":"public"}]}}}"""
            else """{"subsonic-response":{"status":"ok","playlist":{"id":"public","name":"Shared music","owner":"another-user","entry":[{"id":"song"}]}}}""")
        })
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        runBlocking {
            val client = NavidromeClient(http, "https://music.example.test", ServerCredentials("fixture", "test"))
            val changes = NavidromeChanges(store, library) { client }
            changes.importSong(client, ServerSong("song", TrackMetadata("Shared song")))
            changes.importPlaylists(client)
            playlists.create("Own playlist", emptyList())
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Playlists")
                onNodeWithText("Shared music").performClick()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Edit playlist").assertDoesNotExist()
                onNodeWithText("Delete playlist").assertDoesNotExist()
                onNodeWithText("Export M3U").assertIsDisplayed()
                onNode(isRoot() and hasAnyDescendant(hasContentDescription("Settings"))).performMouseInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
                onNodeWithContentDescription("Automatically download playlist").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single { it.name == "Shared music" }.autoDownload }
                onNodeWithContentDescription("Track options: Shared song").performClick()
                onNodeWithText("Remove from playlist").assertDoesNotExist()
                onNodeWithText("Play next").assertIsDisplayed()
                onNodeWithText("Add to playlist").assertDoesNotExist()
                onNode(isRoot() and hasAnyDescendant(hasContentDescription("Settings"))).performMouseInput { click(androidx.compose.ui.geometry.Offset(4f, 4f)) }
                onNodeWithContentDescription("Quick action: Add to playlist: Shared song").performClick()
                onNodeWithText("Own playlist").assertIsDisplayed()
                onNodeWithContentDescription("Playlist membership: Shared music").assertIsNotEnabled().assertIsOn()
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aServerOnlyAppPlaylistCanBePublishedWhileMixedAndSmartPlaylistsStayInTheApp() {
        val directory = Files.createTempDirectory("publish-playlist-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val address = System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533"
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val connection = NavidromeConnection(store, http, vault)
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val changes = NavidromeChanges(store, library, client = connection::client)
        val name = "Publish UI ${Uuid.random()}"
        val playlist = runBlocking {
            connection.connect(address, ServerCredentials("fixture", "fixture-password"))
            val tracks = changes.refreshLibrary(connection.client()!!).map { it.first }
            val local = library.importTrack(TrackMetadata("Local song"), TrackSource("local", SourceKind.LOCAL, "/music/local.flac"))
            playlists.create("Mixed sources", listOf(tracks[0].id, local.id))
            playlists.createSmart("Smart selection", LibraryQuery(sourceKind = SourceKind.SERVER))
            playlists.create(name, listOf(tracks[0].id, tracks[1].id, tracks[0].id))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection,
            playlists = playlists, serverChanges = changes)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Playlists")
                onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Mixed sources"))
                onNodeWithText("Mixed sources").performClick()
                onNodeWithText("Publish to your music server").assertDoesNotExist()
                onNodeWithContentDescription("Back").performClick()
                onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Smart selection"))
                onNodeWithText("Smart selection").performClick()
                onNodeWithText("Publish to your music server").assertDoesNotExist()
                onNodeWithContentDescription("Back").performClick()
                onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText(name))
                onNodeWithText(name).performClick()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Publish to your music server").performClick()
                onNodeWithText("Publish").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    controller.state.value.playlists.any { it.id == playlist.id && it.server?.id != null && !it.pendingSync }
                }
                val saved = runBlocking { playlists.get(playlist.id)!! }
                val remote = runBlocking { connection.client()!!.playlist(saved.server!!.id!!) }
                assertEquals(name, remote.name)
                assertEquals(3, remote.songIds.size)
                assertEquals(remote.songIds[0], remote.songIds[2])
                onNodeWithText("Server playlist").assertIsDisplayed()
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            runBlocking {
                changes.closeAndJoin()
                connection.client()?.let { client -> client.playlists().filter { it.name == name || it.name == "Nevumi pending ${playlist.id}" }
                    .forEach { client.deletePlaylist(it.id) } }
                vault.delete("navidrome")
            }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun severalSelectedSongsCanBeAddedToAMixedPlaylistWhileSmartPlaylistsAreReadOnly() {
        val directory = Files.createTempDirectory("bulk-playlist-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val tracks = runBlocking {
            val songs = listOf(
                library.importTrack(TrackMetadata("Local song"), TrackSource("local", SourceKind.LOCAL, "/music/song.flac")),
                library.importTrack(TrackMetadata("Server song"), TrackSource("server", SourceKind.SERVER, "song")))
            playlists.create("Evening", emptyList())
            playlists.createSmart("Automatic favorites", LibraryQuery(favoritesOnly = true))
            songs
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Local song").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Select all").performClick()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Add to playlist").performClick()
                onNodeWithContentDescription("Playlist membership: Automatic favorites").assertIsNotEnabled()
                onNodeWithText("Evening").performClick()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single { it.name == "Evening" }.trackIds.size == 2 }
                assertEquals(tracks.map { it.id }, runBlocking { playlists.all().single { it.name == "Evening" }.trackIds })
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSmartPlaylistUsesValidatedYearRangesAndASongLimit() {
        val directory = Files.createTempDirectory("smart-playlist-range-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val expected = runBlocking {
            val song = library.importTrack(TrackMetadata("A new song", year = 2024), TrackSource("new", SourceKind.LOCAL, "/music/new.flac"))
            library.importTrack(TrackMetadata("Old song", year = 1990), TrackSource("old", SourceKind.LOCAL, "/music/old.flac"))
            library.importTrack(TrackMetadata("Z new song", year = 2024), TrackSource("other", SourceKind.LOCAL, "/music/other.flac"))
            song.id
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                openLibraryPage("Playlists")
                onNodeWithContentDescription("New smart playlist").performClick()
                onNodeWithText("Playlist name").performTextInput("Recent selection")
                onNodeWithText("Year from").performScrollTo().performTextInput("2020")
                onNodeWithText("Year to").performScrollTo().performTextInput("2010")
                onNodeWithText("Create").assertIsNotEnabled()
                onNodeWithText("Year to").performTextReplacement("2025")
                onNodeWithText("Song limit").performScrollTo().performTextInput("1")
                onNodeWithText("Create").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.size == 1 }
                assertEquals(listOf(expected), controller.state.value.playlists.single().trackIds)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSmartPlaylistCanBeCreatedAndItsRulesEditedFromThePlaylistScreen() {
        val directory = Files.createTempDirectory("smart-playlist-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val songs = runBlocking {
            val ambient = library.importTrack(TrackMetadata("Northern Light", genres = listOf("Ambient")),
                TrackSource("ambient", SourceKind.LOCAL, "/music/ambient.flac"))
            val rock = library.importTrack(TrackMetadata("Loud Song", genres = listOf("Rock")),
                TrackSource("rock", SourceKind.SERVER, "rock"))
            library.setFavorite(ambient.id, true)
            library.setFavorite(rock.id, true)
            listOf(ambient, rock)
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                openLibraryPage("Playlists")
                onNodeWithContentDescription("New smart playlist").performClick()
                onNodeWithText("Playlist name").performTextInput("Favorite ambient")
                onNode(hasText("Genre") and hasSetTextAction()).performTextInput("Ambient")
                onNodeWithText("Favorites only").performScrollTo().performClick()
                onNodeWithText("Create").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.size == 1 }
                val smart = controller.state.value.playlists.single()
                assertEquals(listOf(songs[0].id), smart.trackIds)
                onNodeWithText("Favorite ambient").performClick()
                onNodeWithText("Northern Light").assertIsDisplayed()
                onNodeWithText("Loud Song").assertDoesNotExist()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Edit rules").performClick()
                onNode(hasText("Genre") and hasSetTextAction()).performTextReplacement("Rock")
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().trackIds == listOf(songs[1].id) }
                onNodeWithText("Loud Song").assertIsDisplayed()
                controller.setFavorite(songs[1].id, false)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().trackIds.isEmpty() }
                assertEquals("Rock", runBlocking { playlists.get(smart.id)!!.rule!!.genre })
                onNodeWithText("No songs here yet").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anAppPlaylistCanBeDeletedAfterConfirmingItsName() {
        val directory = Files.createTempDirectory("playlist-delete-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val playlists = Playlists(store)
        val playlist = runBlocking { playlists.create("Temporary playlist", emptyList()) }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Playlists")
                onNodeWithText("Temporary playlist").performClick()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Delete playlist").performClick()
                onNodeWithText("Delete Temporary playlist?").assertIsDisplayed()
                onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isEmpty() }
                assertEquals(null, runBlocking { playlists.get(playlist.id) })
                onNodeWithContentDescription("New playlist").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun movingARepeatedPlaylistEntryMovesOnlyTheSelectedOccurrence() {
        val directory = Files.createTempDirectory("playlist-move-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val playlist = runBlocking {
            val tracks = listOf("a", "b").associateWith { id ->
                library.importTrack(TrackMetadata("Song $id"), TrackSource(id, SourceKind.LOCAL, "/music/$id.flac")).id
            }
            playlists.create("Repeated", listOf("a", "b", "a").map(tracks::getValue))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Playlists")
                onNodeWithText("Repeated").performClick()
                onNode(hasScrollAction()).performScrollToIndex(3)
                onAllNodesWithContentDescription("Track options: Song a")[1].performClick()
                onNodeWithText("Move up").assertDoesNotExist()
                onNodeWithText("Move down").assertDoesNotExist()
                onNodeWithContentDescription("Close song actions").performClick()
                onNodeWithContentDescription("Playlist options").performScrollTo().performClick()
                onNodeWithText("Rearrange").performClick()
                val handle = onNodeWithTag("playlist-reorder:2", useUnmergedTree = true)
                handle.performScrollTo()
                val target = onNodeWithTag("playlist-reorder:1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
                val start = handle.fetchSemanticsNode().boundsInRoot.center.y
                handle.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, -20f), delayMillis = 50) }
                waitForIdle()
                repeat(6) {
                    handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, (target - start + 20f) / 6), delayMillis = 50) }
                    waitForIdle()
                }
                handle.performTouchInput { up() }
                val expected = listOf(playlist.trackIds[0], playlist.trackIds[2], playlist.trackIds[1])
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().trackIds == expected }
                assertEquals(expected, runBlocking { playlists.get(playlist.id)?.trackIds })
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun removingTheSecondOccurrenceFromAPlaylistKeepsTheFirstOne() {
        val directory = Files.createTempDirectory("playlist-remove-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val playlist = runBlocking {
            val track = library.importTrack(TrackMetadata("Repeated song"), TrackSource("local", SourceKind.LOCAL, "/music/repeated.flac"))
            playlists.create("Repeated", listOf(track.id, track.id))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Playlists")
                onNodeWithText("Repeated").performClick()
                onAllNodesWithContentDescription("Track options: Repeated song")[1].performClick()
                onNodeWithText("Remove from playlist").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().trackIds.size == 1 }
                assertEquals(playlist.trackIds.take(1), runBlocking { playlists.get(playlist.id)?.trackIds })
                onAllNodesWithText("Repeated song").assertCountEquals(1)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aPlaylistConflictCanBeResolvedByChoosingTheServerVersion() {
        val directory = Files.createTempDirectory("playlist-conflict-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val changes = NavidromeChanges(store, library) { client }
        val remote = runBlocking {
            val tracks = client.songs().map { song -> changes.importSong(client, song) }
            val created = client.savePlaylist("Conflict ${Uuid.random()}", listOf(tracks.first().sources.single().location))
            changes.importPlaylists(client)
            val imported = playlists.all().single { it.server?.id == created.id }
            playlists.rename(imported.id, "My offline version")
            client.savePlaylist("Server version", listOf(tracks.last().sources.single().location), created.id)
            changes.synchronize()
            created
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, serverChanges = changes)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Playlists")
                onNodeWithText("My offline version").performClick()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Resolve playlist conflict").performClick()
                onNodeWithText("Use server version").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    controller.state.value.playlists.any { it.server?.id == remote.id && it.name == "Server version" && it.conflict == null }
                }
                onNodeWithText("Server version").assertIsDisplayed()
                val local = runBlocking { playlists.all().single { it.server?.id == remote.id } }
                assertEquals(false, local.pendingSync)
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            runBlocking { changes.closeAndJoin(); client.deletePlaylist(remote.id) }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aNavidromePlaylistAppearsInTheAppAndCanBeRenamedFromItsScreen() {
        val directory = Files.createTempDirectory("server-playlist-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val address = System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533"
        val client = NavidromeClient(http, address, ServerCredentials("fixture", "fixture-password"))
        val remote = runBlocking { client.savePlaylist("UI playlist ${Uuid.random()}", listOf(client.songs().first().id)) }
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val library = MusicLibrary(store)
        val connection = NavidromeConnection(store, http, vault)
        val changes = NavidromeChanges(store, library, client = connection::client)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), connection,
            playlists = Playlists(store), serverChanges = changes)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                controller.connectServer(address, "fixture", "fixture-password")
                waitUntil(timeoutMillis = 5_000) { controller.state.value.playlists.any { it.server?.id == remote.id } }
                openLibraryPage("Playlists")
                onNodeWithText(remote.name).performClick()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Edit playlist").performClick()
                onNodeWithContentDescription("Playlist title").performTextReplacement("Renamed from the app")
                onNodeWithContentDescription("Playlist description").performTextInput("Music for late evenings")
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    controller.state.value.playlists.any { it.server?.id == remote.id && it.name == "Renamed from the app" && !it.pendingSync } &&
                        runBlocking { client.playlist(remote.id).name == "Renamed from the app" }
                }
                onNodeWithText("Renamed from the app").assertIsDisplayed()
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            runBlocking { changes.closeAndJoin(); client.deletePlaylist(remote.id); vault.delete("navidrome") }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aPlaylistCanPlayTheSecondOccurrenceOfARepeatedSong() {
        val directory = Files.createTempDirectory("playlist-occurrence-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        runBlocking {
            val track = library.importTrack(TrackMetadata("Northern Light"), TrackSource("local", SourceKind.LOCAL,
                Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
            playlists.create("Repeated", listOf(track.id, track.id))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Playlists")
                onNodeWithText("Repeated").performClick()
                onAllNodesWithText("Northern Light")[1].performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                assertEquals(2 to 1, controller.state.value.queue.entries.size to controller.state.value.queue.currentIndex)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playlistsWithAFolderPrefixAreGroupedAndCanBeMovedBetweenFolders() {
        val directory = Files.createTempDirectory("playlist-folders-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
        val (morning, night, slash, dawn) = runBlocking { listOf("Road ▸ Morning", "Road▸Night", "AC/DC", "Road ▸ Coast ▸ Dawn").map { playlists.create(it, emptyList()).id } }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Playlists")
                onNodeWithText("AC/DC").assertIsDisplayed()
                onNodeWithText("4 playlists").assertIsDisplayed()
                onNodeWithText("Road").assertIsDisplayed()
                onNodeWithText("Morning").assertDoesNotExist()
                runBlocking { controller.updatePreferences { settings -> settings.copy(collectionLayouts = mapOf(LibraryTab.PLAYLISTS to
                    me.d3sox.nevumi.settings.CollectionLayout(order = listOf(slash, morning, night, dawn)))) }.join() }
                onNodeWithText("Road").performClick()
                onNodeWithText("AC/DC").assertDoesNotExist()
                onNodeWithText("Night").assertIsDisplayed()
                onNodeWithText("Dawn").assertDoesNotExist()
                onNodeWithText("3 playlists").assertIsDisplayed()
                onNodeWithText("Coast").performClick()
                onNodeWithText("Dawn").performClick()
                onNodeWithText("Playlist in Road ▸ Coast · 0 songs").assertIsDisplayed()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Edit playlist").performClick()
                onNodeWithContentDescription("Playlist title").performTextReplacement("Da▸wn")
                onNodeWithContentDescription("Playlist title").assertTextEquals("Title", "Dawn")
                onNodeWithContentDescription("Playlist folder").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Road ▸ Coast"))
                onNodeWithContentDescription("Playlist folder").performClick()
                onNodeWithText("New folder").performClick()
                onNodeWithText("Inside Road ▸ Coast").assertIsDisplayed()
                onNodeWithText("Folder name").performTextInput("Cliffs")
                onNodeWithText("Create").performClick()
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.any { it.name == "Road ▸ Coast ▸ Cliffs ▸ Dawn" } }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("library-header").assertContentDescriptionEquals("Road ▸ Coast ▸ Cliffs, 0 songs")
                assertLibraryBackInHeader()
                onNodeWithText("Dawn").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Cliffs").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Coast").assertIsDisplayed()
                onNodeWithContentDescription("Collection order").performClick()
                onNodeWithText("Rearrange").performClick()
                onNodeWithTag("collection-reorder:$night", useUnmergedTree = true).performCustomAccessibilityActionWithLabel("Move up")
                waitUntil(timeoutMillis = 3_000) {
                    controller.state.value.preferences.collectionLayouts[LibraryTab.PLAYLISTS]?.order?.let { it.indexOf(night) in 0 until it.indexOf(morning) && it.containsAll(listOf(slash, dawn)) } == true
                }
                onNodeWithContentDescription("Finish rearranging").performClick()
                onNodeWithText("Morning").performClick()
                onNodeWithText("Playlist in Road · 0 songs").assertIsDisplayed()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Edit playlist").performClick()
                onNodeWithContentDescription("Playlist folder").performClick()
                onNodeWithText("Road ▸ Coast ▸ Cliffs").assertIsDisplayed()
                onNodeWithText("No folder").performClick()
                onNodeWithContentDescription("Playlist folder").performClick()
                onNodeWithText("New folder").performClick()
                onNodeWithText("Inside", substring = true).assertDoesNotExist()
                onNodeWithText("Folder name").performTextInput("Trips")
                onNodeWithText("Create").performClick()
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single { it.id == morning }.name == "Trips ▸ Morning" }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("library-header").assertContentDescriptionEquals("Trips, 0 songs")
                assertLibraryBackInHeader()
                onNodeWithText("Morning").assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Road").assertIsDisplayed()
                onNodeWithText("Morning").assertDoesNotExist()
                onNodeWithText("AC/DC").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
