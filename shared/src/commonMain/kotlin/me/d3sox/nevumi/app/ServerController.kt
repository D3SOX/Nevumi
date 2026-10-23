package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_playlist_edit_discarded
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.CONCURRENT_COVERS
import me.d3sox.nevumi.navidrome.NavidromeRequestException
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.ServerRefreshProgress
import me.d3sox.nevumi.navidrome.ServerRefreshStage
import me.d3sox.nevumi.navidrome.serverUnavailable
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.sftp.ServerFiles
import me.d3sox.nevumi.sftp.SftpAuthentication
import me.d3sox.nevumi.sftp.SftpProfile
import me.d3sox.nevumi.tunnel.TailscaleStatus
import me.d3sox.nevumi.tunnel.Tunnel
import me.d3sox.nevumi.tunnel.TunnelRoutes

/** Connects the music server and its files: imports, uploads, the Tailscale tunnel and new stream URLs when the routes change. */
class ServerController internal constructor(private val context: ControllerContext, private val loader: AppStateLoader,
    private val playback: PlaybackController, private val libraryController: LibraryController, private val library: MusicLibrary,
    private val queue: PlaybackQueue, private val engine: PlaybackEngine, private val navidrome: NavidromeConnection?,
    private val serverFiles: ServerFiles?, private val serverChanges: NavidromeChanges?, private val artwork: ArtworkLibrary?,
    private val offline: OfflineMusic?, private val tunnel: Tunnel?, private val networkAccess: suspend (String) -> Unit) {
    val serverFilesAvailable: Boolean get() = serverFiles != null
    private val serverImports = Mutex()
    private val serverRefreshJob = MutableStateFlow<Job?>(null)

    init {
        // Subscribe before the first sync so its notices are not lost.
        serverChanges?.let { changes -> context.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            changes.discardedEdits.collect { name ->
                context.confirm(Res.string.notice_playlist_edit_discarded, ActionDestination.PLAYLISTS, arguments = listOf(name))
            }
        } }
        serverChanges?.start()
        // Warming up can wait up to 30 seconds inside Tailscale, which cancelling cannot interrupt, so closing does not wait for it.
        serverFiles?.let { files -> CoroutineScope(Dispatchers.Default).launch { files.warmUp() } }
    }

    suspend fun serverMusicDirectories() = requireNotNull(serverFiles) { "Server file editing is unavailable" }.libraries()
    suspend fun connectServerFiles(profile: SftpProfile, libraryId: Long, authentication: SftpAuthentication) {
        reachAccess(profile.host, tunnel?.routes()?.serverFiles == true)
        requireNotNull(serverFiles) { "Server file editing is unavailable" }.connect(profile, libraryId, authentication)
        context.operations.withLock { loader.refresh() }
    }
    suspend fun tailscaleStatus() = requireNotNull(tunnel) { "Tailscale is unavailable" }.status()
    suspend fun signInToTailscale(controlUrl: String, authKey: String?): TailscaleStatus {
        if (controlUrl.isNotEmpty()) networkAccess(controlUrl)
        return requireNotNull(tunnel) { "Tailscale is unavailable" }.signIn(controlUrl, authKey).also { reloadStreams() }
    }
    suspend fun signOutOfTailscale() {
        requireNotNull(tunnel) { "Tailscale is unavailable" }.signOut()
        reloadStreams()
    }
    suspend fun tunnelRoutes() = tunnel?.routes() ?: TunnelRoutes()
    suspend fun updateTunnelRoutes(routes: TunnelRoutes) {
        val tunnel = requireNotNull(tunnel) { "Tailscale is unavailable" }
        val previous = tunnel.routes()
        if (routes.musicServer && !previous.musicServer || routes.serverFiles && !previous.serverFiles || routes.scGate && !previous.scGate)
            reachAccess("", tunneled = true)
        tunnel.updateRoutes(routes)
        reloadStreams()
    }
    /**
     * Stream URLs depend on the music server route and its relay, so the loaded queue gets new ones when either changes.
     * Failures go to the app state, because the route change they follow is already saved.
     */
    private suspend fun reloadStreams() = context.operations.withLock {
        try {
            if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(queue.snapshot()))
            loader.refresh()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { context.update { it.copy(error = failure.message ?: "Playback could not switch connections") } }
    }
    /** Tailscale learns peer endpoints internally. A blank address asks for local network access, so peers on the LAN connect directly. */
    internal suspend fun reachAccess(address: String, tunneled: Boolean) = networkAccess(if (tunneled) "" else address)
    internal suspend fun musicServerAccess(address: String) = reachAccess(address, tunnel?.routes()?.musicServer == true)
    internal suspend fun scGateAccess(address: String) = reachAccess(address, tunnel?.routes()?.scGate == true)
    suspend fun disconnectServerFiles() {
        requireNotNull(serverFiles) { "Server file editing is unavailable" }.disconnect()
        context.operations.withLock { loader.refresh() }
    }
    fun uploadTrack(trackId: String, relativePath: String) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try { requireNotNull(serverFiles) { "Server uploads are unavailable" }.upload(trackId, relativePath) }
        finally { context.operations.withLock { loader.refresh() } }
    }
    fun retryServerUpload(id: String) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try { requireNotNull(serverFiles) { "Server uploads are unavailable" }.retryUpload(id) }
        finally { context.operations.withLock { loader.refresh() } }
    }
    fun discardServerUpload(id: String) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try { requireNotNull(serverFiles) { "Server uploads are unavailable" }.discardUpload(id) }
        finally { context.operations.withLock { loader.refresh() } }
    }

    fun connectServer(address: String, username: String, password: String) = serverRefreshAction {
        val connection = requireNotNull(navidrome) { "Server setup is unavailable" }
        musicServerAccess(address)
        connection.connect(address, ServerCredentials(username, password))
        val client = requireNotNull(connection.client())
        importServer(client)
    }

    fun refreshServer() = serverRefreshAction {
        navidrome?.profile()?.let { musicServerAccess(it.baseUrl) }
        val client = requireNotNull(navidrome?.client()) { "Connect to the music server first" }
        val count = importServer(client)
        offline?.retry()
        count
    }

    fun cancelServerRefresh() { serverRefreshJob.value?.cancel() }

    fun dismissServerRefresh() {
        context.update { if (it.serverRefresh?.running == true) it else it.copy(serverRefresh = null) }
    }

    private fun serverRefreshAction(block: suspend () -> Int) = context.action(serialized = false) {
        if (!serverImports.tryLock()) return@action
        serverRefreshJob.value = currentCoroutineContext()[Job]
        context.update { it.copy(loading = true, error = null, serverRefresh = ServerRefreshProgress(ServerRefreshStage.CONNECTING)) }
        var result = ServerRefreshProgress(ServerRefreshStage.CANCELLED)
        try {
            val count = block()
            context.operations.withLock { loader.refresh() }
            result = ServerRefreshProgress(ServerRefreshStage.COMPLETE, processed = count)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val partial = when (context.state.value.serverRefresh?.stage) {
                ServerRefreshStage.PLAYLISTS -> "Songs are saved. Playlists could not finish. "
                ServerRefreshStage.ARTWORK -> "Songs are saved. Artwork could not finish. "
                ServerRefreshStage.MATCHING -> "Songs are saved. Matching could not finish. "
                else -> ""
            }
            result = ServerRefreshProgress(ServerRefreshStage.FAILED, message = partial + (error.message ?: "Refresh failed. Try again later."))
        } finally {
            context.update { it.copy(loading = false, serverRefresh = result) }
            serverRefreshJob.value = null
            serverImports.unlock()
            // Cancellation must not wait for an unrelated operation holding the UI lock.
            if (result.stage != ServerRefreshStage.COMPLETE) context.action { loader.refresh() }
        }
    }

    fun disconnectServer() = context.action(serialized = false) {
        context.update { it.copy(loading = true, error = null, serverRefresh = null) }
        val connection = requireNotNull(navidrome) { "Server setup is unavailable" }
        val profile = connection.profile() ?: return@action context.operations.withLock { loader.refresh() }
        if (serverFiles?.configuration()?.serverId == profile.serverId) serverFiles.disconnect()
        library.setSourceAvailability(library.tracks().flatMap { track -> track.sources.filter {
            it.kind == SourceKind.SERVER && it.id.startsWith("server:${profile.serverId}:")
        }.map { it.id } }.toSet(), false)
        connection.disconnect()
        context.operations.withLock {
            if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(queue.snapshot()))
            loader.refresh()
        }
    }

    private suspend fun importServer(client: NavidromeClient): Int {
        val progress: (ServerRefreshProgress) -> Unit = { value -> context.update { it.copy(serverRefresh = value) } }
        val paths = serverFiles?.configuration()?.serverId == client.serverId
        val catalogue = serverChanges?.refreshLibrary(client, includeFilePaths = paths, onProgress = progress)
            ?: client.songs(includeFilePaths = paths, onProgress = progress).let { songs ->
                progress(ServerRefreshProgress(ServerRefreshStage.IMPORTING, total = songs.size))
                songs.mapIndexed { index, song ->
                    library.importTrack(song.metadata, TrackSource("server:${client.serverId}:${song.id}", SourceKind.SERVER, song.id,
                        filePath = song.filePath, fileLibraryId = song.fileLibraryId, indexedSize = song.sizeBytes)).also {
                        progress(ServerRefreshProgress(ServerRefreshStage.IMPORTING, index + 1, songs.size))
                    } to song
                }
            }
        if (artwork != null) {
            progress(ServerRefreshProgress(ServerRefreshStage.ARTWORK))
            // Songs of an album share one cover; download it once instead of once per song.
            val covers = catalogue.mapNotNull { (track, song) -> song.coverArt?.let { it to track.id } }.groupBy({ it.first }, { it.second })
            // Navidrome changes a cover's ID with its image; other Subsonic servers may keep it, so their covers are downloaded every time.
            val versioned = try { client.isNavidrome() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: NavidromeRequestException) { if (error.serverUnavailable) throw error else false }
                catch (_: Exception) { false }
            // Songs without a cover come first, so they show one long before a large library finishes refreshing.
            val present = artwork.paths(covers.values.flatten()).keys
            progress(ServerRefreshProgress(ServerRefreshStage.ARTWORK, total = covers.size))
            val pending = Channel<Pair<String, List<String>>>(Channel.UNLIMITED)
            covers.entries.sortedBy { (_, trackIds) -> trackIds.all { it in present } }.forEach { pending.trySend(it.key to it.value) }
            pending.close()
            coroutineScope { repeat(CONCURRENT_COVERS) { launch {
                for ((coverId, trackIds) in pending) {
                    try { if (versioned) artwork.fetchChanged(trackIds, client, coverId) else artwork.fetch(trackIds, client, coverId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: NavidromeRequestException) { if (error.serverUnavailable) throw error }
                    catch (_: Exception) { /* A missing cover must not prevent importing its music. */ }
                    context.update { state -> state.copy(serverRefresh = state.serverRefresh?.let { it.copy(processed = it.processed + 1) }) }
                }
            } } }
        }
        progress(ServerRefreshProgress(ServerRefreshStage.MATCHING))
        libraryController.linkSameFiles()
        return catalogue.size
    }
}
