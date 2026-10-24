package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerPlayQueue
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_continue_queue_from
import me.d3sox.nevumi.resources.notice_continue_server_queue
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Keeps the music server's play queue in step with this device's queue, so another device or Subsonic app can continue it,
 * and offers to continue a queue that another device or app saved there.
 */
class ServerQueueController internal constructor(private val context: ControllerContext, private val playback: PlaybackController,
    private val queue: PlaybackQueue, private val engine: PlaybackEngine, private val navidrome: NavidromeConnection?) {
    private var started = false
    private var checkedAt: TimeMark? = null
    /** The server whose queue this session has read; uploads wait for it, so they never replace a queue nobody saw. */
    private var checkedServer: String? = null
    private var offered: Pair<String, ServerPlayQueue>? = null

    private val enabled: Boolean get() = navidrome != null && context.state.value.server != null &&
        context.state.value.preferences.syncPlayQueue

    /** Checks the server once the persisted queue is loaded, then saves later queue and transport changes. */
    internal fun start() = context.action(serialized = false) {
        coroutineScope {
            val startupCheck = async {
                check(force = true)
                started = true
            }
            launch { startupCheck.await(); checkWhenEnabled() }
            uploadChanges(startupCheck)
        }
    }

    /** Called when the app comes to the foreground; at most one check runs per interval. */
    fun checkServerQueue(): Job = context.action(serialized = false) { if (started) check() }

    /** Turning sync on, or connecting another server, reads that server's queue before this device may replace it. */
    private suspend fun checkWhenEnabled() {
        context.state.map { state -> state.server?.serverId?.takeIf { state.preferences.syncPlayQueue } }.distinctUntilChanged().drop(1)
            .collect { serverId -> checkedServer = null; if (serverId != null) check(force = true) }
    }

    /** Reads the server's queue even while this device plays, so uploads may follow; only the offer waits for a pause. */
    private suspend fun check(force: Boolean = false) {
        if (!enabled || !force && checkedAt?.let { it.elapsedNow() < CHECK_INTERVAL } == true) return
        checkedAt = TimeSource.Monotonic.markNow()
        val connection = connection() ?: return
        val remote = try { connection.playQueue() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return }
        checkedServer = connection.serverId
        if (remote == null) return
        val offer = connection.serverId to remote
        if (engine.status.value.playWhenReady || offer == offered ||
            remote.copy(changedBy = "") == attempt { navidrome?.uploadedPlayQueue(connection.serverId) }) return
        val tracks = context.state.value.tracks
        val songs = serverSongs(tracks, connection.serverId)
        val trackId = songs[remote.currentId] ?: return
        val local = localQueue(queue.snapshot(), tracks, connection.serverId)
        if (local != null && local.songIds == remote.songIds && local.currentId == remote.currentId &&
            abs(local.positionMs - remote.positionMs) < SAME_POSITION_MS) return
        offered = offer
        val title = tracks.first { it.id == trackId }.metadata.title
        val notice = ActionNotice(if (remote.changedBy.isBlank()) Res.string.notice_continue_server_queue else Res.string.notice_continue_queue_from,
            arguments = listOfNotNull(title, remote.changedBy.ifBlank { null }), play = { resume(remote, connection.serverId) })
        // Notices are not replayed, and Android can start the app for its playback service before any window collects them.
        context.scope.launch {
            context.mutableNotices.subscriptionCount.first { it > 0 }
            if (offered == offer && context.state.value.server?.serverId == connection.serverId &&
                !engine.status.value.playWhenReady) context.mutableNotices.tryEmit(notice)
        }
    }

    private fun resume(remote: ServerPlayQueue, serverId: String): Job = context.action {
        if (context.state.value.server?.serverId != serverId || navidrome?.profile()?.serverId != serverId) return@action
        val songs = serverSongs(context.state.value.tracks, serverId)
        val available = remote.songIds.filter { it in songs }
        val index = available.indexOf(remote.currentId)
        require(index >= 0) { "The song to continue is no longer in your library" }
        playback.discardIncomingPlayback()
        playback.failedSources.clear()
        context.update { it.copy(error = null) }
        queue.replace(available.map { songs.getValue(it) }, index, remote.changedBy.ifBlank { "Music server" })
        queue.setPosition(remote.positionMs)
        playback.loadSnapshot(queue.snapshot())
        playback.queueLoaded = true
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private suspend fun uploadChanges(startupCheck: Deferred<Unit>) {
        val queueChanges = context.state.map { it.queue.entries to it.queue.currentEntryId }.distinctUntilChanged().drop(1)
        val playing = engine.status.map { it.playWhenReady }.distinctUntilChanged()
        // A song that keeps playing saves its position now and then, so a device that stops without pausing loses little.
        val progress = playing.flatMapLatest { if (it) flow { while (true) { delay(UPLOAD_INTERVAL_MS); emit(Unit) } } else emptyFlow() }
        merge(queueChanges.map { }, playing.drop(1).map { }, context.seeks.map { }, progress)
            .debounce(UPLOAD_DELAY_MS).collect {
                startupCheck.await()
                upload()
            }
    }

    private suspend fun upload() {
        if (!enabled) return
        val connection = connection() ?: return
        // A server that could not be read yet, such as one unreachable at startup, is read now; the next change uploads.
        if (checkedServer != connection.serverId) return check(force = true)
        val local = localQueue(queue.snapshot(), context.state.value.tracks, connection.serverId) ?: return
        if (local == attempt { navidrome?.uploadedPlayQueue(connection.serverId) }) return
        if (attempt { connection.savePlayQueue(local) } != null) attempt { navidrome?.rememberUploadedPlayQueue(connection.serverId, local) }
    }

    private fun localQueue(snapshot: QueueSnapshot, tracks: List<Track>, serverId: String): ServerPlayQueue? {
        val status = engine.status.value
        val positionMs = if (status.entryId != null && status.entryId == snapshot.currentEntryId) status.positionMs else snapshot.positionMs
        val songs = tracks.associate { track -> track.id to track.serverSongId(serverId) }
        return snapshot.serverPlayQueue(positionMs) { songs[it] }
    }

    private suspend fun connection(): NavidromeClient? = attempt { navidrome?.client() }

    /** Sync runs in the background, so a failure waits for the next change instead of interrupting the user. */
    private suspend fun <T> attempt(block: suspend () -> T?): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private companion object {
        val CHECK_INTERVAL = 30.seconds
        const val SAME_POSITION_MS = 10_000L
        const val UPLOAD_DELAY_MS = 3_000L
        const val UPLOAD_INTERVAL_MS = 60_000L
    }
}

private fun Track.serverSongId(serverId: String): String? =
    sources.firstOrNull { it.kind == SourceKind.SERVER && it.id.startsWith("server:$serverId:") }?.location

/** Maps the server's song IDs to the library tracks that hold them. */
private fun serverSongs(tracks: List<Track>, serverId: String): Map<String, String> = buildMap {
    for (track in tracks) track.serverSongId(serverId)?.let { putIfAbsent(it, track.id) }
}

/** Server queues travel in a request URL, which proxies limit to a few kilobytes. */
internal const val SERVER_QUEUE_LIMIT = 200
internal const val SERVER_QUEUE_HISTORY = 20

/**
 * The server songs around the current one, or null when the current song is not on the server. Earlier plays of the current
 * song are left out, because Subsonic marks the current song by its ID and a client resumes at its first appearance.
 */
internal fun QueueSnapshot.serverPlayQueue(positionMs: Long, serverSongId: (String) -> String?): ServerPlayQueue? {
    val current = currentIndex.takeIf { it >= 0 } ?: return null
    val currentId = serverSongId(entries[current].trackId) ?: return null
    val before = entries.subList(0, current).mapNotNull { serverSongId(it.trackId) }.filter { it != currentId }.takeLast(SERVER_QUEUE_HISTORY)
    val after = entries.subList(current + 1, entries.size).asSequence().mapNotNull { serverSongId(it.trackId) }
        .take(SERVER_QUEUE_LIMIT - before.size - 1).toList()
    return ServerPlayQueue(before + currentId + after, currentId, positionMs.coerceAtLeast(0))
}
