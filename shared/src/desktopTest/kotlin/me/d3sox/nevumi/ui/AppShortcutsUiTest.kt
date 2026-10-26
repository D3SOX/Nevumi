package me.d3sox.nevumi.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.InternalComposeUiApi::class)
class AppShortcutsUiTest {
    private val source = java.awt.Canvas()
    /** A key event as the window delivers it, with the AWT event that text fields read typed characters from. */
    private fun key(id: Int, code: Int, char: Char = KeyEvent.CHAR_UNDEFINED, modifiers: Int = 0): androidx.compose.ui.input.key.KeyEvent {
        val awt = KeyEvent(source, id, 0, modifiers, code, char)
        return androidx.compose.ui.input.key.KeyEvent(Key(code), when (id) {
            KeyEvent.KEY_PRESSED -> KeyEventType.KeyDown
            KeyEvent.KEY_RELEASED -> KeyEventType.KeyUp
            else -> KeyEventType.Unknown
        }, if (char == KeyEvent.CHAR_UNDEFINED) 0 else char.code, isCtrlPressed = awt.isControlDown, isShiftPressed = awt.isShiftDown, nativeEvent = awt)
    }

    @Test fun shortcutsControlPlaybackAndOpenSearchButLeaveTypingToTextFields() {
        val directory = Files.createTempDirectory("shortcuts-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val assets = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets")
        val (first, second) = runBlocking {
            library.importTrack(TrackMetadata("Long song", durationMs = 240_000), TrackSource("long", SourceKind.LOCAL,
                assets.resolve("waveform-four-minutes.mp3").toString())) to
                library.importTrack(TrackMetadata("Short song", durationMs = 3_000), TrackSource("short", SourceKind.LOCAL,
                    assets.resolve("fixture.flac").toString()))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        var quits = 0
        val shortcuts = AppShortcuts(controller) { quits++ }
        try {
            runSkikoComposeUiTest(size = Size(420f, 900f)) {
                val window = FocusRequester()
                // Stands in for the window, which receives the keys that nothing focused inside it handled.
                setContent {
                    Box(Modifier.focusRequester(window).focusable().onKeyEvent(shortcuts::onKeyEvent)) {
                        MusicPlayerApp(controller, shortcuts = shortcuts)
                    }
                }
                val scene = (this as androidx.compose.ui.test.SkikoComposeUiTest).scene
                fun send(events: List<androidx.compose.ui.input.key.KeyEvent>) = events.forEach { event -> runOnIdle { scene.sendKeyEvent(event) } }
                fun space() = listOf(key(KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE, ' '), key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, ' '))
                val spaceUp = key(KeyEvent.KEY_RELEASED, KeyEvent.VK_SPACE, ' ')
                fun withModifier(modifier: Int, code: Int) = send(listOf(key(KeyEvent.KEY_PRESSED, code, modifiers = modifier),
                    key(KeyEvent.KEY_RELEASED, code, modifiers = modifier)))
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                runOnIdle { controller.playTrack(first.id, listOf(first.id, second.id)) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.playback.playing }
                runOnIdle { window.requestFocus() }

                // Holding Space repeats its events; playback pauses once.
                send(space() + space() + spaceUp)
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.playback.playWhenReady }
                waitForIdle()
                assertEquals(false, controller.state.value.playback.playWhenReady)

                withModifier(KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_RIGHT)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs >= 5_000 }
                withModifier(KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_LEFT)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.positionMs < 5_000 }
                withModifier(KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_RIGHT)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.trackId == second.id }
                withModifier(KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_LEFT)
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.trackId == first.id }

                withModifier(KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F)
                onNodeWithTag("library-search-page").assertIsDisplayed()
                onNodeWithContentDescription("Search your library").assertIsFocused()
                // The search field takes the space, so typing it leaves playback alone.
                send(space() + spaceUp)
                onNodeWithContentDescription("Search your library").assertTextEquals(" ")
                waitForIdle()
                assertEquals(false, controller.state.value.playback.playWhenReady)

                // Ctrl+F moves the cursor back to an open search.
                runOnIdle { window.requestFocus() }
                onNodeWithContentDescription("Search your library").assertIsNotFocused()
                withModifier(KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_F)
                onNodeWithContentDescription("Search your library").assertIsFocused()

                withModifier(KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_Q)
                assertEquals(1, quits)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
