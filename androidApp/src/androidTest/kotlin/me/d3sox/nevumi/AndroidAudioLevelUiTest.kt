package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.ParcelFileDescriptor
import me.d3sox.nevumi.artwork.ArtworkLibrary
import kotlin.math.PI
import kotlin.math.sin

class AndroidAudioLevelUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun thePlayingCoverFollowsRealAudioAndReturnsToItsNormalSizeWhenPaused() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val file = File(app.cacheDir, "real-beats.wav")
        val frames = 8_000 * 10
        val pcm = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        repeat(frames) { sample -> pcm.putShort((sin(2 * PI * 90 * sample / 8_000) *
            (if (sample < 40_000) .1 else .8) * 32_767).toInt().toShort()) }
        file.writeBytes(pcm.array())
        try {
            val track = runBlocking {
                app.controller.library.importTrack(TrackMetadata("Actual beats", artist = "Audio fixture", durationMs = 10_000),
                    TrackSource("beats", SourceKind.LOCAL, file.toURI().toString()))
            }
            runBlocking {
                ArtworkLibrary(File(app.filesDir, "artwork").path).save(track.id,
                    InstrumentationRegistry.getInstrumentation().context.assets.open("cover.png").use { it.readBytes() })
                app.controller.reload().join()
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.playTrack(track.id, listOf(track.id)).join() }
                compose.onNodeWithTag("mini-player").performClick()
                // Sample inside the waiter: slow CI emulators can otherwise read the level after the loud section starts.
                var quiet = 0f
                compose.waitUntil(10_000) {
                    val level = app.controller.engine.audioLevel.value
                    val position = app.controller.state.value.playback.positionMs
                    (position in 1_000..4_000 && level > 0f).also { if (it) quiet = level }
                }
                assertTrue("Decoded quiet audio must have energy: $quiet", quiet in .04f.. .14f)
                val quietWidth = compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot.width
                shell("dumpsys gfxinfo ${app.packageName} reset")
                compose.waitUntil(10_000) {
                    app.controller.state.value.playback.positionMs >= 6_000 && app.controller.engine.audioLevel.value > quiet * 4
                }
                val loud = app.controller.engine.audioLevel.value
                assertTrue("Energy must follow the audio: quiet=$quiet loud=$loud", loud > quiet * 4)
                compose.waitUntil(3_000) {
                    compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot.width > quietWidth + 8f
                }
                File(app.filesDir, "native-test-output/pulse-framestats.txt").apply {
                    parentFile?.mkdirs(); writeText(shell("dumpsys gfxinfo ${app.packageName} framestats"))
                }
                runBlocking { app.controller.pause().join() }
                compose.waitUntil(3_000) { app.controller.engine.audioLevel.value == 0f }
                compose.waitUntil(3_000) {
                    compose.onNodeWithTag("player-artwork").fetchSemanticsNode().boundsInRoot.width < quietWidth
                }
            }
        } finally { runBlocking { app.controller.pause().join() }; file.delete() }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
}
