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
}
