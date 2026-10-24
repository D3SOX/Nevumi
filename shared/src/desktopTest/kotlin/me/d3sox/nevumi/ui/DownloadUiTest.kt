package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.downloads.*
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.TrackAction
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalTestApi::class)
class DownloadUiTest {
    @Test
    fun selectedSongsCanAllBeDownloadedAndStayProtectedWhenTheCacheBudgetIsZero() {
        val directory = Files.createTempDirectory("bulk-download-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val tracks = runBlocking { client.songs().map { song -> library.importTrack(song.metadata,
            TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id)) } }
        val playlists = Playlists(store)
        val offline = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), playlists)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), offline = offline,
            playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Select all").performClick()
                onNodeWithText("3 songs selected").assertIsDisplayed()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Download").performClick()
                waitUntil(timeoutMillis = 10_000) { tracks.all { controller.state.value.downloads[it.id]?.status == DownloadStatus.COMPLETE } }
                runBlocking { controller.updatePreferences { it.copy(cacheBytes = 0) }.join(); offline.runPending() }
                assertEquals(3, controller.state.value.downloads.values.count { it.status == DownloadStatus.COMPLETE })
                tracks.forEach { track ->
                    val source = runBlocking { SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false) }
                    kotlin.test.assertNotNull(source)
                    kotlin.test.assertTrue(java.io.File(source.location).length() > 0)
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aStorageFailureIsExplainedInDownloadsAndRetryWorksAfterSpaceIsFreed() {
        val directory = Files.createTempDirectory("download-storage-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val track = runBlocking {
            val song = client.songs().first { it.metadata.title == "Northern Light" }
            library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
        }
        val available = AtomicLong(4)
        val offline = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store),
            availableSpace = { available.get() })
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), offline = offline, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Download").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.downloads[track.id]?.status == DownloadStatus.FAILED }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Downloads & cache").performScrollTo().performClick()
                onNodeWithText("Manage downloads").performScrollTo().performClick()
                onNodeWithText("Not enough storage — free space and retry").assertIsDisplayed()
                available.set(1_073_741_824L)
                onNodeWithText("Retry failed downloads").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.downloads[track.id]?.status == DownloadStatus.COMPLETE }
                onNode(hasText("Downloaded") and !hasClickAction()).assertIsDisplayed()
                onNodeWithText("Not enough storage — free space and retry").assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun removingAnAutomaticDownloadKeepsItExcludedAfterRestartUntilRequestedAgain() {
        val directory = Files.createTempDirectory("remove-automatic-download-ui")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var library = MusicLibrary(store)
        var playlists = Playlists(store)
        val track = runBlocking {
            val song = client.songs().first { it.metadata.title == "Northern Light" }
            library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
        }
        val playlist = runBlocking { playlists.create("Automatic", listOf(track.id)).also { playlists.setAutoDownload(it.id, true) } }
        var worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), playlists)
        var controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), offline = worker, playlists = playlists,
            preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.downloads[track.id]?.status == DownloadStatus.COMPLETE }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Downloads & cache").performScrollTo().performClick()
                onNodeWithText("Manage downloads").performScrollTo().performClick()
                onNodeWithContentDescription("Remove download: Northern Light").performClick()
                waitUntil(timeoutMillis = 3_000) { track.id !in controller.state.value.downloads }
                assertEquals(null, runBlocking { SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false) })
                assertEquals(true, runBlocking { playlists.get(playlist.id)!! }.autoDownload)
            }
            runBlocking { controller.closeAndJoin() }; store.close()
            store = openMusicStore(database)
            library = MusicLibrary(store)
            playlists = Playlists(store)
            worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), playlists)
            controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), offline = worker, playlists = playlists,
                preferences = MusicPreferences(store))
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { delay(500) }
                assertEquals(null, runBlocking { SourceResolver.resolve(library.findTrack(track.id)!!.sources, online = false) })
                runBlocking { controller.downloadTracks(listOf(track.id)).join() }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.downloads[track.id]?.status == DownloadStatus.COMPLETE }
                assertEquals(track.id, controller.state.value.downloads[track.id]?.trackId)
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun downloadsPausedInSettingsStayPausedAfterRestartAndResumeFromTheDownloadsView() {
        val directory = Files.createTempDirectory("download-controls-ui")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        var offline: OfflineMusic? = null
        var controller: MusicController? = null
        try {
            var library = MusicLibrary(store)
            val track = runBlocking {
                val song = client.songs().first { it.metadata.title == "Northern Light" }
                library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
            }
            var worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store))
            offline = worker
            val first = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
                offline = worker, preferences = MusicPreferences(store))
            controller = first
            runComposeUiTest {
                setContent { MusicPlayerApp(first) }
                waitUntil(timeoutMillis = 3_000) { !first.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Downloads & cache").performScrollTo().performClick()
                onNodeWithText("Manage downloads").performScrollTo().performClick()
                onNodeWithText("Pause downloads").performClick()
                waitUntil(timeoutMillis = 3_000) { first.state.value.preferences.downloadsPaused }
            }
            runBlocking { worker.request(listOf(track.id)) }
            first.close(); runBlocking { worker.closeAndJoin() }; store.close()
            store = openMusicStore(database)
            library = MusicLibrary(store)
            worker = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store))
            offline = worker
            val reopened = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
                offline = worker, preferences = MusicPreferences(store))
            controller = reopened
            runComposeUiTest {
                setContent { MusicPlayerApp(reopened) }
                waitUntil(timeoutMillis = 3_000) { !reopened.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Downloads & cache").performScrollTo().performClick()
                onNodeWithText("Manage downloads").performScrollTo().performClick()
                onNodeWithText("Downloads paused").assertIsDisplayed()
                onNodeWithText("Resume downloads").performClick()
                waitUntil(timeoutMillis = 5_000) { reopened.state.value.downloads[track.id]?.status == DownloadStatus.COMPLETE }
                onNode(hasText("Northern Light") and !hasClickAction()).assertIsDisplayed()
                onNode(hasText("Downloaded") and !hasClickAction()).assertIsDisplayed()
            }
        } finally {
            runBlocking { controller?.closeAndJoin(); offline?.closeAndJoin() }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aPlaylistCreatedInTheAppAutomaticallyDownloadsTracksAddedToIt() {
        val directory = Files.createTempDirectory("playlist-download-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val track = runBlocking {
            val song = client.songs().first { it.metadata.title == "Northern Light" }
            library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
        }
        val offline = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), playlists)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), offline = offline, playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                openLibraryPage("Playlists")
                onNodeWithContentDescription("New playlist").performClick()
                onNodeWithText("Playlist name").performTextInput("Evening")
                onNodeWithText("Create").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.size == 1 }
                onNodeWithText("Evening").performClick()
                onNodeWithContentDescription("Automatically download playlist").performClick()
                onNodeWithText("Songs").performClick()
                onNodeWithContentDescription("Quick action: Add to playlist: Northern Light").performClick()
                onNodeWithText("Evening").performClick()
                onNodeWithText("Apply").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.downloads[track.id]?.status == DownloadStatus.COMPLETE }
                val saved = runBlocking { playlists.all().single() }
                assertEquals(true to listOf(track.id), saved.autoDownload to saved.trackIds)
            }
        } finally { runBlocking { controller.closeAndJoin(); offline.closeAndJoin() }; store.close(); http.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun downloadingFromTheTrackMenuMakesTheSongPlayableWithoutAServerConnection() {
        val directory = Files.createTempDirectory("download-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val track = runBlocking {
            val song = client.songs().first { it.metadata.title == "Northern Light" }
            library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id))
        }
        val offline = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), offline = offline, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Download").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.downloads[track.id]?.status == DownloadStatus.COMPLETE }
                onNodeWithContentDescription("Downloaded").assertIsDisplayed()
                // The Download quick action only marks songs stored on the device, so the row keeps its icon.
                runBlocking { controller.updatePreferences { it.copy(quickAction = TrackAction.DOWNLOAD) }.join() }
                onNodeWithContentDescription("Downloaded").assertIsDisplayed()
                http.close()
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                assertEquals(track.id, controller.state.value.playback.trackId)
            }
        } finally { runBlocking { controller.closeAndJoin(); offline.closeAndJoin() }; store.close(); http.close(); directory.toFile().deleteRecursively() }
    }
}
