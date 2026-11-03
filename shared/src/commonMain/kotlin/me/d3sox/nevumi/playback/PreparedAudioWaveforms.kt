package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.TrackSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class WaveformPreparation(val total: Int = 0, val prepared: Int = 0, val failed: Int = 0,
    val running: Boolean = false, val currentSourceId: String? = null)

/** Bounded low-priority decoders share their persistent cache with Now Playing. */
class PreparedAudioWaveforms(private val reader: AudioWaveforms, private val idleDelayMs: Long = 1_000,
    private val parallelism: Int = waveformPreparationParallelism()) : AudioWaveforms, AutoCloseable {
    private data class Work(val sources: List<Pair<TrackSource, Long>>, val allowed: Boolean)
    private val work = MutableStateFlow(Work(emptyList(), false))
    private val foreground = MutableStateFlow(0)
    private class ManualRequest
    private val requested = MutableStateFlow<ManualRequest?>(null)
    private val paused = MutableStateFlow(false)
    private val progress = MutableStateFlow(WaveformPreparation())
    val preparation: StateFlow<WaveformPreparation> = progress.asStateFlow()
    init { require(parallelism in 1..8) { "Choose between one and eight waveform workers" } }
    private val thread = WaveformPreparationThread(parallelism)
    // Coordination must remain runnable while a native decoder occupies its dedicated thread.
    private val coordinator = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + coordinator + WaveformPreparationContext(thread.dispatcher))
    private val completed = mutableSetOf<TrackSource>()
    private val failed = mutableSetOf<TrackSource>()
    // Sources whose disk cache already missed, so later passes do not probe them again; a decode moves them to completed.
    private val uncached = mutableSetOf<TrackSource>()
    private data class Decoding(val source: TrackSource, val result: CompletableDeferred<List<Float>>)
    private val decoding = MutableStateFlow<Map<TrackSource, Decoding>>(emptyMap())
    private var lastManualRequest: ManualRequest? = null
    private val worker = scope.launch {
        // Moving the queue's songs to the front only reorders the work, which the running workers pick up without a restart.
        val members = work.distinctUntilChanged { old, new -> old.allowed == new.allowed &&
            old.sources.size == new.sources.size && old.sources.toSet() == new.sources.toSet() }
        combine(members, foreground, requested, paused) { pending, requests, manual, stopped ->
            Triple(pending, requests == 0 && !stopped && (pending.allowed || manual != null), manual)
        }.collectLatest { (pending, allowed, manual) ->
            if (manual != null && manual !== lastManualRequest) { failed.clear(); lastManualRequest = manual }
            // Check disk once per pass, then update counts in constant time as workers finish.
            for ((source, _) in pending.sources) {
                if (source in completed || source in uncached) continue
                if (reader.cached(source)?.isNotEmpty() == true) completed += source else uncached += source
            }
            var preparedCount = pending.sources.count { it.first in completed }
            var failedCount = pending.sources.count { it.first in failed }
            fun publish(running: Boolean) {
                progress.value = WaveformPreparation(pending.sources.size, preparedCount, failedCount, running,
                    decoding.value.keys.firstOrNull()?.id)
            }
            publish(false)
            if (!allowed) return@collectLatest
            if (manual == null) delay(idleDelayMs)
            try {
                var ordered: Work? = null
                var remaining = emptyList<Pair<TrackSource, Long>>()
                var next = 0
                // Runs on the single coordinator thread, so the workers share the cursor safely.
                fun take(): Pair<TrackSource, Long>? {
                    val current = work.value
                    if (current !== ordered) {
                        ordered = current
                        remaining = current.sources.filter { it.first !in completed && it.first !in failed }
                        next = 0
                    }
                    while (true) {
                        val candidate = remaining.getOrNull(next++) ?: return null
                        if (candidate.first !in completed && candidate.first !in failed && candidate.first !in decoding.value) return candidate
                    }
                }
                coroutineScope {
                    repeat(minOf(parallelism, pending.sources.count { it.first !in completed && it.first !in failed })) {
                        launch {
                            while (true) {
                                ensureActive()
                                if (foreground.value > 0) break
                                // Coordination runs on one dispatcher; blocking decodes run in the pool.
                                val (source, duration) = take() ?: break
                                val request = Decoding(source, CompletableDeferred())
                                decoding.value = decoding.value + (source to request)
                                publish(true)
                                try {
                                    val result = try { reader.load(source, duration) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { emptyList() }
                                    uncached -= source
                                    if (result.isNotEmpty()) { if (completed.add(source)) preparedCount++ }
                                    else if (failed.add(source)) failedCount++
                                    request.result.complete(result)
                                } finally {
                                    request.result.complete(emptyList())
                                    decoding.value = decoding.value - source
                                }
                                publish(true)
                                yield()
                            }
                        }
                    }
                }
            } finally { publish(false) }
            if (requested.value === manual) requested.value = null
        }
    }
    fun prepare(sources: List<Pair<TrackSource, Long>>, allowed: Boolean) { work.value = Work(sources.distinctBy { it.first }, allowed) }
    fun prepareAll() { paused.value = false; requested.value = ManualRequest() }
    fun pause() { paused.value = true; requested.value = null }
    override suspend fun cached(source: TrackSource) = reader.cached(source)
    override suspend fun load(source: TrackSource, durationMs: Long): List<Float> {
        reader.cached(source)?.let { return it }
        decoding.value[source]?.let { request ->
            request.result.await().takeIf { it.isNotEmpty() }?.let { return it }
        }
        var registered = false
        return try {
            withContext(coordinator) {
                foreground.update { it + 1 }
                registered = true
                // Let collectLatest cancel every native decoder before giving playback priority.
                decoding.first { it.isEmpty() }
            }
            reader.load(source, durationMs).also { points ->
                if (points.isNotEmpty()) withContext(coordinator) { uncached -= source; completed += source }
            }
        } finally {
            if (registered) withContext(NonCancellable + coordinator) { foreground.update { it - 1 } }
        }
    }
    override fun close() { worker.cancel(); scope.cancel(); thread.close() }
}
