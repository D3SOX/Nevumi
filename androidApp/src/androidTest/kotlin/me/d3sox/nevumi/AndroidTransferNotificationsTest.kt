package me.d3sox.nevumi

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.security.AndroidCredentialVault
import me.d3sox.nevumi.settings.MusicPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class AndroidTransferNotificationsTest {
    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun aDownloadReportsProgressAndCanBePausedWithoutInterruptingMusic() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val app = context.applicationContext as MusicApplication
        val controller = app.controller
        val original = MusicPreferences(app.store).load()
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        val audio = File(context.cacheDir, "notification-playback-${UUID.randomUUID()}.flac")
        audio.writeBytes(bytes)
        val server = HeldAudioServer(bytes)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val http = HttpClient(OkHttp)
        var phase = "start playback"
        try {
            controller.updatePreferences { it.copy(downloadsPaused = false, downloadOnMetered = true) }.join()
            controller.connectServer(server.address, "test", "test").join()
            check(controller.state.value.error == null) { controller.state.value.error.orEmpty() }
            val remote = controller.state.value.tracks.single { it.metadata.title == "Notification download" }
            val local = controller.library.importTrack(TrackMetadata("Notification playback"),
                TrackSource("notification-local-${UUID.randomUUID()}", SourceKind.LOCAL, audio.path))
            controller.reload().join()
            controller.playTrack(local.id, listOf(local.id)).join()
            controller.setQueueRepeat(RepeatMode.ONE).join()
            withTimeout(5_000) { controller.state.first { it.playback.playing && it.playback.trackId == local.id } }
            phase = "show media controls"
            val notifications = context.getSystemService(NotificationManager::class.java)
            val media = withTimeout(5_000) {
                while (true) {
                    val found = notifications.activeNotifications.firstOrNull { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
                    if (found != null) return@withTimeout found
                    delay(20)
                }
                error("Unreachable")
            }
            controller.downloadTracks(listOf(remote.id)).join()
            phase = "start transfer"
            withTimeout(10_000) { controller.state.first {
                it.downloads[remote.id]?.let { record -> record.status == DownloadStatus.DOWNLOADING && record.bytesDownloaded > 0 } == true
            } }
            val download = try { withTimeout(5_000) {
                while (true) {
                    val found = notifications.activeNotifications.firstOrNull {
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Downloading music"
                    }
                    if (found != null) return@withTimeout found
                    delay(20)
                }
                error("Unreachable")
            } } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                val pending = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.map { it.id to it.isUserInitiated }
                val visible = notifications.activeNotifications.map { it.id to it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() }
                throw AssertionError("Download notification unavailable: notificationsEnabled=${notifications.areNotificationsEnabled()}, jobs=$pending, notifications=$visible", error)
            }
            val active = notifications.activeNotifications.toList()
            assertTrue("Playback controls must remain available during the transfer", active.any {
                it.id == media.id && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            })
            assertNotEquals(media.id, download.id)
            phase = "show measured progress"
            val progress = withTimeout(5_000) {
                while (true) {
                    val notification = notifications.activeNotifications.firstOrNull { it.id == download.id }?.notification
                    if (notification != null && !notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) &&
                        notification.extras.getInt(Notification.EXTRA_PROGRESS) > 0)
                        return@withTimeout notification
                    delay(20)
                }
                error("Unreachable")
            }
            assertEquals("Notification download", progress.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals(100, progress.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertTrue(progress.extras.getInt(Notification.EXTRA_PROGRESS) in 1..99)
            val pause = progress.actions.orEmpty().singleOrNull { it.title.toString() == "Pause downloads" }
            assertNotNull("The download notification needs a pause action", pause)
            pause!!.actionIntent.send()
            phase = "pause transfer without stopping music"
            withTimeout(5_000) { controller.state.first {
                it.preferences.downloadsPaused && it.downloads[remote.id]?.status == DownloadStatus.PAUSED
            } }
            assertEquals(8192L, controller.state.value.downloads.getValue(remote.id).bytesDownloaded)
            assertTrue(controller.state.value.playback.playing)
            assertEquals(local.id, controller.state.value.playback.trackId)
            withTimeout(5_000) {
                while (notifications.activeNotifications.any { it.id == download.id }) delay(20)
            }
            server.release.countDown()
            controller.setDownloadsPaused(false).join()
            phase = "finish transfer"
            withTimeout(10_000) { controller.state.first { it.downloads[remote.id]?.status == DownloadStatus.COMPLETE } }
            phase = "remove completed transfer notification"
            withTimeout(5_000) {
                while (notifications.activeNotifications.any { it.id == download.id }) delay(20)
            }
            assertTrue(notifications.activeNotifications.any {
                it.id == media.id && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            })
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val visible = context.getSystemService(NotificationManager::class.java).activeNotifications.map {
                it.id to it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            }
            throw AssertionError("Timed out during $phase: playback=${controller.state.value.playback}, downloads=${controller.state.value.downloads}, notifications=$visible", error)
        } finally {
            controller.setDownloadsPaused(true).join()
            controller.pause().join()
            controller.setQueueRepeat(RepeatMode.OFF).join()
            NavidromeConnection(app.store, http, AndroidCredentialVault(context)).disconnect()
            controller.updatePreferences { original }.join()
            server.close(); scenario.close(); audio.delete(); http.close()
        }
    }
}

private class HeldAudioServer(private val audio: ByteArray) : AutoCloseable {
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val release = CountDownLatch(1)
    val address = "http://127.0.0.1:${server.localPort}"

    init { executor.execute {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: java.io.IOException) { break }
            sockets.add(socket)
            executor.execute {
                try { socket.use { handle(it) } }
                catch (_: java.io.IOException) { /* A cancelled request closes its connection. */ }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                finally { sockets.remove(socket) }
            }
        }
    } }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val request = input.readLine() ?: return
        val uri = URI.create(request.split(' ')[1])
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        val output = socket.getOutputStream()
        if (uri.path.endsWith("download.view")) {
            val offset = headers["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            val range = if (offset > 0) "Content-Range: bytes $offset-${audio.lastIndex}/${audio.size}\r\n" else ""
            output.write(("HTTP/1.1 ${if (offset > 0) "206 Partial Content" else "200 OK"}\r\n" +
                "Content-Type: audio/flac\r\nContent-Length: ${audio.size - offset}\r\n${range}Connection: close\r\n\r\n").toByteArray())
            // Retried range requests must stop at the same absolute byte boundary.
            val first = (8192 - offset).coerceIn(0, audio.size - offset)
            output.write(audio, offset, first); output.flush()
            release.await()
            output.write(audio, offset + first, audio.size - offset - first); output.flush()
            return
        }
        val song = "{\"id\":\"download\",\"title\":\"Notification download\",\"suffix\":\"flac\",\"size\":${audio.size}}"
        val body = when {
            uri.path.endsWith("ping.view") -> "\"type\":\"fixture\""
            uri.path.endsWith("getSong.view") -> "\"song\":$song"
            uri.path.endsWith("search3.view") -> if (uri.query.contains("songOffset=0")) "\"searchResult3\":{\"song\":[$song]}"
                else "\"searchResult3\":{}"
            uri.path.endsWith("getPlaylists.view") -> "\"playlists\":{}"
            else -> error("Unexpected fixture endpoint ${uri.path}")
        }
        val json = "{\"subsonic-response\":{\"status\":\"ok\",$body}}".toByteArray()
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${json.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(json); output.flush()
    }

    override fun close() {
        release.countDown(); server.close(); sockets.forEach { it.close() }; executor.shutdownNow()
    }
}
