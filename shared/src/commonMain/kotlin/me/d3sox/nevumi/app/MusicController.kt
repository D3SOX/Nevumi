package me.d3sox.nevumi.app

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.history.ScrobblingStatus
import me.d3sox.nevumi.history.LastFmAuthorization
import me.d3sox.nevumi.history.ScrobblingService
import me.d3sox.nevumi.library.MusicFileEdits
import me.d3sox.nevumi.library.LocalFileEdit
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TagChanges
import me.d3sox.nevumi.library.SourceResolver
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.playback.PlayableTrack
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.queue.SavedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.sync.PlaybackTransfer
import me.d3sox.nevumi.sync.withAvailableEntries
import me.d3sox.nevumi.sync.PlaybackReceiver
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.ServerProfile
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.LocalSources
import me.d3sox.nevumi.queue.QueueAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.downloads.DownloadRecord
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.downloads.DownloadNetwork
import me.d3sox.nevumi.downloads.DownloadBatch
import me.d3sox.nevumi.downloads.PlaylistDownloadProgress
import me.d3sox.nevumi.downloads.nextDownloadBatch
import me.d3sox.nevumi.downloads.playlistDownloadProgress
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.library.FileAudioAccess
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.navidrome.NavidromeChanges
import kotlinx.coroutines.flow.debounce
import me.d3sox.nevumi.sftp.*

data class AppState(
    val tracks: List<Track> = emptyList(),
    val queue: QueueSnapshot = QueueSnapshot(),
    val savedQueues: List<SavedQueue> = emptyList(),
    val playback: PlaybackStatus = PlaybackStatus(),
    val loading: Boolean = true,
    val libraryReady: Boolean = false,
    val error: String? = null,
    val playbackConfirmation: PlaybackConfirmation? = null,
    val server: ServerProfile? = null,
    val sftp: SftpConfiguration? = null,
    val serverUploads: List<PendingServerUpload> = emptyList(),
    val downloads: Map<String, DownloadRecord> = emptyMap(),
    val excludedDownloads: Set<String> = emptySet(),
    val playlistDownloads: List<PlaylistDownloadProgress> = emptyList(),
    val downloadBatch: DownloadBatch = DownloadBatch(),
    val downloadNetwork: DownloadNetwork = DownloadNetwork(),
    val playlists: List<Playlist> = emptyList(),
    val preferences: MusicSettings = MusicSettings(),
    val artwork: Map<String, String> = emptyMap(),
    val fileEdits: List<LocalFileEdit> = emptyList(),
    val listens: List<Listen> = emptyList(),
    val scrobbling: ScrobblingStatus = ScrobblingStatus(),
    val lastFmAuthorization: LastFmAuthorization? = null,
    val localScan: me.d3sox.nevumi.library.LocalScanProgress? = null,
    val lastLocalScanCount: Int? = null,
    val sleepTimerEndsAt: Long? = null,
)

data class PlaybackSeek(val entryId: String, val positionMs: Long)
data class PlaybackConfirmation(val title: String, val play: () -> kotlinx.coroutines.Job)
enum class ActionDestination { QUEUE, FAVORITES, PLAYLISTS, DOWNLOADS }
data class ActionNotice(val message: String, val destination: ActionDestination? = null, val undo: (() -> kotlinx.coroutines.Job)? = null,
    val isRedo: Boolean = false)
enum class FileEditTarget { LOCAL, SERVER }

@OptIn(kotlinx.coroutines.FlowPreview::class)
class MusicController(val library: MusicLibrary, val queue: PlaybackQueue, val engine: PlaybackEngine,
    private val navidrome: NavidromeConnection? = null,
    private val localSources: LocalSources? = null,
    private val offline: OfflineMusic? = null,
    private val playlists: Playlists? = null,
    private val sourceAccessible: (String) -> Boolean = FileAudioAccess::available,
    private val preferences: MusicPreferences? = null,
    private val artwork: ArtworkLibrary? = null,
    private val serverChanges: NavidromeChanges? = null,
    private val fileEdits: MusicFileEdits? = null,
    private val listeningHistory: ListeningHistory? = null,
    private val scrobbling: Scrobbling? = null,
    val equalizerProfiles: me.d3sox.nevumi.playback.AutoEqProfiles? = null,
    private val serverFiles: me.d3sox.nevumi.sftp.ServerFiles? = null,
    val pairedDevices: me.d3sox.nevumi.sync.PairedDevices? = null,
    private val networkAccess: suspend (String) -> Unit = {}) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private var sleepTimer: kotlinx.coroutines.Job? = null
    private val mutableState = MutableStateFlow(AppState())
    private val mutableSeeks = MutableSharedFlow<PlaybackSeek>()
    private val mutableNotices = MutableSharedFlow<ActionNotice>(extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val operations = Mutex()
    private val artworkUpdates = Mutex()
    private val transport = Mutex()
    private data class TransportIntent(val revision: Long = 0, val play: Boolean = false)
    private val transportIntent = MutableStateFlow(TransportIntent())
    private val imports = Mutex()
    private val shutdown = Mutex()
    private var shutdownFinished = false
    private var queueLoaded = false
    private var cachedEntryId: String? = null
    private data class IncomingPlayback(val peerId: String, val id: String, val previous: PlaybackTransfer,
        val preparedQueue: QueueSnapshot, val revision: Long)
    private var incomingPlayback: IncomingPlayback? = null
    private val failedSources = mutableMapOf<String, MutableSet<String>>()
    val state = mutableState.asStateFlow()
    val seeks = mutableSeeks.asSharedFlow()
    val notices = mutableNotices.asSharedFlow()
    private fun confirm(message: String, destination: ActionDestination? = null, serializedUndo: Boolean = true,
        undo: (suspend () -> Unit)? = null, redo: (suspend () -> Unit)? = null, isRedo: Boolean = false,
        redoCreatesNotice: Boolean = false) {
        var used = false
        val reverse: (() -> kotlinx.coroutines.Job)? = undo?.let { change -> { action(serialized = serializedUndo) {
            if (!used) {
                change(); used = true
                if (!(isRedo && redoCreatesNotice)) confirm(if (isRedo) "Redone" else "Undone", destination, serializedUndo,
                    undo = redo, redo = undo, isRedo = !isRedo, redoCreatesNotice = redoCreatesNotice)
            }
        } } }
        mutableNotices.tryEmit(ActionNotice(message, destination, reverse, isRedo))
    }

    private suspend fun restoreQueue(before: QueueSnapshot) {
        val active = queue.snapshot()
        val inactiveSaved = before.selectedSavedQueueId?.takeIf { it != active.selectedSavedQueueId }
        require(before.selectedSavedQueueId != null || active.selectedSavedQueueId == null) {
            "The queue changed. This action cannot be undone in the selected saved queue."
        }
        val live = if (inactiveSaved == null) active else requireNotNull(queue.saved().firstOrNull { it.id == inactiveSaved }) {
            "The saved queue was removed. This action cannot be undone."
        }.snapshot
        val playing = live.entries.getOrNull(live.currentIndex)
        val entries = before.entries.toMutableList()
        var original = before.unshuffledEntryIds
        if (playing != null && entries.none { it.id == playing.id }) {
            val previous = live.entries.take(live.currentIndex).lastOrNull { entry -> entries.any { it.id == entry.id } }?.id
            val index = entries.indexOfFirst { it.id == previous } + 1
            entries.add(index, playing)
            original = original?.toMutableList()?.apply { add(indexOf(previous) + 1, playing.id) }
        }
        val current = playing?.id ?: before.currentEntryId
        val restored = before.copy(entries = entries, unshuffledEntryIds = original, currentEntryId = current,
            selectedSavedQueueId = before.selectedSavedQueueId,
            positionMs = if (current == live.currentEntryId) live.positionMs else before.positionMs)
        if (inactiveSaved != null) {
            queue.updateSavedSnapshot(inactiveSaved, restored)
            val saved = queue.saved()
            mutableState.update { it.copy(savedQueues = saved) }
            return
        }
        queue.restore(restored)
        mutableState.update { it.copy(queue = restored) }
        if (queueLoaded) {
            if (current != live.currentEntryId && restored.currentIndex >= 0) loadSnapshot(restored, engine.status.value.playWhenReady)
            else engine.updateQueue(playableQueue(restored))
        }
    }
    val fileEditingAvailable: Boolean get() = fileEdits != null || serverFiles != null
    val localFileEditingAvailable: Boolean get() = fileEdits != null
    val serverPlaylistPublishingAvailable: Boolean get() = serverChanges != null
    val serverFilesAvailable: Boolean get() = serverFiles != null
    val scrobblingAvailable: Boolean get() = scrobbling != null
    fun canEditServerTrack(track: Track): Boolean {
        val configuration = state.value.sftp ?: return false
        return serverFiles != null && configuration.serverId == state.value.server?.serverId && track.sources.any {
            it.kind == SourceKind.SERVER && it.available && it.id.startsWith("server:${configuration.serverId}:") &&
                it.fileLibraryId == configuration.libraryId && it.filePath != null
        }
    }
    private fun fileEditor(target: FileEditTarget): MusicFileEdits = requireNotNull(
        if (target == FileEditTarget.SERVER) serverFiles else fileEdits) { "File editing is unavailable" }
    private fun journalEditor(editId: String): MusicFileEdits {
        val edit = requireNotNull(state.value.fileEdits.firstOrNull { it.id == editId }) { "File edit is missing" }
        return fileEditor(if (edit.providerId.startsWith("sftp:")) FileEditTarget.SERVER else FileEditTarget.LOCAL)
    }
    suspend fun musicFileName(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL) = fileEditor(target).fileName(trackId)

    init {
        pairedDevices?.setPlaybackReceiver(object : PlaybackReceiver {
            override suspend fun receive(peerId: String, playback: PlaybackTransfer) = operations.withLock {
                require(playback.id.isNotBlank() && playback.queue.entries.isNotEmpty() && playback.queue.currentIndex >= 0 &&
                    playback.queue.entries.map { it.id }.distinct().size == playback.queue.entries.size && playback.queue.positionMs >= 0 &&
                    playback.speed.isFinite() && playback.speed in .25..4.0 && playback.pitch.isFinite() && playback.pitch in .25..4.0) { "Invalid playback transfer" }
                val playableIds = playableQueue(playback.queue, checkAllSources = true).map { it.entryId }.toSet()
                require(playback.queue.entries.withIndex().all { (index, entry) -> entry.id in playableIds ||
                    (entry.id != playback.queue.currentEntryId && (index < playback.queue.currentIndex ||
                        entry.section != me.d3sox.nevumi.queue.QueueSection.MANUAL)) }) {
                    "The current song or a manually queued song has no available audio source on this device"
                }
                val prepared = playback.copy(queue = playback.queue.copy(selectedSavedQueueId = null))
                    .withAvailableEntries(playback.queue.entries.filter { it.id in playableIds })
                val previous = capturePlayback(Uuid.random().toString(), includeSavedSelection = true)
                try {
                    applyTransferredPlayback(prepared.copy(playWhenReady = false))
                    incomingPlayback = IncomingPlayback(peerId, playback.id, previous, prepared.queue, transportIntent.value.revision)
                } catch (error: Exception) {
                    restoreTransferredPlayback(previous)
                    throw error
                }
                prepared.skippedSongs
            }
            override suspend fun activate(peerId: String, transferId: String, positionMs: Long, playWhenReady: Boolean) = operations.withLock {
                val incoming = requireNotNull(incomingPlayback) { "Playback transfer was canceled" }
                require(positionMs >= 0 && incoming.peerId == peerId && incoming.id == transferId &&
                    queue.snapshot().copy(positionMs = incoming.preparedQueue.positionMs) == incoming.preparedQueue &&
                    transportIntent.value.revision == incoming.revision) { "Playback changed on the receiving device" }
                engine.seek(positionMs)
                withTimeout(5_000) { engine.status.first { it.entryId == incoming.preparedQueue.currentEntryId && abs(it.positionMs - positionMs) < 250 } }
                queue.setPosition(positionMs)
                if (playWhenReady) play().join() else pause().join()
                incomingPlayback = incoming.copy(revision = transportIntent.value.revision)
                mutableState.update { it.copy(queue = it.queue.copy(positionMs = positionMs), playback = engine.status.value) }
            }
            override suspend fun release(peerId: String, transferId: String): PlaybackTransfer = operations.withLock {
                require(incomingPlayback?.let { it.peerId == peerId && it.id == transferId } == true) { "Playback has changed on the other device" }
                val current = capturePlayback(transferId)
                pause().join()
                incomingPlayback = null
                current
            }
            override suspend fun cancel(peerId: String, transferId: String) = operations.withLock {
                val incoming = incomingPlayback
                if (incoming != null && incoming.peerId == peerId && incoming.id == transferId) {
                    if (queue.snapshot().copy(positionMs = incoming.preparedQueue.positionMs) == incoming.preparedQueue && transportIntent.value.revision == incoming.revision)
                        restoreTransferredPlayback(incoming.previous)
                    incomingPlayback = null
                }
            }
        })
        artwork?.let { covers -> scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val updates = kotlinx.coroutines.channels.Channel<Pair<String, String?>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            launch(start = CoroutineStart.UNDISPATCHED) { covers.changes.collect { updates.send(it) } }
            for (first in updates) {
                // A scan saves thousands of covers in a row; publish the ones that arrived meanwhile in one state change.
                val batch = linkedMapOf(first)
                while (true) batch += updates.tryReceive().getOrNull() ?: break
                artworkUpdates.withLock {
                    mutableState.update { state -> state.copy(artwork = state.artwork.toMutableMap().apply {
                        for ((id, path) in batch) if (path == null) remove(id) else put(id, path)
                    }) }
                }
            }
        } }
        scope.launch { queue.observeSaved().collect { operations.withLock {
            val saved = queue.saved()
            val snapshot = queue.snapshot()
            if (snapshot.selectedSavedQueueId != null && saved.none { it.id == snapshot.selectedSavedQueueId }) {
                queue.detachSaved()
                mutableState.update { it.copy(queue = it.queue.copy(selectedSavedQueueId = null)) }
            }
            mutableState.update { it.copy(savedQueues = saved) }
        } } }
        scope.launch {
            state.map { Triple(it.libraryReady && it.preferences.queueRecoveryEnabled, it.preferences.queueRecoveryMode,
                it.preferences.queueRecoveryIntervalMinutes) }.distinctUntilChanged().collectLatest { (enabled, mode, minutes) ->
                if (enabled && mode == me.d3sox.nevumi.settings.QueueRecoveryMode.TIMER) {
                    while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                        delay(minutes * 60_000L)
                        operations.withLock { persistPlaybackPosition(); queue.captureRecovery() }
                    }
                }
            }
        }
        pairedDevices?.let { devices -> scope.launch {
            devices.state.map { it.peers.map { peer -> peer.lastSyncedAt } }.distinctUntilChanged().drop(1).collect {
                operations.withLock {
                    val saved = queue.saved()
                    val snapshot = queue.snapshot()
                    if (queueLoaded) {
                        if (snapshot.currentIndex >= 0 && snapshot.currentEntryId != engine.status.value.entryId)
                            loadSnapshot(snapshot, engine.status.value.playWhenReady)
                        else engine.updateQueue(playableQueue(snapshot))
                    }
                    mutableState.update { it.copy(savedQueues = saved, queue = snapshot) }
                }
            }
        } }
        serverChanges?.start()
        scrobbling?.start()
        scrobbling?.let { service -> scope.launch {
            service.observe().collect { status -> operations.withLock { mutableState.update { it.copy(scrobbling = status) } } }
        } }
        scope.launch { library.observe().collect {
            operations.withLock {
                val tracks = library.tracks()
                mutableState.update { it.copy(tracks = tracks) }
            }
        } }
        playlists?.let { collections -> scope.launch {
            collections.observe().collect { updated ->
                operations.withLock { mutableState.update { it.copy(playlists = updated) } }
            }
        } }
        listeningHistory?.let { history -> scope.launch {
            history.observe().collect { listens ->
                operations.withLock { mutableState.update { it.copy(listens = listens) } }
            }
        } }
        offline?.let { downloads -> scope.launch {
            var completed: Map<String, String?>? = null
            downloads.state.collect { progress ->
                operations.withLock {
                    // Byte progress only affects the UI; the player needs to hear about finished or evicted files.
                    val files = progress.filterValues { it.status == DownloadStatus.COMPLETE }.mapValues { it.value.path }
                    if (queueLoaded && files != completed) engine.updateQueue(playableQueue(queue.snapshot()))
                    completed = files
                    mutableState.update { it.copy(downloads = progress) }
                }
            }
        } }
        offline?.let { downloads -> scope.launch {
            downloads.excluded.collect { excluded ->
                operations.withLock { mutableState.update { it.copy(excludedDownloads = excluded) } }
            }
        } }
        if (offline != null) scope.launch {
            mutableState.map { listOf(it.downloads, it.playlists, it.tracks, it.server?.serverId, it.excludedDownloads) }
                .distinctUntilChanged().collect {
                    operations.withLock { mutableState.update { state ->
                        val playlists = playlistDownloadProgress(state.playlists, state.tracks, state.downloads,
                            state.server?.serverId, state.excludedDownloads)
                        state.copy(playlistDownloads = playlists,
                            downloadBatch = nextDownloadBatch(state.downloadBatch.trackIds, state.downloads, playlists))
                    } }
                }
        }
        offline?.let { downloads -> scope.launch {
            downloads.network.collect { network ->
                operations.withLock { mutableState.update { it.copy(downloadNetwork = network) } }
            }
        } }
        action(serialized = false) {
            operations.withLock { refreshLibrary() }
            var recoveryError: String? = null
            suspend fun recover(block: suspend () -> Unit) {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (recoveryError == null) recoveryError = error.message ?: "File recovery failed" }
            }
            recover { fileEdits?.recover() }
            recover { serverFiles?.recover() }
            recover { trimFileHistory() }
            recover { pairedDevices?.start() }
            recover {
                val authorization = scrobbling?.pendingLastFmAuthorization()
                mutableState.update { it.copy(lastFmAuthorization = authorization) }
            }
            operations.withLock {
                if (preferences != null) {
                    engine.setSpeed(state.value.preferences.speed)
                    engine.setPitch(state.value.preferences.pitch)
                    engine.setAudioEffects(state.value.preferences.audioEffects)
                    engine.setCrossfadeDuration(state.value.preferences.crossfadeDurationMs)
                    engine.setGaplessPlayback(state.value.preferences.gaplessPlayback)
                }
                val saved = queue.snapshot()
                if (saved.currentIndex >= 0 && !queueLoaded) {
                    loadSnapshot(saved, play = false)
                    queueLoaded = true
                }
                refreshLibrary()
            }
            refreshDetails(loading = false)
            mutableState.update { it.copy(loading = false, error = recoveryError ?: it.error) }
        }
        localSources?.let { sources -> scope.launch {
            sources.pendingRefreshLocations().forEach { importSource(it).join() }
        } }
        localSources?.let { sources -> scope.launch {
            sources.changes.debounce(300).collect { sources.roots().forEach { importSource(it).join() } }
        } }
        scope.launch { engine.status.collect {
            operations.withLock {
                val playback = engine.status.value
                var snapshot = queue.snapshot()
                if (snapshot.entries.any { it.id == playback.entryId }) {
                    if (snapshot.repeatPlays != null && snapshot.currentEntryId == playback.entryId &&
                        (snapshot.repeatMode != playback.repeatMode || snapshot.repeatPlays != playback.repeatPlays))
                        queue.setRepeat(playback.repeatMode, playback.repeatPlays)
                    if (snapshot.currentEntryId != playback.entryId) queue.select(requireNotNull(playback.entryId))
                    if (snapshot.currentEntryId != playback.entryId || abs(snapshot.positionMs - playback.positionMs) >= 1_000 ||
                        !playback.playing && snapshot.positionMs != playback.positionMs)
                        queue.setPosition(playback.positionMs)
                    snapshot = queue.snapshot()
                }
                var recovered = false
                val failedEntry = playback.entryId
                val failedSource = playback.sourceId
                if (playback.error != null && failedEntry != null && failedSource != null && snapshot.currentEntryId == failedEntry &&
                    failedSources.getOrPut(failedEntry) { mutableSetOf() }.add(failedSource)) {
                    val playable = playableQueue(snapshot)
                    val index = playable.indexOfFirst { it.entryId == failedEntry }
                    if (index >= 0) {
                        engine.loadQueue(playable, index, snapshot.positionMs, playback.playWhenReady)
                        queueLoaded = true
                        recovered = true
                    }
                }
                if (offline != null && playback.playing && playback.entryId != cachedEntryId && snapshot.currentEntryId == playback.entryId) {
                    cachedEntryId = playback.entryId
                    offline.useTrack(playback.trackId)
                    val track = playback.trackId?.let { library.findTrack(it) }
                    if (track?.sources?.any { it.id == playback.sourceId && it.kind == SourceKind.SERVER } == true)
                        offline.request(listOf(track.id), owner = null)
                }
                listeningHistory?.record(playback, enabled = state.value.preferences.recordListeningHistory)
                scrobbling?.onPlayback(playback)
                mutableState.update { it.copy(playback = playback, queue = snapshot,
                    error = if (playback.error != null && !recovered) playback.error else it.error) }
            }
        } }
    }

    private suspend fun refreshLibrary() {
        val tracks = library.tracks()
        val snapshot = queue.snapshot()
        val settings = preferences?.load() ?: MusicSettings()
        mutableState.update { it.copy(tracks = tracks, queue = snapshot, preferences = settings, loading = false, libraryReady = true) }
    }

    suspend fun refresh(loading: Boolean = false) {
        refreshLibrary()
        refreshDetails(loading)
    }

    private suspend fun refreshDetails(loading: Boolean) {
        val tracks = state.value.tracks
        val savedQueues = queue.saved()
        val profile = navidrome?.profile()
        val collections = playlists?.all().orEmpty()
        val editHistory = (fileEdits?.history().orEmpty() + serverFiles?.history().orEmpty()).sortedBy { it.createdAt }
        val listens = listeningHistory?.all().orEmpty()
        val sftp = serverFiles?.configuration()
        val uploads = serverFiles?.pendingUploads().orEmpty()
        artworkUpdates.withLock {
            val covers = artwork?.paths(tracks.map { it.id } + collections.flatMap { listOf("playlist:${it.id}", "server-playlist-cover:${it.id}") }).orEmpty()
            mutableState.update { it.copy(savedQueues = savedQueues, loading = loading, server = profile,
                playlists = collections, artwork = covers, fileEdits = editHistory, listens = listens,
                sftp = sftp, serverUploads = uploads) }
        }
    }
    suspend fun serverMusicDirectories() = requireNotNull(serverFiles) { "Server file editing is unavailable" }.libraries()
    suspend fun connectServerFiles(profile: SftpProfile, libraryId: Long, authentication: SftpAuthentication) {
        if (!profile.tailscale) networkAccess(profile.host)
        requireNotNull(serverFiles) { "Server file editing is unavailable" }.connect(profile, libraryId, authentication)
        operations.withLock { refresh() }
    }
    suspend fun tailscaleStatus() = requireNotNull(serverFiles) { "Tailscale is unavailable" }.tailscaleStatus()
    suspend fun signInToTailscale(controlUrl: String, authKey: String?) =
        requireNotNull(serverFiles) { "Tailscale is unavailable" }.signInToTailscale(controlUrl, authKey)
    suspend fun signOutOfTailscale() = requireNotNull(serverFiles) { "Tailscale is unavailable" }.signOutOfTailscale()
    suspend fun disconnectServerFiles() {
        requireNotNull(serverFiles) { "Server file editing is unavailable" }.disconnect()
        operations.withLock { refresh() }
    }
    fun uploadTrack(trackId: String, relativePath: String) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try { requireNotNull(serverFiles) { "Server uploads are unavailable" }.upload(trackId, relativePath) }
        finally { operations.withLock { refresh() } }
    }
    fun retryServerUpload(id: String) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try { requireNotNull(serverFiles) { "Server uploads are unavailable" }.retryUpload(id) }
        finally { operations.withLock { refresh() } }
    }
    fun discardServerUpload(id: String) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try { requireNotNull(serverFiles) { "Server uploads are unavailable" }.discardUpload(id) }
        finally { operations.withLock { refresh() } }
    }
    fun reload() = action { refresh() }
    suspend fun fileTags(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL): TrackMetadata = fileEditor(target).readTags(trackId)
    fun linkTracks(keepId: String, otherId: String) = action {
        mutableState.update { it.copy(error = null) }
        suspend fun link() {
            val linked = mergeTracks(keepId, otherId)
            serverChanges?.setFavorite(linked.id, linked.favorite)
            serverChanges?.setRating(linked.id, linked.rating)
            if (queueLoaded) engine.updateQueue(playableQueue(queue.snapshot()))
            refresh()
        }
        if (offline != null) offline.withLibraryChanges { link() } else link()
    }
    private suspend fun mergeTracks(keepId: String, otherId: String): Track {
        val cover = if (artwork?.path(keepId) == null) artwork?.read(otherId) else null
        if (cover != null) artwork?.save(keepId, cover)
        val linked = fileEdits?.linkTracks(keepId, otherId) ?: library.linkTracks(keepId, otherId)
        if (state.value.playback.trackId == otherId || state.value.playback.trackId == keepId) offline?.useTrack(keepId)
        return linked
    }
    // Local files that were also uploaded to the server become one song, so they are not downloaded a second time.
    suspend fun linkSameFiles() {
        val pairs = library.sameFileCopies()
        if (pairs.isEmpty()) return
        // One pair per lock keeps playback responsive during the first pass over a large library.
        for ((serverId, localId) in pairs) operations.withLock {
            suspend fun link() {
                val server = library.findTrack(serverId) ?: return
                if (library.findTrack(localId) == null) return
                val queued = queueLoaded && queue.snapshot().entries.any { it.trackId == serverId || it.trackId == localId }
                // The local song keeps its identity, so references to the file stay valid.
                val linked = try { mergeTracks(localId, serverId) }
                    // A song with a running download or file edit is linked on a later pass.
                    catch (_: IllegalStateException) { return }
                if (linked.favorite != server.favorite) serverChanges?.setFavorite(linked.id, linked.favorite)
                if (linked.rating != server.rating) serverChanges?.setRating(linked.id, linked.rating)
                if (queued) engine.updateQueue(playableQueue(queue.snapshot()))
            }
            if (offline != null) offline.withLibraryChanges { link() } else link()
        }
        operations.withLock { refresh() }
    }
    fun editTags(trackId: String, metadata: TrackMetadata, target: FileEditTarget = FileEditTarget.LOCAL) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try {
            fileEditor(target).editTags(trackId, metadata)
            trimFileHistory()
        } finally { operations.withLock { refresh() } }
    }
    fun undoFileEdit(editId: String) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try {
            journalEditor(editId).undo(editId)
            trimFileHistory()
        } finally { operations.withLock { refresh() } }
    }
    fun editTags(trackIds: List<String>, changes: TagChanges, target: FileEditTarget = FileEditTarget.LOCAL) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try {
            fileEditor(target).editTags(trackIds, changes)
            trimFileHistory()
        }
        finally { operations.withLock { refresh() } }
    }
    fun replaceArtwork(trackId: String, bytes: ByteArray, target: FileEditTarget = FileEditTarget.LOCAL) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try {
            fileEditor(target).replaceArtwork(trackId, bytes)
            trimFileHistory()
        } finally { operations.withLock { refresh() } }
    }
    fun renameFile(trackId: String, name: String, target: FileEditTarget = FileEditTarget.LOCAL) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try {
            fileEditor(target).rename(trackId, name)
            trimFileHistory()
        }
        finally { operations.withLock { refresh() } }
    }
    fun deleteFile(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try {
            fileEditor(target).delete(trackId)
            trimFileHistory()
        }
        finally { operations.withLock { refresh() } }
    }
    fun restoreOriginalFile(editId: String) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try {
            journalEditor(editId).restoreOriginal(editId)
            trimFileHistory()
        } finally { operations.withLock { refresh() } }
    }
    fun applyFileEdit(editId: String) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        try {
            journalEditor(editId).apply(editId)
            trimFileHistory()
        } finally { operations.withLock { refresh() } }
    }
    fun discardFileEdit(editId: String) = action(serialized = false) {
        mutableState.update { it.copy(error = null) }
        journalEditor(editId).discard(editId)
        operations.withLock { refresh() }
    }
    private suspend fun trimFileHistory() {
        val settings = preferences?.load() ?: MusicSettings()
        fileEdits?.trimHistory(settings.undoRetentionDays * 86_400_000L, settings.undoBackupBytes)
        serverFiles?.trimHistory(settings.undoRetentionDays * 86_400_000L, settings.undoBackupBytes)
    }
    fun updatePreferences(change: (MusicSettings) -> MusicSettings) = action {
        val previous = state.value.preferences
        val settings = requireNotNull(preferences) { "Settings are unavailable" }.update(change)
        if (settings.recordListeningHistory != previous.recordListeningHistory)
            listeningHistory?.record(engine.status.value, enabled = settings.recordListeningHistory)
        mutableState.update { it.copy(preferences = settings) }
        if (settings.audioEffects != previous.audioEffects) engine.setAudioEffects(settings.audioEffects)
        if (settings.crossfadeDurationMs != previous.crossfadeDurationMs) engine.setCrossfadeDuration(settings.crossfadeDurationMs)
        if (settings.gaplessPlayback != previous.gaplessPlayback) engine.setGaplessPlayback(settings.gaplessPlayback)
        if (queueLoaded && settings.sourcePriority != previous.sourcePriority)
            engine.updateQueue(playableQueue(queue.snapshot()))
        if (settings.undoRetentionDays != previous.undoRetentionDays || settings.undoBackupBytes != previous.undoBackupBytes) {
            trimFileHistory()
            refresh()
        }
    }
    fun rememberLibraryTab(tab: me.d3sox.nevumi.library.LibraryTab) = action(serialized = false) {
        if (state.value.preferences.lastLibraryTab != tab) {
            val settings = preferences?.update { it.copy(lastLibraryTab = tab) }
                ?: state.value.preferences.copy(lastLibraryTab = tab)
            mutableState.update { it.copy(preferences = settings) }
        }
    }

    fun rememberLibraryPosition(key: String, position: me.d3sox.nevumi.settings.LibraryPosition) = action(serialized = false) {
        if (state.value.preferences.libraryPositions[key] != position) {
            val settings = preferences?.update { it.copy(libraryPositions = it.libraryPositions + (key to position)) }
            mutableState.update { it.copy(preferences = it.preferences.copy(libraryPositions =
                settings?.libraryPositions ?: (it.preferences.libraryPositions + (key to position)))) }
        }
    }
    fun clearListeningHistory() = action {
        val history = requireNotNull(listeningHistory) { "Listening history is unavailable" }
        if (scrobbling != null) scrobbling.clearHistory(history) else history.clear()
        mutableState.update { it.copy(listens = emptyList()) }
    }
    suspend fun connectListenBrainz(token: String, address: String = "https://api.listenbrainz.org") =
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.connectListenBrainz(token, address)
    suspend fun startLastFmSignIn(apiKey: String, sharedSecret: String): LastFmAuthorization {
        val authorization = requireNotNull(scrobbling) { "Scrobbling is unavailable" }.startLastFmAuthorization(apiKey, sharedSecret)
        mutableState.update { it.copy(lastFmAuthorization = authorization) }
        return authorization
    }
    suspend fun completeLastFmSignIn() {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.completeLastFmAuthorization()
        mutableState.update { it.copy(lastFmAuthorization = null) }
    }
    suspend fun cancelLastFmSignIn() {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.cancelLastFmAuthorization()
        mutableState.update { it.copy(lastFmAuthorization = null) }
    }
    fun setScrobblingEnabled(id: String, enabled: Boolean) = action(serialized = false) {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.setEnabled(id, enabled)
    }
    fun retryScrobbleSubmission(id: String) = action(serialized = false) {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.retrySubmission(id)
    }
    fun discardScrobbleSubmission(id: String) = action(serialized = false) {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.discardSubmission(id)
    }
    fun disconnectScrobbling(id: String) = action(serialized = false) {
        if (requireNotNull(scrobbling) { "Scrobbling is unavailable" }.disconnect(id) == ScrobblingService.LASTFM)
            mutableState.update { it.copy(lastFmAuthorization = null) }
    }
    fun downloadTracks(trackIds: List<String>) = action {
        val remote = trackIds.distinct().filter { id ->
            requireNotNull(library.findTrack(id)) { "Track is missing" }.sources.none { it.kind == SourceKind.LOCAL && it.available }
        }
        if (remote.isEmpty()) { confirm("Already available offline"); return@action }
        val downloads = requireNotNull(offline) { "Downloads are unavailable" }
        var undo = downloads.requestUndoable(remote)
        confirm("Download queued", ActionDestination.DOWNLOADS, undo = { undo() }, redo = { undo = downloads.requestUndoable(remote) })
    }
    fun removeDownload(trackId: String) = action {
        requireNotNull(offline) { "Downloads are unavailable" }.remove(trackId)
        refresh()
    }
    fun setDownloadsPaused(paused: Boolean) = action {
        val downloads = requireNotNull(offline) { "Downloads are unavailable" }
        if (paused) downloads.pause() else downloads.resume()
        refresh()
    }
    fun retryDownloads() { offline?.retry() }
    fun setFavorite(trackId: String, favorite: Boolean) = setFavorite(listOf(trackId), favorite)
    fun setFavorite(trackIds: List<String>, favorite: Boolean) = action {
        val before = trackIds.distinct().associateWith { requireNotNull(library.findTrack(it)) { "Track is missing" }.favorite }
        applyFavorites(before.mapValues { favorite })
        confirm(if (favorite) "Added to favorites" else "Removed from favorites", ActionDestination.FAVORITES,
            undo = { applyFavorites(before) }, redo = { applyFavorites(before.mapValues { favorite }) })
    }
    private suspend fun applyFavorites(values: Map<String, Boolean>) {
        values.forEach { (id, favorite) ->
            if (serverChanges != null) serverChanges.setFavorite(id, favorite) else library.setFavorite(id, favorite)
        }
        mutableState.update { state -> state.copy(tracks = state.tracks.map { track ->
            values[track.id]?.let { track.copy(favorite = it) } ?: track
        }) }
    }
    fun createPlaylist(name: String, trackIds: List<String> = emptyList()) = action {
        createPlaylistWithUndo(name, trackIds)
    }
    suspend fun createQueuePlaylist(name: String, snapshot: QueueSnapshot, selection: me.d3sox.nevumi.queue.QueueExportScope) = withContext(Dispatchers.Default) {
        operations.withLock {
            val ids = selection.trackIds(snapshot)
            require(ids.isNotEmpty()) { "Choose a queue section with songs" }
            ids.forEach { requireNotNull(library.findTrack(it)) { "A queue song is missing from the library" } }
            createPlaylistWithUndo(name, ids)
        }
    }
    private suspend fun createPlaylistWithUndo(name: String, trackIds: List<String>): Playlist {
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val created = collections.create(name, trackIds)
        refreshPlaylists()
        confirm("Playlist created", ActionDestination.PLAYLISTS, undo = { collections.delete(created.id); refreshPlaylists() },
            redo = { collections.restore(created); refreshPlaylists() })
        return created
    }
    fun setRating(trackId: String, rating: Int) = action {
        val before = requireNotNull(library.findTrack(trackId)) { "Track is missing" }.rating
        if (serverChanges != null) serverChanges.setRating(trackId, rating) else library.setRating(trackId, rating)
        updateTrackFeedback(setOf(trackId)) { it.copy(rating = rating) }
        confirm(if (rating == 0) "Rating cleared" else if (rating == 1) "Rated 1 star" else "Rated $rating stars", undo = {
            if (serverChanges != null) serverChanges.setRating(trackId, before) else library.setRating(trackId, before)
            updateTrackFeedback(setOf(trackId)) { it.copy(rating = before) }
        }, redo = {
            if (serverChanges != null) serverChanges.setRating(trackId, rating) else library.setRating(trackId, rating)
            updateTrackFeedback(setOf(trackId)) { it.copy(rating = rating) }
        })
    }
    private fun updateTrackFeedback(ids: Set<String>, change: (Track) -> Track) {
        mutableState.update { state -> state.copy(tracks = state.tracks.map { if (it.id in ids) change(it) else it }) }
    }
    private suspend fun refreshPlaylists() {
        val updated = playlists?.all().orEmpty()
        mutableState.update { it.copy(playlists = updated) }
    }
    fun publishPlaylist(id: String) = action {
        requireNotNull(serverChanges) { "Playlist synchronization is unavailable" }.publishPlaylist(id)
        refreshPlaylists()
    }
    suspend fun importM3u(document: me.d3sox.nevumi.ui.PlaylistDocument) = withContext(Dispatchers.Default) {
        operations.withLock {
            val result = me.d3sox.nevumi.playlists.M3uPlaylists(library, requireNotNull(playlists))
                .import(document.name, document.text, document.baseLocation)
            refreshPlaylists()
            result
        }
    }
    suspend fun exportM3u(id: String) = withContext(Dispatchers.Default) {
        operations.withLock { me.d3sox.nevumi.playlists.M3uPlaylists(library, requireNotNull(playlists)).export(id) }
    }
    suspend fun exportQueueM3u(snapshot: QueueSnapshot, selection: me.d3sox.nevumi.queue.QueueExportScope) = withContext(Dispatchers.Default) {
        operations.withLock { me.d3sox.nevumi.playlists.M3uPlaylists(library).exportTracks(selection.trackIds(snapshot)) }
    }
    fun createSmartPlaylist(name: String, rule: me.d3sox.nevumi.library.LibraryQuery) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.createSmart(name, rule)
        refreshPlaylists()
    }
    fun updatePlaylistRule(id: String, rule: me.d3sox.nevumi.library.LibraryQuery) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.updateRule(id, rule)
        refreshPlaylists()
    }
    fun addToPlaylist(playlistId: String, trackId: String) = addToPlaylist(playlistId, listOf(trackId))
    fun addToPlaylist(playlistId: String, trackIds: List<String>) = action {
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val before = requireNotNull(collections.get(playlistId)) { "Playlist is missing" }.trackIds
        collections.append(playlistId, trackIds)
        refreshPlaylists()
        val after = requireNotNull(collections.get(playlistId)).trackIds
        confirm("Added to playlist", ActionDestination.PLAYLISTS, undo = { collections.replaceTracks(playlistId, before); refreshPlaylists() },
            redo = { collections.replaceTracks(playlistId, after); refreshPlaylists() })
    }
    fun managePlaylistMembership(trackIds: List<String>, choices: Map<String, Boolean>) = action {
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val before = choices.keys.associateWith { requireNotNull(collections.get(it)) { "Playlist is missing" }.trackIds }
        collections.updateMembership(trackIds, choices)
        refreshPlaylists()
        val after = choices.keys.associateWith { requireNotNull(collections.get(it)).trackIds }
        confirm("Playlists updated", ActionDestination.PLAYLISTS, undo = {
            before.forEach { (id, tracks) -> collections.replaceTracks(id, tracks) }; refreshPlaylists()
        }, redo = {
            after.forEach { (id, tracks) -> collections.replaceTracks(id, tracks) }; refreshPlaylists()
        })
    }
    fun setPlaylistShuffle(playlistId: String, enabled: Boolean) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.setShuffle(playlistId, enabled)
        refreshPlaylists()
    }
    fun setPlaylistSort(playlistId: String, sort: me.d3sox.nevumi.playlists.PlaylistSort, descending: Boolean) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.setSort(playlistId, sort, descending)
        refreshPlaylists()
    }
    fun editPlaylist(playlistId: String, name: String, description: String, cover: ByteArray? = null, removeCover: Boolean = false) = action {
        require(name.isNotBlank()) { "Name the playlist" }
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val previous = requireNotNull(collections.get(playlistId)) { "Playlist is missing" }
        require(previous.server?.readOnly != true) { "Only the owner can edit this playlist" }
        val link = previous.server
        if (link != null && (cover != null || removeCover)) {
            val client = serverPlaylistClient(link)
            val oldCover = if (client.playlistImageUploaded(link.id!!)) link.coverArt?.let { client.coverArt(it, size = null) } else null
            val oldLocalCover = artwork?.read("playlist:$playlistId")
            setServerPlaylistCover(playlistId, link, client, cover)
            collections.edit(playlistId, name, description)
            refreshPlaylists()
            confirm("Playlist updated", ActionDestination.PLAYLISTS, undo = {
                collections.edit(playlistId, previous.name, previous.description)
                setServerPlaylistCover(playlistId, link, serverPlaylistClient(link), oldCover)
                oldLocalCover?.let { artwork?.save("playlist:$playlistId", it) }
                refreshPlaylists()
            }, redo = {
                collections.edit(playlistId, name, description)
                setServerPlaylistCover(playlistId, link, serverPlaylistClient(link), cover)
                refreshPlaylists()
            })
            return@action
        }
        val key = "playlist:$playlistId"
        val oldCover = if (cover != null || removeCover) artwork?.read(key) else null
        if (cover != null) requireNotNull(artwork) { "Artwork editing is unavailable" }.save(key, cover)
        else if (removeCover) artwork?.remove(key)
        collections.edit(playlistId, name, description)
        refreshPlaylists()
        confirm("Playlist updated", ActionDestination.PLAYLISTS, undo = {
            collections.edit(playlistId, previous.name, previous.description)
            if (cover != null || removeCover) {
                if (oldCover == null) artwork?.remove(key) else artwork?.save(key, oldCover)
            }
            refreshPlaylists()
        }, redo = {
            collections.edit(playlistId, name, description)
            if (cover != null) artwork?.save(key, cover) else if (removeCover) artwork?.remove(key)
            refreshPlaylists()
        })
    }
    private suspend fun serverPlaylistClient(link: me.d3sox.nevumi.playlists.PlaylistServer): me.d3sox.nevumi.navidrome.NavidromeClient {
        requireNotNull(link.id) { "Wait for this playlist to sync before changing its cover" }
        navidrome?.profile()?.let { networkAccess(it.baseUrl) }
        return requireNotNull(navidrome?.client()?.takeIf { it.serverId == link.serverId }) { "Connect to the music server to change this playlist's cover" }
    }
    /** Uploads [cover] to the server, or removes the uploaded cover when it is null so Navidrome generates one again. */
    private suspend fun setServerPlaylistCover(playlistId: String, link: me.d3sox.nevumi.playlists.PlaylistServer,
        client: me.d3sox.nevumi.navidrome.NavidromeClient, cover: ByteArray?) {
        val remoteId = requireNotNull(link.id)
        val key = "server-playlist-cover:$playlistId"
        if (cover != null) client.setPlaylistImage(remoteId, cover) else client.removePlaylistImage(remoteId)
        // Drop a cover kept only in the app once the server has the new one, so a failed request changes nothing.
        artwork?.remove("playlist:$playlistId")
        if (cover != null) artwork?.save(key, cover)
        else try { client.playlist(remoteId).coverArt?.let { artwork?.fetch(key, client, it) } ?: artwork?.remove(key) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { artwork?.remove(key) }
    }
    fun renamePlaylist(playlistId: String, name: String) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.rename(playlistId, name)
        refreshPlaylists()
    }
    fun removePlaylistTrack(playlistId: String, index: Int) = action {
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val collection = requireNotNull(collections.get(playlistId)) { "Playlist is missing" }
        require(index in collection.trackIds.indices) { "Playlist entry is missing" }
        collections.replaceTracks(playlistId, collection.trackIds.filterIndexed { position, _ -> position != index })
        refreshPlaylists()
    }
    fun movePlaylistTrack(playlistId: String, from: Int, to: Int) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.moveTrack(playlistId, from, to)
        refreshPlaylists()
    }
    fun deletePlaylist(playlistId: String) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.delete(playlistId)
        refreshPlaylists()
    }
    fun resolvePlaylistConflict(playlistId: String, keepLocal: Boolean) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.resolveConflict(playlistId, keepLocal)
        refreshPlaylists()
    }
    fun setPlaylistDownload(playlistId: String, enabled: Boolean) = action {
        requireNotNull(playlists) { "Playlists are unavailable" }.setAutoDownload(playlistId, enabled)
        refreshPlaylists()
        if (enabled) offline?.downloadPlaylists()
    }
    fun dismissError(error: String) {
        mutableState.update { if (it.error == error) it.copy(error = null) else it }
    }

    fun rescanLocalMusic() = action(serialized = false) {
        localSources?.roots()?.forEach { importSource(it).join() }
    }

    fun updateLocalFolders(included: List<String>, excluded: List<String>) = action(serialized = false) {
        requireNotNull(preferences) { "Local folder preferences are unavailable" }.update {
            it.copy(includeFolders = included.distinct(), excludeFolders = excluded.distinct())
        }
        localSources?.roots()?.forEach { importSource(it).join() }
        operations.withLock { refresh() }
    }

    fun importSource(location: String) = action(serialized = false) {
        imports.withLock {
            mutableState.update { it.copy(loading = true, error = null) }
            var lastProgress = 0L
            try {
                val songs = requireNotNull(localSources) { "Local music access is unavailable" }.scan(location, onProgress = { progress ->
                    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    if (progress.processed == 0 || progress.processed == progress.total || now - lastProgress >= 100) {
                        lastProgress = now
                        mutableState.update { it.copy(localScan = progress) }
                    }
                })
                linkSameFiles()
                operations.withLock { refresh() }
                mutableState.update { it.copy(lastLocalScanCount = songs.size) }
            } finally { mutableState.update { it.copy(localScan = null) } }
        }
    }

    fun connectServer(address: String, username: String, password: String) = action(serialized = false) {
        mutableState.update { it.copy(loading = true, error = null) }
        val connection = requireNotNull(navidrome) { "Server setup is unavailable" }
        networkAccess(address)
        connection.connect(address, ServerCredentials(username, password))
        val client = requireNotNull(connection.client())
        importServer(client)
        operations.withLock { refresh() }
    }

    fun refreshServer() = action(serialized = false) {
        mutableState.update { it.copy(loading = true, error = null) }
        navidrome?.profile()?.let { networkAccess(it.baseUrl) }
        val client = requireNotNull(navidrome?.client()) { "Connect to the music server first" }
        importServer(client)
        offline?.retry()
        operations.withLock { refresh() }
    }

    fun disconnectServer() = action(serialized = false) {
        mutableState.update { it.copy(loading = true, error = null) }
        val connection = requireNotNull(navidrome) { "Server setup is unavailable" }
        val profile = connection.profile() ?: return@action operations.withLock { refresh() }
        if (serverFiles?.configuration()?.serverId == profile.serverId) serverFiles.disconnect()
        library.setSourceAvailability(library.tracks().flatMap { track -> track.sources.filter {
            it.kind == SourceKind.SERVER && it.id.startsWith("server:${profile.serverId}:")
        }.map { it.id } }.toSet(), false)
        connection.disconnect()
        operations.withLock {
            if (queueLoaded) engine.updateQueue(playableQueue(queue.snapshot()))
            refresh()
        }
    }

    private suspend fun importServer(client: me.d3sox.nevumi.navidrome.NavidromeClient) {
        val paths = serverFiles?.configuration()?.serverId == client.serverId
        val catalogue = serverChanges?.refreshLibrary(client, includeFilePaths = paths) ?: client.songs(includeFilePaths = paths).map { song ->
            library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id,
                filePath = song.filePath, fileLibraryId = song.fileLibraryId, indexedSize = song.sizeBytes)) to song
        }
        if (artwork != null) {
            // Songs of an album share one cover; download it once instead of once per song.
            val covers = catalogue.groupBy({ (_, song) -> song.coverArt }, { (track, _) -> track.id })
            for ((coverId, trackIds) in covers) {
                if (coverId == null) continue
                try { artwork.fetch(trackIds, client, coverId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A missing cover must not prevent importing its music. */ }
            }
        }
        linkSameFiles()
    }

    fun playTrack(trackId: String, contextTrackIds: List<String>? = null, contextIndex: Int? = null, contextName: String = "Library", shuffleContext: Boolean = false,
        origin: me.d3sox.nevumi.queue.QueueOrigin = me.d3sox.nevumi.queue.QueueOrigin(), preserveManualQueue: Boolean = false,
        confirmed: Boolean = false): kotlinx.coroutines.Job = action {
        if (requestPlaybackConfirmation(
                state.value.tracks.firstOrNull { it.id == trackId }?.metadata?.title ?: "this music", confirmed) {
                playTrack(trackId, contextTrackIds, contextIndex, contextName, shuffleContext, origin, preserveManualQueue, confirmed = true)
            }) return@action
        mutableState.update { it.copy(playbackConfirmation = null) }
        incomingPlayback = null
        failedSources.clear()
        mutableState.update { it.copy(error = null) }
        val available = state.value.tracks.associateBy { it.id }
        val tracks = contextTrackIds?.mapNotNull { available[it] } ?: state.value.tracks
        val index = contextIndex ?: tracks.indexOfFirst { it.id == trackId }
        require(tracks.getOrNull(index)?.id == trackId) { "Track is missing" }
        val currentQueue = queue.snapshot()
        persistPlaybackPosition()
        val manualQueue = currentQueue.upNext.isNotEmpty() || currentQueue.entries.getOrNull(currentQueue.currentIndex)?.section ==
            me.d3sox.nevumi.queue.QueueSection.MANUAL
        if (preserveManualQueue && manualQueue) queue.playNow(trackId)
        else queue.replace(tracks.map { it.id }, index, contextName, shuffle = shuffleContext, origin = origin)
        loadSnapshot(queue.snapshot())
        queueLoaded = true
    }

    fun togglePlayback() = requestPlayback(null)
    fun transferPlayback(deviceId: String): kotlinx.coroutines.Job = action(serialized = false) {
        val devices = requireNotNull(pairedDevices) { "Device pairing is unavailable" }
        mutableState.update { it.copy(error = null) }
        val revision = transportIntent.value.revision
        val transfer = operations.withLock { capturePlayback(Uuid.random().toString()).also {
            require(it.queue.currentIndex >= 0) { "Choose a song first" }
        } }
        val skippedSongs = devices.preparePlayback(deviceId, transfer)
        try {
            val position = operations.withLock {
                require(queue.snapshot().copy(positionMs = transfer.queue.positionMs, selectedSavedQueueId = null) == transfer.queue && transportIntent.value.revision == revision) {
                    "Playback changed while connecting. The transfer was canceled."
                }
                capturePlayback(transfer.id).queue.positionMs
            }
            devices.activatePlaybackTransfer(deviceId, transfer.id, position, transfer.playWhenReady)
            operations.withLock {
                require(queue.snapshot().copy(positionMs = transfer.queue.positionMs, selectedSavedQueueId = null) == transfer.queue && transportIntent.value.revision == revision) {
                    "Playback changed while connecting. The transfer was canceled."
                }
                pause().join(); incomingPlayback = null
            }
        } catch (error: Exception) {
            try { devices.cancelPlaybackTransfer(deviceId, transfer.id) } catch (_: Exception) { }
            throw error
        }
        val name = devices.state.value.peers.firstOrNull { it.deviceId == deviceId }?.name ?: "paired device"
        val skipped = if (skippedSongs > 0) "; skipped $skippedSongs unavailable ${if (skippedSongs == 1) "song" else "songs"}" else ""
        confirm("Playback moved to $name$skipped", serializedUndo = false, redoCreatesNotice = true, redo = { transferPlayback(deviceId).join() }, undo = {
            require(!engine.status.value.playWhenReady && queue.snapshot().currentEntryId == transfer.queue.currentEntryId) { "Playback has changed on this device" }
            val returned = devices.returnPlayback(deviceId, transfer.id)
            operations.withLock { applyTransferredPlayback(returned) }
        })
    }

    private suspend fun capturePlayback(id: String, includeSavedSelection: Boolean = false): PlaybackTransfer {
        val snapshot = queue.snapshot()
        val playing = engine.status.value
        return PlaybackTransfer(id, snapshot.copy(selectedSavedQueueId = snapshot.selectedSavedQueueId.takeIf { includeSavedSelection },
            positionMs = if (playing.entryId == snapshot.currentEntryId) playing.positionMs else snapshot.positionMs),
            playing.playWhenReady, playing.speed, playing.pitch)
    }

    private suspend fun applyTransferredPlayback(playback: PlaybackTransfer) {
        engine.setSpeed(playback.speed); engine.setPitch(playback.pitch)
        queue.restore(playback.queue)
        loadSnapshot(playback.queue, playback.playWhenReady)
        val ready = withTimeout(10_000) { engine.status.first { it.entryId == playback.queue.currentEntryId && (it.durationMs > 0 || it.error != null) } }
        require(ready.error == null) { ready.error ?: "Playback could not start" }
        preferences?.update { it.copy(speed = playback.speed, pitch = playback.pitch) }?.let { settings ->
            mutableState.update { it.copy(preferences = settings) }
        }
        mutableState.update { it.copy(queue = playback.queue, playback = engine.status.value) }
    }

    private suspend fun restoreTransferredPlayback(previous: PlaybackTransfer) {
        pause().join()
        if (previous.queue.currentIndex >= 0) applyTransferredPlayback(previous)
        else { queue.restore(previous.queue); queueLoaded = false; mutableState.update { it.copy(queue = previous.queue) } }
    }
    fun pause() = requestPlayback(false)
    fun setSleepTimer(minutes: Int?) = action {
        require(minutes == null || minutes in 1..180) { "Choose a sleep timer between 1 and 180 minutes" }
        sleepTimer?.cancel()
        val duration = minutes?.times(60_000L)
        val deadline = duration?.let { kotlin.time.Clock.System.now().toEpochMilliseconds() + it }
        mutableState.update { it.copy(sleepTimerEndsAt = deadline) }
        sleepTimer = duration?.let {
            scope.launch {
                delay(it)
                operations.withLock {
                    if (state.value.sleepTimerEndsAt == deadline) {
                        pause().join()
                        mutableState.update { state -> state.copy(sleepTimerEndsAt = null) }
                    }
                }
            }
        }
    }
    fun play() = requestPlayback(true)
    private fun requestPlayback(play: Boolean?) = action(serialized = false) {
        val load = transport.withLock {
            val playback = engine.status.value
            val requested = play ?: !(playback.playWhenReady && !playback.ended && playback.error == null)
            transportIntent.update { TransportIntent(it.revision + 1, requested) }
            val needsLoad = requested && (!queueLoaded || playback.error != null || playback.ended)
            if (!requested) engine.pause() else if (!needsLoad) engine.play()
            mutableState.update { it.copy(playback = engine.status.value.copy(playWhenReady = requested)) }
            needsLoad
        }
        if (load) operations.withLock { if (transportIntent.value.play) resumePlayback() }
    }
    fun setVolume(volume: Double) = action(serialized = false) { engine.setVolume(volume) }
    fun setSpeed(speed: Double) = action {
        engine.setSpeed(speed)
        preferences?.update { it.copy(speed = speed) }?.let { settings ->
            mutableState.update { it.copy(preferences = settings) }
        }
    }
    fun setPitch(pitch: Double) = action {
        engine.setPitch(pitch)
        preferences?.update { it.copy(pitch = pitch) }?.let { settings ->
            mutableState.update { it.copy(preferences = settings) }
        }
    }
    fun stop() = action {
        engine.pause()
        queue.setPosition(0)
        engine.seek(0)
        publishSeek(0)
    }
    fun seekCurrentEntry(entryId: String, positionMs: Long) = action {
        val playback = engine.status.value
        if (entryId == playback.entryId && positionMs in 0..playback.durationMs) {
            queue.setPosition(positionMs)
            engine.seek(positionMs)
            publishSeek(positionMs)
        }
    }
    fun seek(positionMs: Long) = action {
        queue.setPosition(positionMs)
        engine.seek(positionMs)
        publishSeek(positionMs.coerceAtLeast(0))
    }
    fun selectQueueEntry(entryId: String) = action {
        queue.select(entryId)
        val snapshot = queue.snapshot()
        loadSnapshot(snapshot)
        queueLoaded = true
        mutableState.update { it.copy(queue = snapshot) }
    }
    fun saveQueue(name: String, kind: me.d3sox.nevumi.queue.SavedQueueKind = me.d3sox.nevumi.queue.SavedQueueKind.LIVE) = action {
        persistPlaybackPosition()
        queue.save(name, select = kind == me.d3sox.nevumi.queue.SavedQueueKind.LIVE, kind = kind)
        val saved = queue.saved()
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(savedQueues = saved, queue = snapshot) }
    }
    private suspend fun persistPlaybackPosition() {
        val playback = engine.status.value
        if (playback.entryId != null && playback.entryId == queue.snapshot().currentEntryId) queue.setPosition(playback.positionMs)
    }
    fun overwriteSavedQueue(id: String) = action {
        persistPlaybackPosition()
        queue.overwriteSaved(id)
        val snapshot = queue.snapshot()
        val saved = queue.saved()
        mutableState.update { it.copy(queue = snapshot, savedQueues = saved) }
    }
    fun detachSavedQueue() = action {
        persistPlaybackPosition()
        queue.detachSaved()
        mutableState.update { it.copy(queue = it.queue.copy(selectedSavedQueueId = null)) }
    }
    fun startEmptyQueue() = action {
        persistPlaybackPosition()
        queue.startEmpty()
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(queue = snapshot) }
        if (queueLoaded) engine.updateQueue(playableQueue(snapshot))
    }
    fun setQueueShuffled(enabled: Boolean) = action {
        queue.shuffle(enabled)
        val snapshot = queue.snapshot()
        if (queueLoaded) engine.updateQueue(playableQueue(snapshot))
        mutableState.update { it.copy(queue = snapshot) }
    }
    fun setQueueRepeat(mode: RepeatMode, playCount: Int? = null) = action {
        queue.setRepeat(mode, playCount)
        engine.setRepeat(mode, playCount)
        mutableState.update { it.copy(queue = queue.snapshot()) }
    }
    fun shuffleQueueAlbums() = action {
        val albums = state.value.tracks.groupBy { track ->
            if (track.metadata.album.isBlank()) listOf(track.id)
            else listOf(track.metadata.album, track.metadata.albumArtist.ifBlank { track.metadata.artist })
        }.values.map { tracks -> tracks.sortedWith(compareBy<Track> { it.metadata.discNumber }
            .thenBy { it.metadata.trackNumber }.thenBy { it.metadata.title }).map { it.id } }
        queue.shuffleAlbums(albums)
        val snapshot = queue.snapshot()
        if (queueLoaded) engine.updateQueue(playableQueue(snapshot))
        mutableState.update { it.copy(queue = snapshot) }
    }
    private suspend fun requestPlaybackConfirmation(title: String, confirmed: Boolean, play: () -> kotlinx.coroutines.Job): Boolean {
        val snapshot = queue.snapshot()
        val manual = snapshot.upNext.isNotEmpty() || snapshot.entries.getOrNull(snapshot.currentIndex)?.section ==
            me.d3sox.nevumi.queue.QueueSection.MANUAL
        if (!confirmed && state.value.preferences.confirmNextUpInterruption && manual) {
            mutableState.update { it.copy(playbackConfirmation = PlaybackConfirmation(title, play)) }
            return true
        }
        return false
    }
    fun cancelPlaybackConfirmation() { mutableState.update { it.copy(playbackConfirmation = null) } }
    fun acceptPlaybackConfirmation() {
        val request = state.value.playbackConfirmation ?: return
        cancelPlaybackConfirmation()
        request.play()
    }
    fun loadSavedQueue(id: String, confirmed: Boolean = false): kotlinx.coroutines.Job = action {
        if (queue.snapshot().selectedSavedQueueId == id) return@action
        if (requestPlaybackConfirmation(state.value.savedQueues.firstOrNull { it.id == id }?.name ?: "this saved queue", confirmed) {
                loadSavedQueue(id, confirmed = true)
            }) return@action
        val playing = engine.status.value.let { it.playWhenReady && !it.ended && it.error == null }
        persistPlaybackPosition()
        failedSources.clear()
        val saved = requireNotNull(queue.saved().firstOrNull { it.id == id }) { "Saved queue is missing" }
        if (saved.snapshot.currentIndex >= 0) require(playableQueue(saved.snapshot).any { it.entryId == saved.snapshot.currentEntryId }) {
            "This saved queue's current track has no available audio source"
        }
        queue.restoreSaved(id)
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(queue = snapshot) }
        if (snapshot.currentIndex >= 0) {
            loadSnapshot(snapshot, play = playing)
            queueLoaded = true
        } else {
            engine.updateQueue(emptyList())
        }
    }
    fun removeSavedQueue(id: String) = action {
        queue.deleteSaved(id)
        val saved = queue.saved()
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(savedQueues = saved, queue = snapshot) }
    }
    fun renameSavedQueue(id: String, name: String) = action { queue.renameSaved(id, name) }
    fun duplicateSavedQueue(id: String, name: String) = action { persistPlaybackPosition(); queue.duplicateSaved(id, name) }
    fun convertSavedQueue(id: String, kind: me.d3sox.nevumi.queue.SavedQueueKind) = action {
        persistPlaybackPosition(); queue.convertSaved(id, kind)
        mutableState.update { it.copy(queue = queue.snapshot(), savedQueues = queue.saved()) }
    }
    fun skip(direction: Int, restartPrevious: Boolean = true) = action {
        val snapshot = queue.snapshot()
        val playback = engine.status.value
        val playing = playback.playWhenReady && !playback.ended && playback.error == null
        if (restartPrevious && direction < 0 && engine.status.value.positionMs > 3_000) {
            queue.setPosition(0); engine.seek(0)
            publishSeek(0)
        } else {
            val wraps = snapshot.repeatMode == RepeatMode.ALL && snapshot.currentIndex >= 0 && snapshot.entries.isNotEmpty()
            fun step(index: Int) = (index + direction).let { if (wraps) it.mod(snapshot.entries.size) else it }
            // Pass over songs that cannot play right now, such as server songs while offline or files marked missing.
            // Without a current entry no file access is checked here; loading the chosen song still verifies its file.
            val playableIds = playableQueue(snapshot.copy(currentEntryId = null)).mapTo(HashSet()) { it.entryId }
            var destination = step(snapshot.currentIndex)
            var passed = 0
            while (destination in snapshot.entries.indices && snapshot.entries[destination].id !in playableIds && passed < snapshot.entries.size) {
                destination = step(destination); passed++
            }
            if (destination in snapshot.entries.indices) {
                require(snapshot.entries[destination].id in playableIds) { "No upcoming song has an available audio source" }
                queue.select(snapshot.entries[destination].id)
                val selected = queue.snapshot()
                loadSnapshot(selected, play = playing)
                queueLoaded = true
                mutableState.update { it.copy(queue = selected) }
            }
        }
    }
    fun removeQueueEntry(entryId: String) = action {
        val before = queue.snapshot()
        val playing = engine.status.value.playing
        queue.remove(entryId)
        val after = queue.snapshot()
        mutableState.update { it.copy(queue = after) }
        confirm("Removed from queue", undo = { restoreQueue(before) }, redo = { restoreQueue(after) })
        if (before.currentEntryId == entryId && after.currentIndex >= 0)
            loadSnapshot(after, play = playing)
        else if (queueLoaded) engine.updateQueue(playableQueue(after))
    }
    fun moveQueueEntry(entryId: String, destination: Int, section: me.d3sox.nevumi.queue.QueueSection? = null) = action {
        queue.reorder(entryId, destination, section)
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(queue = snapshot) }
        if (queueLoaded) engine.updateQueue(playableQueue(snapshot))
    }
    fun clearQueue(selection: me.d3sox.nevumi.queue.QueueClear = me.d3sox.nevumi.queue.QueueClear.ALL) = action {
        persistPlaybackPosition()
        val before = queue.snapshot()
        queue.clear(selection)
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(queue = snapshot) }
        confirm("Queue cleared", undo = { restoreQueue(before) }, redo = { restoreQueue(snapshot) })
        if (queueLoaded) engine.updateQueue(playableQueue(snapshot))
    }
    fun moveQueueEntryToSection(entryId: String, section: me.d3sox.nevumi.queue.QueueSection) = action {
        val before = queue.snapshot()
        val entry = requireNotNull(before.entries.firstOrNull { it.id == entryId }) { "Queue entry is missing" }
        require(before.entries.indexOf(entry) > before.currentIndex) { "Only upcoming songs can change section" }
        val destination = before.currentIndex + 1 + before.upNext.count { it.id != entryId }
        queue.reorder(entryId, destination.coerceAtMost(before.entries.lastIndex), section)
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(queue = snapshot) }
        if (queueLoaded) engine.updateQueue(playableQueue(snapshot))
        confirm(if (section == me.d3sox.nevumi.queue.QueueSection.MANUAL) "Moved to Up next" else "Moved to context",
            undo = { restoreQueue(before) }, redo = { restoreQueue(snapshot) })
    }
    fun enqueue(trackId: String, operation: QueueAction) = action {
        requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        val before = queue.snapshot()
        when (operation) {
            QueueAction.PLAY_NEXT -> queue.playNext(trackId)
            QueueAction.PLAY_AFTER -> queue.playAfter(trackId)
            QueueAction.APPEND -> queue.append(trackId)
        }
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(queue = snapshot) }
        confirmQueueAction(operation, before, snapshot)
        if (queueLoaded) engine.updateQueue(playableQueue(snapshot))
    }
    fun enqueue(trackIds: List<String>, operation: QueueAction) = action {
        trackIds.forEach { requireNotNull(library.findTrack(it)) { "Track is missing" } }
        val before = queue.snapshot()
        queue.enqueue(trackIds, operation)
        val snapshot = queue.snapshot()
        mutableState.update { it.copy(queue = snapshot) }
        confirmQueueAction(operation, before, snapshot)
        if (queueLoaded) engine.updateQueue(playableQueue(snapshot))
    }

    private fun confirmQueueAction(operation: QueueAction, before: QueueSnapshot, after: QueueSnapshot) = confirm(when (operation) {
        QueueAction.PLAY_NEXT -> "Added to play next"
        QueueAction.PLAY_AFTER -> "Added to play after"
        QueueAction.APPEND -> "Added to queue"
    }, ActionDestination.QUEUE, undo = { restoreQueue(before) }, redo = { restoreQueue(after) })

    private suspend fun loadSnapshot(snapshot: QueueSnapshot, play: Boolean = true) {
        val intention = transportIntent.value
        val playable = playableQueue(snapshot)
        val index = playable.indexOfFirst { it.entryId == snapshot.currentEntryId }
        require(index >= 0) { "This track has no available audio source" }
        transport.withLock {
            val requested = transportIntent.value.let { if (it.revision != intention.revision) it.play else play }
            engine.setRepeat(snapshot.repeatMode, snapshot.repeatPlays)
            engine.loadQueue(playable, index, snapshot.positionMs, requested)
            queueLoaded = true
            val status = engine.status.value
            mutableState.update { it.copy(queue = snapshot, playback = status.copy(trackId = playable[index].id,
                entryId = playable[index].entryId, sourceId = playable[index].sourceId, playWhenReady = requested)) }
        }
    }

    private suspend fun resumePlayback() {
        val playback = engine.status.value
        if (!queueLoaded || playback.error != null || playback.ended) {
            if (playback.ended) queue.setPosition(0)
            val snapshot = queue.snapshot()
            if (playback.error != null) snapshot.currentEntryId?.let { failedSources.remove(it) }
            mutableState.update { it.copy(error = null) }
            loadSnapshot(snapshot)
            queueLoaded = true
        } else transport.withLock {
            if (transportIntent.value.play) engine.play()
            mutableState.update { it.copy(playback = engine.status.value) }
        }
    }

    private suspend fun playableQueue(snapshot: QueueSnapshot, checkAllSources: Boolean = false): List<PlayableTrack> {
        var connection: me.d3sox.nevumi.navidrome.NavidromeClient? = null
        var checkedConnection = false
        val tracks = library.tracks().associateBy { it.id }
        return snapshot.entries.mapNotNull { entry ->
            val track = tracks[entry.trackId] ?: return@mapNotNull null
            val sources = track.sources.filterNot { it.id in failedSources[entry.id].orEmpty() }.map { source ->
                if (source.kind == SourceKind.SERVER) source
                else source.copy(available = source.available && ((!checkAllSources && entry.id != snapshot.currentEntryId) || withContext(Dispatchers.IO) { sourceAccessible(source.location) }))
            }
            var source = SourceResolver.resolve(sources, state.value.preferences.sourcePriority) ?: return@mapNotNull null
            val location = if (source.kind == SourceKind.SERVER) {
                if (!checkedConnection) {
                    checkedConnection = true
                    connection = try { navidrome?.client() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                }
                val connectedSources = sources.filter { candidate ->
                    candidate.kind != SourceKind.SERVER || connection != null &&
                        candidate.id.startsWith("server:${requireNotNull(connection).serverId}:")
                }
                source = SourceResolver.resolve(connectedSources, state.value.preferences.sourcePriority) ?: return@mapNotNull null
                if (source.kind == SourceKind.SERVER) requireNotNull(connection).streamUrl(source.location) else source.location
            } else source.location
            PlayableTrack(track.id, location, track.metadata.title, track.metadata.artist, entry.id, source.id)
        }
    }

    private fun action(serialized: Boolean = true, block: suspend () -> Unit) = scope.launch {
        try { if (serialized) operations.withLock { block() } else block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.update { it.copy(error = error.message ?: "Operation failed", loading = false) } }
    }

    private suspend fun publishSeek(positionMs: Long) {
        queue.snapshot().currentEntryId?.let { mutableSeeks.emit(PlaybackSeek(it, positionMs)) }
    }

    override fun close() { scope.cancel(); pairedDevices?.close(); serverChanges?.close(); offline?.close(); scrobbling?.close(); engine.close() }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        shutdown.withLock {
            if (shutdownFinished) return@withLock
            try {
                if (supervisor.isActive) engine.pause()
                scope.cancel()
                pairedDevices?.closeAndJoin()
                serverChanges?.closeAndJoin()
                offline?.closeAndJoin()
                scrobbling?.closeAndJoin()
                supervisor.join()
                val playback = engine.status.value
                listeningHistory?.record(playback.copy(playing = false), enabled = state.value.preferences.recordListeningHistory)
                listeningHistory?.let { history -> mutableState.update { it.copy(listens = history.all()) } }
                scrobbling?.enqueueEligible()
                if (playback.entryId != null && playback.entryId == queue.snapshot().currentEntryId)
                    queue.setPosition(playback.positionMs)
            } finally {
                engine.close()
                shutdownFinished = true
            }
        }
    }
}
