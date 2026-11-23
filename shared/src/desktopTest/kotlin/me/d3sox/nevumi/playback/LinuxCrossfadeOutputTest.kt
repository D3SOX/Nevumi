package me.d3sox.nevumi.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sound.sampled.*
import kotlin.math.*
import kotlin.test.*

class LinuxCrossfadeOutputTest {
    @Test
    fun crossfadeMixesBothTracksAndSurvivesPauseAndGaplessChanges() = withNullSink { directory, sink ->
        val sources = listOf(440, 660).mapIndexed { index, frequency ->
            val source = directory.resolve("tone-$index.wav")
            writeTone(source, frequency, 3_000) { 1.0 }
            PlayableTrack("tone-$index", source.toString(), entryId = if (index == 0) "first" else "second")
        }
        suspend fun render(name: String, crossfadeMs: Long, pauseDuringFade: Boolean): FloatArray {
            val engine = CrossfadePlaybackEngine { MpvPlaybackEngine("pulse", audioDevice = "pulse/$sink") }
            return capture(directory, sink, name, engine) {
                engine.setCrossfadeDuration(crossfadeMs)
                engine.loadQueue(sources)
                if (pauseDuringFade) {
                    val middle = withTimeout(5_000) { engine.status.first {
                        it.entryId == "second" && it.fadingOut?.entryId == "first" && it.positionMs >= 250 && it.playing
                    } }
                    assertEquals(1.0, middle.volume, "The volume control followed the fade instead of the user's volume")
                    assertTrue(requireNotNull(middle.fadingOut).playing)
                    assertNotEquals(middle.playbackId, middle.fadingOut?.playbackId)
                    engine.pause()
                    withTimeout(2_000) { engine.status.first { !it.playing && it.fadingOut?.playing == false } }
                    engine.setGaplessPlayback(false)
                    engine.setAudioEffects(AudioEffects())
                    delay(100) // Allow the native position poll to observe the completed pause.
                    val paused = engine.status.value
                    assertNotNull(paused.fadingOut, "Changing gapless while paused cancelled the crossfade")
                    delay(250)
                    assertEquals(paused.positionMs.toDouble(), engine.status.value.positionMs.toDouble(), 60.0)
                    assertEquals(requireNotNull(paused.fadingOut).positionMs.toDouble(), requireNotNull(engine.status.value.fadingOut).positionMs.toDouble(), 60.0)
                    engine.play()
                    engine.setGaplessPlayback(true)
                    engine.setVolume(0.8)
                    // Each player polls separately, so the outgoing track may report resuming one poll later.
                    val resumed = withTimeout(2_000) { engine.status.first {
                        it.playing && it.volume == 0.8 && it.fadingOut?.playing != false
                    } }
                    assertTrue(resumed.fadingOut?.playing == true, "Changing gapless interrupted the audible crossfade")
                    engine.setVolume(1.0)
                }
                withTimeout(10_000) { engine.status.first { it.entryId == "second" && it.ended } }
            }
        }
        val gapless = render("gapless", 0, false)
        val faded = render("faded", 1_000, true)
        fun duration(samples: FloatArray): Double {
            val first = samples.indexOfFirst { abs(it) > 0.0001 }
            val last = samples.indexOfLast { abs(it) > 0.0001 }
            assertTrue(first >= 0 && last > first)
            return (last - first) / 48_000.0
        }
        assertEquals(6.0, duration(gapless), 0.2)
        assertEquals(5.35, duration(faded), 0.3)
        val windows = (0 until faded.size - 9_600 step 4_800).map { start ->
            amplitude(faded, start, 440) to amplitude(faded, start, 660)
        }
        assertTrue(windows.any { it.first > 0.025 && it.second > 0.025 }, "The two tracks never overlapped")
        assertTrue(windows.any { it.first > 0.05 && it.second < 0.005 })
        assertTrue(windows.any { it.second > 0.05 && it.first < 0.005 })
        val first = faded.indexOfFirst { abs(it) > 0.0001 }
        val last = faded.indexOfLast { abs(it) > 0.0001 }
        val quietWindows = (first until last - 960 step 960).count { start ->
            (start until start + 960).all { abs(faded[it]) < 0.00001 }
        }
        assertTrue(quietWindows >= 5, "Pausing left audible audio in the mix")
    }

    @Test
    fun crossfadeSkipsTheTrailingAndLeadingSilenceBetweenAnalyzedSongs() = withNullSink { directory, sink ->
        // The first song falls silent halfway through and the second starts after a second of silence.
        val first = directory.resolve("ending.wav").also { writeTone(it, 440, 3_000) { ms -> if (ms < 1_500) 1.0 else 0.0 } }
        val second = directory.resolve("opening.wav").also { writeTone(it, 660, 3_000) { ms -> if (ms < 1_000) 0.0 else 1.0 } }
        val tracks = listOf(first, second).mapIndexed { index, file ->
            PlayableTrack("song-$index", file.toString(), entryId = if (index == 0) "first" else "second", sourceId = "local:$index")
        }
        val waveforms = FfmpegAudioWaveforms(directory.resolve("cache").toString())
        fun source(track: PlayableTrack) = me.d3sox.nevumi.library.TrackSource(requireNotNull(track.sourceId),
            me.d3sox.nevumi.library.SourceKind.LOCAL, track.location)
        tracks.forEach { assertEquals(128, waveforms.load(source(it), 3_000).size) }
        suspend fun render(name: String, analyzed: Boolean): FloatArray {
            val engine = CrossfadePlaybackEngine({ track -> if (analyzed) waveforms.soundEdges(source(track)) else null }) {
                MpvPlaybackEngine("pulse", audioDevice = "pulse/$sink")
            }
            return capture(directory, sink, name, engine) {
                engine.setCrossfadeDuration(1_000)
                engine.loadQueue(tracks)
                val switched = withTimeout(5_000) { engine.status.first { it.entryId == "second" } }
                if (analyzed) {
                    assertEquals(1_000.0, switched.positionMs.toDouble(), 150.0, "The incoming song must start at its first sound")
                    // The outgoing song's 1.5 s of sound limit the fade to 750 ms, which ends where that sound ends.
                    assertEquals(750.0, requireNotNull(switched.fadingOut).positionMs.toDouble(), 150.0,
                        "The fade must fit the outgoing song's sound and end where it falls silent")
                }
                withTimeout(10_000) { engine.status.first { it.entryId == "second" && it.ended } }
            }
        }
        val plain = render("plain", analyzed = false)
        val analyzed = render("analyzed", analyzed = true)
        fun sound(samples: FloatArray) = samples.indexOfFirst { abs(it) > 0.0001 } to samples.indexOfLast { abs(it) > 0.0001 }
        /** The longest stretch of 10 ms windows without sound between the first and the last sound. */
        fun longestGap(samples: FloatArray): Double {
            val (start, end) = sound(samples)
            var longest = 0
            var run = 0
            for (window in start until end - 480 step 480) {
                if ((window until window + 480).all { abs(samples[it]) < 0.0001 }) longest = maxOf(longest, ++run) else run = 0
            }
            return longest * 0.01
        }
        // Printed for comparing transitions by ear or in a chart: the RMS level of each 50 ms of output in dBFS.
        for ((name, samples) in listOf("plain" to plain, "analyzed" to analyzed)) {
            val (start, end) = sound(samples)
            println("Crossfade output $name: " + (start until end step 2_400).joinToString(",") { window ->
                val rms = sqrt((window until minOf(window + 2_400, samples.size)).sumOf { samples[it].toDouble().pow(2) } / 2_400)
                "%.1f".format(20 * log10(rms.coerceAtLeast(1e-6)))
            })
        }
        assertTrue(longestGap(plain) > 1.2, "Without analysis the trailing and leading silence stay: ${longestGap(plain)}s")
        assertTrue(longestGap(analyzed) < 0.1, "The analyzed transition must not fall silent: ${longestGap(analyzed)}s")
        val (start, end) = sound(analyzed)
        assertEquals(2.75, (end - start) / 48_000.0, 0.3, "Both songs' sound must overlap during the fade")
        assertTrue((start until end - 9_600 step 4_800).any { amplitude(analyzed, it, 440) > 0.02 && amplitude(analyzed, it, 660) > 0.02 },
            "The two songs never overlapped")
    }

    /** Plays into a private null sink so the captured output contains only this test's audio. */
    private fun withNullSink(test: suspend (java.nio.file.Path, String) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("nevumi-crossfade-output")
        val sink = "nevumi_test_${UUID.randomUUID().toString().replace("-", "") }"
        var module: String? = null
        val previousSink = command("pactl", "get-default-sink").trim()
        try {
            module = command("pactl", "load-module", "module-null-sink", "sink_name=$sink", "rate=48000", "channels=2",
                "sink_properties=\"device.description=Nevumi_test priority.session=0\"").trim()
            if (command("pactl", "get-default-sink").trim() == sink) command("pactl", "set-default-sink", previousSink)
            test(directory, sink)
        } finally {
            if (command("pactl", "get-default-sink").trim() == sink) command("pactl", "set-default-sink", previousSink)
            module?.let { command("pactl", "unload-module", it) }
            directory.toFile().deleteRecursively()
        }
    }

    /** Records the sink while [play] runs the engine, and returns the left channel. */
    private suspend fun capture(directory: java.nio.file.Path, sink: String, name: String, engine: CrossfadePlaybackEngine,
        play: suspend () -> Unit): FloatArray {
        val output = directory.resolve("$name.pcm")
        val capture = ProcessBuilder("parec", "--device=$sink.monitor", "--format=float32le", "--rate=48000", "--channels=2",
            "--latency-msec=20").redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            withTimeout(3_000) { while (Files.size(output) < 48_000) delay(25) }
            play()
            delay(600)
        } finally {
            engine.close()
            capture.destroy()
            if (!capture.waitFor(2, TimeUnit.SECONDS)) { capture.destroyForcibly(); capture.waitFor(2, TimeUnit.SECONDS) }
        }
        val bytes = Files.readAllBytes(output)
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 8) { samples.float.also { samples.float } }
    }

    /** A 48 kHz mono tone whose loudness follows [gain] over the milliseconds of the file. */
    private fun writeTone(file: java.nio.file.Path, frequency: Int, durationMs: Int, gain: (Int) -> Double) {
        val frames = 48 * durationMs
        val pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { pcm.putShort((sin(2 * PI * frequency * it / 48_000) * 6_000 * gain(it / 48)).toInt().toShort()) }
        AudioInputStream(ByteArrayInputStream(pcm.array()), AudioFormat(48_000f, 16, 1, true, false), frames.toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file.toFile())
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

    private fun amplitude(samples: FloatArray, start: Int, frequency: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (frame in start until start + 9_600) {
            val angle = 2 * PI * frequency * frame / 48_000
            real += samples[frame] * cos(angle)
            imaginary += samples[frame] * sin(angle)
        }
        return 2 * hypot(real, imaginary) / 9_600
    }
}
