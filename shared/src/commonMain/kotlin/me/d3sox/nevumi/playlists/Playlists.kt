package me.d3sox.nevumi.playlists

import me.d3sox.nevumi.storage.MusicStore
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlin.time.Clock
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.library.filterTracks
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.listeningStatistics

@Serializable
data class PlaylistServer(val serverId: String, val id: String?, val baselineName: String,
    val baselineSongIds: List<String>, val owner: String, val readOnly: Boolean = false, val baselineDescription: String = "",
    val coverArt: String? = null, val changed: String = "")

@Serializable
data class PlaylistConflict(val name: String, val songIds: List<String>, val trackIds: List<String>, val deletionRequested: Boolean = false, val description: String = "")

@Serializable
enum class PlaylistSort { CUSTOM, TITLE, ARTIST, ALBUM, RECENT }

@Serializable
data class Playlist(val id: String, val name: String, val trackIds: List<String>, val autoDownload: Boolean = false,
    val server: PlaylistServer? = null, val pendingSync: Boolean = false, val revision: String = Uuid.random().toString(),
    val conflict: PlaylistConflict? = null, val rule: LibraryQuery? = null, val description: String = "",
    val shuffle: Boolean = false, val sort: PlaylistSort = PlaylistSort.CUSTOM, val descending: Boolean = false)

// Navidrome has no playlist folders, so names like "Folder ▸ Playlist" group playlists. Keyboards cannot type the
// separator, so existing names with slashes or other punctuation are left alone.
const val PLAYLIST_FOLDER_SEPARATOR = '▸'
private const val FOLDER_JOINER = " $PLAYLIST_FOLDER_SEPARATOR "
private fun Playlist.segments() = name.split(PLAYLIST_FOLDER_SEPARATOR).map(String::trim).takeIf { it.size > 1 && it.none(String::isEmpty) }
/** The folder path, such as "Music ▸ Road", or null when the playlist is not in a folder. */
val Playlist.folder: String? get() = segments()?.dropLast(1)?.let(::folderPath)
val Playlist.title: String get() = segments()?.last() ?: name
fun playlistName(folders: List<String>, title: String) = folderPath(folders.map(String::trim).filter(String::isNotEmpty) + title.trim())
fun folderPath(segments: List<String>) = segments.joinToString(FOLDER_JOINER)
fun folderSegments(folder: String) = folder.split(FOLDER_JOINER)
fun parentFolder(folder: String) = folder.substringBeforeLast(FOLDER_JOINER, "").ifEmpty { null }
fun isInFolder(playlistFolder: String?, folder: String) = playlistFolder == folder || playlistFolder?.startsWith(folder + FOLDER_JOINER) == true

@Serializable
internal data class PlaylistRemoval(val playlist: Playlist, val revision: String = Uuid.random().toString())

internal suspend fun remapPlaylistTracks(store: MusicStore, from: String, to: String) {
    fun remap(playlist: Playlist) = playlist.copy(trackIds = playlist.trackIds.map { if (it == from) to else it },
        conflict = playlist.conflict?.let { conflict -> conflict.copy(trackIds = conflict.trackIds.map { if (it == from) to else it }) })
    for (playlist in store.list<Playlist>("playlists")) {
        val updated = remap(playlist)
        if (updated != playlist) store.put("playlist:${playlist.id}", "playlists", updated.copy(revision = Uuid.random().toString()))
    }
    for (removal in store.list<PlaylistRemoval>("playlist-removals")) {
        val updated = remap(removal.playlist)
        if (updated != removal.playlist)
            store.put("playlist-removal:${updated.id}", "playlist-removals", removal.copy(playlist = updated))
    }
}

class Playlists(private val store: MusicStore, private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val refreshEveryMs: Long = 60_000) {
    init { require(refreshEveryMs > 0) { "Playlist refresh interval must be positive" } }
    suspend fun createSmart(name: String, rule: LibraryQuery): Playlist {
        val playlist = Playlist(Uuid.random().toString(), name.trim(), emptyList(), rule = rule)
        require(playlist.name.isNotEmpty()) { "Name the playlist" }
        store.put("playlist:${playlist.id}", "playlists", playlist)
        return evaluate(listOf(playlist)).single()
    }
    suspend fun create(name: String, trackIds: List<String>): Playlist {
        val playlist = Playlist(Uuid.random().toString(), name.trim(), trackIds)
        require(playlist.name.isNotEmpty()) { "Name the playlist" }
        store.put("playlist:${playlist.id}", "playlists", playlist)
        return playlist
    }
    internal suspend fun restore(playlist: Playlist) = store.transaction {
        require(store.read<Playlist>("playlist:${playlist.id}") == null) { "This playlist already exists" }
        store.database.records().delete("playlist-removal:${playlist.id}")
        store.put("playlist:${playlist.id}", "playlists", playlist.copy(revision = Uuid.random().toString()))
    }
    suspend fun updateRule(id: String, rule: LibraryQuery) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        require(playlist.rule != null) { "This playlist has no rules" }
        store.put("playlist:$id", "playlists", playlist.copy(rule = rule, trackIds = emptyList(),
            revision = Uuid.random().toString()))
    }
    suspend fun get(id: String): Playlist? = store.read<Playlist>("playlist:$id")?.let { evaluate(listOf(it)).single() }
    suspend fun all(): List<Playlist> = evaluate(store.list("playlists"))
    private suspend fun evaluate(playlists: List<Playlist>): List<Playlist> {
        if (playlists.none { it.rule != null }) return playlists
        val tracks = MusicLibrary(store).tracks()
        val statistics = listeningStatistics(store.list<Listen>("listens"))
        return playlists.map { playlist -> playlist.rule?.let { rule ->
            playlist.copy(trackIds = filterTracks(tracks, rule, statistics, clock()).map { it.id })
        } ?: playlist }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<List<Playlist>> = store.database.records().observe("playlists").distinctUntilChanged().flatMapLatest { records ->
        val playlists = records.map { store.json.decodeFromString<Playlist>(it.value) }
        if (playlists.none { it.rule != null }) flowOf(playlists)
        else combine(MusicLibrary(store).observe(), me.d3sox.nevumi.history.ListeningHistory(store, MusicLibrary(store)).observe().map { listens -> listeningStatistics(listens) }.distinctUntilChanged(),
        flow { while (true) { emit(clock()); delay(refreshEveryMs) } }) { tracks, statistics, now ->
        playlists.map { playlist ->
            playlist.rule?.let { rule -> playlist.copy(trackIds = filterTracks(tracks, rule, statistics, now).map { it.id }) } ?: playlist
        }
        }
    }.distinctUntilChanged()
    suspend fun append(id: String, trackId: String) = append(id, listOf(trackId))
    suspend fun append(id: String, trackIds: List<String>) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        require(playlist.rule == null) { "Edit this smart playlist's rules to change its songs" }
        validateServerTracks(playlist, trackIds)
        store.put("playlist:$id", "playlists", playlist.copy(trackIds = playlist.trackIds + trackIds,
            pendingSync = playlist.server != null, revision = Uuid.random().toString()))
    }
    suspend fun updateMembership(trackIds: List<String>, choices: Map<String, Boolean>) = store.transaction {
        val selected = trackIds.distinct()
        require(selected.isNotEmpty()) { "Select at least one song" }
        for ((id, included) in choices) {
            val playlist = requireNotNull(get(id)) { "Playlist is missing" }
            require(playlist.rule == null) { "Edit this smart playlist's rules to change its songs" }
            val updated = if (included) playlist.trackIds + selected.filterNot { it in playlist.trackIds }
                else playlist.trackIds.filterNot { it in selected }
            if (updated != playlist.trackIds) replaceTracks(id, updated)
        }
    }
    suspend fun replaceTracks(id: String, trackIds: List<String>) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        require(playlist.rule == null) { "Edit this smart playlist's rules to change its songs" }
        validateServerTracks(playlist, trackIds)
        store.put("playlist:$id", "playlists", playlist.copy(trackIds = trackIds,
            pendingSync = playlist.server != null, revision = Uuid.random().toString()))
    }
    suspend fun rename(id: String, name: String) = store.transaction {
        require(name.isNotBlank()) { "Name the playlist" }
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        requireEditable(playlist)
        store.put("playlist:$id", "playlists", playlist.copy(name = name.trim(),
            pendingSync = playlist.server != null, revision = Uuid.random().toString()))
    }
    suspend fun setShuffle(id: String, enabled: Boolean) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        store.put("playlist:$id", "playlists", playlist.copy(shuffle = enabled))
    }
    suspend fun setSort(id: String, sort: PlaylistSort, descending: Boolean) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        store.put("playlist:$id", "playlists", playlist.copy(sort = sort, descending = descending))
    }

    suspend fun edit(id: String, name: String, description: String) = store.transaction {
        require(name.isNotBlank()) { "Name the playlist" }
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        requireEditable(playlist)
        store.put("playlist:$id", "playlists", playlist.copy(name = name.trim(), description = description.trim(),
            pendingSync = playlist.server != null, revision = Uuid.random().toString()))
    }

    suspend fun moveTrack(id: String, from: Int, to: Int) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        require(playlist.rule == null) { "Edit this smart playlist's rules to change its songs" }
        require(from in playlist.trackIds.indices && to in playlist.trackIds.indices) { "Playlist entry is missing" }
        val tracks = playlist.trackIds.toMutableList()
        tracks.add(to, tracks.removeAt(from))
        replaceTracks(id, tracks)
    }
    suspend fun delete(id: String) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        requireEditable(playlist)
        if (playlist.server != null) store.put("playlist-removal:$id", "playlist-removals", PlaylistRemoval(playlist))
        store.database.records().delete("playlist:$id")
    }
    suspend fun setAutoDownload(id: String, enabled: Boolean) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        store.put("playlist:$id", "playlists", playlist.copy(autoDownload = enabled))
    }
    suspend fun resolveConflict(id: String, keepLocal: Boolean) = store.transaction {
        val playlist = requireNotNull(get(id)) { "Playlist is missing" }
        requireEditable(playlist)
        val conflict = requireNotNull(playlist.conflict) { "Playlist has no conflict" }
        val link = requireNotNull(playlist.server)
        if (keepLocal && conflict.deletionRequested) {
            store.put("playlist-removal:$id", "playlist-removals", PlaylistRemoval(playlist.copy(
                server = link.copy(baselineName = conflict.name, baselineSongIds = conflict.songIds, baselineDescription = conflict.description), conflict = null)))
            store.database.records().delete("playlist:$id")
            return@transaction
        }
        store.put("playlist:$id", "playlists", playlist.copy(
            name = if (keepLocal) playlist.name else conflict.name,
            trackIds = if (keepLocal) playlist.trackIds else conflict.trackIds,
            description = if (keepLocal) playlist.description else conflict.description,
            server = link.copy(baselineName = conflict.name, baselineSongIds = conflict.songIds, baselineDescription = conflict.description),
            pendingSync = keepLocal, conflict = null, revision = Uuid.random().toString()))
    }
    private suspend fun validateServerTracks(playlist: Playlist, trackIds: List<String>) {
        requireEditable(playlist)
        val server = playlist.server ?: return
        val library = MusicLibrary(store)
        for (trackId in trackIds) require(library.findTrack(trackId)?.sources?.any {
            it.kind == SourceKind.SERVER && it.id.startsWith("server:${server.serverId}:")
        } == true) { "Server playlists can only contain tracks from their server. Use an app playlist for mixed sources." }
    }
    private fun requireEditable(playlist: Playlist) {
        require(playlist.server?.readOnly != true) { "Only the owner can edit this Navidrome playlist" }
    }
}
