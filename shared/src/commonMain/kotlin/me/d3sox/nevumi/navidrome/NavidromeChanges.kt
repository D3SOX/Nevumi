package me.d3sox.nevumi.navidrome

import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.storage.MusicStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.PlaylistServer
import me.d3sox.nevumi.playlists.PlaylistConflict
import me.d3sox.nevumi.playlists.PlaylistRemoval

@Serializable
private data class FavoriteChange(val sourceId: String, val songId: String, val favorite: Boolean,
    val revision: String = Uuid.random().toString())

@Serializable
private data class RatingChange(val sourceId: String, val songId: String, val rating: Int,
    val revision: String = Uuid.random().toString())

class NavidromeChanges(private val store: MusicStore, private val library: MusicLibrary,
    private val artwork: ArtworkLibrary? = null, private val client: suspend () -> NavidromeClient?) : AutoCloseable {
    private val synchronization = Mutex()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val playlists = Playlists(store, library = library)
    private val mutableDiscardedEdits = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Names of playlists whose local edit gave way to the server's version because the server keeps them read-only. */
    val discardedEdits = mutableDiscardedEdits.asSharedFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        for (kind in listOf("favorite-changes", "rating-changes", "servers", "playlists", "playlist-removals")) scope.launch {
            store.database.records().observeRevision(kind).distinctUntilChanged().collect { wake.trySend(Unit) }
        }
        scope.launch {
            var pulled: TimeMark? = null
            while (isActive) {
                withTimeoutOrNull(30_000) { wake.receive() }
                try { synchronize() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain the journal and retry after reconnection. */ }
                if (pulled == null || pulled.elapsedNow() >= 5.minutes) {
                    pulled = TimeSource.Monotonic.markNow()
                    try { pullPlaylists() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Pull again on the next interval. */ }
                }
            }
        }
    }

    override fun close() { supervisor.cancel() }
    suspend fun closeAndJoin() { close(); supervisor.join() }

    suspend fun setFavorite(trackId: String, favorite: Boolean) = store.transaction {
        val track = requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        library.setFavorite(trackId, favorite)
        track.sources.filter { it.kind == SourceKind.SERVER }.forEach { source ->
            store.put("favorite-change:${source.id}", "favorite-changes",
                FavoriteChange(source.id, source.location, favorite))
        }
    }

    suspend fun setFavorites(values: Map<String, Boolean>) {
        for (batch in values.entries.chunked(100)) store.transaction {
            batch.forEach { (id, favorite) -> setFavorite(id, favorite) }
        }
    }

    suspend fun setRating(trackId: String, rating: Int) = store.transaction {
        val track = requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        library.setRating(trackId, rating)
        track.sources.filter { it.kind == SourceKind.SERVER }.forEach { source ->
            store.put("rating-change:${source.id}", "rating-changes", RatingChange(source.id, source.location, rating))
        }
    }

    suspend fun pendingCount(): Int = store.list<FavoriteChange>("favorite-changes").size + store.list<RatingChange>("rating-changes").size

    suspend fun publishPlaylist(id: String) {
        val connection = requireNotNull(client()) { "Connect to your music server before publishing a playlist" }
        store.transaction {
            val playlist = requireNotNull(playlists.get(id)) { "Playlist is missing" }
            require(playlist.server == null && playlist.rule == null) { "Publish a regular app playlist" }
            require(playlist.trackIds.all { trackId -> library.findTrack(trackId)?.sources?.any {
                it.kind == SourceKind.SERVER && it.id.startsWith("server:${connection.serverId}:")
            } == true }) { "Server playlists can only contain tracks from their server" }
            store.put("playlist:$id", "playlists", playlist.copy(
                server = PlaylistServer(connection.serverId, null, "Nevumi pending $id", serverSongIds(playlist, connection), connection.username),
                pendingSync = true, revision = Uuid.random().toString()))
        }
    }

    suspend fun refreshLibrary(connection: NavidromeClient, includeFilePaths: Boolean = false,
        onProgress: (ServerRefreshProgress) -> Unit = {}): List<Pair<Track, ServerSong>> {
        onProgress(ServerRefreshProgress(ServerRefreshStage.WAITING))
        return synchronization.withLock {
            val catalogue = connection.songs(includeFilePaths, onProgress)
            onProgress(ServerRefreshProgress(ServerRefreshStage.IMPORTING, total = catalogue.size))
            // Short transactions let playback checkpoints and user actions write between batches of a large import.
            var processed = 0
            val imported = catalogue.chunked(200).flatMap { batch ->
                store.transaction { batch.map { song -> importSong(connection, song) to song } }.also {
                    processed += batch.size
                    onProgress(ServerRefreshProgress(ServerRefreshStage.IMPORTING, processed, catalogue.size))
                }
            }
            val songs = store.transaction {
                val present = catalogue.map { "server:${connection.serverId}:${it.id}" }.toSet()
                val absent = library.tracks().flatMap { track -> track.sources.filter {
                    it.kind == SourceKind.SERVER && it.id.startsWith("server:${connection.serverId}:") && it.id !in present
                }.map { it.id } }.toSet()
                library.setSourceAvailability(absent, false)
                imported
            }
            importPlaylists(connection, onProgress = onProgress)
            songs
        }
    }

    /** Imports one changed song after any running catalogue refresh, so the refresh cannot overwrite it with older metadata. */
    suspend fun refreshSong(connection: NavidromeClient, song: ServerSong): Track = synchronization.withLock { importSong(connection, song) }

    suspend fun importSong(connection: NavidromeClient, song: ServerSong, linkTo: String? = null): Track = store.transaction {
        val sourceId = "server:${connection.serverId}:${song.id}"
        val owner = library.trackForSource(sourceId)
        val known = owner != null
        if (linkTo != null && owner != null && owner.id != linkTo) library.linkTracks(linkTo, owner.id)
        val track = library.importTrack(song.metadata, TrackSource(sourceId, SourceKind.SERVER, song.id, filePath = song.filePath,
            fileLibraryId = song.fileLibraryId, indexedSize = song.sizeBytes), linkTo = linkTo)
        if (store.read<FavoriteChange>("favorite-change:$sourceId") == null) {
            if ((!known || linkTo != null) && track.favorite && !song.favorite && track.sources.any { it.kind == SourceKind.LOCAL }) setFavorite(track.id, true)
            else library.setFavorite(track.id, song.favorite)
        }
        if (store.read<RatingChange>("rating-change:$sourceId") == null) {
            if ((!known || linkTo != null) && track.rating > 0 && track.rating != song.rating && track.sources.any { it.kind == SourceKind.LOCAL })
                setRating(track.id, track.rating)
            else library.setRating(track.id, song.rating)
        }
        requireNotNull(library.findTrack(track.id))
    }

    /** Imports playlists edited on the server since the last import, along with their covers. */
    suspend fun pullPlaylists() = synchronization.withLock { client()?.let { importPlaylists(it, changedOnly = true) } }

    /**
     * Imports the server's playlists. With [changedOnly], only playlists whose change time moved are downloaded,
     * and those containing songs missing from the library wait for the next library refresh.
     */
    suspend fun importPlaylists(connection: NavidromeClient, changedOnly: Boolean = false,
        onProgress: (ServerRefreshProgress) -> Unit = {}) {
        onProgress(ServerRefreshProgress(ServerRefreshStage.PLAYLISTS))
        val tracks = library.tracks()
        val mapping = tracks.flatMap { track -> track.sources.filter {
            it.kind == SourceKind.SERVER && it.id.startsWith("server:${connection.serverId}:")
        }.map { it.location to track.id } }.toMap()
        val remotes = if (!changedOnly) connection.playlists() else {
            val known = playlists.all().mapNotNull { it.server?.takeIf { link -> link.serverId == connection.serverId && link.id != null } }
                .associate { it.id to it.changed }
            // Servers that omit change times get every playlist downloaded again.
            connection.playlistVersions().filter { (id, changed) -> changed.isEmpty() || known[id] != changed }.keys.map { connection.playlist(it) }
        }
        onProgress(ServerRefreshProgress(ServerRefreshStage.PLAYLISTS, total = remotes.size))
        for ((index, remote) in remotes.withIndex()) {
            val imported = store.transaction {
                val id = store.list<Playlist>("playlists").firstOrNull {
                    it.server?.serverId == connection.serverId && (it.server.id == remote.id ||
                        (it.server.id == null && it.pendingSync && remote.owner == connection.username && remote.name == "Nevumi pending ${it.id}"))
                }?.id ?: "server-playlist:${connection.serverId}:${remote.id}"
                if (store.read<PlaylistRemoval>("playlist-removal:$id") != null) return@transaction null
                val previous = playlists.get(id)
                if (previous?.pendingSync == true) return@transaction null
                val ids = remote.songIds.map { mapping[it] ?: if (changedOnly) return@transaction null
                    else throw IllegalArgumentException("Refresh the server library before importing its playlists") }
                val link = PlaylistServer(connection.serverId, remote.id, remote.name, remote.songIds, remote.owner,
                    readOnly = remote.readOnly || remote.owner != connection.username, baselineDescription = remote.description, coverArt = remote.coverArt,
                    changed = remote.changed)
                fun time(value: String) = kotlin.time.Instant.parseOrNull(value)?.toEpochMilliseconds()
                val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                // Playlists saved before times were recorded take them from the server.
                val updatedAt = if (previous != null && previous.updatedAt != 0L && previous.name == remote.name && previous.trackIds == ids &&
                    previous.description == remote.description) previous.updatedAt else time(remote.changed) ?: now
                store.put("playlist:$id", "playlists", previous?.copy(name = remote.name, trackIds = ids, server = link, description = remote.description,
                    createdAt = previous.createdAt.takeIf { it != 0L } ?: time(remote.created) ?: now, updatedAt = updatedAt) ?: Playlist(id, remote.name, ids, server = link, description = remote.description,
                    createdAt = time(remote.created) ?: now, updatedAt = updatedAt))
                id to (remote.changed.isEmpty() || previous?.server?.changed != remote.changed)
            }
            if (artwork != null && imported != null) {
                val (id, changed) = imported
                val key = "server-playlist-cover:$id"
                // Navidrome keeps a playlist's cover ID when its image changes, so its change time decides when to download again.
                try { if (remote.coverArt == null) artwork.remove(key) else if (changed || artwork.path(key) == null) artwork.fetch(key, connection, remote.coverArt) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    // A missing cover must not prevent importing the playlist; forget its change time so the next pull retries it.
                    store.transaction {
                        val current = playlists.get(id)
                        current?.server?.let { store.put("playlist:$id", "playlists", current.copy(server = it.copy(changed = ""))) }
                    }
                    if (!changedOnly && error is NavidromeRequestException && error.serverUnavailable) throw error
                }
            }
            onProgress(ServerRefreshProgress(ServerRefreshStage.PLAYLISTS, index + 1, remotes.size))
        }
    }

    suspend fun synchronize() = synchronization.withLock {
        val connection = client() ?: return@withLock
        // Each journal entry is sent on its own so one rejected change cannot block every later change forever.
        // A server that cannot be reached or rejects the account fails every entry, so those stop the run at once.
        var failure: Exception? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (request: NavidromeRequestException) {
                // A failed connection, a rejected account, rate limiting or a server error fails every entry; other failures belong to this entry.
                val status = request.httpStatus
                if (request.connectionFailure || status != null && (status in setOf(401, 403, 429) || status >= 500)) throw request
                if (failure == null) failure = request
            }
            catch (rejected: NavidromeException) { if (rejected.code in setOf(40, 41, 50)) throw rejected; if (failure == null) failure = rejected }
            catch (error: Exception) { if (failure == null) failure = error }
        }
        // A song the server no longer has cannot take the change; drop it from the journal.
        suspend fun ifPresent(block: suspend () -> Unit) {
            try { block() } catch (error: NavidromeException) { if (error.code != 70) throw error }
        }
        store.list<RatingChange>("rating-changes").filter { it.sourceId.startsWith("server:${connection.serverId}:") }.forEach { change -> attempt {
            ifPresent { connection.setRating(change.songId, change.rating) }
            store.transaction {
                val key = "rating-change:${change.sourceId}"
                if (store.read<RatingChange>(key) == change) store.database.records().delete(key)
            }
        } }
        store.list<FavoriteChange>("favorite-changes")
            .filter { it.sourceId.startsWith("server:${connection.serverId}:") }.forEach { change -> attempt {
                ifPresent { connection.setFavorite(change.songId, change.favorite) }
                store.transaction {
                    val key = "favorite-change:${change.sourceId}"
                    if (store.read<FavoriteChange>(key) == change) store.database.records().delete(key)
                }
            } }
        for (removal in store.list<PlaylistRemoval>("playlist-removals").filter { it.playlist.server?.serverId == connection.serverId }) attempt {
            val link = requireNotNull(removal.playlist.server)
            val remote = if (link.id == null) pendingPlaylist(connection, removal.playlist.id) else
                try { connection.playlist(link.id) }
                catch (error: NavidromeException) { if (error.code != 70) throw error else null }
            if (remote != null && (remote.name != link.baselineName || remote.songIds != link.baselineSongIds || remote.description != link.baselineDescription)) {
                val remoteTracks = localTracks(remote, connection)
                store.transaction {
                    val key = "playlist-removal:${removal.playlist.id}"
                    if (store.read<PlaylistRemoval>(key) == removal) {
                        store.put("playlist:${removal.playlist.id}", "playlists", removal.playlist.copy(pendingSync = true,
                            server = link.copy(id = remote.id, readOnly = remote.readOnly || remote.owner != connection.username),
                            conflict = PlaylistConflict(remote.name, remote.songIds, remoteTracks, deletionRequested = true, description = remote.description)))
                        store.database.records().delete(key)
                    }
                }
                return@attempt
            }
            if (remote != null) {
                try { connection.deletePlaylist(remote.id) }
                catch (error: NavidromeException) { if (error.code != 70) throw error }
            }
            store.transaction {
                val key = "playlist-removal:${removal.playlist.id}"
                if (store.read<PlaylistRemoval>(key) == removal) store.database.records().delete(key)
            }
        }
        for (playlist in playlists.all().filter { it.pendingSync && it.conflict == null && it.server?.serverId == connection.serverId }) attempt {
            val songIds = serverSongIds(playlist, connection)
            var link = requireNotNull(playlist.server)
            if (link.id == null) {
                // A stable provisional name lets a retry recover a lost creation response.
                val provisional = "Nevumi pending ${playlist.id}"
                val saved = pendingPlaylist(connection, playlist.id) ?: connection.savePlaylist(provisional, songIds, description = playlist.description)
                link = link.copy(id = saved.id, baselineName = saved.name, baselineSongIds = saved.songIds, baselineDescription = saved.description)
                val retained = store.transaction {
                    val current = playlists.get(playlist.id)
                    if (current == null) {
                        val key = "playlist-removal:${playlist.id}"
                        store.read<PlaylistRemoval>(key)?.let { removal ->
                            store.put(key, "playlist-removals", removal.copy(playlist = removal.playlist.copy(server = link)))
                        }
                        false
                    } else {
                        store.put("playlist:${playlist.id}", "playlists", current.copy(server = link))
                        true
                    }
                }
                if (!retained) return@attempt
            }
            val remote = connection.playlist(requireNotNull(link.id))
            if (remote.readOnly && (remote.owner != connection.username || songIds != link.baselineSongIds)) {
                // Locked tracks cannot be replaced. Metadata-only edits by the owner can still be sent below.
                // Its change time stays unrecorded so the next pull imports the rest of this version, such as a new cover.
                val remoteTracks = localTracks(remote, connection)
                val discarded = store.transaction {
                    val current = playlists.get(playlist.id) ?: return@transaction false
                    store.put("playlist:${playlist.id}", "playlists", current.copy(name = remote.name, trackIds = remoteTracks,
                        description = remote.description, pendingSync = false, server = link.copy(baselineName = remote.name,
                            baselineSongIds = remote.songIds, baselineDescription = remote.description, readOnly = true, changed = "")))
                    true
                }
                if (discarded) mutableDiscardedEdits.emit(remote.name)
                return@attempt
            }
            val alreadySaved = remote.name == playlist.name && remote.songIds == songIds && remote.description == playlist.description
            if (!alreadySaved && (remote.name != link.baselineName || remote.songIds != link.baselineSongIds || remote.description != link.baselineDescription)) {
                val remoteTracks = localTracks(remote, connection)
                store.transaction {
                    val current = playlists.get(playlist.id) ?: return@transaction
                    store.put("playlist:${playlist.id}", "playlists", current.copy(
                        server = link.copy(readOnly = remote.readOnly || remote.owner != connection.username),
                        conflict = PlaylistConflict(remote.name, remote.songIds, remoteTracks, description = remote.description)))
                }
                return@attempt
            }
            val saved = when {
                alreadySaved -> remote
                songIds == link.baselineSongIds -> connection.updatePlaylistDetails(requireNotNull(link.id), playlist.name, playlist.description)
                else -> connection.savePlaylist(playlist.name, songIds, link.id, description = playlist.description)
            }
            store.transaction {
                val current = playlists.get(playlist.id) ?: return@transaction
                store.put("playlist:${playlist.id}", "playlists", current.copy(
                    server = link.copy(baselineName = saved.name, baselineSongIds = saved.songIds, baselineDescription = saved.description,
                        readOnly = saved.readOnly || saved.owner != connection.username),
                    pendingSync = current.revision != playlist.revision))
            }
        }
        failure?.let { throw it }
    }

    private suspend fun pendingPlaylist(connection: NavidromeClient, id: String): ServerPlaylist? {
        val candidates = connection.playlists().filter { it.name == "Nevumi pending $id" && it.owner == connection.username }
        require(candidates.size <= 1) { "Multiple pending copies of this playlist exist on your music server" }
        return candidates.singleOrNull()
    }

    private suspend fun serverSongIds(playlist: Playlist, connection: NavidromeClient): List<String> = playlist.trackIds.map { id ->
        requireNotNull(library.findTrack(id)?.sources?.firstOrNull {
            it.kind == SourceKind.SERVER && it.id.startsWith("server:${connection.serverId}:")
        }) { "Server playlists can only contain tracks from their server" }.location
    }

    private suspend fun localTracks(remote: ServerPlaylist, connection: NavidromeClient): List<String> {
        val tracks = library.tracks()
        return remote.songIds.map { songId -> requireNotNull(tracks.firstOrNull { track ->
            track.sources.any { it.kind == SourceKind.SERVER && it.location == songId && it.id.startsWith("server:${connection.serverId}:") }
        }) { "Refresh the server library before resolving this playlist" }.id }
    }
}
