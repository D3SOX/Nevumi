package me.d3sox.nevumi.downloads

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.navidrome.NavidromeException
import me.d3sox.nevumi.navidrome.ServerSong
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Serializable
internal data class OfflineRequest(val trackId: String)

private data class ManualDownloadBefore(val pinned: Boolean, val download: DownloadRecord?, val requested: Boolean, val excluded: Boolean)

class OfflineMusic(private val store: MusicStore, private val library: MusicLibrary,
    private val client: suspend () -> NavidromeClient?, private val directory: String,
    private val playlists: Playlists,
    val network: StateFlow<DownloadNetwork> = MutableStateFlow(DownloadNetwork()),
    private val availableSpace: (String) -> Long = ::downloadUsableSpace,
    private val sourceAccessible: (String) -> Boolean = FileAudioAccess::available,
    // Runs [runPending] on the platform's schedule. New work needs a pass soon; other calls only check that one is due.
    private val scheduleWork: ((userInitiated: Boolean, newWork: Boolean) -> Unit)? = null,
    private val timeSource: TimeSource = TimeSource.Monotonic) : AutoCloseable {
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
    // What the last pass that left nothing to download read; while it stays the same, another pass would only repeat it.
    @Volatile private var settled: Settled? = null
    // Passes in a row that failed and completed no download; each one doubles the wait before the next automatic pass.
    @Volatile private var fruitlessPasses = 0
    /** How long the worker waits for a change before its next automatic pass, from 30 seconds up to 32 minutes. */
    internal val nextPassDelay get() = 30.seconds * (1 shl fruitlessPasses.coerceAtMost(6))
    /** Whether the last pass left downloads that another attempt soon could complete, such as after a connection failure. */
    @Volatile var awaitingRetry = false
        private set

    init {
        scope.launch {
            network.collect {
                if (!networkPermitted()) processing?.cancel()
                wake.trySend(Unit)
            }
        }
        scope.launch {
            store.database.records().observeRevision("settings").distinctUntilChanged().collect {
                if (preferences.load().downloadsPaused || !networkPermitted()) processing?.cancel()
                wake.trySend(Unit)
            }
        }
        scope.launch {
            store.database.records().observeRevision("servers").distinctUntilChanged().collect { wake.trySend(Unit) }
        }
        scope.launch {
            playlists.observe().collect { wake.trySend(Unit) }
        }
        scope.launch {
            store.database.records().observeRevision("downloads").distinctUntilChanged().collect { refresh() }
        }
        scope.launch {
            refresh()
            wake.trySend(Unit)
            while (isActive) {
                withTimeoutOrNull(nextPassDelay) { wake.receive() }
                try {
                    // A settled pass has nothing to do until a pass input changes, so no platform job is scheduled for it.
                    if (scheduleWork == null) runPending() else if (!settledNow()) scheduleWork.invoke(false, false)
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
                awaitingRetry = true
                fruitlessPasses++
                store.list<OfflineRequest>("offline-requests").forEach { request ->
                    if (library.findTrack(request.trackId)?.onDevice() == true) return@forEach
                    val previous = store.read<DownloadRecord>("download:${request.trackId}")
                    // Keeping the source, size and validator lets the next attempt resume the partial file.
                    if (previous?.status != DownloadStatus.COMPLETE)
                        store.put("download:${request.trackId}", "downloads", previous?.copy(status = DownloadStatus.FAILED, problem = null)
                            ?: DownloadRecord(request.trackId, DownloadStatus.FAILED))
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
        val requested = store.transaction {
            var requested = false
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
                    requested = true
                    store.put("request:$id", "offline-requests", OfflineRequest(id))
                    if (record == null || record.status == DownloadStatus.COMPLETE) {
                        library.removeSource(id, "download:$id")
                        store.put("download:$id", "downloads", DownloadRecord(id, DownloadStatus.PENDING))
                    }
                }
            }
            requested
        }
        refresh()
        scheduleWork?.invoke(owner != null, requested)
        wake.trySend(Unit)
    }

    suspend fun requestUndoable(trackIds: List<String>): suspend () -> Unit {
        val before = store.transaction { trackIds.distinct().associateWith { id ->
            ManualDownloadBefore(store.read<TrackPins>("pins:$id")?.owners?.contains("manual") == true,
                store.read("download:$id"), store.read<OfflineRequest>("request:$id") != null,
                store.read<OfflineRequest>("excluded:$id") != null)
        } }
        request(trackIds)
        return {
            if (before.values.any { !it.pinned }) withLibraryChanges {
                for ((id, previous) in before.filterValues { !it.pinned }) {
                    val pins = store.read<TrackPins>("pins:$id") ?: TrackPins(id)
                    val remaining = pins.owners - "manual"
                    if (remaining.isEmpty()) store.database.records().delete("pins:$id")
                    else store.put("pins:$id", "pins", pins.copy(owners = remaining))
                    // Requesting cleared an earlier exclusion, so only that one comes back, also if a playlist pinned the song since.
                    if (previous.excluded && !previous.requested) store.put("excluded:$id", "offline-exclusions", OfflineRequest(id))
                    if (remaining.isEmpty() && !previous.requested) {
                        store.database.records().delete("request:$id")
                        if (previous.download == null) {
                            AudioDownloads(store, library, null, directory).remove(id)
                        } else if (previous.download.status == DownloadStatus.COMPLETE) {
                            store.put("download:$id", "downloads", previous.download)
                        }
                    }
                }
            }
        }
    }

    fun retry() {
        // Asking again also covers what a settled pass gave up on, such as songs the server lacked.
        settled = null
        wake.trySend(Unit)
    }

    // The user asked for these downloads, so the platform may show the transfer and its progress.
    fun downloadPlaylists() {
        scheduleWork?.invoke(true, true)
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
        scheduleWork?.invoke(true, true)
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

    /** Whether the last pass settled and nothing it reads has changed since. */
    internal suspend fun settledNow(): Boolean {
        val settled = settled ?: return false
        val downloads = AudioDownloads(store, library, null, directory, availableSpace)
        return settled.recheck?.hasPassedNow() != true && passInputs() == settled.inputs &&
            settled.waitingForSpace.all { downloads.waitingForSpace(downloads.state(it)) }
    }

    // The playing and current queue songs are kept out of cache eviction, so a change to them needs a pass too.
    private suspend fun passInputs() = store.database.records().revisions(PASS_INPUTS) to inUseTracks()

    private suspend fun process() {
        if (settledNow()) { awaitingRetry = false; return }
        settled = null
        val inputs = passInputs()
        val before = completedDownloads()
        val result = pass()
        awaitingRetry = result == PassResult.RETRY
        fruitlessPasses = if (result == PassResult.FINISHED || (completedDownloads() - before).isNotEmpty()) 0 else fruitlessPasses + 1
        // A pass that wrote changes, or ran while something else changed them, runs once more before settling.
        // Smart playlists follow listening and the clock, which no record revision shows, so they are checked again after a while.
        if (result == PassResult.FINISHED && passInputs() == inputs) {
            settled = Settled(inputs,
                timeSource.markNow().takeIf { store.list<Playlist>("playlists").any { it.autoDownload && it.rule != null } }
                    ?.plus(SMART_PLAYLIST_CHECKS),
                // Every download that ran out of space, also one whose space was freed during the pass, so the next check sees it.
                store.list<DownloadRecord>("downloads").filter { it.status == DownloadStatus.FAILED && it.problem == DownloadProblem.INSUFFICIENT_SPACE }
                    .mapTo(HashSet()) { it.trackId })
        }
    }

    private suspend fun completedDownloads() =
        store.list<DownloadRecord>("downloads").filter { it.status == DownloadStatus.COMPLETE }.mapTo(HashSet()) { it.trackId }

    /** Paused and offline passes only protect the cache. */
    private suspend fun pass(): PassResult {
        dropDeviceFileDownloads()
        val downloadsPaused = preferences.load().downloadsPaused
        val paused = downloadsPaused || !networkPermitted()
        val connection = if (paused) null else client()
        val downloads = AudioDownloads(store, library, connection, directory, availableSpace)
        downloads.removeUnusedFiles()
        val excluded = store.list<OfflineRequest>("offline-exclusions").map { it.trackId }.toSet()
        if (paused || connection == null) {
            downloads.replacePlaylistPins(playlists.all(), excluded)
            downloads.enforceCacheBudget(preferences.load().cacheBytes, inUseTracks())
            // Without a music server nothing can download until one connects, which wakes the worker.
            return if (downloadsPaused) PassResult.PAUSED else if (paused) PassResult.RETRY else PassResult.FINISHED
        }
        val requests = store.list<OfflineRequest>("offline-requests")
            .filterNot { it.trackId in excluded || library.findTrack(it.trackId)?.onDevice() == true }
        // A few songs are cheaper to look up one by one; a large share of the library is cheaper as one catalogue download,
        // which the playlist downloads below reuse instead of downloading it again.
        var fetched: Map<String, ServerSong>? = null
        suspend fun catalogue() = fetched ?: connection.songs().associateBy { it.id }.also { fetched = it }
        val serverSongs = library.tracks().count { track -> track.sources.any { it.kind == SourceKind.SERVER && it.id.startsWith("server:${connection.serverId}:") } }
        val catalogue = if (PlaylistDownloads.catalogueCheaper(requests.size, serverSongs)) catalogue() else null
        val releases = mutableMapOf<String, String>()
        var completed = true
        for (request in requests) {
            currentCoroutineContext().ensureActive()
            val track = library.findTrack(request.trackId) ?: continue
            if (downloads.waitingForSpace(downloads.state(track.id))) continue
            val source = track.sources.firstOrNull {
                it.kind == SourceKind.SERVER && it.id.startsWith("server:${connection.serverId}:")
            } ?: continue
            // A song the server lacks shows as failed and waits for a retry or a library change, such as the next server refresh.
            val song = (if (catalogue != null) catalogue[source.location]
                else try { connection.song(source.location, releases) }
                catch (gone: NavidromeException) { if (gone.code == 70) null else throw gone }) ?: run {
                    val record = downloads.state(track.id)
                    if (record?.status != DownloadStatus.FAILED)
                        store.put("download:${track.id}", "downloads", record?.copy(status = DownloadStatus.FAILED, problem = null)
                            ?: DownloadRecord(track.id, DownloadStatus.FAILED))
                    refresh()
                    continue
                }
            val complete = downloads.download(track.id, song)
            if (complete.status == DownloadStatus.COMPLETE) store.database.records().delete("request:${track.id}")
            else if (complete.retryable()) completed = false
            refresh()
        }
        if (!PlaylistDownloads(playlists, library, downloads, connection, sourceAccessible, ::catalogue).synchronize(excluded)) completed = false
        downloads.enforceCacheBudget(preferences.load().cacheBytes, inUseTracks())
        return if (completed) PassResult.FINISHED else PassResult.RETRY
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

    /** Lets tests evict down to [bytes] without changing the saved cache size. */
    internal suspend fun enforceCacheBudget(bytes: Long) {
        transfers.withLock { AudioDownloads(store, library, null, directory).enforceCacheBudget(bytes, inUseTracks()) }
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

/** How a pass ended: nothing left that another attempt soon could download, downloads paused, or worth retrying. */
private enum class PassResult { FINISHED, PAUSED, RETRY }

/** A settled pass's inputs, when a smart playlist needs another check, and the songs that wait for free space. */
private class Settled(val inputs: Pair<List<RecordRevision>, Set<String>>, val recheck: TimeMark?, val waitingForSpace: Set<String>)

private val SMART_PLAYLIST_CHECKS = 15.minutes

// Everything a pass reads from the store to decide what to download, keep or evict.
private val PASS_INPUTS = listOf("downloads", "offline-exclusions", "offline-requests", "pins", "playlists", "servers", "settings", "tracks")
