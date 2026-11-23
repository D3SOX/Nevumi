package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class PreparedAudioWaveformsTest {
    @Test fun foregroundWaveformsPreemptASynchronousBackgroundDecoder() = runBlocking {
        val directory = Files.createTempDirectory("waveform-blocking-decoder")
        val started = CompletableDeferred<Unit>()
        val background = TrackSource("background", SourceKind.LOCAL, "/long-song.flac")
        val current = TrackSource("current", SourceKind.LOCAL, "/current.mp3")
        val reader = CachedAudioWaveforms(directory.toString()) { location, _ ->
            if (location == background.location) {
                started.complete(Unit)
                // Native codecs perform synchronous work, checking cancellation between buffers.
                repeat(30) { currentCoroutineContext().ensureActive(); Thread.sleep(20) }
            }
            AudioAnalysis(List(128) { .5f })
        }
        val prepared = PreparedAudioWaveforms(reader, idleDelayMs = 20)
        try {
            prepared.prepare(listOf(background to 600_000L), allowed = true)
            withTimeout(1_000) { started.await() }
            assertEquals(128, withTimeout(150) { prepared.load(current, 240_000) }.size)
        } finally { prepared.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun openingACachedTrackDoesNotInterruptPreparationOfAnotherSong() = runBlocking {
        val directory = Files.createTempDirectory("waveform-cache-priority")
        val current = TrackSource("cached", SourceKind.LOCAL, "/cached.wav")
        val next = TrackSource("next", SourceKind.LOCAL, "/next.wav")
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var nextDecodes = 0
        val reader = CachedAudioWaveforms(directory.toString()) { location, _ ->
            if (location == next.location) {
                nextDecodes++; started.complete(Unit); delay(200); finished.complete(Unit)
            }
            AudioAnalysis(List(128) { .5f })
        }
        reader.load(current, 1_000)
        val prepared = PreparedAudioWaveforms(reader, idleDelayMs = 20)
        try {
            prepared.prepare(listOf(next to 1_000L), allowed = true)
            withTimeout(1_000) { started.await() }
            assertEquals(128, withTimeout(100) { prepared.load(current, 1_000) }.size)
            withTimeout(1_000) { finished.await() }
            assertEquals(1, nextDecodes, "Prepared songs must open immediately without discarding unrelated preparation")
        } finally { prepared.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun openingTheTrackBeingPreparedUsesThatDecodeInsteadOfRestartingIt() = runBlocking {
        val directory = Files.createTempDirectory("waveform-already-preparing")
        val source = TrackSource("current", SourceKind.LOCAL, "/current.wav")
        val started = CompletableDeferred<Unit>()
        var decodes = 0
        val reader = CachedAudioWaveforms(directory.toString()) { _, _ ->
            decodes++
            started.complete(Unit)
            delay(200)
            AudioAnalysis(List(128) { .5f })
        }
        val prepared = PreparedAudioWaveforms(reader, idleDelayMs = 20)
        try {
            prepared.prepare(listOf(source to 1_000L), allowed = true)
            withTimeout(1_000) { started.await() }
            assertEquals(128, withTimeout(500) { prepared.load(source, 1_000) }.size)
            assertEquals(1, decodes, "Opening Now Playing must not discard work on this same song")
        } finally { prepared.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun indexedAndDecodedDurationsReuseThePreparedWaveformButChangedFilesAreRegenerated() = runBlocking {
        val directory = Files.createTempDirectory("waveform-duration-cache")
        var decodes = 0
        val source = TrackSource("song", SourceKind.LOCAL, "/song.mp3", modifiedAt = 100L, indexedSize = 500L)
        try {
            val reader = CachedAudioWaveforms(directory.toString()) { _, _ -> decodes++; AudioAnalysis(List(128) { .5f }) }
            assertEquals(128, reader.load(source, 123_456).size)
            val reopened = CachedAudioWaveforms(directory.toString()) { _, _ -> decodes++; AudioAnalysis(List(128) { .5f }) }
            assertEquals(128, reopened.load(source, 123_457).size)
            assertEquals(1, decodes, "Playback duration rounding must not invalidate a prepared waveform")
            assertEquals(128, reopened.load(source.copy(modifiedAt = 200L), 123_457).size)
            assertEquals(2, decodes, "Actual file changes must invalidate the cache")
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun foregroundWaveformsPreemptIdleWorkAndIdleWorkIsCachedForTheNextRequest() = runBlocking {
        val directory = Files.createTempDirectory("prepared-waveforms")
        val started = CompletableDeferred<Unit>()
        val interrupted = CompletableDeferred<Unit>()
        val backgroundFinished = CompletableDeferred<Unit>()
        var blockBackground = true
        val background = TrackSource("background", SourceKind.LOCAL, "/background.wav")
        val current = TrackSource("current", SourceKind.LOCAL, "/current.wav")
        val cache = CachedAudioWaveforms(directory.toString()) { source, _ ->
            if (source == background.location && blockBackground) {
                started.complete(Unit)
                try { awaitCancellation() } finally { interrupted.complete(Unit) }
            }
            if (source == background.location) backgroundFinished.complete(Unit)
            AudioAnalysis(List(128) { .5f })
        }
        val prepared = PreparedAudioWaveforms(cache, idleDelayMs = 20)
        try {
            prepared.prepare(listOf(background to 1_000L), allowed = false)
            delay(60)
            assertFalse(started.isCompleted, "Preparation must wait while playback/indexing is busy")
            prepared.prepare(listOf(background to 1_000L), allowed = true)
            withTimeout(1_000) { started.await() }
            assertEquals(List(128) { .5f }, withTimeout(500) { prepared.load(current, 1_000) })
            assertTrue(interrupted.isCompleted, "Opening Now Playing must interrupt unrelated decoding")
            blockBackground = false
            withTimeout(1_000) { backgroundFinished.await() }
            val reopened = CachedAudioWaveforms(directory.toString()) { _, _ -> error("Idle-prepared waveform must already be on disk") }
            withTimeout(500) { while (reopened.load(background, 1_000).isEmpty()) delay(10) }
            prepared.close()
            assertEquals(List(128) { .5f }, reopened.load(background, 1_000))
        } finally { prepared.close(); directory.toFile().deleteRecursively() }
    }
}
