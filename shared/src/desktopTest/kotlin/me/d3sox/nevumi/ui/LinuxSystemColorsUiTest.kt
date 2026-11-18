package me.d3sox.nevumi.ui

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.ColorMode
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LinuxSystemColorsUiTest {
    @Test
    fun followingTheSystemUsesTheLinuxDesktopsDarkAppearance() {
        val portal = ProcessBuilder("gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop", "--method", "org.freedesktop.portal.Settings.ReadOne",
            "org.freedesktop.appearance", "color-scheme").redirectErrorStream(true).start()
        val dark = try { portal.waitFor(3, TimeUnit.SECONDS) && portal.exitValue() == 0 &&
            portal.inputStream.bufferedReader().readText().contains("uint32 1") }
            finally { portal.destroyForcibly() }
        assumeTrue("Requires a desktop preferring dark appearance", dark)
        val directory = Files.createTempDirectory("nevumi-desktop-dark-appearance")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(themeMode = ThemeMode.LIGHT) } }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForStartup(controller)
                waitUntil(timeoutMillis = 3_000) { onRoot().captureToImage().toPixelMap()[5, 5].luminance() > 0.8f }
                runBlocking { controller.updatePreferences { it.copy(themeMode = ThemeMode.SYSTEM) }.join() }
                waitUntil(timeoutMillis = 8_000) { onRoot().captureToImage().toPixelMap()[5, 5].luminance() < 0.1f }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun choosingSystemColorsUsesTheLinuxDesktopAccentAndArtworkColorsRemainIndependent() {
        val portal = ProcessBuilder("gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop", "--method", "org.freedesktop.portal.Settings.ReadOne",
            "org.freedesktop.appearance", "accent-color").redirectErrorStream(true).start()
        val supported = try { portal.waitFor(3, TimeUnit.SECONDS) && portal.exitValue() == 0 }
            finally { portal.destroyForcibly() }
        assumeTrue("Requires a desktop exposing its accent through the appearance portal", supported)
        val directory = Files.createTempDirectory("nevumi-desktop-colors")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(themeMode = ThemeMode.DARK, colors = ColorMode.ARTWORK) } }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForStartup(controller)
                val artworkFallback = onRoot().captureToImage().toPixelMap()[5, 5]
                runBlocking { controller.updatePreferences { it.copy(colors = ColorMode.SYSTEM) }.join() }
                waitUntil(timeoutMillis = 8_000) { onRoot().captureToImage().toPixelMap()[5, 5] != artworkFallback }
                runBlocking { controller.updatePreferences { it.copy(colors = ColorMode.ARTWORK) }.join() }
                waitUntil(timeoutMillis = 3_000) { onRoot().captureToImage().toPixelMap()[5, 5] == artworkFallback }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
