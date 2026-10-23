package me.d3sox.nevumi.artwork

import me.d3sox.nevumi.navidrome.NavidromeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

class ArtworkLibrary(private val directory: String) {
    private val writes = Mutex()
    private val mutableChanges = MutableSharedFlow<Pair<String, String?>>(extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val changes = mutableChanges.asSharedFlow()
    suspend fun fetch(trackId: String, client: NavidromeClient, coverId: String) = fetch(listOf(trackId), client, coverId)

    /** Downloads a cover once and stores it for every track that shares it, such as all songs of an album. */
    suspend fun fetch(trackIds: Collection<String>, client: NavidromeClient, coverId: String) {
        if (trackIds.isEmpty()) return
        val original = client.coverArt(coverId)
        writes.withLock { withContext(Dispatchers.IO) {
            val bytes = artworkPreview(original)
            trackIds.toSet().forEach { store(it, bytes) }
        } }
    }

    /**
     * Like [fetch], but skips tracks that already hold this server cover. Use it only where cover IDs change with the image,
     * as on Navidrome, so a library refresh downloads new or changed covers and an interrupted refresh resumes where it stopped.
     */
    suspend fun fetchChanged(trackIds: Collection<String>, client: NavidromeClient, coverId: String) {
        val indexed = withIndex { index -> trackIds.filterTo(HashSet()) { index[key(it)] != null } }
        val missing = withContext(Dispatchers.IO) {
            trackIds.toSet().filter { id -> id !in indexed || coverIdFile(id).let {
                FileSystem.SYSTEM.metadataOrNull(it) == null || FileSystem.SYSTEM.read(it) { readUtf8() } != coverId
            } }
        }
        if (missing.isEmpty()) return
        val original = client.coverArt(coverId)
        writes.withLock { withContext(Dispatchers.IO) {
            val bytes = artworkPreview(original)
            missing.forEach { store(it, bytes); FileSystem.SYSTEM.write(coverIdFile(it)) { writeUtf8(coverId) } }
        } }
    }

    suspend fun save(trackId: String, original: ByteArray) = writes.withLock { withContext(Dispatchers.IO) {
        store(trackId, artworkPreview(original))
    } }

    private fun key(trackId: String) = trackId.encodeUtf8().sha256().hex()
    private fun coverIdFile(trackId: String) = directory.toPath() / "${key(trackId)}.cover"

    /**
     * Runs [lookup] on the stored paths, which are read once; later saves and removals keep the index current.
     * Concurrent fetches save while others look up, so every access to the map holds the index's lock.
     */
    private suspend fun <T> withIndex(lookup: (Map<String, String>) -> T): T {
        val index = storedPaths()
        return index.lock.withLock { lookup(index.paths ?: withContext(Dispatchers.IO) {
            val fs = FileSystem.SYSTEM
            val root = directory.toPath()
            val names = fs.listOrNull(root)?.map { it.name }.orEmpty()
            val images = names.filterTo(HashSet()) { it.endsWith(".image") }
            val paths = HashMap<String, String>()
            for (name in names) {
                if (!name.endsWith(".current")) continue
                val key = name.removeSuffix(".current")
                val hash = runCatching { fs.read(root / name) { readUtf8() } }.getOrNull() ?: continue
                if (hash.matches(hashPattern) && "$key-$hash.image" in images) paths[key] = (root / "$key-$hash.image").toString()
            }
            paths
        }.also { index.paths = it }) }
    }

    private suspend fun indexed(key: String, path: String?) = storedPaths().let { index -> index.lock.withLock {
        index.paths?.let { if (path == null) it.remove(key) else it[key] = path }
    } }

    /** The index of this directory, shared by every library that stores covers there so each sees the others' saves. */
    private suspend fun storedPaths(): StoredPaths = registry.withLock {
        indexes.getOrPut(directory.toPath().normalized().toString()) { StoredPaths() }
    }

    private suspend fun store(trackId: String, bytes: ByteArray) {
        val fs = FileSystem.SYSTEM
        val root = directory.toPath()
        fs.createDirectories(root)
        // Any other image replaces the server cover, so the next refresh downloads it again.
        fs.delete(coverIdFile(trackId), mustExist = false)
        val key = key(trackId)
        val hash = bytes.toByteString().sha256().hex()
        val destination = root / "$key-$hash.image"
        val index = root / "$key.current"
        val previous = storedPath(trackId)
        val temporary = root / "${Uuid.random()}.part"
        try {
            fs.write(temporary) { write(bytes) }
            fs.atomicMove(temporary, destination)
            fs.write(temporary) { writeUtf8(hash) }
            fs.atomicMove(temporary, index)
            if (previous != null && previous != destination) fs.delete(previous, mustExist = false)
            indexed(key, destination.toString())
            mutableChanges.tryEmit(trackId to destination.toString())
        } finally { fs.delete(temporary, mustExist = false) }
    }

    suspend fun paths(trackIds: List<String>): Map<String, String> =
        withIndex { index -> trackIds.mapNotNull { id -> index[key(id)]?.let { id to it } }.toMap() }

    suspend fun path(trackId: String): String? = withIndex { it[key(trackId)] }

    /** The stored image according to the files on disk, for writers that must see other writers' changes. */
    private fun storedPath(trackId: String): Path? {
        val key = key(trackId)
        val fs = FileSystem.SYSTEM
        val root = directory.toPath()
        val index = root / "$key.current"
        if (fs.metadataOrNull(index)?.size != 64L) return null
        val hash = fs.read(index) { readUtf8() }
        if (!hash.matches(hashPattern)) return null
        val path = root / "$key-$hash.image"
        return path.takeIf { fs.metadataOrNull(path)?.isRegularFile == true }
    }

    suspend fun read(trackId: String): ByteArray? = withContext(Dispatchers.IO) {
        path(trackId)?.let { file -> FileSystem.SYSTEM.read(file.toPath()) { readByteArray() } }
    }

    suspend fun remove(trackId: String) = writes.withLock { withContext(Dispatchers.IO) {
        val previous = storedPath(trackId)
        FileSystem.SYSTEM.delete(coverIdFile(trackId), mustExist = false)
        FileSystem.SYSTEM.delete(directory.toPath() / "${key(trackId)}.current", mustExist = false)
        if (previous != null) FileSystem.SYSTEM.delete(previous, mustExist = false)
        indexed(key(trackId), null)
        mutableChanges.tryEmit(trackId to null)
    } }

    // The library asks for every song's cover after each refresh; one directory listing answers all of them.
    private class StoredPaths {
        val lock = Mutex()
        var paths: HashMap<String, String>? = null
    }

    private companion object {
        val hashPattern = Regex("[0-9a-f]{64}")
        val registry = Mutex()
        val indexes = HashMap<String, StoredPaths>()
    }
}
