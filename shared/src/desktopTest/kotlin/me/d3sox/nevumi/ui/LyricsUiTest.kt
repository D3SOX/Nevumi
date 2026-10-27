package me.d3sox.nevumi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.lyrics.LyricLine
import me.d3sox.nevumi.lyrics.Lyrics
import me.d3sox.nevumi.lyrics.LyricsLibrary
import me.d3sox.nevumi.lyrics.LyricsQuery
import me.d3sox.nevumi.lyrics.LyricsSource
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class LyricsUiTest {
    private val lines = listOf(LyricLine(0, "First line"), LyricLine(2_000, "Second line"), LyricLine(4_000, "Third line"))

    @Test fun nowPlayingShowsSyncedLyricsInPlaceOfTheArtworkHighlightsTheCurrentLineAndSeeksOnTap() {
        val directory = Files.createTempDirectory("lyrics-panel")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("Lyrics fixture", durationMs = 10_000), TrackSource("lyrics", SourceKind.LOCAL, audio)) }
        val lyrics = object : LyricsLibrary {
            override suspend fun lyrics(track: Track, online: Set<LyricsSource>, query: LyricsQuery?) = Lyrics(LyricsSource.EMBEDDED, lines)
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), lyrics = lyrics)
        PlayerLyrics.visible = false
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join(); controller.seek(2_500).join() }
                onNodeWithTag("mini-player").performClick()
                onNodeWithContentDescription("Lyrics").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Second line").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Lyrics from the song’s tags").assertExists()
                waitUntil(timeoutMillis = 3_000) {
                    onNodeWithTag("lyrics-line-1").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Selected) { false }
                }
                onNodeWithTag("lyrics-line-0").assertIsNotSelected()
                onNodeWithText("Third line").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs == 4_000L }
                onNodeWithContentDescription("Lyrics").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("lyrics-panel").fetchSemanticsNodes().isEmpty() }
            }
        } finally { PlayerLyrics.visible = false; runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun onlineLyricsAreOffUntilTheListenerTurnsThemOn() {
        val directory = Files.createTempDirectory("lyrics-setting")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val lyrics = object : LyricsLibrary { override suspend fun lyrics(track: Track, online: Set<LyricsSource>, query: LyricsQuery?): Lyrics? = null }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), lyrics = lyrics)
        try {
            runComposeUiTest {
                setContent { MaterialTheme { LyricsSettings(controller.state.collectAsState().value.preferences, controller) } }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                assertFalse(controller.state.value.preferences.fetchLyricsOnline)
                onNodeWithContentDescription("Also search NetEase Cloud Music").assertDoesNotExist()
                onNodeWithText("Sends the artist, title, album and length of songs without lyrics to LRCLIB.").assertExists()
                onNodeWithContentDescription("Find lyrics online").assertIsOff().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.fetchLyricsOnline }
                onNodeWithContentDescription("Find lyrics online").assertIsOn()
                // NetEase stays off, and only appears once online lyrics are on.
                onNodeWithText("When LRCLIB has no synced lyrics, sends the song’s artist and title to NetEase Cloud Music, a Chinese streaming service. Its lyrics search isn’t an official API.").assertExists()
                onNodeWithContentDescription("Also search NetEase Cloud Music").assertIsOff().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.fetchLyricsFromNetEase }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun theListenerCanCorrectTheSongTheOnlineServicesAreAskedFor() {
        val directory = Files.createTempDirectory("lyrics-search")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac").toString()
        val track = runBlocking { library.importTrack(TrackMetadata("track01", "Someone", "Album"), TrackSource("lyrics", SourceKind.LOCAL, audio)) }
        val next = runBlocking { library.importTrack(TrackMetadata("track02"), TrackSource("next", SourceKind.LOCAL, audio)) }
        val asked = java.util.Collections.synchronizedList(mutableListOf<LyricsQuery?>())
        val lyrics = object : LyricsLibrary {
            override suspend fun lyrics(track: Track, online: Set<LyricsSource>, query: LyricsQuery?): Lyrics? {
                asked += query
                return query?.let { Lyrics(LyricsSource.LRCLIB, plain = "Words of ${it.title}") }
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), lyrics = lyrics)
        val search = "Edit lyrics search"
        try {
            runComposeUiTest {
                setContent { MaterialTheme { LyricsPanel(controller.state.collectAsState().value, controller) } }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                runBlocking { controller.playTrack(track.id).join(); controller.pause().join() }
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("No lyrics for this song").fetchSemanticsNodes().isNotEmpty() }
                // Searching is only offered while the online services may be asked.
                onNodeWithText(search).assertDoesNotExist()
                runBlocking { controller.updatePreferences { it.copy(fetchLyricsOnline = true) }.join() }
                onNodeWithText(search).performClick()
                onNodeWithText("track01").performTextReplacement("Real title")
                onNodeWithText("Album").performTextClearance()
                onNodeWithText("Someone").performTextClearance()
                onNodeWithText("Search").assertIsNotEnabled()
                onNode(hasSetTextAction() and hasText("Artist")).performTextInput("Real artist")
                onNodeWithText("Search").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Words of Real title").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(LyricsQuery("Real title", "Real artist", ""), asked.last())
                // An online match can be searched again, starting from the last search.
                onNodeWithContentDescription(search).performClick()
                onNodeWithText("Real title").assertExists()
                onNodeWithText("Cancel").performClick()
                onNodeWithText("Real artist").assertDoesNotExist()
                // A search written for one song is never kept for the next one.
                onNodeWithContentDescription(search).performClick()
                onNode(isDialog()).assertExists()
                runBlocking { controller.playTrack(next.id).join(); controller.pause().join() }
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("No lyrics for this song").fetchSemanticsNodes().isNotEmpty() }
                onNode(isDialog()).assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
