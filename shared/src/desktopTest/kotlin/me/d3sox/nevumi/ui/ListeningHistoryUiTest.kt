package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.playback.PlaybackStatus
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ListeningHistoryUiTest {
    @Test
    fun recommendationsExplainTheirSourceRespectLibraryFiltersAndPlayTheSelectedSong() {
        val directory = Files.createTempDirectory("recommendations-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        var elapsed = 0L
        val history = ListeningHistory(store, library, elapsedClock = { elapsed })
        val song = runBlocking {
            val file = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                "Fixture Album", "01 Northern Light.flac").toString()
            val seed = library.importTrack(TrackMetadata("Heard seed", "Fixture Artist", durationMs = 20_000, genres = listOf("Ambient")),
                TrackSource("seed", SourceKind.LOCAL, file))
            val next = library.importTrack(TrackMetadata("Unheard local", "Fixture Artist", genres = listOf("Ambient")),
                TrackSource("next", SourceKind.LOCAL, file))
            library.importTrack(TrackMetadata("Unheard server", "Fixture Artist", genres = listOf("Ambient")),
                TrackSource("server:fixture:one", SourceKind.SERVER, "one"))
            val playing = PlaybackStatus(seed.id, playing = true, playbackId = "seed-play", durationMs = 20_000)
            history.record(playing); elapsed = 10_000; history.record(playing.copy(playing = false, ended = true))
            next
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), listeningHistory = history,
            preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Recommendations")
                onNodeWithText("Unheard local").assertIsDisplayed()
                onNodeWithText("Unheard server").assertIsDisplayed()
                onAllNodesWithText("More from Fixture Artist").assertCountEquals(2)
                onNodeWithContentDescription("Music sources").performClick()
                onNodeWithText("Device").performClick()
                onNodeWithText("Unheard server").assertDoesNotExist()
                onNodeWithText("Unheard local").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing && controller.state.value.playback.trackId == song.id }
                runBlocking { controller.clearListeningHistory().join() }
                onNodeWithText("Play some music to discover more from your library.").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun statisticsCanSwitchTimeRangesAndOpenArtistAlbumAndGenreRankings() {
        val directory = Files.createTempDirectory("history-statistics-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        var elapsed = 0L
        var wall = System.currentTimeMillis() - 20 * 86_400_000L
        val history = ListeningHistory(store, library, wallClock = { wall }, elapsedClock = { elapsed })
        runBlocking {
            val song = library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album",
                durationMs = 20_000, genres = listOf("Ambient")), TrackSource("local", SourceKind.LOCAL,
                Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                    "Fixture Album", "01 Northern Light.flac").toString()))
            for (index in 0..2) {
                val playing = PlaybackStatus(song.id, playing = true, playbackId = "play-$index", durationMs = 20_000)
                history.record(playing); elapsed += 10_000; history.record(playing.copy(playing = false, ended = true))
                wall = System.currentTimeMillis() - 1_000
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), listeningHistory = history)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                openLibraryPage("Statistics")
                onNodeWithText("3 plays").assertIsDisplayed()
                onNodeWithContentDescription("Statistics period").performClick()
                onNodeWithText("Last 7 days").performClick()
                onNodeWithText("2 plays").assertIsDisplayed()
                onNodeWithText("Listening time: 20s").assertIsDisplayed()
                onNodeWithText("2 plays · 20s listened").assertIsDisplayed()
                for ((category, name) in listOf("Artists" to "Fixture Artist", "Albums" to "Fixture Album", "Genres" to "Ambient")) {
                    onNodeWithContentDescription("Statistics grouping").performClick()
                    onNode(hasText(category) and hasAnyAncestor(isPopup())).performClick()
                    onNode(hasText(name) and hasAnyAncestor(hasTestTag("statistics-ranking"))).performClick()
                    onNodeWithText("Northern Light").assertIsDisplayed()
                    assertLibraryBackInHeader()
                    onAllNodes(hasText(name) and !hasAnyAncestor(hasTestTag("statistics-most-played")),
                        useUnmergedTree = true).assertCountEquals(1)
                    captureUiSnapshot("statistics-${category.lowercase()}-header")
                    onNodeWithText("Back to rankings").assertDoesNotExist()
                    onNodeWithContentDescription("Back").performClick()
                    onNodeWithContentDescription("Back").assertDoesNotExist()
                    onNodeWithContentDescription("Nevumi logo").assertIsDisplayed()
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSmartPlaylistCanCombineValidatedPlayCountsAndRecentlyPlayedRules() {
        val directory = Files.createTempDirectory("listening-rules-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        var elapsed = 0L
        val history = ListeningHistory(store, library, elapsedClock = { elapsed })
        val song = runBlocking {
            val track = library.importTrack(TrackMetadata("Played song", durationMs = 20_000),
                TrackSource("played", SourceKind.LOCAL, "/music/played.flac"))
            library.importTrack(TrackMetadata("Unplayed song"), TrackSource("unplayed", SourceKind.LOCAL, "/music/unplayed.flac"))
            val playing = PlaybackStatus(track.id, playing = true, playbackId = "one", durationMs = 20_000)
            history.record(playing); elapsed = 10_000; history.record(playing.copy(playing = false))
            track
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            listeningHistory = history, playlists = Playlists(store), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                openLibraryPage("Playlists")
                onNodeWithContentDescription("New smart playlist").performClick()
                onNodeWithText("Playlist name").performTextInput("Recently played")
                onNodeWithText("Minimum plays").performScrollTo().performTextInput("-1")
                onNodeWithText("Create").assertIsNotEnabled()
                onNodeWithText("Minimum plays").performTextReplacement("1")
                onNodeWithText("Played within days").performScrollTo().performTextInput("7")
                onNode(hasContentDescription("Song order") and hasText("Title")).performScrollTo().performClick()
                onNodeWithText("Most played").performClick()
                onNodeWithText("Create").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.size == 1 }
                assertEquals(listOf(song.id), controller.state.value.playlists.single().trackIds)
                assertEquals(LibrarySort.PLAY_COUNT, controller.state.value.playlists.single().rule?.sort)
                runBlocking { controller.clearListeningHistory().join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().trackIds.isEmpty() }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun historyRecordingCanBeDisabledAndClearingNeedsConfirmationWithoutInterruptingPlayback() {
        val directory = Files.createTempDirectory("history-controls-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking {
            library.importTrack(TrackMetadata("Northern Light", durationMs = 3_000),
                TrackSource("local", SourceKind.LOCAL, Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
        }
        val history = ListeningHistory(store, library)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            listeningHistory = history, preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithContentDescription("Record listening history").performScrollTo().performClick()
                onNode(hasText("Off") and hasAnyAncestor(isPopup())).performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended }
                assertEquals(emptyList(), runBlocking { history.all() })
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithContentDescription("Record listening history").performScrollTo().performClick()
                onNodeWithText("On").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                runBlocking { controller.playTrack(controller.state.value.tracks.single().id).join() }
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended && controller.state.value.listens.singleOrNull()?.qualified == true }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Clear listening history").performScrollTo().performClick()
                onNodeWithText("Cancel").performClick()
                assertEquals(1, runBlocking { history.all() }.size)
                onNodeWithText("Clear listening history").performScrollTo().performClick()
                onNodeWithText("Clear history").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.listens.isEmpty() }
                assertEquals(emptyMap(), runBlocking { history.statistics() })
                assertEquals(true, controller.state.value.playback.ended)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aCompletedSongAppearsInHistoryAndTheMostPlayedStatistics() {
        val directory = Files.createTempDirectory("listening-history-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        runBlocking {
            library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", durationMs = 3_000),
                TrackSource("local", SourceKind.LOCAL, Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), listeningHistory = ListeningHistory(store, library), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended && controller.state.value.listens.singleOrNull()?.ended == true }
                openLibraryPage("History")
                onNodeWithText("Listening history").assertDoesNotExist()
                val id = controller.state.value.tracks.single().id
                onNode(hasText("0:03") and hasAnyAncestor(hasTestTag("track-row:$id"))).assertIsDisplayed()
                onNodeWithContentDescription("Song artwork: Northern Light").assertIsDisplayed()
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Rate song").performClick()
                onNodeWithContentDescription("4 stars").performClick()
                onNodeWithText("Confirm").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.tracks.single().rating == 4 }
                openLibraryPage("Statistics")
                onNodeWithText("1 play").assertIsDisplayed()
                onNodeWithText("Most played").assertIsDisplayed()
                onNode(hasText("Northern Light") and hasAnyAncestor(hasTestTag("statistics-most-played"))).assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
