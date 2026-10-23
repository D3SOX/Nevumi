package me.d3sox.nevumi.history

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.storage.MusicStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class Listen(val id: String, val trackId: String, val playbackId: String, val startedAt: Long,
    val metadata: TrackMetadata, val listenedMs: Long = 0, val playedMs: Long = 0, val durationMs: Long = 0, val ended: Boolean = false,
    val originDeviceId: String? = null,
    /** When this device first received the listen from a paired device; local, never shared. Omitted when absent so
     *  shared snapshots keep the wire format that previous versions accept. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER) val importedAt: Long? = null) {
    val qualified: Boolean get() = playedMs >= (if (durationMs > 0) minOf(durationMs / 2, 240_000) else 240_000).coerceAtLeast(1)
}

data class ListeningStatistics(val playCount: Int, val listenedMs: Long, val lastPlayedAt: Long)

fun listeningStatistics(listens: List<Listen>): Map<String, ListeningStatistics> = listens.groupBy { it.trackId }.mapValues { (_, events) ->
    ListeningStatistics(events.count { it.qualified }, events.sumOf { it.listenedMs }, events.maxOf { it.startedAt })
}

internal suspend fun remapListeningHistory(store: MusicStore, from: String, to: String) {
    for (event in store.list<Listen>("listens").filter { it.trackId == from })
        store.put("listen:${event.id}", "listens", event.copy(trackId = to))
}

private fun createElapsedClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

class ListeningHistory(private val store: MusicStore, private val library: MusicLibrary,
    private val wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val elapsedClock: () -> Long = createElapsedClock()) {
    private val recording = Mutex()
    // Only the running listen changes between reads; keep the decoded history and decode changed records.
    private val decoding = Mutex()
    private val decoded = me.d3sox.nevumi.storage.decodedRecords<Listen>(store.json)
    private var loadedRevision: Long? = null
    private var loaded = false
    private var sorted = emptyList<Listen>()
    private fun List<Listen>.newestFirst() = sortedWith(compareByDescending<Listen> { it.startedAt }.thenBy { it.id })
    private suspend fun load(): List<Listen> = decoding.withLock {
        val revision = store.revision("listens")
        if (loaded && revision == loadedRevision) return@withLock sorted
        sorted = decoded.update(store.database.records().list("listens")).all.newestFirst()
        loadedRevision = revision
        // A transaction may roll back after this read, so its result is checked against the store again outside it.
        loaded = !store.inTransaction()
        sorted
    }
    private data class Recording(var listen: Listen, var previous: PlaybackStatus,
        var observedAt: Long, var writtenAt: Long)
    private val sessions = mutableMapOf<String, Recording>()

    suspend fun record(playback: PlaybackStatus, enabled: Boolean = true) = recording.withLock {
        val now = elapsedClock()
        val observed = listOfNotNull(playback.copy(fadingOut = null), playback.fadingOut?.copy(fadingOut = null))
            .filter { it.playbackId != null && it.trackId != null }.distinctBy { it.playbackId }
            .associateBy { requireNotNull(it.playbackId) }
        for ((id, session) in sessions.toMap()) {
            val current = observed[id]?.let { it.copy(playing = it.playing && enabled) }
                ?: session.previous.copy(playing = false, ended = true)
            val heard = if (session.previous.playing) (now - session.observedAt).coerceAtLeast(0) else 0
            val speed = session.previous.speed.takeIf { it.isFinite() && it > 0 } ?: 1.0
            val event = session.listen
            val updated = event.copy(listenedMs = event.listenedMs + heard, playedMs = event.playedMs + (heard * speed).toLong(),
                durationMs = current.durationMs.takeIf { it > 0 } ?: event.durationMs, ended = current.ended)
            session.listen = updated
            // Every write wakes every database observer, so checkpoint a running listen only every few seconds.
            if (current.ended || session.previous.playing != current.playing || updated.qualified != event.qualified || now - session.writtenAt >= 5_000) {
                session.listen = persist(updated)
                session.writtenAt = now
            }
            session.previous = current
            session.observedAt = now
            if (current.ended) sessions.remove(id)
        }
        for ((id, current) in observed) {
            if (id !in sessions && enabled && current.playing && !current.ended && current.error == null) {
                library.findTrack(requireNotNull(current.trackId))?.let { track ->
                    val event = Listen(Uuid.random().toString(), track.id, id, wallClock(), track.metadata.copy(folder = ""),
                        durationMs = current.durationMs.takeIf { it > 0 } ?: track.metadata.durationMs)
                    sessions[id] = Recording(persist(event), current, now, now)
                }
            }
        }
    }

    private suspend fun persist(event: Listen): Listen = store.transaction {
        val saved = event.copy(trackId = store.read<Listen>("listen:${event.id}")?.trackId ?: event.trackId)
        store.put("listen:${event.id}", "listens", saved)
        saved
    }

    suspend fun all(): List<Listen> = load()
    suspend fun clear() = recording.withLock {
        store.transaction {
            val received = store.list<me.d3sox.nevumi.sync.SyncSnapshot>("sync-received")
            val ids = (all().map { it.id } + received.flatMap { it.listens }.map { it.id }).toSet()
            for (id in ids) store.put("listen-suppressed:$id", "listen-suppressed", id)
            for (snapshot in received) store.put("sync:received:${snapshot.deviceId}", "sync-received", snapshot.copy(listens = emptyList()))
            store.database.records().deleteKind("listens")
        }
        sessions.clear()
    }
    fun observe(): kotlinx.coroutines.flow.Flow<List<Listen>> = store.database.records().observeRevision("listens").distinctUntilChanged().map { load() }
    suspend fun statistics(): Map<String, ListeningStatistics> = listeningStatistics(all())
    suspend fun report(tracks: List<Track>, period: ListeningPeriod = ListeningPeriod.ALL_TIME): ListeningReport =
        listeningReport(all(), tracks, period, wallClock())
    suspend fun recommendations(tracks: List<Track>, limit: Int = 20): List<ListeningRecommendation> =
        listeningRecommendations(all(), tracks, limit, wallClock())
}
