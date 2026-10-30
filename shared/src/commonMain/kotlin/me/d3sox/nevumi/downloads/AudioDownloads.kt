package me.d3sox.nevumi.downloads

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerSong
import me.d3sox.nevumi.storage.MusicStore
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.time.TimeSource
import kotlin.time.Clock
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.ByteString.Companion.encodeUtf8
import okio.buffer
import okio.BufferedSink
import me.d3sox.nevumi.playlists.Playlist

@Serializable
enum class DownloadStatus { PENDING, DOWNLOADING, COMPLETE, PAUSED, FAILED }

@Serializable
enum class DownloadProblem { INSUFFICIENT_SPACE }

@Serializable
data class DownloadRecord(
    val trackId: String,
    val status: DownloadStatus,
    val path: String? = null,
    val bytesDownloaded: Long = 0,
    val sizeBytes: Long = 0,
    val lastUsedAt: Long = 0,
    val problem: DownloadProblem? = null,
    val sourceId: String? = null,
    val validator: String? = null,
)

internal suspend fun remapDownloadedTrack(store: MusicStore, from: String, to: String): DownloadRecord? {
    val old = store.read<DownloadRecord>("download:$from")
    val current = store.read<DownloadRecord>("download:$to")
    check(listOfNotNull(old, current).none { it.status == DownloadStatus.DOWNLOADING }) { "Pause the download before linking these songs" }
    val chosen = listOfNotNull(current, old).firstOrNull { it.status == DownloadStatus.COMPLETE } ?: current ?: old
    val updated = chosen?.copy(trackId = to, bytesDownloaded = if (chosen.status == DownloadStatus.COMPLETE) chosen.bytesDownloaded else 0)
    if (updated != null) store.put("download:$to", "downloads", updated)
    store.database.records().delete("download:$from")
    val owners = store.read<TrackPins>("pins:$from")?.owners.orEmpty() + store.read<TrackPins>("pins:$to")?.owners.orEmpty()
    if (owners.isNotEmpty()) store.put("pins:$to", "pins", TrackPins(to, owners))
    store.database.records().delete("pins:$from")
    for ((prefix, kind) in listOf("request" to "offline-requests", "excluded" to "offline-exclusions")) {
        if (store.read<OfflineRequest>("$prefix:$from") != null) store.put("$prefix:$to", kind, OfflineRequest(to))
        store.database.records().delete("$prefix:$from")
    }
    return updated
}

class AudioDownloads(
    private val store: MusicStore,
    private val library: MusicLibrary,
    private val client: NavidromeClient?,
    private val directory: String,
    private val availableSpace: (String) -> Long = ::downloadUsableSpace,
) {
    suspend fun markUsed(trackId: String) = store.transaction {
        val record = state(trackId)
        if (record?.status == DownloadStatus.COMPLETE)
            store.put("download:$trackId", "downloads", record.copy(lastUsedAt = Clock.System.now().toEpochMilliseconds()))
    }
    suspend fun pin(trackId: String, owner: String) = store.transaction {
        val pins = store.read<TrackPins>("pins:$trackId") ?: TrackPins(trackId)
        if (owner !in pins.owners) store.put("pins:$trackId", "pins", pins.copy(owners = pins.owners + owner))
    }
    suspend fun unpinOwner(owner: String) = store.transaction {
        store.list<TrackPins>("pins").filter { owner in it.owners }.forEach {
            store.put("pins:${it.trackId}", "pins", it.copy(owners = it.owners - owner))
        }
    }
    internal suspend fun replacePlaylistPins(playlists: List<Playlist>, excluded: Set<String> = emptySet()) = store.transaction {
        val existing = store.list<TrackPins>("pins").associateBy { it.trackId }
        val desired = mutableMapOf<String, Set<String>>()
        playlists.filter { it.autoDownload }.forEach { playlist ->
            playlist.trackIds.filterNot { it in excluded }.forEach { trackId ->
                desired[trackId] = desired[trackId].orEmpty() + "playlist:${playlist.id}"
            }
        }
        // This runs on every download pass; unchanged pins are not written again.
        (existing.keys + desired.keys).forEach { trackId ->
            val retained = existing[trackId]?.owners.orEmpty().filterNot { it.startsWith("playlist:") }.toSet()
            val owners = retained + desired[trackId].orEmpty()
            if (existing[trackId]?.owners != owners) store.put("pins:$trackId", "pins", TrackPins(trackId, owners))
        }
    }
    suspend fun enforceCacheBudget(bytes: Long, inUse: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        require(bytes >= 0)
        val protected = store.list<TrackPins>("pins").filter { it.owners.isNotEmpty() }.map { it.trackId }.toSet()
        val rolling = store.list<DownloadRecord>("downloads")
            .filter { it.status == DownloadStatus.COMPLETE && it.trackId !in protected && it.trackId !in inUse }
            .sortedBy { it.lastUsedAt }
        var size = rolling.sumOf { it.bytesDownloaded }
        for (record in rolling) {
            if (size <= bytes) break
            record.path?.let { FileSystem.SYSTEM.delete(it.toPath(), mustExist = false) }
            library.removeSource(record.trackId, "download:${record.trackId}")
            store.database.records().delete("download:${record.trackId}")
            size -= record.bytesDownloaded
        }
    }
    suspend fun state(trackId: String): DownloadRecord? = store.read("download:$trackId")

    /**
     * Deletes files no download record uses, such as abandoned partial downloads or the audio left behind when linking two
     * downloaded songs kept only one. An unfinished download keeps its files to resume. Run it while no transfer is writing.
     */
    internal suspend fun removeUnusedFiles() = withContext(Dispatchers.IO) {
        val records = store.list<DownloadRecord>("downloads")
        val used = records.mapNotNullTo(HashSet()) { it.path?.toPath()?.name }
        val unfinished = records.filter { it.status != DownloadStatus.COMPLETE }.mapTo(HashSet()) { fileName(it.trackId) }
        for (file in FileSystem.SYSTEM.listOrNull(directory.toPath()).orEmpty()) {
            if (file.name in used || file.name.substringBefore('.') in unfinished) continue
            // A file still open elsewhere, such as by playback on Windows, is deleted on a later run.
            try { if (FileSystem.SYSTEM.metadataOrNull(file)?.isRegularFile == true) FileSystem.SYSTEM.delete(file, mustExist = false) }
            catch (_: okio.IOException) {}
        }
    }

    suspend fun remove(trackId: String, unpin: Boolean = true) = withContext(Dispatchers.IO) {
        state(trackId)?.path?.let { FileSystem.SYSTEM.delete(it.toPath(), mustExist = false) }
        val name = fileName(trackId)
        FileSystem.SYSTEM.listOrNull(directory.toPath()).orEmpty().filter { it.name.startsWith("$name.") }.forEach {
            FileSystem.SYSTEM.delete(it, mustExist = false)
        }
        store.transaction {
            library.removeSource(trackId, "download:$trackId")
            store.database.records().delete("download:$trackId")
            if (unpin) store.database.records().delete("pins:$trackId")
        }
    }

    suspend fun download(trackId: String, song: ServerSong): DownloadRecord = withContext(Dispatchers.IO) {
        val fs = FileSystem.SYSTEM
        val root = directory.toPath()
        val extension = song.format.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "audio" }
        val name = fileName(trackId)
        val partial = root / "$name.$extension.part"
        val complete = root / "$name.$extension"
        val sourceId = client?.let { "server:${it.serverId}:${song.id}" }
        val previous = state(trackId)
        // The previous validator stays until the server answers, so an attempt that fails before then can still resume later.
        var record = DownloadRecord(trackId, DownloadStatus.DOWNLOADING, sizeBytes = song.sizeBytes, sourceId = sourceId,
            validator = previous?.validator)
        store.put("download:$trackId", "downloads", record)
        try {
            fs.createDirectories(root)
            if (previous?.sourceId != sourceId || previous?.sizeBytes != song.sizeBytes || previous?.validator == null)
                fs.delete(partial, mustExist = false)
            val offset = fs.metadataOrNull(partial)?.size ?: 0
            val remaining = (song.sizeBytes - offset).coerceAtLeast(0)
            if (availableSpace(root.toString()).coerceAtLeast(0) - 64L * 1024 * 1024 < remaining)
                throw InsufficientDownloadSpace()
            var output: BufferedSink? = null
            var start = 0L
            var receivedBytes = 0L
            var firstProgress = true
            var lastProgress = TimeSource.Monotonic.markNow()
            val bytes = try {
                val received = requireNotNull(client) { "Connect to the music server to download audio" }.downloadSong(song.id,
                    offset = offset, ifRange = previous?.validator, onValidator = { validator ->
                        record = record.copy(validator = validator)
                    }, onStart = { resumedAt ->
                    start = resumedAt
                    receivedBytes = resumedAt
                    output = (if (resumedAt > 0) fs.appendingSink(partial) else fs.sink(partial)).buffer()
                    store.put("download:$trackId", "downloads", record.copy(bytesDownloaded = resumedAt))
                }) { chunk ->
                    if (availableSpace(root.toString()).coerceAtLeast(0) - 64L * 1024 * 1024 < chunk.size)
                        throw InsufficientDownloadSpace()
                    requireNotNull(output).write(chunk)
                    receivedBytes += chunk.size
                    if (firstProgress || receivedBytes - record.bytesDownloaded >= 262_144 || lastProgress.elapsedNow().inWholeMilliseconds >= 500) {
                        firstProgress = false
                        lastProgress = TimeSource.Monotonic.markNow()
                        record = record.copy(bytesDownloaded = receivedBytes)
                        store.put("download:$trackId", "downloads", record)
                    }
                }
                start + received
            } finally {
                output?.close()
            }
            check(song.sizeBytes == 0L || bytes == song.sizeBytes) { "Audio download is incomplete" }
            fs.atomicMove(partial, complete)
            record = record.copy(status = DownloadStatus.COMPLETE, path = complete.toString(),
                bytesDownloaded = bytes, lastUsedAt = Clock.System.now().toEpochMilliseconds())
            store.transaction {
                library.importTrack(song.metadata,
                    TrackSource("download:$trackId", SourceKind.DOWNLOAD, complete.toString()), linkTo = trackId)
                store.put("download:$trackId", "downloads", record)
            }
            record
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                store.put("download:$trackId", "downloads", record.copy(status = DownloadStatus.PAUSED,
                    bytesDownloaded = fs.metadataOrNull(partial)?.size ?: 0))
            }
            throw cancelled
        } catch (error: Exception) {
            record = record.copy(status = DownloadStatus.FAILED,
                bytesDownloaded = fs.metadataOrNull(partial)?.size ?: 0,
                problem = if (error is InsufficientDownloadSpace) DownloadProblem.INSUFFICIENT_SPACE else null)
            store.put("download:$trackId", "downloads", record)
            record
        }
    }
}

private class InsufficientDownloadSpace : Exception()

/** The owners keeping a song's download out of cache eviction, such as "manual" or "playlist:<id>". */
@Serializable
internal data class TrackPins(val trackId: String, val owners: Set<String> = emptySet())

private fun fileName(trackId: String) = trackId.encodeUtf8().sha256().hex()
