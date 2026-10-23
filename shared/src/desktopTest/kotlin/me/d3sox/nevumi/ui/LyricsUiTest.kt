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
            override suspend fun lyrics(track: Track, online: Boolean) = Lyrics(LyricsSource.EMBEDDED, lines)
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
        val lyrics = object : LyricsLibrary { override suspend fun lyrics(track: Track, online: Boolean): Lyrics? = null }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), lyrics = lyrics)
        try {
            runComposeUiTest {
                setContent { MaterialTheme { LyricsSettings(controller.state.collectAsState().value.preferences, controller) } }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.libraryReady }
                assertFalse(controller.state.value.preferences.fetchLyricsOnline)
                onNodeWithText("Sends the artist, title, album and length of songs without lyrics to LRCLIB.").assertExists()
                onNodeWithContentDescription("Find lyrics online").assertIsOff().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.fetchLyricsOnline }
                onNodeWithContentDescription("Find lyrics online").assertIsOn()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
