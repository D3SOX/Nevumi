package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxTrayIconTest {
    private class Watcher : StatusNotifierWatcher {
        val services = CopyOnWriteArrayList<String>()
        override fun getObjectPath() = "/StatusNotifierWatcher"
        override fun RegisterStatusNotifierItem(service: String) { services += service }
    }

    @Test fun trayMenuControlsPlaybackShowsTheWindowAndQuits() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-tray")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val track = library.importTrack(TrackMetadata("Tray song", artist = "Tray artist", durationMs = 3_000), TrackSource("local", SourceKind.LOCAL,
            Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"))
        val watcher = Watcher()
        var watcherConnection = DBusConnectionBuilder.forAddress(address).withShared(false).build().apply {
            exportObject(watcher.objectPath, watcher); requestBusName("org.kde.StatusNotifierWatcher")
        }
        val shown = AtomicInteger()
        val quit = AtomicInteger()
        var tray: LinuxTrayIcon? = null
        val service = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
        fun call(path: String, method: String, vararg arguments: String): String {
            val process = ProcessBuilder(listOf("gdbus", "call", "--address", address, "--dest", service, "--object-path", path,
                "--method", method) + arguments).redirectErrorStream(true).start()
            try {
                // The icon's pixels make long replies, so the pipe is drained before waiting.
                val result = process.inputStream.bufferedReader().readText()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue(), result)
                return result
            } finally { process.destroyForcibly() }
        }
        fun layout() = call("/MenuBar", "com.canonical.dbusmenu.GetLayout", "0", "int32 -1", "@as []")
        fun click(id: Int) = call("/MenuBar", "com.canonical.dbusmenu.Event", "$id", "clicked", "<0>", "0")
        try {
            withTimeout(3_000) { controller.state.first { it.libraryReady } }
            tray = LinuxTrayIcon(controller, BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB), { shown.incrementAndGet() },
                { quit.incrementAndGet() }, address)
            assertEquals(listOf(service), watcher.services)
            assertTrue(tray.registered)
            // Tray hosts check the reply types: a revision, then the root item with its properties and its children as variants.
            assertTrue(Regex("""^\(uint32 \d+, \(0, \{'children-display': <'submenu'>}, \[<\(1, \{""").containsMatchIn(layout()), layout())
            val properties = call("/StatusNotifierItem", "org.freedesktop.DBus.Properties.GetAll", "org.kde.StatusNotifierItem")
            assertTrue(properties.contains("'Menu': <objectpath '/MenuBar'>"), properties)
            assertTrue(properties.contains("'IconPixmap': <[(22, 22, "), properties)
            assertTrue(properties.contains("'ItemIsMenu': <false>"), properties)
            assertTrue(layout().contains("'label': <'Play'>, 'icon-name': <'media-playback-start'>, 'enabled': <false>"), layout())
            listOf("Previous track", "Next track", "Show Nevumi", "Quit").forEach { assertTrue(layout().contains("'label': <'$it'>"), layout()) }

            controller.playTrack(track.id, listOf(track.id)).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            withTimeout(3_000) { while (!layout().contains("'label': <'Pause'>")) kotlinx.coroutines.delay(20) }
            assertTrue(call("/StatusNotifierItem", "org.freedesktop.DBus.Properties.Get", "org.kde.StatusNotifierItem", "ToolTip")
                .contains("'Nevumi', 'Tray song – Tray artist'"))
            click(2)
            withTimeout(3_000) { controller.state.first { !it.playback.playing } }

            call("/StatusNotifierItem", "org.kde.StatusNotifierItem.Activate", "0", "0")
            click(6)
            assertEquals(2, shown.get())
            click(7)
            assertEquals(1, quit.get())

            // Plasma's panel can restart; the new tray host learns about the icon again.
            watcherConnection.close()
            withTimeout(3_000) { while (tray.registered) kotlinx.coroutines.delay(20) }
            watcherConnection = DBusConnectionBuilder.forAddress(address).withShared(false).build().apply {
                exportObject(watcher.objectPath, watcher); requestBusName("org.kde.StatusNotifierWatcher")
            }
            withTimeout(3_000) { while (watcher.services.size < 2 || !tray.registered) kotlinx.coroutines.delay(20) }
        } finally {
            tray?.close(); watcherConnection.close(); controller.closeAndJoin(); store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun theIconAppearsWhenTheTrayHostStartsAfterNevumi() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-tray-late")
        val address = "unix:path=${directory.resolve("bus")}"
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1", "--address=$address")
            .redirectError(directory.resolve("bus.log").toFile()).start()
        assertTrue(daemon.inputStream.bufferedReader().readLine().startsWith("unix:path="))
        val store = openMusicStore(directory.resolve("music.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        val watcher = Watcher()
        var tray: LinuxTrayIcon? = null
        var watcherConnection: org.freedesktop.dbus.connections.impl.DBusConnection? = null
        try {
            tray = LinuxTrayIcon(controller, null, {}, {}, address)
            assertEquals(false, tray.registered)
            watcherConnection = DBusConnectionBuilder.forAddress(address).withShared(false).build().apply {
                exportObject(watcher.objectPath, watcher); requestBusName("org.kde.StatusNotifierWatcher")
            }
            withTimeout(3_000) { while (watcher.services.isEmpty() || !tray.registered) kotlinx.coroutines.delay(20) }
            watcherConnection.close()
            withTimeout(3_000) { while (tray.registered) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"), watcher.services)
        } finally {
            tray?.close(); watcherConnection?.close(); controller.closeAndJoin(); store.close()
            daemon.destroy(); daemon.waitFor(5, TimeUnit.SECONDS); daemon.destroyForcibly()
            directory.toFile().deleteRecursively()
        }
    }
}
