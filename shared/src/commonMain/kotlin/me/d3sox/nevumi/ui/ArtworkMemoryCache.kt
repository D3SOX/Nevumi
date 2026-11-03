package me.d3sox.nevumi.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath

/** Artwork paths include a content hash, so replacing a cover creates a new cache key and identical covers share one. */
internal object ArtworkMemoryCache {
    private data class Key(val path: String, val size: Int)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val lock = Mutex()
    private val images = MutableStateFlow<Map<Key, ImageBitmap>>(emptyMap())
    private val pending = mutableMapOf<Key, Deferred<ImageBitmap?>>()
    private const val MAX_BYTES = 32L * 1024 * 1024

    // Stored covers end in their content hash, so songs sharing an album cover share one decoded image.
    private val contentHash = Regex("([0-9a-f]{64})\\.image$")
    private fun key(path: String, size: Int) = Key(contentHash.find(path)?.groupValues?.get(1) ?: path, when {
        size <= 128 -> 128
        size <= 256 -> 256
        size <= 512 -> 512
        else -> 1024
    })

    fun peek(path: String?, size: Int): ImageBitmap? = path?.let { images.value[key(it, size)] }

    suspend fun load(path: String, size: Int): ImageBitmap? {
        val key = key(path, size)
        val request = lock.withLock {
            images.value[key]?.let { image ->
                images.value = images.value.toMutableMap().apply { remove(key); put(key, image) }
                return image
            }
            pending.getOrPut(key) {
                scope.async {
                    val image = try {
                        val file = path.toPath()
                        val length = FileSystem.SYSTEM.metadataOrNull(file)?.size ?: 0
                        if (length !in 1..8L * 1024 * 1024) null
                        else decodeArtwork(FileSystem.SYSTEM.read(file) { readByteArray() }, key.size)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    lock.withLock {
                        pending.remove(key)
                        if (image != null) {
                            val cached = images.value.toMutableMap().apply { put(key, image) }
                            var bytes = cached.values.sumOf { it.width.toLong() * it.height * 4 }
                            val oldest = cached.entries.iterator()
                            while (bytes > MAX_BYTES && oldest.hasNext()) {
                                val entry = oldest.next()
                                bytes -= entry.value.width.toLong() * entry.value.height * 4
                                oldest.remove()
                            }
                            images.value = cached
                        }
                    }
                    image
                }
            }
        }
        // A recycled row can stop waiting without canceling the shared decode.
        return request.await()
    }
}
