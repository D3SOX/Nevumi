package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.SourceResolver
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.playback.PlayableTrack
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueSection
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.queue.QueueEntry
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_paired_device
import me.d3sox.nevumi.resources.notice_playback_moved
import org.jetbrains.compose.resources.getString
import me.d3sox.nevumi.resources.notice_playback_moved_skipped_song
import me.d3sox.nevumi.resources.notice_playback_moved_skipped_songs
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.sync.PairedDevices
import me.d3sox.nevumi.sync.PlaybackReceiver
import me.d3sox.nevumi.sync.PlaybackTransfer
import me.d3sox.nevumi.sync.withAvailableEntries
import kotlin.math.abs
import kotlin.uuid.Uuid

/** Drives the playback engine: transport, seeking, skipping, playback settings, the sleep timer and playback transfers to paired devices. */
class PlaybackController internal constructor(private val context: ControllerContext, private val library: MusicLibrary,
    private val queue: PlaybackQueue, private val engine: PlaybackEngine, private val navidrome: NavidromeConnection?,
    private val sourceAccessible: (String) -> Boolean, private val preferences: MusicPreferences?, private val offline: OfflineMusic?,
    private val listeningHistory: ListeningHistory?, private val scrobbling: Scrobbling?, private val pairedDevices: PairedDevices?) {
    private var sleepTimer: Job? = null
    private val transport = Mutex()
    private data class TransportIntent(val revision: Long = 0, val play: Boolean = false)
    private val transportIntent = MutableStateFlow(TransportIntent())
    private data class IncomingPlayback(val peerId: String, val id: String, val previous: PlaybackTransfer,
        val preparedQueue: QueueSnapshot, val revision: Long)
    private var incomingPlayback: IncomingPlayback? = null
    private var cachedEntryId: String? = null
    private val clock = kotlin.time.TimeSource.Monotonic.markNow()
    // Starts one interval in the past so the first checkpoint is not delayed; Long.MIN_VALUE would overflow the subtraction.
    private var positionCheckpointAt = -POSITION_CHECKPOINT_MS
    /** Whether the engine holds the queue; until it does, a transport request loads the queue first. */
    internal var queueLoaded = false
    /** Sources that failed per queue entry; they are left out until the song is chosen again. */
    internal val failedSources = mutableMapOf<String, MutableSet<String>>()

    init {
        pairedDevices?.setPlaybackReceiver(object : PlaybackReceiver {
            override suspend fun receive(peerId: String, playback: PlaybackTransfer) = context.operations.withLock {
                require(playback.id.isNotBlank() && playback.queue.entries.isNotEmpty() && playback.queue.currentIndex >= 0 &&
                    playback.queue.entries.map { it.id }.distinct().size == playback.queue.entries.size && playback.queue.positionMs >= 0 &&
                    playback.speed.isFinite() && playback.speed in .25..4.0 && playback.pitch.isFinite() && playback.pitch in .25..4.0) { "Invalid playback transfer" }
                val playableIds = playableQueue(playback.queue, checkAllSources = true).map { it.entryId }.toSet()
                require(playback.queue.entries.withIndex().all { (index, entry) -> entry.id in playableIds ||
                    (entry.id != playback.queue.currentEntryId && (index < playback.queue.currentIndex ||
                        entry.section != QueueSection.MANUAL)) }) {
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
            override suspend fun activate(peerId: String, transferId: String, positionMs: Long, playWhenReady: Boolean) = context.operations.withLock {
                val incoming = requireNotNull(incomingPlayback) { "Playback transfer was canceled" }
                require(positionMs >= 0 && incoming.peerId == peerId && incoming.id == transferId &&
                    queue.snapshot().copy(positionMs = incoming.preparedQueue.positionMs) == incoming.preparedQueue &&
                    transportIntent.value.revision == incoming.revision) { "Playback changed on the receiving device" }
                engine.seek(positionMs)
                withTimeout(5_000) { engine.status.first { it.entryId == incoming.preparedQueue.currentEntryId && abs(it.positionMs - positionMs) < 250 } }
                queue.setPosition(positionMs)
                if (playWhenReady) play().join() else pause().join()
                incomingPlayback = incoming.copy(revision = transportIntent.value.revision)
                context.update { it.copy(queue = it.queue.copy(positionMs = positionMs), playback = engine.status.value) }
            }
            override suspend fun release(peerId: String, transferId: String): PlaybackTransfer = context.operations.withLock {
                require(incomingPlayback?.let { it.peerId == peerId && it.id == transferId } == true) { "Playback has changed on the other device" }
                val current = capturePlayback(transferId)
                pause().join()
                incomingPlayback = null
                current
            }
            override suspend fun cancel(peerId: String, transferId: String) = context.operations.withLock {
                val incoming = incomingPlayback
                if (incoming != null && incoming.peerId == peerId && incoming.id == transferId) {
                    if (queue.snapshot().copy(positionMs = incoming.preparedQueue.positionMs) == incoming.preparedQueue && transportIntent.value.revision == incoming.revision)
                        restoreTransferredPlayback(incoming.previous)
                    incomingPlayback = null
                }
            }
        })
        context.scope.launch { engine.status.collect {
            context.operations.withLock {
                val playback = engine.status.value
                var snapshot = queue.snapshot()
                if (snapshot.indexOf(playback.entryId) >= 0) {
                    if (snapshot.repeatPlays != null && snapshot.currentEntryId == playback.entryId &&
                        (snapshot.repeatMode != playback.repeatMode || snapshot.repeatPlays != playback.repeatPlays))
                        queue.setRepeat(playback.repeatMode, playback.repeatPlays)
                    if (snapshot.currentEntryId != playback.entryId) queue.select(requireNotNull(playback.entryId))
                    // Every write wakes every database observer, so a playing song checkpoints its position only every
                    // half minute; a pause or a song change saves it at once, and so does closing the app.
                    val now = clock.elapsedNow().inWholeMilliseconds
                    if (snapshot.currentEntryId != playback.entryId || !playback.playing && snapshot.positionMs != playback.positionMs ||
                        abs(snapshot.positionMs - playback.positionMs) >= 1_000 && now - positionCheckpointAt >= POSITION_CHECKPOINT_MS) {
                        queue.setPosition(playback.positionMs)
                        positionCheckpointAt = now
                    }
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
                listeningHistory?.record(playback, enabled = context.state.value.preferences.recordListeningHistory)
                scrobbling?.onPlayback(playback)
                context.update { it.copy(playback = playback, queue = snapshot,
                    error = if (playback.error != null && !recovered) playback.error else it.error) }
            }
        } }
    }

    fun play() = requestPlayback(true)
    fun pause() = requestPlayback(false)
    fun togglePlayback() = requestPlayback(null)
    private fun requestPlayback(play: Boolean?) = context.action(serialized = false) {
        val load = transport.withLock {
            val playback = engine.status.value
            val requested = play ?: !(playback.playWhenReady && !playback.ended && playback.error == null)
            transportIntent.update { TransportIntent(it.revision + 1, requested) }
            val needsLoad = requested && (!queueLoaded || playback.error != null || playback.ended)
            if (!requested) engine.pause() else if (!needsLoad) engine.play()
            context.update { it.copy(playback = engine.status.value.copy(playWhenReady = requested)) }
            needsLoad
        }
        if (load) context.operations.withLock { if (transportIntent.value.play) resumePlayback() }
    }

    private suspend fun resumePlayback() {
        val playback = engine.status.value
        if (!queueLoaded || playback.error != null || playback.ended) {
            if (playback.ended) queue.setPosition(0)
            val snapshot = queue.snapshot()
            if (playback.error != null) snapshot.currentEntryId?.let { failedSources.remove(it) }
            context.update { it.copy(error = null) }
            loadSnapshot(snapshot)
            queueLoaded = true
        } else transport.withLock {
            if (transportIntent.value.play) engine.play()
            context.update { it.copy(playback = engine.status.value) }
        }
    }

    fun setVolume(volume: Double) = context.action(serialized = false) { engine.setVolume(volume) }
    fun setSpeed(speed: Double) = context.action {
        engine.setSpeed(speed)
        preferences?.update { it.copy(speed = speed) }?.let { settings ->
            context.update { it.copy(preferences = settings) }
        }
    }
    fun setPitch(pitch: Double) = context.action {
        engine.setPitch(pitch)
        preferences?.update { it.copy(pitch = pitch) }?.let { settings ->
            context.update { it.copy(preferences = settings) }
        }
    }
    fun stop() = context.action {
        engine.pause()
        queue.setPosition(0)
        engine.seek(0)
        publishSeek(0)
    }
    fun seekCurrentEntry(entryId: String, positionMs: Long) = context.action {
        val playback = engine.status.value
        if (entryId == playback.entryId && positionMs in 0..playback.durationMs) {
            queue.setPosition(positionMs)
            engine.seek(positionMs)
            publishSeek(positionMs)
        }
    }
    fun seek(positionMs: Long) = context.action {
        queue.setPosition(positionMs)
        engine.seek(positionMs)
        publishSeek(positionMs.coerceAtLeast(0))
    }
    fun skip(direction: Int, restartPrevious: Boolean = true) = context.action {
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
            val unchecked = snapshot.copy(currentEntryId = null)
            val lookup = SourceLookup(library.tracks().associateBy { it.id })
            suspend fun playable(index: Int) = playableQueue(unchecked, listOf(snapshot.entries[index]), lookup = lookup).isNotEmpty()
            var destination = step(snapshot.currentIndex)
            var passed = 0
            while (destination in snapshot.entries.indices && !playable(destination) && passed < snapshot.entries.size) {
                destination = step(destination); passed++
            }
            if (destination in snapshot.entries.indices) {
                require(playable(destination)) { "No upcoming song has an available audio source" }
                queue.select(snapshot.entries[destination].id)
                val selected = queue.snapshot()
                loadSnapshot(selected, play = playing)
                queueLoaded = true
                context.update { it.copy(queue = selected) }
            }
        }
    }
    fun setSleepTimer(minutes: Int?) = context.action {
        require(minutes == null || minutes in 1..180) { "Choose a sleep timer between 1 and 180 minutes" }
        sleepTimer?.cancel()
        val duration = minutes?.times(60_000L)
        val deadline = duration?.let { kotlin.time.Clock.System.now().toEpochMilliseconds() + it }
        context.update { it.copy(sleepTimerEndsAt = deadline) }
        sleepTimer = duration?.let {
            context.scope.launch {
                delay(it)
                context.operations.withLock {
                    if (context.state.value.sleepTimerEndsAt == deadline) {
                        pause().join()
                        context.update { state -> state.copy(sleepTimerEndsAt = null) }
                    }
                }
            }
        }
    }

    fun transferPlayback(deviceId: String): Job = context.action(serialized = false) {
        val devices = requireNotNull(pairedDevices) { "Device pairing is unavailable" }
        context.update { it.copy(error = null) }
        val revision = transportIntent.value.revision
        val transfer = context.operations.withLock { capturePlayback(Uuid.random().toString()).also {
            require(it.queue.currentIndex >= 0) { "Choose a song first" }
        } }
        val skippedSongs = devices.preparePlayback(deviceId, transfer)
        try {
            val position = context.operations.withLock {
                require(queue.snapshot().copy(positionMs = transfer.queue.positionMs, selectedSavedQueueId = null) == transfer.queue && transportIntent.value.revision == revision) {
                    "Playback changed while connecting. The transfer was canceled."
                }
                capturePlayback(transfer.id).queue.positionMs
            }
            devices.activatePlaybackTransfer(deviceId, transfer.id, position, transfer.playWhenReady)
            context.operations.withLock {
                require(queue.snapshot().copy(positionMs = transfer.queue.positionMs, selectedSavedQueueId = null) == transfer.queue && transportIntent.value.revision == revision) {
                    "Playback changed while connecting. The transfer was canceled."
                }
                pause().join(); incomingPlayback = null
            }
        } catch (error: Exception) {
            try { devices.cancelPlaybackTransfer(deviceId, transfer.id) } catch (_: Exception) { }
            throw error
        }
        val name = devices.state.value.peers.firstOrNull { it.deviceId == deviceId }?.name ?: getString(Res.string.notice_paired_device)
        val (message, arguments) = when {
            skippedSongs <= 0 -> Res.string.notice_playback_moved to listOf(name)
            skippedSongs == 1 -> Res.string.notice_playback_moved_skipped_song to listOf(name, skippedSongs)
            else -> Res.string.notice_playback_moved_skipped_songs to listOf(name, skippedSongs)
        }
        context.confirm(message, serializedUndo = false, redoCreatesNotice = true, arguments = arguments, redo = { transferPlayback(deviceId).join() }, undo = {
            require(!engine.status.value.playWhenReady && queue.snapshot().currentEntryId == transfer.queue.currentEntryId) { "Playback has changed on this device" }
            val returned = devices.returnPlayback(deviceId, transfer.id)
            context.operations.withLock { applyTransferredPlayback(returned) }
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
            context.update { it.copy(preferences = settings) }
        }
        context.update { it.copy(queue = playback.queue, playback = engine.status.value) }
    }

    private suspend fun restoreTransferredPlayback(previous: PlaybackTransfer) {
        pause().join()
        if (previous.queue.currentIndex >= 0) applyTransferredPlayback(previous)
        else { queue.restore(previous.queue); queueLoaded = false; context.update { it.copy(queue = previous.queue) } }
    }

    /** A play request on this device voids a playback transfer that is still being received. */
    internal fun discardIncomingPlayback() { incomingPlayback = null }

    /** Applies the saved playback settings to the engine at startup. */
    internal suspend fun applyPreferences() {
        if (preferences != null) {
            engine.setSpeed(context.state.value.preferences.speed)
            engine.setPitch(context.state.value.preferences.pitch)
            engine.setAudioEffects(context.state.value.preferences.audioEffects)
            engine.setCrossfadeDuration(context.state.value.preferences.crossfadeDurationMs)
            engine.setGaplessPlayback(context.state.value.preferences.gaplessPlayback)
            engine.setReplayGain(context.state.value.preferences.replayGain)
        }
    }

    /** Loads the persisted queue paused at startup, so the player shows the last song. */
    internal suspend fun loadPersistedQueue() {
        val saved = queue.snapshot()
        if (saved.currentIndex >= 0 && !queueLoaded) {
            loadSnapshot(saved, play = false)
            queueLoaded = true
        }
    }

    internal suspend fun loadSnapshot(snapshot: QueueSnapshot, play: Boolean = true) {
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
            context.update { it.copy(queue = snapshot, playback = status.copy(trackId = playable[index].id,
                entryId = playable[index].entryId, sourceId = playable[index].sourceId, playWhenReady = requested)) }
        }
    }

    /** Resolves sources against one library catalogue and one server connection lookup for a whole pass. */
    internal inner class SourceLookup(val catalog: Map<String, Track>) {
        private var connection: NavidromeClient? = null
        private var checked = false
        suspend fun connection(): NavidromeClient? {
            if (!checked) {
                checked = true
                connection = try { navidrome?.client() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
            }
            return connection
        }
    }

    internal suspend fun playableQueue(snapshot: QueueSnapshot, entries: List<QueueEntry> = snapshot.entries,
        checkAllSources: Boolean = false, lookup: SourceLookup? = null): List<PlayableTrack> {
        val sources = lookup ?: SourceLookup(library.tracks().associateBy { it.id })
        return entries.mapNotNull { entry -> playable(entry, snapshot, checkAllSources, sources) }
    }

    private suspend fun playable(entry: QueueEntry, snapshot: QueueSnapshot, checkAllSources: Boolean, lookup: SourceLookup): PlayableTrack? {
        val track = lookup.catalog[entry.trackId] ?: return null
        val sources = track.sources.filterNot { it.id in failedSources[entry.id].orEmpty() }.map { source ->
            if (source.kind == SourceKind.SERVER) source
            else source.copy(available = source.available && ((!checkAllSources && entry.id != snapshot.currentEntryId) || withContext(Dispatchers.IO) { sourceAccessible(source.location) }))
        }
        var source = SourceResolver.resolve(sources, context.state.value.preferences.sourcePriority) ?: return null
        val location = if (source.kind == SourceKind.SERVER) {
            val connection = lookup.connection()
            val connectedSources = sources.filter { candidate ->
                candidate.kind != SourceKind.SERVER || connection != null && candidate.id.startsWith("server:${connection.serverId}:")
            }
            source = SourceResolver.resolve(connectedSources, context.state.value.preferences.sourcePriority) ?: return null
            if (source.kind == SourceKind.SERVER) requireNotNull(connection).streamUrl(source.location) else source.location
        } else source.location
        return PlayableTrack(track.id, location, track.metadata.title, track.metadata.artist, entry.id, source.id)
    }

    private suspend fun publishSeek(positionMs: Long) {
        queue.snapshot().currentEntryId?.let { context.mutableSeeks.emit(PlaybackSeek(it, positionMs)) }
    }

    private companion object {
        const val POSITION_CHECKPOINT_MS = 30_000L
    }
}
