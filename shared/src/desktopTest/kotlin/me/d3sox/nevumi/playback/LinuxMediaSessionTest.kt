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
import kotlinx.coroutines.delay
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource

class LinuxMediaSessionTest {
    @Test fun kdeReceivesReadableCoverUrlsAndArtworkUpdates() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-mpris-artwork")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Cover song", durationMs = 3_000), TrackSource("local", SourceKind.LOCAL,
            Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()))
        val covers = me.d3sox.nevumi.artwork.ArtworkLibrary(directory.resolve("covers with space #").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = covers)
        var session: LinuxMediaSession? = null
        fun metadata(): String {
            val process = ProcessBuilder("gdbus", "call", "--address", address, "--dest", "org.mpris.MediaPlayer2.nevumi",
                "--object-path", "/org/mpris/MediaPlayer2", "--method", "org.freedesktop.DBus.Properties.Get",
                "org.mpris.MediaPlayer2.Player", "Metadata").redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                val result = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), result)
                return result
            } finally { process.destroyForcibly() }
        }
        fun cover(color: Int): ByteArray {
            val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, color)
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        }
        try {
            session = LinuxMediaSession(controller, address)
            withTimeout(3_000) { controller.state.first { it.libraryReady } }
            controller.playTrack(track.id).join(); controller.pause().join()
            for (color in listOf(0xff0000, 0x0000ff)) {
                covers.save(track.id, cover(color))
                val path = requireNotNull(covers.path(track.id))
                withTimeout(3_000) { controller.state.first { it.artwork[track.id] == path } }
                val url = Path.of(path).toUri().toASCIIString()
                assertTrue(metadata().contains("'mpris:artUrl': <'$url'>"), metadata())
                assertTrue(Files.isReadable(Path.of(java.net.URI(url))))
            }
            covers.remove(track.id)
            withTimeout(3_000) { controller.state.first { track.id !in it.artwork } }
            assertTrue(!metadata().contains("mpris:artUrl"))
        } finally {
            session?.close(); controller.closeAndJoin(); store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun desktopRepeatControlsWrapQueueNavigationAndKeepPlaybackPaused() = runBlocking {
        val directory = Files.createTempDirectory("music-mpris-repeat")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
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
            val flac = tracks.first { it.metadata.title == "Northern Light" }
            val order = tracks.filterNot { it.id == flac.id }.map { it.id } + flac.id
            controller.playTrack(flac.id, order, contextIndex = 2).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            controller.pause().join()
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "LoopStatus").contains("None"))
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "LoopStatus", "<'Playlist'>")
            withTimeout(3_000) { controller.state.first { it.queue.repeatMode == RepeatMode.ALL } }
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "LoopStatus").contains("Playlist"))
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "CanGoNext").contains("true"))
            call("org.mpris.MediaPlayer2.Player.Next")
            withTimeout(3_000) { controller.state.first { it.queue.currentIndex == 0 && it.playback.trackId == order.first() } }
            assertEquals(false, controller.state.value.playback.playWhenReady)
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "CanGoPrevious").contains("true"))
            call("org.mpris.MediaPlayer2.Player.Previous")
            withTimeout(3_000) { controller.state.first { it.queue.currentIndex == 2 && it.playback.trackId == flac.id } }
            assertEquals(false, controller.state.value.playback.playWhenReady)
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "LoopStatus", "<'Track'>")
            withTimeout(3_000) { controller.state.first { it.queue.repeatMode == RepeatMode.ONE } }
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "LoopStatus").contains("Track"))
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "LoopStatus", "<'None'>")
            withTimeout(3_000) { controller.state.first { it.queue.repeatMode == RepeatMode.OFF } }
            Unit
        } finally {
            session?.close(); controller.closeAndJoin(); store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun desktopShuffleControlsRestoreTheOriginalQueueWithoutMovingPausedPlayback() = runBlocking {
        val directory = Files.createTempDirectory("music-mpris-shuffle")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
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
            controller.playTrack(tracks.first { it.metadata.title == "Northern Light" }.id,
                tracks.map { it.id }, contextIndex = tracks.indexOfFirst { it.metadata.title == "Northern Light" }).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            controller.pause().join(); controller.seek(1_000).join()
            withTimeout(3_000) { controller.state.first { it.playback.positionMs == 1_000L } }
            val original = controller.state.value.queue
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Shuffle").contains("false"))
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "Shuffle", "<true>")
            withTimeout(3_000) { controller.state.first { it.queue.unshuffledEntryIds != null } }
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Shuffle").contains("true"))
            call("org.freedesktop.DBus.Properties.Set", "org.mpris.MediaPlayer2.Player", "Shuffle", "<false>")
            withTimeout(3_000) { controller.state.first { it.queue.unshuffledEntryIds == null } }
            assertEquals(original.entries, controller.state.value.queue.entries)
            assertEquals(original.currentEntryId to 1_000L,
                controller.state.value.playback.entryId to controller.state.value.playback.positionMs)
            assertEquals(false, controller.state.value.playback.playWhenReady)
        } finally {
            session?.close(); controller.closeAndJoin(); store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnreadableSongReportsStoppedToDesktopControlsAndPlayRetriesAfterRepair() = runBlocking {
        val directory = Files.createTempDirectory("music-mpris-error")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = directory.resolve("repair.flac")
        Files.writeString(audio, "This is not FLAC audio")
        val track = library.importTrack(TrackMetadata("Repair song"), TrackSource("repair", SourceKind.LOCAL, audio.toString()))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
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
            controller.playTrack(track.id, listOf(track.id)).join()
            withTimeout(3_000) { controller.state.first { it.playback.error != null } }
            val failed = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "PlaybackStatus")
            assertTrue(failed.contains("Stopped"), failed)
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                "Fixture Album", "01 Northern Light.flac"), audio, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            call("org.mpris.MediaPlayer2.Player.Play")
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "PlaybackStatus").contains("Playing"))
        } finally {
            session?.close(); controller.closeAndJoin(); store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun desktopControlsSeekTheCurrentOccurrenceAndStopBeforeRestartingAtTheBeginning() = runBlocking {
        val directory = Files.createTempDirectory("music-mpris-controls")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()).associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
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
            val first = tracks.getValue("Northern Light").id
            val next = tracks.getValue("Night Walk").id
            controller.playTrack(first, listOf(first, next)).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.durationMs == 3_000L } }
            call("org.mpris.MediaPlayer2.Player.Pause")
            withTimeout(3_000) { controller.state.first { !it.playback.playing } }
            call("org.mpris.MediaPlayer2.Player.Pause")
            val metadata = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Metadata")
            val path = Regex("objectpath '([^']+)'").find(metadata)!!.groupValues[1]
            call("org.mpris.MediaPlayer2.Player.SetPosition", path, "0")
            withTimeout(3_000) { controller.state.first { it.playback.positionMs == 0L } }
            call("org.mpris.MediaPlayer2.Player.Seek", "1000000")
            withTimeout(3_000) { controller.state.first { it.playback.positionMs in 990..1010 } }
            val position = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Position")
            assertTrue(position.contains("int64 1000000"), position)
            call("org.mpris.MediaPlayer2.Player.SetPosition", "/org/mpris/MediaPlayer2/track/stale", "2000000")
            assertEquals(1_000, controller.state.value.playback.positionMs)
            call("org.mpris.MediaPlayer2.Player.SetPosition", path, "2000000")
            withTimeout(3_000) { controller.state.first { it.playback.positionMs in 1990..2010 } }
            call("org.mpris.MediaPlayer2.Player.SetPosition", path, "4000000")
            assertEquals(2_000, controller.state.value.playback.positionMs)
            call("org.mpris.MediaPlayer2.Player.Next")
            withTimeout(3_000) { controller.state.first { it.playback.trackId == next && it.playback.durationMs > 0 } }
            call("org.mpris.MediaPlayer2.Player.Previous")
            withTimeout(3_000) { controller.state.first { it.playback.trackId == first && it.playback.durationMs > 0 } }
            call("org.mpris.MediaPlayer2.Player.SetPosition", path, "1500000")
            withTimeout(3_000) { controller.state.first { it.playback.positionMs == 1_500L } }
            call("org.mpris.MediaPlayer2.Player.Stop")
            withTimeout(3_000) { controller.state.first { it.playback.positionMs == 0L && !it.playback.playing } }
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "PlaybackStatus").contains("Stopped"))
            call("org.mpris.MediaPlayer2.Player.Play")
            val playing = withTimeout(3_000) { controller.state.first { it.playback.playing } }
            assertTrue(playing.playback.positionMs < 500)
            call("org.mpris.MediaPlayer2.Player.Play")
            call("org.mpris.MediaPlayer2.Player.PlayPause")
            withTimeout(3_000) { controller.state.first { !it.playback.playing } }
            call("org.mpris.MediaPlayer2.Player.Next")
            withTimeout(3_000) { controller.state.first { it.playback.trackId == next && it.playback.durationMs > 0 } }
            val lastMetadata = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Metadata")
            val lastPath = Regex("objectpath '([^']+)'").find(lastMetadata)!!.groupValues[1]
            call("org.mpris.MediaPlayer2.Player.SetPosition", lastPath, "2700000")
            withTimeout(3_000) { controller.state.first { it.playback.positionMs == 2_700L } }
            call("org.mpris.MediaPlayer2.Player.Play")
            withTimeout(3_000) { controller.state.first { it.playback.ended } }
            val finished = call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "PlaybackStatus")
            assertTrue(finished.contains("Stopped"), finished)
        } finally {
            session?.close(); runBlocking { controller.closeAndJoin() }; store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun desktopMediaControlsDiscoverThePlayerAndReceiveSongPlaybackAndSeekChanges() = runBlocking {
        val directory = Files.createTempDirectory("music-mpris-signals")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        var session: LinuxMediaSession? = null
        var monitor: Process? = null
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
            val root = call("org.freedesktop.DBus.Properties.GetAll", "org.mpris.MediaPlayer2")
            assertTrue(root.contains("'Identity': <'Nevumi'>"), root)
            assertTrue(root.contains("'HasTrackList': <false>"), root)
            assertTrue(root.contains("'CanQuit': <false>"), root)
            val output = directory.resolve("signals.log")
            monitor = ProcessBuilder("gdbus", "monitor", "--address", address, "--dest", "org.mpris.MediaPlayer2.nevumi",
                "--object-path", "/org/mpris/MediaPlayer2").redirectErrorStream(true).redirectOutput(output.toFile()).start()
            withTimeout(3_000) { while (!Files.readString(output).contains("is owned by")) delay(20) }
            withTimeout(3_000) { controller.state.first { !it.loading } }
            val northern = tracks.first { it.metadata.title == "Northern Light" }.id
            controller.playTrack(northern, listOf(northern)).join()
            withTimeout(3_000) { while (!Files.readString(output).contains("Northern Light")) delay(20) }
            withTimeout(3_000) { while (!Files.readString(output).contains("'CanSeek': <true>")) delay(20) }
            call("org.mpris.MediaPlayer2.Player.Pause")
            withTimeout(3_000) { while (!Files.readString(output).contains("'PlaybackStatus': <'Paused'>")) delay(20) }
            controller.seek(1_234).join()
            withTimeout(3_000) { while (!Files.readString(output).contains("Seeked (int64 1234000")) delay(20) }
            assertEquals(false, Files.readString(output).contains("'Position':"))
            val player = call("org.freedesktop.DBus.Properties.GetAll", "org.mpris.MediaPlayer2.Player")
            assertTrue(player.contains("'CanGoNext': <false>"), player)
            assertTrue(player.contains("'CanGoPrevious': <false>"), player)
            assertTrue(player.contains("'CanSeek': <true>"), player)
            assertTrue(player.contains("'xesam:artist': <['Fixture Artist']>"), player)
            assertTrue(player.contains("'xesam:album': <'Fixture Album'>"), player)
            assertTrue(player.contains("'mpris:length': <int64 3000000>"), player)
            assertTrue(player.contains("objectpath '/org/mpris/MediaPlayer2/track/"), player)
        } finally {
            monitor?.destroyForcibly(); session?.close(); runBlocking { controller.closeAndJoin() }; store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aDesktopMediaClientCanReadTheCurrentSongPauseItAndSkipWithoutResuming() = runBlocking {
        val directory = Files.createTempDirectory("music-mpris")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        val ready = daemon.inputStream.bufferedReader().readLine()
        assertTrue(ready.startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
            ".test-environment", "music").toString()).associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
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
            val first = tracks.getValue("Northern Light").id
            val next = tracks.getValue("Night Walk").id
            controller.playTrack(first, listOf(first, next)).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "Metadata").contains("Northern Light"))
            call("org.mpris.MediaPlayer2.Player.Pause")
            withTimeout(3_000) { controller.state.first { !it.playback.playing } }
            assertTrue(call("org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "PlaybackStatus").contains("Paused"))
            call("org.mpris.MediaPlayer2.Player.Next")
            val skipped = withTimeout(3_000) { controller.state.first { it.playback.trackId == next && it.playback.durationMs > 0 } }
            assertEquals(false, skipped.playback.playing)
        } finally {
            session?.close(); runBlocking { controller.closeAndJoin() }; store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }
}
