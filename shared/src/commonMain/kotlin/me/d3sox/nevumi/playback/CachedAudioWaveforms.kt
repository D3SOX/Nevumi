package me.d3sox.nevumi.playback

import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

class CachedAudioWaveforms(private val directory: String, private val decode: suspend (String, Long) -> List<Float>) : AudioWaveforms {
    private val lock = Mutex()
    // Loads of one source share a lock, dropped once no load holds or awaits it so the registry stays bounded.
    private class SourceLock { val mutex = Mutex(); var users = 0 }
    private val sourceLocks = mutableMapOf<String, SourceLock>()
    private val memory = linkedMapOf<String, List<Float>>()
    private suspend fun remembered(key: String): List<Float>? = lock.withLock {
        memory.remove(key)?.also { memory[key] = it }
    }
    private suspend fun remember(key: String, points: List<Float>) = lock.withLock {
        memory.remove(key)
        memory[key] = points
        if (memory.size > 256) memory.remove(memory.keys.first())
    }
    private fun key(source: TrackSource) = "${source.id}|${source.location}|${source.modifiedAt}|${source.indexedSize}".encodeUtf8().sha256().hex()
    override suspend fun cached(source: TrackSource): List<Float>? {
        if (source.kind == SourceKind.SERVER || !source.available) return null
        val key = key(source)
        remembered(key)?.let { return it }
        // Atomic cache writes allow readers to bypass an unrelated long-running decoder.
        return withContext(Dispatchers.IO) { readCached(key) }
    }
    private suspend fun readCached(key: String): List<Float>? {
        remembered(key)?.let { return it }
        val path = directory.toPath() / "$key.rms.json"
        val saved = runCatching { Json.decodeFromString<List<Float>>(FileSystem.SYSTEM.read(path) { readUtf8() }) }.getOrNull()
        return saved?.takeIf { it.size == 128 && it.all { point -> point.isFinite() && point in 0f..1f } }?.also { points -> remember(key, points) }
    }
    override suspend fun load(source: TrackSource, durationMs: Long): List<Float> {
        if (source.kind == SourceKind.SERVER || !source.available || durationMs <= 0) return emptyList()
        val dispatcher = currentCoroutineContext()[WaveformPreparationContext]?.dispatcher ?: Dispatchers.IO
        val key = key(source)
        val sourceLock = lock.withLock { sourceLocks.getOrPut(key) { SourceLock() }.apply { users++ } }
        return try { sourceLock.mutex.withLock { withContext(dispatcher) {
            val fs = FileSystem.SYSTEM
            val root = directory.toPath()
            val path = root / "$key.rms.json"
            try {
                readCached(key)?.let { return@withContext it }
                val peaks = decode(source.location, durationMs)
                require(peaks.size == 128 && peaks.all { it.isFinite() && it in 0f..1f })
                fs.createDirectories(root)
                val temporary = root / "${Uuid.random()}.part"
                try {
                    fs.write(temporary) { writeUtf8(Json.encodeToString(peaks)) }
                    fs.atomicMove(temporary, path)
                    remember(key, peaks)
                } finally { fs.delete(temporary, mustExist = false) }
                peaks
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        } } } finally {
            withContext(NonCancellable) { lock.withLock { if (--sourceLock.users == 0) sourceLocks.remove(key) } }
        }
    }
}
