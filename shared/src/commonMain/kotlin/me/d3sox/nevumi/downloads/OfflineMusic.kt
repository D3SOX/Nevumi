package me.d3sox.nevumi.downloads

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.navidrome.NavidromeException
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.FileAudioAccess
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.storage.RecordRevision
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import me.d3sox.nevumi.settings.MusicPreferences
import kotlin.time.Clock
import kotlin.concurrent.Volatile
import me.d3sox.nevumi.queue.PlaybackQueue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Serializable
internal data class OfflineRequest(val trackId: String)

private data class ManualDownloadBefore(val pinned: Boolean, val download: DownloadRecord?, val requested: Boolean)

class OfflineMusic(private val store: MusicStore, private val library: MusicLibrary,
    private val client: suspend () -> NavidromeClient?, private val directory: String,
    private val playlists: Playlists,
    val network: StateFlow<DownloadNetwork> = MutableStateFlow(DownloadNetwork()),
    private val availableSpace: (String) -> Long = ::downloadUsableSpace,
    private val sourceAccessible: (String) -> Boolean = FileAudioAccess::available,
    private val scheduleWork: ((userInitiated: Boolean) -> Unit)? = null) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow<Map<String, DownloadRecord>>(emptyMap())
    val state = mutableState.asStateFlow()
    private val mutableExcluded = MutableStateFlow<Set<String>>(emptySet())
    // Songs the user removed; automatic playlist downloads skip them.
    val excluded = mutableExcluded.asStateFlow()
    private val activeTrack = MutableStateFlow<String?>(null)
    private val preferences = MusicPreferences(store)
    private val transfers = Mutex()
    private val runs = Mutex()
    private val refreshing = Mutex()
    @Volatile private var processing: Deferred<Unit>? = null
    @Volatile private var changingLibrary = false
    // Record revisions and protected songs after a pass that left nothing to download; while both stay the same, another pass would only repeat it.
    @Volatile private var settled: Pair<List<RecordRevision>, Set<String>>? = null

    init {
        scope.launch {
            network.collect {
                if (!networkPermitted()) processing?.cancel()
                wake.trySend(Unit)
            }
        }
        scope.launch {
            store.database.records().observe("settings").distinctUntilChanged().collect {
                if (preferences.load().downloadsPaused || !networkPermitted()) processing?.cancel()
                wake.trySend(Unit)
            }
        }
        scope.launch {
            store.database.records().observe("servers").distinctUntilChanged().collect { wake.trySend(Unit) }
        }
        scope.launch {
            playlists.observe().collect { wake.trySend(Unit) }
        }
        scope.launch {
            store.database.records().observe("downloads").distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            refresh()
            wake.trySend(Unit)
            while (isActive) {
                withTimeoutOrNull(30_000) { wake.receive() }
                try {
                    if (scheduleWork == null) runPending() else scheduleWork.invoke(false)
                }
                catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
            }
        }
    }

    suspend fun runPending() = runs.withLock {
        if (changingLibrary) return@withLock
        supervisorScope {
            val task = async(start = CoroutineStart.LAZY) { transfers.withLock { process() } }
            processing = task
            try { task.start(); task.await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                store.list<OfflineRequest>("offline-requests").forEach { request ->
                    if (library.findTrack(request.trackId)?.onDevice() == true) return@forEach
                    val previous = store.read<DownloadRecord>("download:${request.trackId}")
                    if (previous?.status != DownloadStatus.COMPLETE)
                        store.put("download:${request.trackId}", "downloads", DownloadRecord(request.trackId,
                            DownloadStatus.FAILED, bytesDownloaded = previous?.bytesDownloaded ?: 0))
                }
            }
            finally {
                processing = null
                withContext(NonCancellable) { refresh() }
            }
        }
    }

    suspend fun request(trackIds: List<String>, owner: String? = "manual") {
        if (owner == null && preferences.load().cacheBytes == 0L) return
        store.transaction {
            for (id in trackIds.distinct()) {
                requireNotNull(library.findTrack(id)) { "Track is missing" }
                if (owner != null) store.database.records().delete("excluded:$id")
                else if (store.read<OfflineRequest>("excluded:$id") != null) continue
                if (owner != null) {
                    val pins = store.read<TrackPins>("pins:$id") ?: TrackPins(id)
                    store.put("pins:$id", "pins", pins.copy(owners = pins.owners + owner))
                }
                val record = store.read<DownloadRecord>("download:$id")
                if (record?.status != DownloadStatus.COMPLETE || record.path?.let(sourceAccessible) != true) {
                    store.put("request:$id", "offline-requests", OfflineRequest(id))
                    if (record == null || record.status == DownloadStatus.COMPLETE) {
                        library.removeSource(id, "download:$id")
                        store.put("download:$id", "downloads", DownloadRecord(id, DownloadStatus.PENDING))
                    }
                }
            }
        }
        refresh()
        scheduleWork?.invoke(owner != null)
        wake.trySend(Unit)
    }

    suspend fun requestUndoable(trackIds: List<String>): suspend () -> Unit {
        val before = store.transaction { trackIds.distinct().associateWith { id ->
            ManualDownloadBefore(store.read<TrackPins>("pins:$id")?.owners?.contains("manual") == true,
                store.read("download:$id"), store.read<OfflineRequest>("request:$id") != null)
        } }
        request(trackIds)
        return {
            if (before.values.any { !it.pinned }) withLibraryChanges {
                for ((id, previous) in before.filterValues { !it.pinned }) {
                    val pins = store.read<TrackPins>("pins:$id") ?: TrackPins(id)
                    val remaining = pins.owners - "manual"
                    if (remaining.isEmpty()) store.database.records().delete("pins:$id")
                    else store.put("pins:$id", "pins", pins.copy(owners = remaining))
                    if (remaining.isEmpty() && !previous.requested) {
                        store.database.records().delete("request:$id")
                        if (previous.download == null) {
                            store.put("excluded:$id", "offline-exclusions", OfflineRequest(id))
                            AudioDownloads(store, library, null, directory).remove(id)
                        } else if (previous.download.status == DownloadStatus.COMPLETE) {
                            store.put("download:$id", "downloads", previous.download)
                        }
                    }
                }
            }
        }
    }

    fun retry() { wake.trySend(Unit) }

    // The user asked for these downloads, so the platform may show the transfer and its progress.
    fun downloadPlaylists() {
        scheduleWork?.invoke(true)
        wake.trySend(Unit)
    }

    internal suspend fun withLibraryChanges(change: suspend () -> Unit) {
        changingLibrary = true
        try {
            processing?.cancelAndJoin()
            runs.withLock { transfers.withLock { change() } }
        } finally {
            changingLibrary = false
            refresh()
            wake.trySend(Unit)
        }
    }

    suspend fun remove(trackId: String) {
        store.transaction {
            store.put("excluded:$trackId", "offline-exclusions", OfflineRequest(trackId))
            store.database.records().delete("request:$trackId")
        }
        processing?.cancelAndJoin()
        transfers.withLock { AudioDownloads(store, library, null, directory).remove(trackId) }
        refresh()
        wake.trySend(Unit)
    }

    suspend fun pause() {
        preferences.update { it.copy(downloadsPaused = true) }
        processing?.cancelAndJoin()
        refresh()
    }

    suspend fun resume() {
        preferences.update { it.copy(downloadsPaused = false) }
        scheduleWork?.invoke(true)
        wake.trySend(Unit)
    }

    suspend fun useTrack(trackId: String?) {
        if (activeTrack.value == trackId) return
        activeTrack.value = trackId
        if (trackId != null) store.transaction {
            val record = store.read<DownloadRecord>("download:$trackId")
            if (record?.status == DownloadStatus.COMPLETE)
                store.put("download:$trackId", "downloads", record.copy(lastUsedAt = Clock.System.now().toEpochMilliseconds()))
        }
        wake.trySend(Unit)
    }

    private suspend fun process() {
        // The playing and current queue songs are kept out of cache eviction, so a change to them needs a pass too.
        val inputs = store.database.records().revisions(PASS_INPUTS) to inUseTracks()
        if (inputs == settled) return
        settled = null
        // A pass that wrote changes, or ran while something else changed them, runs once more before settling.
        // Smart playlists follow listening and the clock, which no record revision shows, so they keep checking.
        if (pass() && (store.database.records().revisions(PASS_INPUTS) to inUseTracks()) == inputs &&
            store.list<Playlist>("playlists").none { it.autoDownload && it.rule != null }) settled = inputs
    }

    /** Returns whether every download the pass needed completed. Paused and offline passes only protect the cache, so they return false. */
    private suspend fun pass(): Boolean {
        dropDeviceFileDownloads()
        val paused = preferences.load().downloadsPaused || !networkPermitted()
        val connection = if (paused) null else client()
        val downloads = AudioDownloads(store, library, connection, directory, availableSpace)
        downloads.removeUnusedFiles()
        val excluded = store.list<OfflineRequest>("offline-exclusions").map { it.trackId }.toSet()
        if (paused || connection == null) {
            downloads.replacePlaylistPins(playlists.all(), excluded)
            downloads.enforceCacheBudget(preferences.load().cacheBytes, inUseTracks())
            return false
        }
        val requests = store.list<OfflineRequest>("offline-requests")
            .filterNot { it.trackId in excluded || library.findTrack(it.trackId)?.onDevice() == true }
        // A few songs are cheaper to look up one by one; a large batch is cheaper as one catalogue download.
        val catalogue = if (requests.size > PlaylistDownloads.SINGLE_LOOKUPS) connection.songs().associateBy { it.id } else null
        val releases = mutableMapOf<String, String>()
        var completed = true
        for (request in requests) {
            currentCoroutineContext().ensureActive()
            val track = library.findTrack(request.trackId) ?: continue
            val source = track.sources.firstOrNull {
                it.kind == SourceKind.SERVER && it.id.startsWith("server:${connection.serverId}:")
            } ?: continue
            // A song the server lacks can return without any library change, so the pass stays unsettled and asks again.
            val song = (if (catalogue != null) catalogue[source.location]
                else try { connection.song(source.location, releases) }
                catch (gone: NavidromeException) { if (gone.code == 70) null else throw gone }) ?: run { completed = false; continue }
            val complete = downloads.download(track.id, song)
            if (complete.status == DownloadStatus.COMPLETE) store.database.records().delete("request:${track.id}") else completed = false
            refresh()
        }
        if (!PlaylistDownloads(playlists, library, downloads, connection, sourceAccessible).synchronize(excluded)) completed = false
        downloads.enforceCacheBudget(preferences.load().cacheBytes, inUseTracks())
        return completed
    }

    // A song whose server file is on the device needs no download; this also drops copies made before it was linked to that file.
    private suspend fun dropDeviceFileDownloads() {
        val downloads = AudioDownloads(store, library, null, directory)
        val playing = inUseTracks()
        val tracks = library.tracks().associateBy { it.id }
        for (record in store.list<DownloadRecord>("downloads")) {
            val track = tracks[record.trackId] ?: continue
            if (!track.onDevice()) continue
            // A manual download returns if the file leaves the device; playlist downloads follow their playlist instead.
            if (store.read<TrackPins>("pins:${track.id}")?.owners?.contains("manual") == true)
                store.put("request:${track.id}", "offline-requests", OfflineRequest(track.id))
            else store.database.records().delete("request:${track.id}")
            if (record.status != DownloadStatus.COMPLETE || track.id !in playing) downloads.remove(track.id, unpin = false)
        }
    }

    // The device holds this song's server file when a readable local copy has the same byte size.
    private fun Track.onDevice(): Boolean {
        val sizes = sources.filter { it.kind == SourceKind.SERVER && it.indexedSize > 0 }.map { it.indexedSize }.toSet()
        return sources.any { it.kind == SourceKind.LOCAL && it.indexedSize in sizes && it.available && sourceAccessible(it.location) }
    }

    private suspend fun networkPermitted(): Boolean = network.value.let {
        it.connected && (!it.metered || preferences.load().downloadOnMetered)
    }

    private suspend fun inUseTracks(): Set<String> =
        setOfNotNull(activeTrack.value, PlaybackQueue(store).snapshot().currentTrackId)

    suspend fun enforceCacheBudget(bytes: Long) {
        val connection = client()
        AudioDownloads(store, library, connection, directory).enforceCacheBudget(bytes, inUseTracks())
        refresh()
    }

    // Refreshes run concurrently, so read and publish under one lock. Otherwise an older read can replace newer state.
    private suspend fun refresh() = refreshing.withLock {
        mutableState.value = store.list<DownloadRecord>("downloads").associateBy { it.trackId }
        mutableExcluded.value = store.list<OfflineRequest>("offline-exclusions").map { it.trackId }.toSet()
    }
    override fun close() { processing?.cancel(); supervisor.cancel() }
    suspend fun closeAndJoin() { val task = processing; close(); task?.join(); supervisor.join() }
}

// Everything a pass reads from the store to decide what to download, keep or evict.
private val PASS_INPUTS = listOf("downloads", "offline-exclusions", "offline-requests", "pins", "playlists", "servers", "settings", "tracks")
