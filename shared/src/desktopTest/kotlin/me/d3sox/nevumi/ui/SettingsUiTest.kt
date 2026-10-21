package me.d3sox.nevumi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.*
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import me.d3sox.nevumi.downloads.*
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.playlists.Playlists
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalTestApi::class)
class SettingsUiTest {
    @Test fun newSettingsEnableFivePreviousTracksAndPlaylistQuickActionsWithoutOverwritingChoices() = runBlocking {
        val directory = Files.createTempDirectory("library-settings-defaults")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        try {
            val preferences = MusicPreferences(store)
            val defaults = preferences.load()
            assertEquals(me.d3sox.nevumi.settings.TrackAction.ADD_TO_PLAYLIST, defaults.quickAction)
            assertEquals(true, defaults.showPreviousQueueTracks)
            assertEquals(5, defaults.previousQueueTrackLimit)
            preferences.update { it.copy(quickAction = me.d3sox.nevumi.settings.TrackAction.NONE,
                showPreviousQueueTracks = false, previousQueueTrackLimit = 17) }
            store.close(); store = openMusicStore(path)
            val saved = MusicPreferences(store).load()
            assertEquals(me.d3sox.nevumi.settings.TrackAction.NONE, saved.quickAction)
            assertEquals(false, saved.showPreviousQueueTracks)
            assertEquals(17, saved.previousQueueTrackLimit)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun theAppShowsItsSourceAndLicenseWithoutRequiringANetworkConnection() {
        val directory = Files.createTempDirectory("nevumi-about")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("About Nevumi").performScrollTo().performClick()
                onNodeWithText("AGPL-3.0-only").assertIsDisplayed()
                onNodeWithText("Source code").assertHasClickAction().assertIsDisplayed()
                onNodeWithText("View license").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithText("GNU AFFERO GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("GNU AFFERO GENERAL PUBLIC LICENSE", substring = true).assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun undoRetentionCanBeChangedAndExpiresOnlyBackupsWhileSavingThePreference() {
        val directory = Files.createTempDirectory("undo-retention-settings")
        val database = directory.resolve("library.db").toString()
        val store = openMusicStore(database)
        val library = MusicLibrary(store)
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        val now = System.currentTimeMillis()
        var time = now - 10 * 86_400_000L
        val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), now = { time })
        val track = runBlocking { LocalFiles(library).scan(audio.toString()).single() }
        val edit = runBlocking { edits.editTags(track.id, track.metadata.copy(title = "Keep this edited music")) }
        val editedCopy = directory.resolve("edited-copy.flac")
        Files.copy(audio, editedCopy)
        time = now
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = MusicPreferences(store), fileEdits = edits)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                assertEquals(listOf(edit.id), controller.state.value.fileEdits.map { it.id })
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Files & recovery").performScrollTo().performClick()
                onNodeWithContentDescription("Keep undo backups").performScrollTo().performClick()
                onNodeWithText("7 days").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.fileEdits.isEmpty() }
                assertEquals(7, runBlocking { MusicPreferences(store).load() }.undoRetentionDays)
                kotlin.test.assertFalse(Files.exists(Path.of(edit.backupPath)))
                assertEquals(-1L, Files.mismatch(editedCopy, audio))
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close() }
        val reopened = openMusicStore(database)
        try { assertEquals(7, runBlocking { MusicPreferences(reopened).load() }.undoRetentionDays) }
        finally { reopened.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun allowingMeteredDownloadsInSettingsStartsAWaitingDownloadAndSavesTheChoice() {
        val directory = Files.createTempDirectory("network-settings-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val client = NavidromeClient(http, System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val song = runBlocking { client.songs().first() }
        val track = runBlocking { library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id)) }
        val network = MutableStateFlow(DownloadNetwork(connected = true, metered = true))
        val offline = OfflineMusic(store, library, { client }, directory.resolve("audio").toString(), Playlists(store), network)
        val preferences = MusicPreferences(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), offline = offline, preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { controller.downloadTracks(listOf(track.id)).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.downloads.containsKey(track.id) }
                assertEquals(DownloadStatus.PENDING, controller.state.value.downloads[track.id]?.status)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Downloads & cache").performScrollTo().performClick()
                onNodeWithText("Manage downloads").performScrollTo().performClick()
                onNodeWithText("Waiting for an unmetered network").assertIsDisplayed()
                onNodeWithText("Done").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Downloads & cache").performScrollTo().performClick()
                onNodeWithContentDescription("Download network").performScrollTo().performClick()
                onNodeWithText("Any network").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.downloads[track.id]?.status == DownloadStatus.COMPLETE }
                assertEquals(true, runBlocking { preferences.load() }.downloadOnMetered)
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playbackSpeedAndPitchCanBeChangedIndependentlyAndRestoreAfterRestart() {
        val directory = Files.createTempDirectory("speed-settings-ui")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val library = MusicLibrary(store)
        val track = runBlocking { LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }.first { it.metadata.title == "Northern Light" }
        var controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                runBlocking { controller.playTrack(track.id, listOf(track.id)).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.pause().join() }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.playback.playing }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithContentDescription("Playback speed").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.speed == 1.5 }
                assertEquals(1.5, runBlocking { MusicPreferences(store).load() }.speed)
                onNodeWithContentDescription("Playback pitch").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(12f) }
                try { waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.pitch == 2.0 } }
                catch (failure: Throwable) { throw AssertionError("Pitch setting failed: ${controller.state.value.playback}; ${controller.state.value.preferences.pitch}; ${controller.state.value.error}", failure) }
                assertEquals(2.0, runBlocking { MusicPreferences(store).load() }.pitch)
                assertEquals(1.5, controller.state.value.playback.speed)
            }
            controller.close(); store.close()
            store = openMusicStore(database)
            controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading && controller.state.value.playback.speed == 1.5 && controller.state.value.playback.pitch == 2.0 }
                assertEquals(false, controller.state.value.playback.playing)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithContentDescription("Playback speed").performScrollTo().assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(1.5f, 0.5f..2f, 29))
                onNodeWithContentDescription("Playback pitch").performScrollTo().assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(12f, -12f..12f, 23))
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playbackCachingCanBeDisabledFromSettings() {
        val directory = Files.createTempDirectory("cache-settings-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = MusicPreferences(store)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Downloads & cache").performScrollTo().performClick()
                onNodeWithContentDescription("Playback cache").performScrollTo().performClick()
                onNode(hasText("Off") and hasAnyAncestor(isPopup())).performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.cacheBytes == 0L }
                assertEquals(0L, runBlocking { preferences.load() }.cacheBytes)
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun amoledTitleScrollSpeedAndPlayAfterSwipeSettingsSurviveAnAppRestart() {
        val directory = Files.createTempDirectory("settings-ui")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val tracks = runBlocking { LocalFiles(MusicLibrary(store)).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()) }.associateBy { it.metadata.title }
        var controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Appearance").performScrollTo().performClick()
                onNodeWithText("Theme", useUnmergedTree = true).performMouseInput { click() }
                onNodeWithText("Dark").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.themeMode == me.d3sox.nevumi.settings.ThemeMode.DARK }
                onNodeWithContentDescription("AMOLED black").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.amoledBlack }
                onNodeWithContentDescription("Title scroll speed").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.marqueeSpeed == 2.0 }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Library & gestures").performScrollTo().performClick()
                onNodeWithText("Swipe right", useUnmergedTree = true).performMouseInput { click() }
                onNodeWithText("Play after").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                assertEquals(Color.Black, onRoot().captureToImage().toPixelMap()[5, 5])
            }
            controller.close(); store.close()
            store = openMusicStore(database)
            controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                assertEquals(Color.Black, onRoot().captureToImage().toPixelMap()[5, 5])
                assertEquals(2.0, controller.state.value.preferences.marqueeSpeed)
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join(); controller.enqueue(tracks.getValue("Night Walk").id, QueueAction.APPEND).join() }
                onNodeWithTag("track-row:${tracks.getValue("Long Way Home").id}").performTouchInput {
                    swipe(center, center.copy(x = center.x + 200), 200)
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.size == 5 }
                val titles = tracks.values.associate { it.id to it.metadata.title }
                assertEquals(listOf("Long Way Home", "Night Walk", "Northern Light", "Night Walk", "Long Way Home"),
                    controller.state.value.queue.entries.map { titles[it.trackId] })
            }
        } finally { controller.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
