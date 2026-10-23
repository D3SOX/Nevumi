package me.d3sox.nevumi.app

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.downloads.nextDownloadBatch
import me.d3sox.nevumi.downloads.playlistDownloadProgress
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_already_available_offline
import me.d3sox.nevumi.resources.notice_download_queued

/** Tracks offline downloads: requests, pausing, retries and the batch and playlist progress shown in the app. */
class DownloadController internal constructor(private val context: ControllerContext, private val loader: AppStateLoader,
    private val playback: PlaybackController, private val library: MusicLibrary, private val queue: PlaybackQueue,
    private val engine: PlaybackEngine, private val offline: OfflineMusic?) {
    init {
        offline?.let { downloads -> context.scope.launch {
            var completed: Map<String, String?>? = null
            downloads.state.collect { progress ->
                context.operations.withLock {
                    // Byte progress only affects the UI; the player needs to hear about finished or evicted files.
                    val files = progress.filterValues { it.status == DownloadStatus.COMPLETE }.mapValues { it.value.path }
                    if (playback.queueLoaded && files != completed) engine.updateQueue(playback.playableQueue(queue.snapshot()))
                    completed = files
                    context.update { it.copy(downloads = progress) }
                }
            }
        } }
        offline?.let { downloads -> context.scope.launch {
            downloads.excluded.collect { excluded ->
                context.operations.withLock { context.update { it.copy(excludedDownloads = excluded) } }
            }
        } }
        if (offline != null) context.scope.launch {
            context.state.map { listOf(it.downloads, it.playlists, it.tracks, it.server?.serverId, it.excludedDownloads) }
                .distinctUntilChanged().collect {
                    context.operations.withLock { context.update { state ->
                        val playlists = playlistDownloadProgress(state.playlists, state.tracks, state.downloads,
                            state.server?.serverId, state.excludedDownloads)
                        state.copy(playlistDownloads = playlists,
                            downloadBatch = nextDownloadBatch(state.downloadBatch.trackIds, state.downloads, playlists))
                    } }
                }
        }
        offline?.let { downloads -> context.scope.launch {
            downloads.network.collect { network ->
                context.operations.withLock { context.update { it.copy(downloadNetwork = network) } }
            }
        } }
    }

    fun downloadTracks(trackIds: List<String>) = context.action {
        val remote = trackIds.distinct().filter { id ->
            requireNotNull(library.findTrack(id)) { "Track is missing" }.sources.none { it.kind == SourceKind.LOCAL && it.available }
        }
        if (remote.isEmpty()) { context.confirm(Res.string.notice_already_available_offline); return@action }
        val downloads = requireNotNull(offline) { "Downloads are unavailable" }
        var undo = downloads.requestUndoable(remote)
        context.confirm(Res.string.notice_download_queued, ActionDestination.DOWNLOADS, undo = { undo() }, redo = { undo = downloads.requestUndoable(remote) })
    }
    fun removeDownload(trackId: String) = context.action {
        requireNotNull(offline) { "Downloads are unavailable" }.remove(trackId)
        loader.refresh()
    }
    fun setDownloadsPaused(paused: Boolean) = context.action {
        val downloads = requireNotNull(offline) { "Downloads are unavailable" }
        if (paused) downloads.pause() else downloads.resume()
        loader.refresh()
    }
    fun retryDownloads() { offline?.retry() }
}
