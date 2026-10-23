package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.test.*

class LinuxReplayGainOutputTest {
    @Test
    fun replayGainTagsChangeTheRenderedLevelWithoutClippingTheStoredPeak() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-replaygain-output")
        try {
            fun tone(name: String, vararg tags: Pair<String, String>): Path = directory.resolve(name).also { file ->
                val arguments = listOf("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                    "-af", "volume=0.25", "-ac", "2") + tags.flatMap { (key, value) -> listOf("-metadata", "$key=$value") } + file.toString()
                val encoder = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                assertTrue(encoder.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, encoder.exitValue())
            }
            val untagged = tone("untagged.flac")
            val tagged = tone("tagged.flac", "REPLAYGAIN_TRACK_GAIN" to "-6.00 dB", "REPLAYGAIN_TRACK_PEAK" to "0.25",
                "REPLAYGAIN_ALBUM_GAIN" to "-12.00 dB", "REPLAYGAIN_ALBUM_PEAK" to "0.25")
            val loud = tone("loud.flac", "REPLAYGAIN_TRACK_GAIN" to "+20.00 dB", "REPLAYGAIN_TRACK_PEAK" to "0.25")
            suspend fun render(name: String, source: Path, replayGain: ReplayGain): Double {
                val output = directory.resolve("$name.wav")
                val engine = MpvPlaybackEngine("pcm", pcmOutputFile = output.toString())
                try {
                    engine.setReplayGain(replayGain)
                    engine.load(PlayableTrack(name, source.toString()))
                    withTimeout(5_000) { engine.status.first { it.ended } }
                } finally { engine.close() }
                val decoder = ProcessBuilder("ffmpeg", "-v", "error", "-i", output.toString(), "-ac", "2", "-ar", "48000",
                    "-f", "f32le", "pipe:1").redirectError(ProcessBuilder.Redirect.INHERIT).start()
                try {
                    val bytes = decoder.inputStream.readAllBytes()
                    assertTrue(decoder.waitFor(5, TimeUnit.SECONDS))
                    assertEquals(0, decoder.exitValue())
                    val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    return amplitude(FloatArray(bytes.size / 4) { samples.float })
                } finally { decoder.destroyForcibly() }
            }
            val off = render("off", tagged, ReplayGain())
            assertEquals(10.0.pow(-6.0 / 20), render("track", tagged, ReplayGain(ReplayGainMode.TRACK)) / off, 0.02)
            assertEquals(10.0.pow(-12.0 / 20), render("album", tagged, ReplayGain(ReplayGainMode.ALBUM)) / off, 0.02)
            assertEquals(10.0.pow(-3.0 / 20), render("preamp", tagged, ReplayGain(ReplayGainMode.TRACK, preampDb = 3.0)) / off, 0.02)
            assertEquals(1.0, render("untagged", untagged, ReplayGain(ReplayGainMode.TRACK, preampDb = 6.0)) / off, 0.02)
            assertEquals(4.0, render("limited", loud, ReplayGain(ReplayGainMode.TRACK)) / off, 0.08)
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun amplitude(samples: FloatArray): Double {
        assertTrue(samples.size >= 144_000, "Not enough captured audio")
        var real = 0.0
        var imaginary = 0.0
        for (frame in 24_000 until 72_000) {
            val phase = 2 * PI * 440 * frame / 48_000
            real += samples[frame * 2] * cos(phase)
            imaginary += samples[frame * 2] * sin(phase)
        }
        return 2 * hypot(real, imaginary) / 48_000
    }
}
