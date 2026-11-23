package me.d3sox.nevumi

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.playback.AndroidPlaybackEngine
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSleepTimerTest {
    @Test fun endOfSongPausesARepeatingSongAtItsEndAndWaitsAtTheNextSong() = verifyEndOfSong(crossfadeMs = 0)

    @Test fun endOfSongDoesNotCrossfadeIntoTheNextSong() = verifyEndOfSong(crossfadeMs = 2_000)

    @Test fun mediaControlsCanSkipWhileWaitingForTheSongToEnd() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "sleep-skip-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use(input::copyTo) }
        val tracks = (1..3).map { app.controller.library.importTrack(TrackMetadata("Sleep skip $it"),
            TrackSource("sleep-skip-$it-${UUID.randomUUID()}", SourceKind.LOCAL, audio.path)) }
        var controls: androidx.media3.session.MediaController? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                controller.refresh()
                controller.playTrack(tracks[0].id, tracks.map { it.id }).join()
                withTimeout(10_000) { controller.state.first { it.playback.playing && it.playback.durationMs > 0 } }
                controller.setSleepAfterSong().join()
                lateinit var connection: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>
                instrumentation.runOnMainSync { connection = androidx.media3.session.MediaController.Builder(app,
                    androidx.media3.session.SessionToken(app, android.content.ComponentName(app, PlaybackService::class.java))).buildAsync() }
                val session = connection.get(10, java.util.concurrent.TimeUnit.SECONDS).also { controls = it }
                // A headset, the notification or a car sends Next to the session, which still holds the whole queue.
                withTimeout(5_000) {
                    var count = 0
                    while (count != 3) { instrumentation.runOnMainSync { count = session.mediaItemCount }; kotlinx.coroutines.delay(10) }
                }
                instrumentation.runOnMainSync { session.seekToNext() }
                withTimeout(10_000) { controller.state.first { it.playback.trackId == tracks[1].id && it.playback.playing && it.playback.durationMs > 0 } }
                assertTrue(controller.state.value.sleepAfterSong)
                controller.seek(2_000).join()
                val waiting = withTimeout(10_000) { controller.state.first {
                    !it.sleepAfterSong && it.playback.trackId == tracks[2].id && it.playback.durationMs > 0
                } }
                assertEquals(false, waiting.playback.playWhenReady)
            }
        } finally {
            app.controller.setSleepTimer(null).join()
            app.controller.pause().join()
            instrumentation.runOnMainSync { controls?.release() }
            audio.delete()
        }
    }

    private fun verifyEndOfSong(crossfadeMs: Long) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "sleep-timer-${UUID.randomUUID()}.db"
        val file = File(context.cacheDir, "sleep-timer-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use(input::copyTo) }
        val store = openMusicStore(context, name)
        val library = MusicLibrary(store)
        val first = library.importTrack(TrackMetadata("First"), TrackSource("sleep-first", SourceKind.LOCAL, file.toURI().toString()))
        val next = library.importTrack(TrackMetadata("Next"), TrackSource("sleep-next", SourceKind.LOCAL, file.toURI().toString()))
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = MusicController(library, PlaybackQueue(store), AndroidPlaybackEngine(context, PlaybackService::class.java))
                try {
                    withTimeout(10_000) { controller.state.first { !it.loading } }
                    controller.engine.setCrossfadeDuration(crossfadeMs)
                    controller.playTrack(first.id, listOf(first.id, next.id)).join()
                    controller.setQueueRepeat(RepeatMode.ONE).join()
                    withTimeout(10_000) { controller.state.first { it.playback.playing && it.playback.durationMs == 3_000L } }
                    controller.setSleepAfterSong().join()
                    controller.seek(2_000).join()
                    val waiting = withTimeout(10_000) { controller.state.first {
                        !it.sleepAfterSong && it.playback.trackId == next.id && it.playback.durationMs > 0
                    } }
                    assertEquals(false, waiting.playback.playWhenReady)
                    assertEquals(next.id, waiting.queue.currentTrackId)
                    assertTrue("Waits at the start: ${waiting.playback.positionMs}", waiting.playback.positionMs < 500)
                    assertEquals(RepeatMode.ONE, waiting.queue.repeatMode)
                    val repeating = withTimeout(5_000) { controller.engine.status.first { it.repeatMode == RepeatMode.ONE } }
                    assertEquals(false, repeating.playing)
                } finally { controller.engine.setCrossfadeDuration(0); controller.closeAndJoin() }
            }
        } finally { store.close(); context.deleteDatabase(name); file.delete() }
    }
}
