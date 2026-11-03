package me.d3sox.nevumi

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.playback.AndroidPlaybackEngine
import me.d3sox.nevumi.playback.RepeatMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidExternalControlsTest {
    @Test
    fun repeatAndSpeedFromAnotherControllerStayAfterTheNextSong() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val player = app.controller
        val audio = File(app.filesDir, "external-controls.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(10_000) { player.state.first { it.started } }
                val songs = (1..3).map { number ->
                    player.library.importTrack(TrackMetadata("External $number", durationMs = 3_000),
                        TrackSource("external-$number", SourceKind.LOCAL, audio.path))
                }
                player.reload().join()
                player.playTrack(songs[0].id, songs.map { it.id }).join()
                withTimeout(10_000) { player.state.first { it.playback.trackId == songs[0].id && it.playback.playing } }
                // A watch or headset is another media controller, so it reaches the session without the app's controller.
                val headset = AndroidPlaybackEngine(instrumentation.targetContext, PlaybackService::class.java)
                try {
                    headset.setRepeat(RepeatMode.ALL)
                    headset.setSpeed(1.5)
                    withTimeout(10_000) { player.state.first { it.queue.repeatMode == RepeatMode.ALL && it.preferences.speed == 1.5 } }
                    player.skip(1).join()
                    withTimeout(10_000) { player.state.first { it.playback.trackId == songs[1].id } }
                    // Loading the next song applies the queue's repeat mode, which must not undo the headset's choice.
                    assertEquals(RepeatMode.ALL, withTimeout(10_000) { player.engine.status.first { it.trackId == songs[1].id } }.repeatMode)
                    assertEquals(RepeatMode.ALL, player.state.value.queue.repeatMode)
                    assertEquals(1.5, player.state.value.preferences.speed, 0.0)
                } finally {
                    headset.close()
                    player.setQueueRepeat(RepeatMode.OFF).join()
                    player.setSpeed(1.0).join()
                    player.pause().join()
                }
            }
        } finally { audio.delete() }
    }
}
