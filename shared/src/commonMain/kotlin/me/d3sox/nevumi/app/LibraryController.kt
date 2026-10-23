package me.d3sox.nevumi.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.LocalSources
import me.d3sox.nevumi.library.MusicFileEdits
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_added_to_favorites
import me.d3sox.nevumi.resources.notice_rated_one_star
import me.d3sox.nevumi.resources.notice_rated_stars
import me.d3sox.nevumi.resources.notice_rating_cleared
import me.d3sox.nevumi.resources.notice_ratings_cleared
import me.d3sox.nevumi.resources.notice_removed_from_favorites
import me.d3sox.nevumi.settings.LibraryPosition
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings

/** Keeps the library in the app state: local music scans, linked songs, favorites, ratings, covers and the preferences. */
@OptIn(FlowPreview::class)
class LibraryController internal constructor(private val context: ControllerContext, private val loader: AppStateLoader,
    private val playback: PlaybackController, private val fileEditing: FileEditController, private val library: MusicLibrary,
    private val queue: PlaybackQueue, private val engine: PlaybackEngine, private val localSources: LocalSources?,
    private val offline: OfflineMusic?, private val preferences: MusicPreferences?, private val artwork: ArtworkLibrary?,
    private val serverChanges: NavidromeChanges?, private val fileEdits: MusicFileEdits?, private val listeningHistory: ListeningHistory?) {
    private val imports = Mutex()

    init {
        artwork?.let { covers -> context.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val updates = Channel<Pair<String, String?>>(Channel.UNLIMITED)
            launch(start = CoroutineStart.UNDISPATCHED) { covers.changes.collect { updates.send(it) } }
            for (first in updates) {
                // A scan saves thousands of covers in a row; publish the ones that arrived meanwhile in one state change.
                val batch = linkedMapOf(first)
                while (true) batch += updates.tryReceive().getOrNull() ?: break
                loader.artworkUpdates.withLock {
                    context.update { state -> state.copy(artwork = state.artwork.toMutableMap().apply {
                        for ((id, path) in batch) if (path == null) remove(id) else put(id, path)
                    }) }
                }
            }
        } }
        context.scope.launch { library.observe().collect {
            context.operations.withLock {
                val tracks = library.tracks()
                // An equal list keeps its identity, so the screens do not derive their contents again.
                context.update { if (it.tracks == tracks) it else it.copy(tracks = tracks) }
            }
        } }
        localSources?.let { sources -> context.scope.launch {
            sources.pendingRefreshLocations().forEach { importSource(it).join() }
        } }
        localSources?.let { sources -> context.scope.launch {
            sources.changes.debounce(300).collect { sources.roots().forEach { importSource(it).join() } }
        } }
    }

    suspend fun refresh(loading: Boolean = false) = loader.refresh(loading)
    fun reload() = context.action { loader.refresh() }
    fun linkTracks(keepId: String, otherId: String) = context.action {
        context.update { it.copy(error = null) }
        suspend fun link() {
            val linked = mergeTracks(keepId, otherId)
            serverChanges?.setFavorite(linked.id, linked.favorite)
            serverChanges?.setRating(linked.id, linked.rating)
            if (playback.queueLoaded) engine.updateQueue(playback.playableQueue(queue.snapshot()))
            loader.refresh()
        }
        if (offline != null) offline.withLibraryChanges { link() } else link()
    }
    private suspend fun mergeTracks(keepId: String, otherId: String): Track {
        val cover = if (artwork?.path(keepId) == null) artwork?.read(otherId) else null
        if (cover != null) artwork?.save(keepId, cover)
        val linked = fileEdits?.linkTracks(keepId, otherId) ?: library.linkTracks(keepId, otherId)
        if (context.state.value.playback.trackId == otherId || context.state.value.playback.trackId == keepId) offline?.useTrack(keepId)
        return linked
    }
    // Local files that were also uploaded to the server become one song, so they are not downloaded a second time.
    suspend fun linkSameFiles() {
        val pairs = library.sameFileCopies()
        if (pairs.isEmpty()) return
        // One pair per lock keeps playback responsive during the first pass over a large library.
        for ((serverId, localId) in pairs) context.operations.withLock {
            suspend fun link() {
                val server = library.findTrack(serverId) ?: return
                if (library.findTrack(localId) == null) return
                val queued = playback.queueLoaded && queue.snapshot().entries.any { it.trackId == serverId || it.trackId == localId }
                // The local song keeps its identity, so references to the file stay valid.
                val linked = try { mergeTracks(localId, serverId) }
                    // A song with a running download or file edit is linked on a later pass.
                    catch (_: IllegalStateException) { return }
                if (linked.favorite != server.favorite) serverChanges?.setFavorite(linked.id, linked.favorite)
                if (linked.rating != server.rating) serverChanges?.setRating(linked.id, linked.rating)
                if (queued) engine.updateQueue(playback.playableQueue(queue.snapshot()))
            }
            if (offline != null) offline.withLibraryChanges { link() } else link()
        }
        context.operations.withLock { loader.refresh() }
    }
    fun updatePreferences(change: (MusicSettings) -> MusicSettings) = context.action {
        val previous = context.state.value.preferences
        val settings = requireNotNull(preferences) { "Settings are unavailable" }.update { current ->
            change(current).let { if (it.rememberScrollPositions) it else it.copy(libraryPositions = emptyMap()) }
        }
        if (settings.recordListeningHistory != previous.recordListeningHistory)
            listeningHistory?.record(engine.status.value, enabled = settings.recordListeningHistory)
        context.update { it.copy(preferences = settings) }
        if (settings.audioEffects != previous.audioEffects) engine.setAudioEffects(settings.audioEffects)
        if (settings.crossfadeDurationMs != previous.crossfadeDurationMs) engine.setCrossfadeDuration(settings.crossfadeDurationMs)
        if (settings.gaplessPlayback != previous.gaplessPlayback) engine.setGaplessPlayback(settings.gaplessPlayback)
        if (playback.queueLoaded && settings.sourcePriority != previous.sourcePriority)
            engine.updateQueue(playback.playableQueue(queue.snapshot()))
        if (settings.undoRetentionDays != previous.undoRetentionDays || settings.undoBackupBytes != previous.undoBackupBytes) {
            fileEditing.trimFileHistory()
            loader.refresh()
        }
    }
    fun rememberLibraryTab(tab: LibraryTab) = context.action(serialized = false) {
        if (context.state.value.preferences.lastLibraryTab != tab) {
            val settings = preferences?.update { it.copy(lastLibraryTab = tab) }
                ?: context.state.value.preferences.copy(lastLibraryTab = tab)
            context.update { it.copy(preferences = settings) }
        }
    }
    fun rememberLibraryPosition(key: String, position: LibraryPosition) = context.action(serialized = false) {
        if (context.state.value.preferences.rememberScrollPositions && context.state.value.preferences.libraryPositions[key] != position) {
            val settings = preferences?.update { if (it.rememberScrollPositions) it.copy(libraryPositions = it.libraryPositions + (key to position)) else it }
            context.update { it.copy(preferences = it.preferences.copy(libraryPositions =
                if (!it.preferences.rememberScrollPositions) emptyMap() else settings?.libraryPositions ?: (it.preferences.libraryPositions + (key to position)))) }
        }
    }
    fun setFavorite(trackId: String, favorite: Boolean) = setFavorite(listOf(trackId), favorite)
    fun setFavorite(trackIds: List<String>, favorite: Boolean) = context.action {
        val before = trackIds.distinct().associateWith { requireNotNull(library.findTrack(it)) { "Track is missing" }.favorite }
        applyFavorites(before.mapValues { favorite })
        context.confirm(if (favorite) Res.string.notice_added_to_favorites else Res.string.notice_removed_from_favorites, ActionDestination.FAVORITES,
            undo = { applyFavorites(before) }, redo = { applyFavorites(before.mapValues { favorite }) })
    }
    private suspend fun applyFavorites(values: Map<String, Boolean>) {
        if (serverChanges != null) serverChanges.setFavorites(values) else library.setFavorites(values)
        context.update { state -> state.copy(tracks = state.tracks.map { track ->
            values[track.id]?.let { track.copy(favorite = it) } ?: track
        }) }
    }
    fun setRating(trackId: String, rating: Int) = context.action {
        val before = requireNotNull(library.findTrack(trackId)) { "Track is missing" }.rating
        if (serverChanges != null) serverChanges.setRating(trackId, rating) else library.setRating(trackId, rating)
        updateTrackFeedback(setOf(trackId)) { it.copy(rating = rating) }
        context.confirm(if (rating == 0) Res.string.notice_rating_cleared else if (rating == 1) Res.string.notice_rated_one_star else Res.string.notice_rated_stars,
            arguments = listOf(rating), undo = {
            if (serverChanges != null) serverChanges.setRating(trackId, before) else library.setRating(trackId, before)
            updateTrackFeedback(setOf(trackId)) { it.copy(rating = before) }
        }, redo = {
            if (serverChanges != null) serverChanges.setRating(trackId, rating) else library.setRating(trackId, rating)
            updateTrackFeedback(setOf(trackId)) { it.copy(rating = rating) }
        })
    }
    fun clearRatings() = context.action {
        val before = library.tracks().filter { it.rating != 0 }.associate { it.id to it.rating }
        if (before.isEmpty()) return@action
        val cleared = before.mapValues { 0 }
        applyRatings(cleared)
        context.confirm(Res.string.notice_ratings_cleared, undo = { applyRatings(before) }, redo = { applyRatings(cleared) })
    }
    private suspend fun applyRatings(values: Map<String, Int>) {
        values.forEach { (id, rating) ->
            if (serverChanges != null) serverChanges.setRating(id, rating) else library.setRating(id, rating)
        }
        context.update { state -> state.copy(tracks = state.tracks.map { track ->
            values[track.id]?.let { track.copy(rating = it) } ?: track
        }) }
    }
    private fun updateTrackFeedback(ids: Set<String>, change: (Track) -> Track) {
        context.update { state -> state.copy(tracks = state.tracks.map { if (it.id in ids) change(it) else it }) }
    }
    fun rescanLocalMusic() = context.action(serialized = false) {
        localSources?.roots()?.forEach { importSource(it).join() }
    }
    fun updateLocalFolders(included: List<String>, excluded: List<String>) = context.action(serialized = false) {
        requireNotNull(preferences) { "Local folder preferences are unavailable" }.update {
            it.copy(includeFolders = included.distinct(), excludeFolders = excluded.distinct())
        }
        localSources?.roots()?.forEach { importSource(it).join() }
        context.operations.withLock { loader.refresh() }
    }
    fun importSource(location: String) = context.action(serialized = false) {
        imports.withLock {
            context.update { it.copy(loading = true, error = null) }
            var lastProgress = 0L
            try {
                val songs = requireNotNull(localSources) { "Local music access is unavailable" }.scan(location, onProgress = { progress ->
                    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                    if (progress.processed == 0 || progress.processed == progress.total || now - lastProgress >= 100) {
                        lastProgress = now
                        context.update { it.copy(localScan = progress) }
                    }
                })
                linkSameFiles()
                context.operations.withLock { loader.refresh() }
                context.update { it.copy(lastLocalScanCount = songs.size) }
            } finally { context.update { it.copy(localScan = null) } }
        }
    }
}
