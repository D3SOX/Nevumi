package me.d3sox.nevumi.playback

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxAudioControlsTest {
    @Test
    fun desktopSpeedControlsUseTheNativeRateAndAZeroRatePausesPlayback() = runBlocking {
        val directory = Files.createTempDirectory("music-mpris-speed")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString())
        val engine = MpvPlaybackEngine("null")
        val controller = MusicController(library, PlaybackQueue(store), engine)
        var session: LinuxMediaSession? = null
        fun call(method: String, vararg arguments: String): String {
            val process = ProcessBuilder(listOf("gdbus", "call", "--address", address, "--dest", "org.mpris.MediaPlayer2.nevumi",
                "--object-path", "/org/mpris/MediaPlayer2", "--method", method) + arguments).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "D-Bus client timed out")
                val result = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), result)
                return result
            } finally { process.destroyForcibly() }
        }
        try {
            session = LinuxMediaSession(controller, address)
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(tracks.first { it.metadata.title == "Northern Light" }.id).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            val initial = call("org.freedesktop.DBus.Properties.GetAll", "org.mpris.MediaPlayer2.Player")
            assertTrue(initial.contains("'Rate': <1.0>"), initial)
            assertTrue(initial.contains("'MinimumRate': <0.5>"), initial)
            assertTrue(initial.contains("'MaximumRate': <2.0>"), initial)
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "Rate", "<1.5>")
            withTimeout(3_000) { engine.status.first { it.speed == 1.5 } }
            withTimeout(3_000) { controller.state.first { it.playback.speed == 1.5 } }
            val property = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Rate")
            assertTrue(property.contains("1.5"), property)
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "Rate", "<0.0>")
            val paused = withTimeout(3_000) { engine.status.first { !it.playing } }
            assertEquals(1.5, paused.speed)
        } finally {
            session?.close(); runBlocking { controller.closeAndJoin() }; store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun desktopVolumeControlsChangeTheNativePlayerGainAndClampNegativeVolumeToMute() = runBlocking {
        val directory = Files.createTempDirectory("music-mpris-volume")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString())
        val engine = MpvPlaybackEngine("null")
        val controller = MusicController(library, PlaybackQueue(store), engine)
        var session: LinuxMediaSession? = null
        fun call(method: String, vararg arguments: String): String {
            val process = ProcessBuilder(listOf("gdbus", "call", "--address", address, "--dest", "org.mpris.MediaPlayer2.nevumi",
                "--object-path", "/org/mpris/MediaPlayer2", "--method", method) + arguments).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "D-Bus client timed out")
                val result = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), result)
                return result
            } finally { process.destroyForcibly() }
        }
        try {
            session = LinuxMediaSession(controller, address)
            withTimeout(3_000) { controller.state.first { !it.loading } }
            controller.playTrack(tracks.first { it.metadata.title == "Northern Light" }.id).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "Volume", "<0.25>")
            val lowered = withTimeout(3_000) { engine.status.first { it.volume == 0.25 } }
            assertTrue(lowered.playing)
            withTimeout(3_000) { controller.state.first { it.playback.volume == 0.25 } }
            val property = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Volume")
            assertTrue(property.contains("0.25"), property)
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "Volume", "<-1.0>")
            withTimeout(3_000) { engine.status.first { it.volume == 0.0 } }
            assertEquals(0.0, engine.status.value.volume)
        } finally {
            session?.close(); runBlocking { controller.closeAndJoin() }; store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }
}
