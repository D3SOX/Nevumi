package me.d3sox.nevumi.artwork

import me.d3sox.nevumi.navidrome.NavidromeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
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
        val missing = withContext(Dispatchers.IO) {
            trackIds.toSet().filter { id -> existingPath(id) == null || coverIdFile(id).let {
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

    private fun coverIdFile(trackId: String) = directory.toPath() / "${trackId.encodeUtf8().sha256().hex()}.cover"

    private fun store(trackId: String, bytes: ByteArray) {
        val fs = FileSystem.SYSTEM
        val root = directory.toPath()
        fs.createDirectories(root)
        // Any other image replaces the server cover, so the next refresh downloads it again.
        fs.delete(coverIdFile(trackId), mustExist = false)
        val key = trackId.encodeUtf8().sha256().hex()
        val hash = bytes.toByteString().sha256().hex()
        val destination = root / "$key-$hash.image"
        val index = root / "$key.current"
        val previous = existingPath(trackId)?.toPath()
        val temporary = root / "${Uuid.random()}.part"
        try {
            fs.write(temporary) { write(bytes) }
            fs.atomicMove(temporary, destination)
            fs.write(temporary) { writeUtf8(hash) }
            fs.atomicMove(temporary, index)
            if (previous != null && previous != destination) fs.delete(previous, mustExist = false)
            mutableChanges.tryEmit(trackId to destination.toString())
        } finally { fs.delete(temporary, mustExist = false) }
    }

    suspend fun paths(trackIds: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        trackIds.mapNotNull { id -> existingPath(id)?.let { id to it } }.toMap()
    }

    suspend fun path(trackId: String): String? = withContext(Dispatchers.IO) { existingPath(trackId) }

    private fun existingPath(trackId: String): String? {
        val key = trackId.encodeUtf8().sha256().hex()
        val fs = FileSystem.SYSTEM
        val root = directory.toPath()
        val index = root / "$key.current"
        if (fs.metadataOrNull(index)?.size != 64L) return null
        val hash = fs.read(index) { readUtf8() }
        if (!hash.matches(Regex("[0-9a-f]{64}"))) return null
        val path = root / "$key-$hash.image"
        return path.toString().takeIf { fs.metadataOrNull(path)?.isRegularFile == true }
    }

    suspend fun read(trackId: String): ByteArray? = withContext(Dispatchers.IO) {
        path(trackId)?.let { file -> FileSystem.SYSTEM.read(file.toPath()) { readByteArray() } }
    }

    suspend fun remove(trackId: String) = writes.withLock { withContext(Dispatchers.IO) {
        val previous = path(trackId)?.toPath()
        FileSystem.SYSTEM.delete(coverIdFile(trackId), mustExist = false)
        FileSystem.SYSTEM.delete(directory.toPath() / "${trackId.encodeUtf8().sha256().hex()}.current", mustExist = false)
        if (previous != null) FileSystem.SYSTEM.delete(previous, mustExist = false)
        mutableChanges.tryEmit(trackId to null)
    } }
}
