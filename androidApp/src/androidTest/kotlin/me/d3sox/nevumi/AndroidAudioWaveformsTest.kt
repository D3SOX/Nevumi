package me.d3sox.nevumi

import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.playback.AndroidAudioWaveforms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import android.content.ContentValues
import android.provider.MediaStore
import me.d3sox.nevumi.playback.PreparedAudioWaveforms
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

class AndroidAudioWaveformsTest {
    @Test fun mediaStoreMp3WaveformsPreemptBackgroundWorkAndStayCachedWhenReturningToTheSong() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "nevumi-waveform-${UUID.randomUUID()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/NevumiWaveformTests")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values))
        val longFile = File(context.cacheDir, "background-${UUID.randomUUID()}.flac")
        val prepared = PreparedAudioWaveforms(AndroidAudioWaveforms(context), idleDelayMs = 20)
        try {
            val arguments = InstrumentationRegistry.getArguments()
            val external = arguments.getString("waveformFile")
            val input = if (external == null) instrumentation.context.assets.open("waveform-four-minutes.mp3") else File(external).inputStream()
            input.use { audio -> requireNotNull(context.contentResolver.openOutputStream(uri)).use { audio.copyTo(it) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            instrumentation.context.assets.open("waveform-long.flac").use { audio -> longFile.outputStream().use { audio.copyTo(it) } }
            prepared.prepare(listOf(TrackSource("background:${longFile.name}", SourceKind.LOCAL, longFile.toURI().toString()) to 600_000L), true)
            withTimeout(2_000) { prepared.preparation.first { it.running } }
            delay(50)
            val source = TrackSource("foreground:$uri", SourceKind.LOCAL, uri.toString())
            val duration = arguments.getString("waveformDuration")?.toLong() ?: 240_000L
            val start = System.nanoTime()
            val waveform = withTimeout(4_000) { prepared.load(source, duration) }
            val elapsed = (System.nanoTime() - start) / 1_000_000
            println("MediaStore stereo MP3 during preparation: ${elapsed}ms")
            assertEquals(128, waveform.size)
            assertTrue("Foreground waveform was blocked by preparation: ${elapsed}ms", elapsed < 3_000)
            repeat(3) {
                val cachedStart = System.nanoTime()
                assertEquals(waveform, withTimeout(100) { prepared.load(source, duration) })
                println("Returning to the MP3 waveform: ${(System.nanoTime() - cachedStart) / 1_000_000}ms")
            }
        } finally { prepared.close(); context.contentResolver.delete(uri, null, null); longFile.delete() }
    }

    @Test fun longTracksProduceAWaveformWithoutWaitingForTheWholeSongToPlay() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "long-waveform-${UUID.randomUUID()}.flac")
        try {
            instrumentation.context.assets.open("waveform-long.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
            val source = TrackSource("long:${file.name}", SourceKind.LOCAL, file.toURI().toString(),
                modifiedAt = file.lastModified(), indexedSize = file.length())
            val start = System.nanoTime()
            // Allow shared-emulator scheduling delays while still catching per-buffer waits.
            val peaks = withTimeout(10_000) { AndroidAudioWaveforms(context).load(source, 600_000) }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("Ten-minute FLAC waveform: ${elapsedMs}ms")
            assertEquals(128, peaks.size)
            val cachedStart = System.nanoTime()
            assertEquals(peaks, AndroidAudioWaveforms(context).load(source, 600_000))
            val cachedMs = (System.nanoTime() - cachedStart) / 1_000_000
            println("Ten-minute FLAC waveform after reopening cache: ${cachedMs}ms")
            assertTrue("Prepared waveforms must load immediately: ${cachedMs}ms", cachedMs < 100)
        } finally { file.delete() }
    }

    @Test fun commonFormatsPreserveTheAudibleQuietAndLoudSections() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val reader = AndroidAudioWaveforms(context)
        for (format in listOf("wav", "flac", "mp3")) {
            val file = File(context.cacheDir, "waveform-${UUID.randomUUID()}.$format")
            try {
                instrumentation.context.assets.open("waveform.$format").use { input -> file.outputStream().use { input.copyTo(it) } }
                val peaks = reader.load(TrackSource("local:${file.name}", SourceKind.LOCAL, file.toURI().toString(),
                    modifiedAt = file.lastModified(), indexedSize = file.length()), 800)
                assertEquals("$format must show real audio peaks", 128, peaks.size)
                val quiet = peaks.subList(8, 48).average()
                val loud = peaks.subList(80, 120).average()
                assertTrue("$format must preserve the loudness difference: $quiet / $loud", loud > quiet * 4)
                assertTrue(peaks.all { it.isFinite() && it in 0f..1f })
                assertEquals(peaks, reader.load(TrackSource("local:${file.name}", SourceKind.LOCAL, file.toURI().toString(),
                    modifiedAt = file.lastModified(), indexedSize = file.length()), 800))
            } finally { file.delete() }
        }
    }

    @Test fun soundEdgesSkipLeadingSilenceAndANearSilentTailForDecodedAndRawAudio() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // One second of silence, two seconds of music, one second 50 dB down, then one second of silence.
        val flac = File(context.cacheDir, "edges-${UUID.randomUUID()}.flac")
        val wav = File(context.cacheDir, "edges-${UUID.randomUUID()}.wav")
        try {
            instrumentation.context.assets.open("sound-edges.flac").use { input -> flac.outputStream().use { input.copyTo(it) } }
            val frames = 48_000 * 5
            val pcm = java.nio.ByteBuffer.allocate(44 + frames * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            pcm.put("RIFF".toByteArray()).putInt(36 + frames * 2).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(48_000).putInt(96_000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(frames * 2)
            repeat(frames) { frame ->
                val amplitude = when (frame / 48_000) { 1, 2 -> .5; 3 -> .5 * .003; else -> 0.0 }
                pcm.putShort((kotlin.math.sin(2 * Math.PI * 440 * frame / 48_000) * amplitude * 32_767).toInt().toShort())
            }
            wav.writeBytes(pcm.array())
            for (file in listOf(flac, wav)) {
                val source = TrackSource("local:${file.name}", SourceKind.LOCAL, file.toURI().toString(),
                    modifiedAt = file.lastModified(), indexedSize = file.length())
                assertNull("Edges must never start a decoder", AndroidAudioWaveforms(context).soundEdges(source))
                assertEquals(128, AndroidAudioWaveforms(context).load(source, 5_000).size)
                val edges = requireNotNull(AndroidAudioWaveforms(context).soundEdges(source)) { "${file.name} edges must be cached" }
                assertEquals("${file.name} sound starts after its leading silence: $edges", 1_000.0, edges.startMs.toDouble(), 100.0)
                assertEquals("${file.name} sound ends before its near-silent tail: $edges", 3_000.0, edges.endMs.toDouble(), 100.0)
            }
        } finally { flac.delete(); wav.delete() }
    }
}
