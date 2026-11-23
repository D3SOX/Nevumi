package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WaveformBatchPerformanceTest {
    @Test fun coldLibraryPreparationOverlapsIndependentDecodersAndBeatsSerialThroughput() = runBlocking {
        val directory = Files.createTempDirectory("waveform-batch-throughput")
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val sources = (0 until 12).map { TrackSource("song:$it", SourceKind.LOCAL, "/song-$it.flac") to 240_000L }
        fun reader(cache: String) = CachedAudioWaveforms(directory.resolve(cache).toString()) { _, _ ->
            val count = active.incrementAndGet(); maximum.accumulateAndGet(count, ::maxOf)
            try { repeat(8) { currentCoroutineContext().ensureActive(); Thread.sleep(10) }; AudioAnalysis(List(128) { .5f }) }
            finally { active.decrementAndGet() }
        }
        val sequential = reader("serial")
        var preparation: PreparedAudioWaveforms? = null
        try {
            val serialStart = System.nanoTime()
            for ((source, duration) in sources) sequential.load(source, duration)
            val serialMs = (System.nanoTime() - serialStart) / 1_000_000
            maximum.set(0)
            val prepared = PreparedAudioWaveforms(reader("batch"), idleDelayMs = 0, parallelism = 3).also { preparation = it }
            val batchStart = System.nanoTime()
            prepared.prepare(sources, allowed = false); prepared.prepareAll()
            withTimeout(5_000) { prepared.preparation.first { it.prepared == sources.size && !it.running } }
            val batchMs = (System.nanoTime() - batchStart) / 1_000_000
            println("Cold library: serial=${serialMs}ms preparation=${batchMs}ms simultaneousDecoders=${maximum.get()}")
            assertTrue(maximum.get() >= 2, "Independent songs must prepare concurrently on this multicore test machine")
            assertTrue(maximum.get() <= 3, "Preparation must respect its decoder limit")
            assertTrue(batchMs < serialMs * .8, "Library preparation should improve throughput: serial=$serialMs batch=$batchMs")
        } finally { preparation?.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun realColdAndWarmLibraryPreparationBenchmark() = runBlocking {
        val directory = Files.createTempDirectory("waveform-real-batch")
        val assets = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets")
        val sources = (0 until 12).map { index ->
            val long = index % 3 == 0
            val source = assets.resolve(if (long) "waveform-long.flac" else "waveform-four-minutes.mp3")
            val file = directory.resolve("song-$index.${if (long) "flac" else "mp3"}")
            Files.copy(source, file)
            TrackSource("real:$index", SourceKind.LOCAL, file.toString(), modifiedAt = 100, indexedSize = Files.size(file)) to if (long) 600_000L else 240_000L
        }
        val prepared = PreparedAudioWaveforms(FfmpegAudioWaveforms(directory.resolve("cache").toString()), idleDelayMs = 0)
        try {
            val coldStart = System.nanoTime()
            prepared.prepare(sources, allowed = false); prepared.prepareAll()
            val result = withTimeout(30_000) { prepared.preparation.first { it.prepared + it.failed == sources.size && !it.running } }
            val coldMs = (System.nanoTime() - coldStart) / 1_000_000
            assertEquals(0, result.failed)
            val warmStart = System.nanoTime()
            for ((source, duration) in sources) assertEquals(128, prepared.load(source, duration).size)
            val warmMs = (System.nanoTime() - warmStart) / 1_000_000
            println("Real 12-track library (4x10-minute FLAC, 8x4-minute MP3): cold=${coldMs}ms warm=${warmMs}ms")
        } finally { prepared.close(); directory.toFile().deleteRecursively() }
    }
}
