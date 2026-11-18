package me.d3sox.nevumi.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class LinuxPlaybackSettingsTest {
    private val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()

    /** Writes a mono 8 kHz WAV of tones and silences, each given in seconds; 0 Hz is silence. */
    private fun wave(path: Path, vararg parts: Pair<Int, Double>): String {
        val frames = parts.sumOf { (it.second * 8_000).toInt() }
        val wave = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        wave.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(frames * 2)
        for ((frequency, seconds) in parts) repeat((seconds * 8_000).toInt()) {
            wave.putShort((sin(2 * PI * frequency * it / 8_000) * 8_000).toInt().toShort())
        }
        Files.write(path, wave.array())
        return path.toString()
    }

    @Test fun skippingSilenceKeepsPositionsInTheFile() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-skip-silence")
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null") }
        try {
            val song = wave(directory.resolve("gap.wav"), 0 to 1.0, 440 to 1.0, 0 to 4.0, 660 to 1.0)
            engine.setSkipSilence(true)
            val started = System.nanoTime()
            engine.loadQueue(listOf(PlayableTrack("gap", song)))
            val positions = mutableListOf<Long>()
            val ended = withTimeout(8_000) { engine.status.onEach { if (it.playing) positions += it.positionMs }.first { it.ended } }
            val seconds = (System.nanoTime() - started) / 1e9
            // Seven seconds of audio, five of them silent, play in about two.
            assertTrue(seconds < 4.0, "Playback took $seconds seconds")
            assertEquals(7_000.0, ended.durationMs.toDouble(), 50.0)
            // The position jumps over the removed silence instead of lagging behind the file, which the waveform and seeking use.
            assertTrue(positions.any { it in 6_100..7_000 }, "Positions: $positions")
            assertTrue(positions.zipWithNext().all { (a, b) -> b >= a }, "Positions went backwards: $positions")
            // Seeking still reaches positions in the file.
            engine.seek(6_200)
            engine.play()
            val sought = withTimeout(3_000) { engine.status.first { it.playing } }
            assertTrue(sought.positionMs in 6_150..6_700, "Sought to ${sought.positionMs}")
            withTimeout(3_000) { engine.status.first { it.ended } }
            // Turning it off plays silences again.
            engine.setSkipSilence(false)
            engine.seek(2_000)
            engine.play()
            withTimeout(3_000) { engine.status.first { it.playing && it.positionMs >= 2_000 } }
            kotlinx.coroutines.delay(1_500)
            assertTrue(engine.status.value.positionMs in 3_000..4_000, "Silence was skipped with the setting off: ${engine.status.value.positionMs}")
        } finally { engine.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun resumingTheSongACrossfadeLeadsIntoKeepsTheCrossfade() = runBlocking {
        val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("null") }
        try {
            val tracks = (1..2).map { PlayableTrack("song-$it", audio, entryId = "entry-$it") }
            engine.setCrossfadeDuration(1_000)
            engine.loadQueue(tracks, positionMs = 1_900)
            withTimeout(3_000) { engine.status.first { it.entryId == "entry-2" && it.playing && it.fadingOut != null } }
            engine.resume("entry-2", 1_200)
            val resumed = withTimeout(2_000) { engine.status.first { it.entryId == "entry-2" && it.positionMs >= 1_200 } }
            assertNotNull(resumed.fadingOut, "Resuming stopped the crossfade")
            assertEquals(true, resumed.fadingOut?.playing)
            // A resume for a song that is no longer current is dropped.
            engine.resume("entry-1", 100)
            kotlinx.coroutines.delay(200)
            assertTrue(engine.status.value.positionMs >= 1_200)
        } finally { engine.close() }
    }

    @Test fun theFadeIsAudibleInTheOutput() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-play-pause-fade")
        val sink = "nevumi_test_${UUID.randomUUID().toString().replace("-", "")}"
        val previousSink = command("pactl", "get-default-sink").trim()
        var module: String? = null
        try {
            module = command("pactl", "load-module", "module-null-sink", "sink_name=$sink", "rate=48000", "channels=2",
                "sink_properties=\"device.description=Nevumi_test priority.session=0\"").trim()
            if (command("pactl", "get-default-sink").trim() == sink) command("pactl", "set-default-sink", previousSink)
            val song = PlayableTrack("tone", wave(directory.resolve("tone.wav"), 440 to 6.0))
            /**
             * Plays twice and pauses after each, then counts, at the start and at the end of each stretch of sound, the 10 ms
             * windows that are neither silent nor at the tone's full level: the fades.
             */
            suspend fun rampWindows(fade: Boolean): List<Pair<Int, Int>> {
                val output = directory.resolve("$fade.pcm")
                val capture = ProcessBuilder("parec", "--device=$sink.monitor", "--format=float32le", "--rate=48000", "--channels=1",
                    "--latency-msec=10").redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                val engine = try { CrossfadePlaybackEngine { MpvPlaybackEngine("pulse", audioDevice = "pulse/$sink") } }
                catch (failure: Throwable) { capture.destroyForcibly(); capture.waitFor(2, TimeUnit.SECONDS); throw failure }
                try {
                    withTimeout(3_000) { while (Files.size(output) < 48_000) delay(25) }
                    engine.setPlayPauseFade(fade)
                    engine.loadQueue(listOf(song))
                    withTimeout(3_000) { engine.status.first { it.playing } }
                    delay(1_000)
                    engine.pause()
                    delay(700)
                    engine.play()
                    delay(1_000)
                    engine.pause()
                    delay(700)
                } finally {
                    engine.close()
                    capture.destroy()
                    if (!capture.waitFor(2, TimeUnit.SECONDS)) { capture.destroyForcibly(); capture.waitFor(2, TimeUnit.SECONDS) }
                }
                val bytes = Files.readAllBytes(output)
                val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val levels = (0 until samples.limit() / 480).map { window ->
                    sqrt((0 until 480).sumOf { val sample = samples.get(window * 480 + it).toDouble(); sample * sample } / 480)
                }
                val full = levels.max()
                assertTrue(full > 0.05, "The tone was not captured")
                // Stretches of sound, split where at least 100 ms stay silent.
                val sounding = levels.indices.filter { levels[it] > full * 0.02 }
                val stretches = sounding.fold(mutableListOf<MutableList<Int>>()) { runs, window ->
                    if (runs.isEmpty() || window - runs.last().last() > 10) runs += mutableListOf(window) else runs.last() += window
                    runs
                }.filter { it.size > 50 }
                assertEquals(2, stretches.size, "Expected two stretches of playback")
                fun ramp(windows: List<Int>) = windows.count { levels[it] > full * 0.1 && levels[it] < full * 0.85 }
                return stretches.map { ramp(it.take(40)) to ramp(it.takeLast(40)) }
            }
            // Each start and each pause fades over 250 ms, about 25 windows, of which mpv's volume steps leave some at full level.
            val faded = rampWindows(true)
            faded.forEachIndexed { index, (start, pause) ->
                assertTrue(start >= 6, "Start ${index + 1} faded in only $start windows: $faded")
                assertTrue(pause >= 6, "Pause ${index + 1} faded out only $pause windows: $faded")
            }
            val plain = rampWindows(false)
            assertTrue(plain.all { (start, pause) -> start <= 2 && pause <= 2 }, "Playback without the fade still ramped: $plain")
        } finally {
            // Each step runs even when an earlier one fails, so the test never leaves its sink behind.
            runCatching { if (command("pactl", "get-default-sink").trim() == sink) command("pactl", "set-default-sink", previousSink) }
            runCatching { module?.let { command("pactl", "unload-module", it) } }
            directory.toFile().deleteRecursively()
        }
    }

    private fun command(vararg arguments: String): String {
        val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Audio fixture command timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            return output
        } finally { process.destroyForcibly() }
    }
}
