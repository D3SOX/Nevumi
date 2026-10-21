package me.d3sox.nevumi.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.queue.QueueClear
import me.d3sox.nevumi.queue.QueueOrigin
import me.d3sox.nevumi.queue.QueueSection
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.queue.SavedQueueKind
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_added_to_play_after
import me.d3sox.nevumi.resources.notice_added_to_play_next
import me.d3sox.nevumi.resources.notice_added_to_queue
import me.d3sox.nevumi.resources.notice_moved_to_context
import me.d3sox.nevumi.resources.notice_moved_to_up_next
import me.d3sox.nevumi.resources.notice_queue_cleared
import me.d3sox.nevumi.resources.notice_removed_from_queue
import me.d3sox.nevumi.settings.QueueRecoveryMode
import me.d3sox.nevumi.sync.PairedDevices

/** Keeps the queue and the saved queues: what plays next, shuffle and repeat, saved queues and the undo of queue changes. */
class QueueController internal constructor(private val context: ControllerContext, private val playback: PlaybackController,
    private val library: MusicLibrary, private val queue: PlaybackQueue, private val engine: PlaybackEngine,
    private val playlists: Playlists?, private val pairedDevices: PairedDevices?) {
    init {
        context.scope.launch { queue.observeSaved().collect { context.operations.withLock {
            val saved = queue.saved()
            val snapshot = queue.snapshot()
            if (snapshot.selectedSavedQueueId != null && saved.none { it.id == snapshot.selectedSavedQueueId }) {
                queue.detachSaved()
                context.update { it.copy(queue = it.queue.copy(selectedSavedQueueId = null)) }
            }
            context.update { it.copy(savedQueues = saved) }
        } } }
        context.scope.launch {
            context.state.map { Triple(it.libraryReady && it.preferences.queueRecoveryEnabled, it.preferences.queueRecoveryMode,
                it.preferences.queueRecoveryIntervalMinutes) }.distinctUntilChanged().collectLatest { (enabled, mode, minutes) ->
                if (enabled && mode == QueueRecoveryMode.TIMER) {
                    while (currentCoroutineContext().isActive) {
                        delay(minutes * 60_000L)
                        context.operations.withLock { persistPlaybackPosition(); queue.captureRecovery() }
                    }
                }
            }
        }
        pairedDevices?.let { devices -> context.scope.launch {
            devices.state.map { it.peers.map { peer -> peer.lastSyncedAt } }.distinctUntilChanged().drop(1).collect {
                context.operations.withLock {
                    val saved = queue.saved()
                    val snapshot = queue.snapshot()
                    if (playback.queueLoaded) {
                        if (snapshot.currentIndex >= 0 && snapshot.currentEntryId != engine.status.value.entryId)
                            playback.loadSnapshot(snapshot, engine.status.value.playWhenReady)
                        else engine.updateQueue(playback.playableQueue(snapshot))
                    }
                    context.update { it.copy(savedQueues = saved, queue = snapshot) }
                }
            }
        } }
    }

    fun playTrack(trackId: String, contextTrackIds: List<String>? = null, contextIndex: Int? = null, contextName: String = "Library", shuffleContext: Boolean = false,
        origin: QueueOrigin = QueueOrigin(), preserveManualQueue: Boolean = false, confirmed: Boolean = false): Job = context.action {
        if (requestPlaybackConfirmation(
                context.state.value.tracks.firstOrNull { it.id == trackId }?.metadata?.title ?: "this music", confirmed) {
                playTrack(trackId, contextTrackIds, contextIndex, contextName, shuffleContext, origin, preserveManualQueue, confirmed = true)
            }) return@action
        context.update { it.copy(playbackConfirmation = null) }
        playback.discardIncomingPlayback()
        playback.failedSources.clear()
        context.update { it.copy(error = null) }
        val available = context.state.value.tracks.associateBy { it.id }
        val tracks = contextTrackIds?.mapNotNull { available[it] } ?: context.state.value.tracks
        val index = contextIndex ?: tracks.indexOfFirst { it.id == trackId }
        require(tracks.getOrNull(index)?.id == trackId) { "Track is missing" }
        val currentQueue = queue.snapshot()
        persistPlaybackPosition()
        val manualQueue = currentQueue.upNext.isNotEmpty() || currentQueue.entries.getOrNull(currentQueue.currentIndex)?.section == QueueSection.MANUAL
        if (preserveManualQueue && manualQueue) queue.playNow(trackId)
        else queue.replace(tracks.map { it.id }, index, contextName, shuffle = shuffleContext, origin = origin)
        playback.loadSnapshot(queue.snapshot())
        // Only a successful load counts as a play.
        if (origin.tab == LibraryTab.PLAYLISTS) origin.collectionId?.let { playlists?.markPlayed(it) }
        playback.queueLoaded = true
    }

    fun selectQueueEntry(entryId: String) = context.action {
        queue.select(entryId)
        val snapshot = queue.snapshot()
        playback.loadSnapshot(snapshot)
        playback.queueLoaded = true
        context.update { it.copy(queue = snapshot) }
    }
    fun saveQueue(name: String, kind: SavedQueueKind = SavedQueueKind.LIVE) = context.action {
        persistPlaybackPosition()
        queue.save(name, select = kind == SavedQueueKind.LIVE, kind = kind)
        val saved = queue.saved()
        val snapshot = queue.snapshot()
        context.update { it.copy(savedQueues = saved, queue = snapshot) }
    }
    private suspend fun persistPlaybackPosition() {
        val status = engine.status.value
        if (status.entryId != null && status.entryId == queue.snapshot().currentEntryId) queue.setPosition(status.positionMs)
    }
    fun overwriteSavedQueue(id: String) = context.action {
        persistPlaybackPosition()
        queue.overwriteSaved(id)
        val snapshot = queue.snapshot()
        val saved = queue.saved()
        context.update { it.copy(queue = snapshot, savedQueues = saved) }
    }
    fun detachSavedQueue() = context.action {
        persistPlaybackPosition()
        queue.detachSaved()
        context.update { it.copy(queue = it.queue.copy(selectedSavedQueueId = null)) }
    }
    fun startEmptyQueue() = context.action {
        persistPlaybackPosition()
        queue.startEmpty()
        val snapshot = queue.snapshot()
        context.update { it.copy(queue = snapshot) }
        if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(snapshot))
    }
    fun setQueueShuffled(enabled: Boolean) = context.action {
        queue.shuffle(enabled)
        val snapshot = queue.snapshot()
        if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(snapshot))
        context.update { it.copy(queue = snapshot) }
    }
    fun setQueueRepeat(mode: RepeatMode, playCount: Int? = null) = context.action {
        queue.setRepeat(mode, playCount)
        engine.setRepeat(mode, playCount)
        context.update { it.copy(queue = queue.snapshot()) }
    }
    fun shuffleQueueAlbums() = context.action {
        val albums = context.state.value.tracks.groupBy { track ->
            if (track.metadata.album.isBlank()) listOf(track.id)
            else listOf(track.metadata.album, track.metadata.albumArtist.ifBlank { track.metadata.artist })
        }.values.map { tracks -> tracks.sortedWith(compareBy<Track> { it.metadata.discNumber }
            .thenBy { it.metadata.trackNumber }.thenBy { it.metadata.title }).map { it.id } }
        queue.shuffleAlbums(albums)
        val snapshot = queue.snapshot()
        if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(snapshot))
        context.update { it.copy(queue = snapshot) }
    }
    private suspend fun requestPlaybackConfirmation(title: String, confirmed: Boolean, play: () -> Job): Boolean {
        val snapshot = queue.snapshot()
        val manual = snapshot.upNext.isNotEmpty() || snapshot.entries.getOrNull(snapshot.currentIndex)?.section == QueueSection.MANUAL
        if (!confirmed && context.state.value.preferences.confirmNextUpInterruption && manual) {
            context.update { it.copy(playbackConfirmation = PlaybackConfirmation(title, play)) }
            return true
        }
        return false
    }
    fun cancelPlaybackConfirmation() { context.update { it.copy(playbackConfirmation = null) } }
    fun acceptPlaybackConfirmation() {
        val request = context.state.value.playbackConfirmation ?: return
        cancelPlaybackConfirmation()
        request.play()
    }
    fun loadSavedQueue(id: String, confirmed: Boolean = false): Job = context.action {
        if (queue.snapshot().selectedSavedQueueId == id) return@action
        if (requestPlaybackConfirmation(context.state.value.savedQueues.firstOrNull { it.id == id }?.name ?: "this saved queue", confirmed) {
                loadSavedQueue(id, confirmed = true)
            }) return@action
        val playing = engine.status.value.let { it.playWhenReady && !it.ended && it.error == null }
        persistPlaybackPosition()
        playback.failedSources.clear()
        val saved = requireNotNull(queue.saved().firstOrNull { it.id == id }) { "Saved queue is missing" }
        if (saved.snapshot.currentIndex >= 0) require(playback.playableQueue(saved.snapshot, listOf(saved.snapshot.entries[saved.snapshot.currentIndex])).isNotEmpty()) {
            "This saved queue's current track has no available audio source"
        }
        queue.restoreSaved(id)
        val snapshot = queue.snapshot()
        context.update { it.copy(queue = snapshot) }
        if (snapshot.currentIndex >= 0) {
            playback.loadSnapshot(snapshot, play = playing)
            playback.queueLoaded = true
        } else {
            engine.updateQueue(emptyList())
        }
    }
    fun removeSavedQueue(id: String) = context.action {
        queue.deleteSaved(id)
        val saved = queue.saved()
        val snapshot = queue.snapshot()
        context.update { it.copy(savedQueues = saved, queue = snapshot) }
    }
    fun renameSavedQueue(id: String, name: String) = context.action { queue.renameSaved(id, name) }
    fun duplicateSavedQueue(id: String, name: String) = context.action { persistPlaybackPosition(); queue.duplicateSaved(id, name) }
    fun convertSavedQueue(id: String, kind: SavedQueueKind) = context.action {
        persistPlaybackPosition(); queue.convertSaved(id, kind)
        context.update { it.copy(queue = queue.snapshot(), savedQueues = queue.saved()) }
    }
    fun removeQueueEntry(entryId: String) = context.action {
        val before = queue.snapshot()
        val playing = engine.status.value.playing
        queue.remove(entryId)
        val after = queue.snapshot()
        context.update { it.copy(queue = after) }
        context.confirm(Res.string.notice_removed_from_queue, undo = { restoreQueue(before) }, redo = { restoreQueue(after) })
        if (before.currentEntryId == entryId && after.currentIndex >= 0)
            playback.loadSnapshot(after, play = playing)
        else if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(after))
    }
    fun moveQueueEntry(entryId: String, destination: Int, section: QueueSection? = null) = context.action {
        queue.reorder(entryId, destination, section)
        val snapshot = queue.snapshot()
        context.update { it.copy(queue = snapshot) }
        if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(snapshot))
    }
    fun clearQueue(selection: QueueClear = QueueClear.ALL) = context.action {
        persistPlaybackPosition()
        val before = queue.snapshot()
        queue.clear(selection)
        val snapshot = queue.snapshot()
        context.update { it.copy(queue = snapshot) }
        context.confirm(Res.string.notice_queue_cleared, undo = { restoreQueue(before) }, redo = { restoreQueue(snapshot) })
        if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(snapshot))
    }
    fun moveQueueEntryToSection(entryId: String, section: QueueSection) = context.action {
        val before = queue.snapshot()
        val entry = requireNotNull(before.entries.firstOrNull { it.id == entryId }) { "Queue entry is missing" }
        require(before.entries.indexOf(entry) > before.currentIndex) { "Only upcoming songs can change section" }
        val destination = before.currentIndex + 1 + before.upNext.count { it.id != entryId }
        queue.reorder(entryId, destination.coerceAtMost(before.entries.lastIndex), section)
        val snapshot = queue.snapshot()
        context.update { it.copy(queue = snapshot) }
        if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(snapshot))
        context.confirm(if (section == QueueSection.MANUAL) Res.string.notice_moved_to_up_next else Res.string.notice_moved_to_context,
            undo = { restoreQueue(before) }, redo = { restoreQueue(snapshot) })
    }
    fun enqueue(trackId: String, operation: QueueAction) = context.action {
        requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        val before = queue.snapshot()
        when (operation) {
            QueueAction.PLAY_NEXT -> queue.playNext(trackId)
            QueueAction.PLAY_AFTER -> queue.playAfter(trackId)
            QueueAction.APPEND -> queue.append(trackId)
        }
        val snapshot = queue.snapshot()
        context.update { it.copy(queue = snapshot) }
        confirmQueueAction(operation, before, snapshot)
        if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(snapshot))
    }
    fun enqueue(trackIds: List<String>, operation: QueueAction) = context.action {
        trackIds.forEach { requireNotNull(library.findTrack(it)) { "Track is missing" } }
        val before = queue.snapshot()
        queue.enqueue(trackIds, operation)
        val snapshot = queue.snapshot()
        context.update { it.copy(queue = snapshot) }
        confirmQueueAction(operation, before, snapshot)
        if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(snapshot))
    }

    private fun confirmQueueAction(operation: QueueAction, before: QueueSnapshot, after: QueueSnapshot) = context.confirm(when (operation) {
        QueueAction.PLAY_NEXT -> Res.string.notice_added_to_play_next
        QueueAction.PLAY_AFTER -> Res.string.notice_added_to_play_after
        QueueAction.APPEND -> Res.string.notice_added_to_queue
    }, ActionDestination.QUEUE, undo = { restoreQueue(before) }, redo = { restoreQueue(after) })

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
            context.update { it.copy(savedQueues = saved) }
            return
        }
        queue.restore(restored)
        context.update { it.copy(queue = restored) }
        if (playback.queueLoaded) {
            if (current != live.currentEntryId && restored.currentIndex >= 0) playback.loadSnapshot(restored, engine.status.value.playWhenReady)
            else engine.updateQueue(playback.playableQueue(restored))
        }
    }
}
