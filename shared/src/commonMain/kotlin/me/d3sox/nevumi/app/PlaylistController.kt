package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.playlists.M3uPlaylists
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.PlaylistServer
import me.d3sox.nevumi.playlists.PlaylistSort
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.QueueExportScope
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_added_to_playlist
import me.d3sox.nevumi.resources.notice_playlist_created
import me.d3sox.nevumi.resources.notice_playlist_updated
import me.d3sox.nevumi.resources.notice_playlists_updated
import me.d3sox.nevumi.ui.PlaylistDocument

/** Manages playlists: their songs and order, covers, server publishing, M3U files, smart rules and automatic downloads. */
class PlaylistController internal constructor(private val context: ControllerContext, private val server: ServerController,
    private val library: MusicLibrary, private val playlists: Playlists?, private val artwork: ArtworkLibrary?,
    private val navidrome: NavidromeConnection?, private val serverChanges: NavidromeChanges?, private val offline: OfflineMusic?) {
    val serverPlaylistPublishingAvailable: Boolean get() = serverChanges != null

    init {
        playlists?.let { collections -> context.scope.launch {
            collections.observe().collect { updated ->
                context.operations.withLock { context.update { it.copy(playlists = updated) } }
            }
        } }
    }

    fun createPlaylist(name: String, trackIds: List<String> = emptyList()) = context.action {
        createPlaylistWithUndo(name, trackIds)
    }
    suspend fun createQueuePlaylist(name: String, snapshot: QueueSnapshot, selection: QueueExportScope) = withContext(Dispatchers.Default) {
        context.operations.withLock {
            val ids = selection.trackIds(snapshot)
            require(ids.isNotEmpty()) { "Choose a queue section with songs" }
            ids.forEach { requireNotNull(library.findTrack(it)) { "A queue song is missing from the library" } }
            createPlaylistWithUndo(name, ids)
        }
    }
    private suspend fun createPlaylistWithUndo(name: String, trackIds: List<String>): Playlist {
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val created = collections.create(name, trackIds)
        refreshPlaylists()
        context.confirm(Res.string.notice_playlist_created, ActionDestination.PLAYLISTS, undo = { collections.delete(created.id); refreshPlaylists() },
            redo = { collections.restore(created); refreshPlaylists() })
        return created
    }
    private suspend fun refreshPlaylists() {
        val updated = playlists?.all().orEmpty()
        context.update { it.copy(playlists = updated) }
    }
    fun publishPlaylist(id: String) = context.action {
        requireNotNull(serverChanges) { "Playlist synchronization is unavailable" }.publishPlaylist(id)
        refreshPlaylists()
    }
    suspend fun importM3u(document: PlaylistDocument) = withContext(Dispatchers.Default) {
        context.operations.withLock {
            val result = M3uPlaylists(library, requireNotNull(playlists)).import(document.name, document.text, document.baseLocation)
            refreshPlaylists()
            result
        }
    }
    suspend fun exportM3u(id: String) = withContext(Dispatchers.Default) {
        context.operations.withLock { M3uPlaylists(library, requireNotNull(playlists)).export(id) }
    }
    suspend fun exportQueueM3u(snapshot: QueueSnapshot, selection: QueueExportScope) = withContext(Dispatchers.Default) {
        context.operations.withLock { M3uPlaylists(library).exportTracks(selection.trackIds(snapshot)) }
    }
    fun createSmartPlaylist(name: String, rule: LibraryQuery) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.createSmart(name, rule)
        refreshPlaylists()
    }
    fun updatePlaylistRule(id: String, rule: LibraryQuery) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.updateRule(id, rule)
        refreshPlaylists()
    }
    fun addToPlaylist(playlistId: String, trackId: String) = addToPlaylist(playlistId, listOf(trackId))
    fun addToPlaylist(playlistId: String, trackIds: List<String>) = context.action {
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val before = requireNotNull(collections.get(playlistId)) { "Playlist is missing" }.trackIds
        collections.append(playlistId, trackIds)
        refreshPlaylists()
        val after = requireNotNull(collections.get(playlistId)).trackIds
        context.confirm(Res.string.notice_added_to_playlist, ActionDestination.PLAYLISTS, undo = { collections.replaceTracks(playlistId, before); refreshPlaylists() },
            redo = { collections.replaceTracks(playlistId, after); refreshPlaylists() })
    }
    fun managePlaylistMembership(trackIds: List<String>, choices: Map<String, Boolean>) = context.action {
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val before = choices.keys.associateWith { requireNotNull(collections.get(it)) { "Playlist is missing" }.trackIds }
        collections.updateMembership(trackIds, choices)
        refreshPlaylists()
        val after = choices.keys.associateWith { requireNotNull(collections.get(it)).trackIds }
        context.confirm(Res.string.notice_playlists_updated, ActionDestination.PLAYLISTS, undo = {
            before.forEach { (id, tracks) -> collections.replaceTracks(id, tracks) }; refreshPlaylists()
        }, redo = {
            after.forEach { (id, tracks) -> collections.replaceTracks(id, tracks) }; refreshPlaylists()
        })
    }
    fun setPlaylistShuffle(playlistId: String, enabled: Boolean) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.setShuffle(playlistId, enabled)
        refreshPlaylists()
    }
    fun setPlaylistSort(playlistId: String, sort: PlaylistSort, descending: Boolean) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.setSort(playlistId, sort, descending)
        refreshPlaylists()
    }
    fun editPlaylist(playlistId: String, name: String, description: String, cover: ByteArray? = null, removeCover: Boolean = false) = context.action {
        require(name.isNotBlank()) { "Name the playlist" }
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val previous = requireNotNull(collections.get(playlistId)) { "Playlist is missing" }
        require(previous.server?.readOnly != true) { "Only the owner can edit this playlist" }
        val link = previous.server
        if (link != null && (cover != null || removeCover)) {
            val client = serverPlaylistClient(link)
            val oldCover = if (client.playlistImageUploaded(requireNotNull(link.id))) link.coverArt?.let { client.coverArt(it, size = null) } else null
            val oldLocalCover = artwork?.read("playlist:$playlistId")
            setServerPlaylistCover(playlistId, link, client, cover)
            collections.edit(playlistId, name, description)
            refreshPlaylists()
            context.confirm(Res.string.notice_playlist_updated, ActionDestination.PLAYLISTS, undo = {
                collections.edit(playlistId, previous.name, previous.description)
                setServerPlaylistCover(playlistId, link, serverPlaylistClient(link), oldCover)
                oldLocalCover?.let { artwork?.save("playlist:$playlistId", it) }
                refreshPlaylists()
            }, redo = {
                collections.edit(playlistId, name, description)
                setServerPlaylistCover(playlistId, link, serverPlaylistClient(link), cover)
                refreshPlaylists()
            })
            return@action
        }
        val key = "playlist:$playlistId"
        val oldCover = if (cover != null || removeCover) artwork?.read(key) else null
        if (cover != null) requireNotNull(artwork) { "Artwork editing is unavailable" }.save(key, cover)
        else if (removeCover) artwork?.remove(key)
        collections.edit(playlistId, name, description)
        refreshPlaylists()
        context.confirm(Res.string.notice_playlist_updated, ActionDestination.PLAYLISTS, undo = {
            collections.edit(playlistId, previous.name, previous.description)
            if (cover != null || removeCover) {
                if (oldCover == null) artwork?.remove(key) else artwork?.save(key, oldCover)
            }
            refreshPlaylists()
        }, redo = {
            collections.edit(playlistId, name, description)
            if (cover != null) artwork?.save(key, cover) else if (removeCover) artwork?.remove(key)
            refreshPlaylists()
        })
    }
    private suspend fun serverPlaylistClient(link: PlaylistServer): NavidromeClient {
        requireNotNull(link.id) { "Wait for this playlist to sync before changing its cover" }
        navidrome?.profile()?.let { server.musicServerAccess(it.baseUrl) }
        return requireNotNull(navidrome?.client()?.takeIf { it.serverId == link.serverId }) { "Connect to the music server to change this playlist's cover" }
    }
    /** Uploads [cover] to the server, or removes the uploaded cover when it is null so Navidrome generates one again. */
    private suspend fun setServerPlaylistCover(playlistId: String, link: PlaylistServer, client: NavidromeClient, cover: ByteArray?) {
        val remoteId = requireNotNull(link.id)
        val key = "server-playlist-cover:$playlistId"
        if (cover != null) client.setPlaylistImage(remoteId, cover) else client.removePlaylistImage(remoteId)
        // Drop a cover kept only in the app once the server has the new one, so a failed request changes nothing.
        artwork?.remove("playlist:$playlistId")
        if (cover != null) artwork?.save(key, cover)
        else try { client.playlist(remoteId).coverArt?.let { artwork?.fetch(key, client, it) } ?: artwork?.remove(key) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { artwork?.remove(key) }
    }
    fun renamePlaylist(playlistId: String, name: String) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.rename(playlistId, name)
        refreshPlaylists()
    }
    fun removePlaylistTrack(playlistId: String, index: Int) = context.action {
        val collections = requireNotNull(playlists) { "Playlists are unavailable" }
        val collection = requireNotNull(collections.get(playlistId)) { "Playlist is missing" }
        require(index in collection.trackIds.indices) { "Playlist entry is missing" }
        collections.replaceTracks(playlistId, collection.trackIds.filterIndexed { position, _ -> position != index })
        refreshPlaylists()
    }
    fun movePlaylistTrack(playlistId: String, from: Int, to: Int) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.moveTrack(playlistId, from, to)
        refreshPlaylists()
    }
    fun deletePlaylist(playlistId: String) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.delete(playlistId)
        refreshPlaylists()
    }
    fun resolvePlaylistConflict(playlistId: String, keepLocal: Boolean) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.resolveConflict(playlistId, keepLocal)
        refreshPlaylists()
    }
    fun setPlaylistDownload(playlistId: String, enabled: Boolean) = context.action {
        requireNotNull(playlists) { "Playlists are unavailable" }.setAutoDownload(playlistId, enabled)
        refreshPlaylists()
        if (enabled) offline?.downloadPlaylists()
    }
}
