package me.d3sox.nevumi.app

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.history.ScrobblingStatus
import me.d3sox.nevumi.history.LastFmAuthorization
import me.d3sox.nevumi.library.MusicFileEdits
import me.d3sox.nevumi.library.LocalFileEdit
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TagChanges
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.queue.SavedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerProfile
import me.d3sox.nevumi.library.LocalSources
import me.d3sox.nevumi.queue.QueueAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.downloads.DownloadRecord
import me.d3sox.nevumi.downloads.DownloadNetwork
import me.d3sox.nevumi.downloads.DownloadBatch
import me.d3sox.nevumi.downloads.PlaylistDownloadProgress
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.library.FileAudioAccess
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.sftp.*
import me.d3sox.nevumi.soundcloud.*
import me.d3sox.nevumi.navidrome.ServerRefreshProgress
import org.jetbrains.compose.resources.StringResource

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
    val serverRefresh: ServerRefreshProgress? = null,
    val lastLocalScanCount: Int? = null,
    val sleepTimerEndsAt: Long? = null,
    val soundCloud: SoundCloudProfile? = null,
    val soundCloudJobs: List<SoundCloudJob> = emptyList(),
) {
    /** Every song in any playlist; rows ask for membership, and scanning each playlist per row does not scale. */
    val playlistTrackIds: Set<String> by lazy { playlists.flatMapTo(HashSet()) { it.trackIds } }
}

data class PlaybackSeek(val entryId: String, val positionMs: Long)
data class PlaybackConfirmation(val title: String, val play: () -> kotlinx.coroutines.Job)
enum class ActionDestination { QUEUE, FAVORITES, PLAYLISTS, DOWNLOADS }
/** A short confirmation of an action; [message] is formatted with [arguments] where it is shown. */
data class ActionNotice(val message: StringResource, val destination: ActionDestination? = null, val undo: (() -> kotlinx.coroutines.Job)? = null,
    val isRedo: Boolean = false, val arguments: List<Any> = emptyList(), val play: (() -> kotlinx.coroutines.Job)? = null)
enum class FileEditTarget { LOCAL, SERVER }

/**
 * The app's entry point to its music: builds the domain controllers around one shared state and forwards every call to the
 * controller that owns it, so the UI keeps a single object to talk to.
 */
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
    val tunnel: me.d3sox.nevumi.tunnel.Tunnel? = null,
    private val networkAccess: suspend (String) -> Unit = {},
    private val soundCloud: SoundCloudDownloads? = null,
    discovery: me.d3sox.nevumi.navidrome.ServerDiscovery? = null,
    backup: me.d3sox.nevumi.backup.MusicBackup? = null,
    lyrics: me.d3sox.nevumi.lyrics.LyricsLibrary? = null) : AutoCloseable {
    private val context = ControllerContext()
    private val loader = AppStateLoader(context, library, queue, preferences, navidrome, playlists, fileEdits, serverFiles, listeningHistory, artwork)
    val playbackController = PlaybackController(context, library, queue, engine, navidrome, sourceAccessible, preferences, offline,
        listeningHistory, scrobbling, pairedDevices)
    val fileEditController = FileEditController(context, loader, fileEdits, serverFiles, preferences)
    val libraryController = LibraryController(context, loader, playbackController, fileEditController, library, queue, engine, localSources,
        offline, preferences, artwork, serverChanges, fileEdits, listeningHistory)
    val queueController = QueueController(context, playbackController, library, queue, engine, playlists, pairedDevices)
    val serverController = ServerController(context, loader, playbackController, libraryController, library, queue, engine, navidrome,
        serverFiles, serverChanges, artwork, offline, tunnel, networkAccess)
    val historyController = HistoryController(context, listeningHistory, scrobbling)
    val playlistController = PlaylistController(context, serverController, library, playlists, artwork, navidrome, serverChanges, offline)
    val downloadController = DownloadController(context, loader, playbackController, library, queue, engine, offline)
    val soundCloudController = SoundCloudController(context, serverController, libraryController, queueController, soundCloud)
    val discoveryController = DiscoveryController(context, queueController, discovery)
    val backupController = BackupController(context, loader, playbackController, fileEditController, queue, engine, backup)
    val lyricsController = LyricsController(context, lyrics)
    private val shutdown = Mutex()
    private var shutdownFinished = false
    val state = context.state
    val seeks = context.seeks
    val notices = context.notices
    val fileEditingAvailable: Boolean get() = fileEditController.fileEditingAvailable
    val localFileEditingAvailable: Boolean get() = fileEditController.localFileEditingAvailable
    val serverPlaylistPublishingAvailable: Boolean get() = playlistController.serverPlaylistPublishingAvailable
    val serverFilesAvailable: Boolean get() = serverController.serverFilesAvailable
    val scrobblingAvailable: Boolean get() = historyController.scrobblingAvailable
    val soundCloudAvailable: Boolean get() = soundCloudController.soundCloudAvailable
    val soundCloudDeviceDownloads: Boolean get() = soundCloudController.soundCloudDeviceDownloads

    init {
        context.action(serialized = false) {
            context.operations.withLock { loader.refreshLibrary() }
            var recoveryError: String? = null
            suspend fun recover(block: suspend () -> Unit) {
                try { block() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { if (recoveryError == null) recoveryError = error.message ?: "File recovery failed" }
            }
            recover { fileEditController.recover(FileEditTarget.LOCAL) }
            recover { fileEditController.recover(FileEditTarget.SERVER) }
            recover { fileEditController.trimFileHistory() }
            recover { pairedDevices?.start() }
            recover { historyController.loadPendingLastFmAuthorization() }
            context.operations.withLock {
                playbackController.applyPreferences()
                playbackController.loadPersistedQueue()
                loader.refreshLibrary()
            }
            loader.refreshDetails(loading = false)
            context.update { it.copy(loading = it.serverRefresh?.running == true, error = recoveryError ?: it.error) }
        }
    }

    // Library
    suspend fun refresh(loading: Boolean = false) = libraryController.refresh(loading)
    fun reload() = libraryController.reload()
    fun linkTracks(keepId: String, otherId: String) = libraryController.linkTracks(keepId, otherId)
    suspend fun linkSameFiles() = libraryController.linkSameFiles()
    fun updatePreferences(change: (MusicSettings) -> MusicSettings) = libraryController.updatePreferences(change)
    fun rememberLibraryTab(tab: me.d3sox.nevumi.library.LibraryTab) = libraryController.rememberLibraryTab(tab)
    fun rememberLibraryPosition(key: String, position: me.d3sox.nevumi.settings.LibraryPosition) = libraryController.rememberLibraryPosition(key, position)
    fun setFavorite(trackId: String, favorite: Boolean) = libraryController.setFavorite(trackId, favorite)
    fun setFavorite(trackIds: List<String>, favorite: Boolean) = libraryController.setFavorite(trackIds, favorite)
    fun setRating(trackId: String, rating: Int) = libraryController.setRating(trackId, rating)
    fun clearRatings() = libraryController.clearRatings()
    fun rescanLocalMusic() = libraryController.rescanLocalMusic()
    fun updateLocalFolders(included: List<String>, excluded: List<String>) = libraryController.updateLocalFolders(included, excluded)
    fun importSource(location: String) = libraryController.importSource(location)
    fun dismissError(error: String) = context.dismissError(error)

    // File editing
    fun canEditServerTrack(track: Track) = fileEditController.canEditServerTrack(track)
    fun prepareServerFileEdit(track: Track) = fileEditController.prepareServerFileEdit(track)
    suspend fun musicFileName(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL) = fileEditController.musicFileName(trackId, target)
    suspend fun fileTags(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL): TrackMetadata = fileEditController.fileTags(trackId, target)
    fun editTags(trackId: String, metadata: TrackMetadata, target: FileEditTarget = FileEditTarget.LOCAL) = fileEditController.editTags(trackId, metadata, target)
    fun editTags(trackIds: List<String>, changes: TagChanges, target: FileEditTarget = FileEditTarget.LOCAL) = fileEditController.editTags(trackIds, changes, target)
    fun undoFileEdit(editId: String) = fileEditController.undoFileEdit(editId)
    fun replaceArtwork(trackId: String, bytes: ByteArray, target: FileEditTarget = FileEditTarget.LOCAL) = fileEditController.replaceArtwork(trackId, bytes, target)
    fun renameFile(trackId: String, name: String, target: FileEditTarget = FileEditTarget.LOCAL) = fileEditController.renameFile(trackId, name, target)
    fun deleteFile(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL) = fileEditController.deleteFile(trackId, target)
    fun restoreOriginalFile(editId: String) = fileEditController.restoreOriginalFile(editId)
    fun applyFileEdit(editId: String) = fileEditController.applyFileEdit(editId)
    fun discardFileEdit(editId: String) = fileEditController.discardFileEdit(editId)

    // Server, server files and the tunnel
    suspend fun serverMusicDirectories() = serverController.serverMusicDirectories()
    suspend fun connectServerFiles(profile: SftpProfile, libraryId: Long, authentication: SftpAuthentication) = serverController.connectServerFiles(profile, libraryId, authentication)
    suspend fun disconnectServerFiles() = serverController.disconnectServerFiles()
    suspend fun tailscaleStatus() = serverController.tailscaleStatus()
    suspend fun signInToTailscale(controlUrl: String, authKey: String?): me.d3sox.nevumi.tunnel.TailscaleStatus = serverController.signInToTailscale(controlUrl, authKey)
    suspend fun signOutOfTailscale() = serverController.signOutOfTailscale()
    suspend fun tunnelRoutes() = serverController.tunnelRoutes()
    suspend fun updateTunnelRoutes(routes: me.d3sox.nevumi.tunnel.TunnelRoutes) = serverController.updateTunnelRoutes(routes)
    fun uploadTrack(trackId: String, relativePath: String) = serverController.uploadTrack(trackId, relativePath)
    fun retryServerUpload(id: String) = serverController.retryServerUpload(id)
    fun discardServerUpload(id: String) = serverController.discardServerUpload(id)
    fun connectServer(address: String, username: String, password: String) = serverController.connectServer(address, username, password)
    fun refreshServer() = serverController.refreshServer()
    fun cancelServerRefresh() = serverController.cancelServerRefresh()
    fun dismissServerRefresh() = serverController.dismissServerRefresh()
    fun disconnectServer() = serverController.disconnectServer()

    // SoundCloud
    suspend fun testSoundCloud(address: String): ScGateCapabilities = soundCloudController.testSoundCloud(address)
    suspend fun connectSoundCloud(address: String, format: ScGateFormat, destination: SoundCloudDestination, browser: ScGateBrowser? = null,
        reviewTags: Boolean = false): SoundCloudProfile = soundCloudController.connectSoundCloud(address, format, destination, browser, reviewTags)
    fun updateSoundCloud(format: ScGateFormat, destination: SoundCloudDestination, browser: ScGateBrowser?, reviewTags: Boolean) =
        soundCloudController.updateSoundCloud(format, destination, browser, reviewTags)
    suspend fun disconnectSoundCloud() = soundCloudController.disconnectSoundCloud()
    suspend fun searchSoundCloud(query: String, offset: Int = 0): SoundCloudPage = soundCloudController.searchSoundCloud(query, offset)
    suspend fun soundCloudArtwork(url: String): ByteArray = soundCloudController.soundCloudArtwork(url)
    fun downloadFromSoundCloud(track: SoundCloudTrack, destination: ScGateDestination, keepOffline: Boolean = false, reviewTags: Boolean? = null) =
        soundCloudController.downloadFromSoundCloud(track, destination, keepOffline, reviewTags)
    fun submitSoundCloudLink(id: String, link: String) = soundCloudController.submitSoundCloudLink(id, link)
    fun useSoundCloudStream(id: String) = soundCloudController.useSoundCloudStream(id)
    fun chooseSoundCloudTrack(id: String, url: String) = soundCloudController.chooseSoundCloudTrack(id, url)
    suspend fun soundCloudCover(id: String, fromFile: Boolean): ByteArray = soundCloudController.soundCloudCover(id, fromFile)
    suspend fun finishSoundCloudTags(id: String, choice: ScGateTagChoice) = soundCloudController.finishSoundCloudTags(id, choice)
    suspend fun soundCloudBrowserView(id: String): String = soundCloudController.soundCloudBrowserView(id)
    fun cancelSoundCloudDownload(id: String) = soundCloudController.cancelSoundCloudDownload(id)
    fun retrySoundCloudDownload(id: String) = soundCloudController.retrySoundCloudDownload(id)
    fun dismissSoundCloudDownload(id: String) = soundCloudController.dismissSoundCloudDownload(id)
    fun addSoundCloudFolder(id: String) = soundCloudController.addSoundCloudFolder(id)

    // Listening history and scrobbling
    fun clearListeningHistory() = historyController.clearListeningHistory()
    suspend fun connectListenBrainz(token: String, address: String = "https://api.listenbrainz.org") = historyController.connectListenBrainz(token, address)
    suspend fun startLastFmSignIn(apiKey: String, sharedSecret: String): LastFmAuthorization = historyController.startLastFmSignIn(apiKey, sharedSecret)
    suspend fun completeLastFmSignIn() = historyController.completeLastFmSignIn()
    suspend fun cancelLastFmSignIn() = historyController.cancelLastFmSignIn()
    fun setScrobblingEnabled(id: String, enabled: Boolean) = historyController.setScrobblingEnabled(id, enabled)
    fun retryScrobbleSubmission(id: String) = historyController.retryScrobbleSubmission(id)
    fun discardScrobbleSubmission(id: String) = historyController.discardScrobbleSubmission(id)
    fun disconnectScrobbling(id: String) = historyController.disconnectScrobbling(id)

    // Downloads
    fun downloadTracks(trackIds: List<String>) = downloadController.downloadTracks(trackIds)
    fun removeDownload(trackId: String) = downloadController.removeDownload(trackId)
    fun setDownloadsPaused(paused: Boolean) = downloadController.setDownloadsPaused(paused)
    fun retryDownloads() = downloadController.retryDownloads()

    // Playlists
    fun createPlaylist(name: String, trackIds: List<String> = emptyList()) = playlistController.createPlaylist(name, trackIds)
    suspend fun createQueuePlaylist(name: String, snapshot: QueueSnapshot, selection: me.d3sox.nevumi.queue.QueueExportScope) = playlistController.createQueuePlaylist(name, snapshot, selection)
    fun publishPlaylist(id: String) = playlistController.publishPlaylist(id)
    suspend fun importM3u(document: me.d3sox.nevumi.ui.PlaylistDocument) = playlistController.importM3u(document)
    suspend fun exportM3u(id: String) = playlistController.exportM3u(id)
    suspend fun exportQueueM3u(snapshot: QueueSnapshot, selection: me.d3sox.nevumi.queue.QueueExportScope) = playlistController.exportQueueM3u(snapshot, selection)
    fun createSmartPlaylist(name: String, rule: me.d3sox.nevumi.library.LibraryQuery) = playlistController.createSmartPlaylist(name, rule)
    fun updatePlaylistRule(id: String, rule: me.d3sox.nevumi.library.LibraryQuery) = playlistController.updatePlaylistRule(id, rule)
    fun addToPlaylist(playlistId: String, trackId: String) = playlistController.addToPlaylist(playlistId, trackId)
    fun addToPlaylist(playlistId: String, trackIds: List<String>) = playlistController.addToPlaylist(playlistId, trackIds)
    fun managePlaylistMembership(trackIds: List<String>, choices: Map<String, Boolean>) = playlistController.managePlaylistMembership(trackIds, choices)
    fun setPlaylistShuffle(playlistId: String, enabled: Boolean) = playlistController.setPlaylistShuffle(playlistId, enabled)
    fun setPlaylistSort(playlistId: String, sort: me.d3sox.nevumi.playlists.PlaylistSort, descending: Boolean) = playlistController.setPlaylistSort(playlistId, sort, descending)
    fun editPlaylist(playlistId: String, name: String, description: String, cover: ByteArray? = null, removeCover: Boolean = false) =
        playlistController.editPlaylist(playlistId, name, description, cover, removeCover)
    fun renamePlaylist(playlistId: String, name: String) = playlistController.renamePlaylist(playlistId, name)
    fun removePlaylistTrack(playlistId: String, index: Int) = playlistController.removePlaylistTrack(playlistId, index)
    fun movePlaylistTrack(playlistId: String, from: Int, to: Int) = playlistController.movePlaylistTrack(playlistId, from, to)
    fun deletePlaylist(playlistId: String) = playlistController.deletePlaylist(playlistId)
    fun resolvePlaylistConflict(playlistId: String, keepLocal: Boolean) = playlistController.resolvePlaylistConflict(playlistId, keepLocal)
    fun setPlaylistDownload(playlistId: String, enabled: Boolean) = playlistController.setPlaylistDownload(playlistId, enabled)

    // Playback
    fun play() = playbackController.play()
    fun pause() = playbackController.pause()
    fun togglePlayback() = playbackController.togglePlayback()
    fun stop() = playbackController.stop()
    fun seek(positionMs: Long) = playbackController.seek(positionMs)
    fun seekCurrentEntry(entryId: String, positionMs: Long) = playbackController.seekCurrentEntry(entryId, positionMs)
    fun skip(direction: Int, restartPrevious: Boolean = true) = playbackController.skip(direction, restartPrevious)
    fun setVolume(volume: Double) = playbackController.setVolume(volume)
    fun setSpeed(speed: Double) = playbackController.setSpeed(speed)
    fun setPitch(pitch: Double) = playbackController.setPitch(pitch)
    fun setSleepTimer(minutes: Int?) = playbackController.setSleepTimer(minutes)
    fun transferPlayback(deviceId: String): kotlinx.coroutines.Job = playbackController.transferPlayback(deviceId)

    // Queue
    fun playTrack(trackId: String, contextTrackIds: List<String>? = null, contextIndex: Int? = null, contextName: String = "Library", shuffleContext: Boolean = false,
        origin: me.d3sox.nevumi.queue.QueueOrigin = me.d3sox.nevumi.queue.QueueOrigin(), preserveManualQueue: Boolean = false,
        confirmed: Boolean = false): kotlinx.coroutines.Job =
        queueController.playTrack(trackId, contextTrackIds, contextIndex, contextName, shuffleContext, origin, preserveManualQueue, confirmed)
    fun selectQueueEntry(entryId: String) = queueController.selectQueueEntry(entryId)
    fun saveQueue(name: String, kind: me.d3sox.nevumi.queue.SavedQueueKind = me.d3sox.nevumi.queue.SavedQueueKind.LIVE) = queueController.saveQueue(name, kind)
    fun overwriteSavedQueue(id: String) = queueController.overwriteSavedQueue(id)
    fun detachSavedQueue() = queueController.detachSavedQueue()
    fun startEmptyQueue() = queueController.startEmptyQueue()
    fun setQueueShuffled(enabled: Boolean) = queueController.setQueueShuffled(enabled)
    fun setQueueRepeat(mode: RepeatMode, playCount: Int? = null) = queueController.setQueueRepeat(mode, playCount)
    fun shuffleQueueAlbums() = queueController.shuffleQueueAlbums()
    fun cancelPlaybackConfirmation() = queueController.cancelPlaybackConfirmation()
    fun acceptPlaybackConfirmation() = queueController.acceptPlaybackConfirmation()
    fun loadSavedQueue(id: String, confirmed: Boolean = false): kotlinx.coroutines.Job = queueController.loadSavedQueue(id, confirmed)
    fun removeSavedQueue(id: String) = queueController.removeSavedQueue(id)
    fun renameSavedQueue(id: String, name: String) = queueController.renameSavedQueue(id, name)
    fun duplicateSavedQueue(id: String, name: String) = queueController.duplicateSavedQueue(id, name)
    fun convertSavedQueue(id: String, kind: me.d3sox.nevumi.queue.SavedQueueKind) = queueController.convertSavedQueue(id, kind)
    fun removeQueueEntry(entryId: String) = queueController.removeQueueEntry(entryId)
    fun moveQueueEntry(entryId: String, destination: Int, section: me.d3sox.nevumi.queue.QueueSection? = null) = queueController.moveQueueEntry(entryId, destination, section)
    fun clearQueue(selection: me.d3sox.nevumi.queue.QueueClear = me.d3sox.nevumi.queue.QueueClear.ALL) = queueController.clearQueue(selection)
    fun moveQueueEntryToSection(entryId: String, section: me.d3sox.nevumi.queue.QueueSection) = queueController.moveQueueEntryToSection(entryId, section)
    fun enqueue(trackId: String, operation: QueueAction) = queueController.enqueue(trackId, operation)
    fun enqueue(trackIds: List<String>, operation: QueueAction) = queueController.enqueue(trackIds, operation)

    override fun close() { context.scope.cancel(); pairedDevices?.close(); serverChanges?.close(); offline?.close(); scrobbling?.close(); soundCloud?.close(); engine.close() }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        shutdown.withLock {
            if (shutdownFinished) return@withLock
            try {
                if (context.supervisor.isActive) engine.pause()
                context.scope.cancel()
                pairedDevices?.closeAndJoin()
                serverChanges?.closeAndJoin()
                offline?.closeAndJoin()
                scrobbling?.closeAndJoin()
                soundCloud?.closeAndJoin()
                context.supervisor.join()
                val playback = engine.status.value
                listeningHistory?.record(playback.copy(playing = false), enabled = state.value.preferences.recordListeningHistory)
                listeningHistory?.let { history -> context.update { it.copy(listens = history.all()) } }
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
