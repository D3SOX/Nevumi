package me.d3sox.nevumi

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.d3sox.nevumi.playback.AndroidPlaybackEngine
import me.d3sox.nevumi.playback.PlayableTrack
import me.d3sox.nevumi.playback.RepeatMode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AndroidCrossfadeSessionTest {
    @Test
    fun theMediaSessionReportsBothAudibleTracksAndPausesThemTogether() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "session-crossfade-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                (context.applicationContext as MusicApplication).controller.reload().join()
                val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
                try {
                    engine.setRepeat(RepeatMode.OFF)
                    engine.setCrossfadeDuration(1_000)
                    withTimeout(10_000) { engine.status.first { it.crossfadeDurationMs == 1_000L } }
                    engine.loadQueue(listOf(PlayableTrack("song", file.toURI().toString(), entryId = "first"),
                        PlayableTrack("song", file.toURI().toString(), entryId = "next")), positionMs = 1_000)
                    val overlap = try { withTimeout(12_000) { engine.status.first {
                        it.entryId == "next" && it.playing && it.positionMs >= 200 && it.fadingOut?.playing == true
                    } } } catch (failure: Exception) {
                        throw AssertionError("Crossfade session did not overlap: ${engine.status.value}", failure)
                    }
                    assertEquals(1_000L, overlap.crossfadeDurationMs)
                    assertNotNull(overlap.playbackId)
                    assertNotNull(overlap.fadingOut?.playbackId)
                    assertNotEquals(overlap.playbackId, overlap.fadingOut?.playbackId)
                    engine.pause()
                    withTimeout(5_000) { engine.status.first { !it.playing && it.fadingOut?.playing == false } }
                    // pause() reports paused immediately; the service can need longer to stop both decoders on a busy emulator.
                    try { withTimeout(5_000) { while (true) {
                        val paused = engine.status.value
                        delay(200)
                        val later = engine.status.value
                        if (abs(later.positionMs - paused.positionMs) <= 75 &&
                            abs(requireNotNull(later.fadingOut).positionMs - requireNotNull(paused.fadingOut).positionMs) <= 75) break
                    } } } catch (failure: TimeoutCancellationException) {
                        throw AssertionError("Paused crossfade kept advancing: ${engine.status.value}", failure)
                    }
                    engine.play()
                    withTimeout(6_000) { engine.status.first { it.entryId == "next" && it.ended } }
                    assertNull(engine.status.value.fadingOut)
                } finally { engine.setCrossfadeDuration(0); engine.pause(); engine.close() }
            }
        } finally { file.delete() }
    }
}
