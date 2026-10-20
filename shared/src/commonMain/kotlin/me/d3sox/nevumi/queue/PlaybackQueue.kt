package me.d3sox.nevumi.queue

import me.d3sox.nevumi.storage.MusicStore
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlin.random.Random
import kotlinx.coroutines.flow.map
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.settings.QueueRecoveryMode

private fun List<QueueEntry>.indexOfFirstAfter(index: Int, predicate: (QueueEntry) -> Boolean): Int =
    ((index + 1).coerceAtLeast(0) until size).firstOrNull { predicate(this[it]) } ?: size

@Serializable
enum class QueueSection { MANUAL, CONTEXT }

enum class QueueClear { DUPLICATES, PREVIOUS, NEXT, ALL }

@Serializable
data class QueueEntry(val id: String, val trackId: String, val section: QueueSection = QueueSection.CONTEXT)

@Serializable
data class QueueOrigin(val tab: LibraryTab = LibraryTab.SONGS, val collectionId: String? = null)

@Serializable
enum class SavedQueueKind { LIVE, SNAPSHOT, RECOVERY }

@Serializable
data class SavedQueue(val id: String, val name: String, val snapshot: QueueSnapshot,
    val kind: SavedQueueKind = SavedQueueKind.LIVE, val capturedAt: Long = 0)

internal suspend fun remapQueueTracks(store: MusicStore, from: String, to: String) {
    fun remap(snapshot: QueueSnapshot) = snapshot.copy(entries = snapshot.entries.map { entry ->
        if (entry.trackId == from) entry.copy(trackId = to) else entry
    })
    for (record in store.database.records().list("queues")) {
        val snapshot = store.json.decodeFromString<QueueSnapshot>(record.value)
        if (snapshot.entries.any { it.trackId == from }) store.put(record.key, "queues", remap(snapshot))
    }
    for (saved in store.list<SavedQueue>("saved-queues")) {
        if (saved.snapshot.entries.any { it.trackId == from })
            store.put("saved-queue:${saved.id}", "saved-queues", saved.copy(snapshot = remap(saved.snapshot)))
    }
}

@Serializable
data class QueueSnapshot(
    val entries: List<QueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val positionMs: Long = 0,
    val lastInsertedEntryId: String? = null,
    val unshuffledEntryIds: List<String>? = null,
    val albumShuffled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val repeatPlays: Int? = null,
    val contextName: String = "Library",
    val origin: QueueOrigin? = null,
    val selectedSavedQueueId: String? = null,
) {
    val currentIndex: Int get() = entries.indexOfFirst { it.id == currentEntryId }
    val currentTrackId: String? get() = entries.getOrNull(currentIndex)?.trackId
    val upNext: List<QueueEntry> get() = entries.drop(currentIndex + 1).filter { it.section == QueueSection.MANUAL }
    val context: List<QueueEntry> get() = entries.drop(currentIndex + 1).filter { it.section == QueueSection.CONTEXT }
}

class PlaybackQueue(private val store: MusicStore, private val queueId: String = "main") {
    private val key = "queue:$queueId"
    /** The last decoded queue with its stored JSON; playback reads the queue many times per second. */
    @Volatile private var decoded: Pair<String, QueueSnapshot>? = null

    private suspend fun write(snapshot: QueueSnapshot, replacement: Boolean = false, captureEdits: Boolean = true) = store.transaction {
        if (captureEdits) {
            val settings = store.read<MusicSettings>("settings:music") ?: MusicSettings()
            if (settings.queueRecoveryEnabled && (replacement || settings.queueRecoveryMode == QueueRecoveryMode.ON_CHANGE)) {
                val before = snapshot()
                if (before.recoveryContents() != snapshot.recoveryContents()) {
                    if (replacement) captureRecovery(before)
                    if (settings.queueRecoveryMode == QueueRecoveryMode.ON_CHANGE) captureRecovery(snapshot)
                }
            }
        }
        val selected = snapshot.selectedSavedQueueId?.let { store.read<SavedQueue>("saved-queue:$it") }?.takeIf { it.kind == SavedQueueKind.LIVE }
        val live = if (selected == null) snapshot.copy(selectedSavedQueueId = null) else snapshot
        val json = store.json.encodeToString(live)
        store.database.records().put(key, "queues", json)
        decoded = json to live
        if (selected != null) store.put("saved-queue:${selected.id}", "saved-queues",
            selected.copy(snapshot = live.copy(selectedSavedQueueId = null)))
    }

    suspend fun enqueue(trackIds: List<String>, action: QueueAction) = store.transaction {
        if (trackIds.isEmpty()) return@transaction
        val current = snapshot()
        val anchor = current.entries.indexOfFirst { it.id == current.lastInsertedEntryId && it.section == QueueSection.MANUAL }
        val destination = when (action) {
            QueueAction.PLAY_NEXT -> (current.currentIndex + 1).coerceAtLeast(0)
            QueueAction.PLAY_AFTER -> maxOf(anchor, current.currentIndex) + 1
            QueueAction.APPEND -> current.entries.indexOfFirstAfter(current.currentIndex) { it.section == QueueSection.CONTEXT }
        }
        val inserted = trackIds.map { QueueEntry(Uuid.random().toString(), it, QueueSection.MANUAL) }
        val before = current.entries.getOrNull(destination - 1)?.id
        val entries = current.entries.toMutableList().apply { addAll(destination, inserted) }
        write(current.copy(entries = entries,
            currentEntryId = current.currentEntryId ?: inserted.first().id, lastInsertedEntryId = inserted.last().id,
            unshuffledEntryIds = current.unshuffledEntryIds?.toMutableList()?.apply {
                val index = (indexOf(before) + 1).coerceAtLeast(0)
                addAll(index, inserted.map { it.id })
            }))
    }

    suspend fun setRepeat(mode: RepeatMode, playCount: Int? = null) = store.transaction {
        require(playCount == null || (mode == RepeatMode.ONE && playCount > 0)) { "A repeat count requires song repeat and at least one play" }
        write(snapshot().copy(repeatMode = mode, repeatPlays = playCount))
    }

    suspend fun shuffle(enabled: Boolean, random: Random = Random.Default) = store.transaction {
        val current = snapshot()
        if (enabled && (current.unshuffledEntryIds == null || current.albumShuffled)) {
            val upcoming = (current.currentIndex + 1).coerceAtLeast(0)
            write(current.copy(
                entries = current.entries.take(upcoming) + current.upNext + current.context.shuffled(random),
                unshuffledEntryIds = current.unshuffledEntryIds ?: current.entries.map { it.id }, albumShuffled = false))
        } else if (!enabled && current.unshuffledEntryIds != null) {
            val entries = current.entries.associateBy { it.id }
            val original = current.unshuffledEntryIds.mapNotNull(entries::get)
            val originalIds = current.unshuffledEntryIds.toHashSet()
            write(current.copy(
                entries = (original + current.entries.filterNot { it.id in originalIds }).let { restored ->
                    val split = restored.indexOfFirst { it.id == current.currentEntryId } + 1
                    restored.take(split) + restored.drop(split).filter { it.section == QueueSection.MANUAL } +
                        restored.drop(split).filter { it.section == QueueSection.CONTEXT }
                },
                unshuffledEntryIds = null, albumShuffled = false))
        }
    }

    suspend fun shuffleAlbums(albums: List<List<String>>, random: Random = Random.Default) = store.transaction {
        val current = snapshot()
        val albumByTrack = albums.flatMapIndexed { index, tracks -> tracks.map { it to index } }.toMap()
        val trackOrder = albums.flatMap { tracks -> tracks.mapIndexed { index, id -> id to index } }.toMap()
        fun group(entry: QueueEntry): String = albumByTrack[entry.trackId]?.toString() ?: entry.id
        val upcoming = (current.currentIndex + 1).coerceAtLeast(0)
        val groups = current.context.groupBy(::group).mapValues { (_, entries) ->
            entries.sortedBy { trackOrder[it.trackId] ?: 0 }
        }
        val currentGroup = current.entries.getOrNull(current.currentIndex)?.let(::group)
        val remaining = groups.filterKeys { it != currentGroup }.values.toList().shuffled(random).flatten()
        write(current.copy(
            entries = current.entries.take(upcoming) + current.upNext + groups[currentGroup].orEmpty() + remaining,
            unshuffledEntryIds = current.unshuffledEntryIds ?: current.entries.map { it.id }, albumShuffled = true))
    }

    suspend fun save(name: String, select: Boolean = false, kind: SavedQueueKind = SavedQueueKind.LIVE): SavedQueue = store.transaction {
        require(name.isNotBlank()) { "Enter a queue name" }
        require(kind != SavedQueueKind.RECOVERY && (!select || kind == SavedQueueKind.LIVE)) { "Only live queues can save edits automatically" }
        val saved = SavedQueue(Uuid.random().toString(), name.trim(), snapshot().copy(selectedSavedQueueId = null), kind,
            kotlin.time.Clock.System.now().toEpochMilliseconds())
        store.put("saved-queue:${saved.id}", "saved-queues", saved)
        if (select) write(saved.snapshot.copy(selectedSavedQueueId = saved.id))
        saved
    }

    suspend fun saved(): List<SavedQueue> = store.list<SavedQueue>("saved-queues").sortedBy { it.name.lowercase() }
    fun observeSaved() = store.database.records().observe("saved-queues").map { Unit }

    suspend fun renameSaved(id: String, name: String) = store.transaction {
        require(name.isNotBlank()) { "Enter a queue name" }
        val saved = requireNotNull(store.read<SavedQueue>("saved-queue:$id")) { "Saved queue is missing" }
        store.put("saved-queue:$id", "saved-queues", saved.copy(name = name.trim()))
    }

    suspend fun duplicateSaved(id: String, name: String): SavedQueue = store.transaction {
        require(name.isNotBlank()) { "Enter a queue name" }
        val saved = requireNotNull(store.read<SavedQueue>("saved-queue:$id")) { "Saved queue is missing" }
        val copy = saved.copy(id = Uuid.random().toString(), name = name.trim(),
            kind = if (saved.kind == SavedQueueKind.RECOVERY) SavedQueueKind.SNAPSHOT else saved.kind,
            capturedAt = kotlin.time.Clock.System.now().toEpochMilliseconds())
        store.put("saved-queue:${copy.id}", "saved-queues", copy)
        copy
    }

    suspend fun convertSaved(id: String, kind: SavedQueueKind) = store.transaction {
        require(kind != SavedQueueKind.RECOVERY) { "Recovery snapshots are captured automatically" }
        val saved = requireNotNull(store.read<SavedQueue>("saved-queue:$id")) { "Saved queue is missing" }
        store.put("saved-queue:$id", "saved-queues", saved.copy(kind = kind))
        if (kind != SavedQueueKind.LIVE && snapshot().selectedSavedQueueId == id) detachSaved()
    }

    /** Fifteen unique queue contents; playback checkpoints update an existing recovery copy. */
    suspend fun captureRecovery(now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()) = captureRecovery(snapshot(), now)

    suspend fun captureRecovery(snapshot: QueueSnapshot, now: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()) = store.transaction {
        if (snapshot.entries.isEmpty()) return@transaction
        val recoveries = saved().filter { it.kind == SavedQueueKind.RECOVERY }
        val capturedAt = maxOf(now, (recoveries.maxOfOrNull { it.capturedAt } ?: 0) + 1)
        val previous = recoveries.firstOrNull { it.snapshot.recoveryContents() == snapshot.recoveryContents() }
        val saved = previous?.copy(snapshot = snapshot.copy(selectedSavedQueueId = null), capturedAt = capturedAt)
            ?: SavedQueue(Uuid.random().toString(), snapshot.contextName, snapshot.copy(selectedSavedQueueId = null), SavedQueueKind.RECOVERY, capturedAt)
        store.put("saved-queue:${saved.id}", "saved-queues", saved)
        (recoveries.filterNot { it.id == saved.id } + saved).sortedByDescending { it.capturedAt }.drop(15).forEach {
            store.database.records().delete("saved-queue:${it.id}")
        }
    }

    suspend fun overwriteSaved(id: String) = store.transaction {
        val saved = requireNotNull(store.read<SavedQueue>("saved-queue:$id")) { "Saved queue is missing" }
        val updated = saved.copy(snapshot = snapshot().copy(selectedSavedQueueId = null),
            capturedAt = kotlin.time.Clock.System.now().toEpochMilliseconds())
        store.put("saved-queue:$id", "saved-queues", updated)
        if (saved.kind == SavedQueueKind.LIVE) write(updated.snapshot.copy(selectedSavedQueueId = id))
    }

    suspend fun detachSaved() = write(snapshot().copy(selectedSavedQueueId = null))

    /** Starts a temporary queue that keeps only the current song; a selected live queue keeps its contents. */
    suspend fun startEmpty() = store.transaction {
        val current = snapshot()
        write(QueueSnapshot(listOfNotNull(current.entries.getOrNull(current.currentIndex)?.copy(section = QueueSection.CONTEXT)), current.currentEntryId,
            current.positionMs, repeatMode = current.repeatMode, repeatPlays = current.repeatPlays), replacement = true)
    }

    internal suspend fun updateSavedSnapshot(id: String, snapshot: QueueSnapshot) = store.transaction {
        val saved = requireNotNull(store.read<SavedQueue>("saved-queue:$id")) { "Saved queue is missing" }
        store.put("saved-queue:$id", "saved-queues", saved.copy(snapshot = snapshot.copy(selectedSavedQueueId = null)))
    }

    suspend fun restoreSaved(id: String) = store.transaction {
        val saved = requireNotNull(store.read<SavedQueue>("saved-queue:$id")) { "Saved queue is missing" }
        write(saved.snapshot.copy(selectedSavedQueueId = id.takeIf { saved.kind == SavedQueueKind.LIVE }), replacement = true)
    }

    suspend fun deleteSaved(id: String) = store.transaction {
        store.database.records().delete("saved-queue:$id")
        if (snapshot().selectedSavedQueueId == id) detachSaved()
    }

    suspend fun playAfter(trackId: String) = enqueue(listOf(trackId), QueueAction.PLAY_AFTER)
    suspend fun snapshot(): QueueSnapshot {
        val json = store.database.records().read(key) ?: return QueueSnapshot()
        decoded?.let { (stored, snapshot) -> if (stored == json) return snapshot }
        return store.json.decodeFromString<QueueSnapshot>(json).also { decoded = json to it }
    }

    suspend fun playNow(trackId: String) = store.transaction {
        val current = snapshot()
        val playing = QueueEntry(Uuid.random().toString(), trackId, QueueSection.MANUAL)
        val entries = current.entries.toMutableList().apply { add(current.currentIndex + 1, playing) }
        write(current.copy(entries = entries, currentEntryId = playing.id,
            positionMs = 0, repeatPlays = null, unshuffledEntryIds = current.unshuffledEntryIds?.toMutableList()?.apply {
                add((indexOf(current.currentEntryId) + 1).coerceAtLeast(0), playing.id)
            }))
    }

    suspend fun restore(snapshot: QueueSnapshot) {
        write(snapshot, replacement = true)
    }

    suspend fun replace(trackIds: List<String>, startIndex: Int = 0, contextName: String = "Library", shuffle: Boolean = false,
        origin: QueueOrigin = QueueOrigin()) {
        val original = trackIds.map { QueueEntry(Uuid.random().toString(), it) }
        val current = original.getOrNull(startIndex)
        val remaining = original.filterNot { it.id == current?.id }
        val entries = if (shuffle && current != null) listOf(current) + remaining.shuffled() else original
        write(QueueSnapshot(entries, current?.id, contextName = contextName, origin = origin,
            unshuffledEntryIds = if (shuffle && current != null) (listOf(current) + remaining).map { it.id } else null), replacement = true)
    }

    suspend fun clearUpcoming() = clear(QueueClear.ALL)

    suspend fun clear(scope: QueueClear) = store.transaction {
        val current = snapshot()
        val seen = mutableSetOf<String>().apply { current.currentTrackId?.let(::add) }
        val entries = current.entries.filterIndexed { index, entry ->
            entry.id == current.currentEntryId || when (scope) {
                QueueClear.DUPLICATES -> seen.add(entry.trackId)
                QueueClear.PREVIOUS -> index >= current.currentIndex
                QueueClear.NEXT -> index < current.currentIndex
                QueueClear.ALL -> false
            }
        }
        val ids = entries.map { it.id }.toSet()
        val original = current.unshuffledEntryIds?.filter { it in ids }
        write(current.copy(
            entries = entries, lastInsertedEntryId = current.lastInsertedEntryId?.takeIf { it in ids },
            unshuffledEntryIds = original, albumShuffled = original != null && current.albumShuffled), replacement = true)
    }

    suspend fun playNext(trackId: String) = enqueue(listOf(trackId), QueueAction.PLAY_NEXT)

    suspend fun append(trackId: String) = enqueue(listOf(trackId), QueueAction.APPEND)

    suspend fun reorder(entryId: String, destination: Int, section: QueueSection? = null) = store.transaction {
        val current = snapshot()
        val entries = current.entries.toMutableList()
        val origin = entries.indexOfFirst { it.id == entryId }
        require(origin >= 0 && destination in entries.indices) { "Queue entry or destination is invalid" }
        val destinationSection = section ?: if (destination > current.currentIndex) entries[destination].section else entries[origin].section
        val moved = entries.removeAt(origin)
        entries.add(destination, moved.copy(section = destinationSection))
        val originalOrder = current.unshuffledEntryIds?.toMutableList()?.apply {
            remove(entryId)
            val preceding = entries.getOrNull(destination - 1)?.id
            add((indexOf(preceding) + 1).coerceAtLeast(0), entryId)
        }
        val insertion = when {
            moved.section != QueueSection.MANUAL && destinationSection == QueueSection.MANUAL -> entryId
            entryId == current.lastInsertedEntryId && destinationSection != QueueSection.MANUAL ->
                entries.drop(current.currentIndex + 1).lastOrNull { it.section == QueueSection.MANUAL }?.id
            else -> current.lastInsertedEntryId
        }
        write(current.copy(entries = entries, unshuffledEntryIds = originalOrder,
            lastInsertedEntryId = insertion))
    }

    suspend fun remove(entryId: String) = store.transaction {
        val current = snapshot()
        val entries = current.entries.filterNot { it.id == entryId }
        val removedCurrent = current.currentEntryId == entryId
        val selected = if (removedCurrent)
            entries.getOrNull(current.currentIndex.coerceAtMost(entries.lastIndex))?.id
        else current.currentEntryId
        val finishedRepeat = removedCurrent && current.repeatPlays != null
        write(current.copy(entries = entries,
            currentEntryId = selected, positionMs = if (removedCurrent) 0 else current.positionMs,
            repeatMode = if (finishedRepeat) RepeatMode.OFF else current.repeatMode,
            repeatPlays = if (finishedRepeat) null else current.repeatPlays))
    }

    suspend fun setPosition(positionMs: Long) = store.transaction {
        write(snapshot().copy(positionMs = positionMs.coerceAtLeast(0)), captureEdits = false)
    }

    suspend fun select(entryId: String) = store.transaction {
        val current = snapshot()
        require(current.entries.any { it.id == entryId }) { "Queue entry is missing" }
        val finishedRepeat = current.repeatPlays != null && current.currentEntryId != entryId
        write(current.copy(currentEntryId = entryId, positionMs = 0,
            repeatMode = if (finishedRepeat) RepeatMode.OFF else current.repeatMode,
            repeatPlays = if (finishedRepeat) null else current.repeatPlays), captureEdits = false)
    }
}

private fun QueueSnapshot.recoveryContents(): List<Any?> {
    val indices = entries.withIndex().associate { it.value.id to it.index }
    return listOf(entries.map { it.trackId to it.section }, unshuffledEntryIds?.map { indices[it] }, albumShuffled,
        repeatMode, repeatPlays, contextName, origin)
}
