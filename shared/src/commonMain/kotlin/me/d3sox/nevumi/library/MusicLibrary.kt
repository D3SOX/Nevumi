package me.d3sox.nevumi.library

import me.d3sox.nevumi.storage.MusicStore
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import okio.ByteString.Companion.encodeUtf8
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid
import kotlin.math.abs
import me.d3sox.nevumi.queue.remapQueueTracks
import me.d3sox.nevumi.playlists.remapPlaylistTracks
import me.d3sox.nevumi.downloads.remapDownloadedTrack
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.history.remapListeningHistory
import me.d3sox.nevumi.sftp.remapServerUploads
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.ListeningStatistics
import me.d3sox.nevumi.history.listeningStatistics

@Serializable
data class TrackMetadata(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val genres: List<String> = emptyList(),
    val albumArtist: String = "",
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val folder: String = "",
    val year: Int = 0,
    val recordingId: String = "",
    val releaseId: String = "",
)

@Serializable
data class Track(val id: String, val metadata: TrackMetadata, val sources: List<TrackSource>, val favorite: Boolean = false,
    val addedAt: Long = 0, val rating: Int = 0)

val Track.recentAt: Long get() = sources.filter { it.kind == SourceKind.LOCAL && it.modifiedAt > 0 }
    .maxOfOrNull { it.modifiedAt } ?: addedAt

@Serializable
enum class LibrarySort { TITLE, ARTIST, ALBUM, RECENT, RATING, PLAY_COUNT, LAST_PLAYED }

@Serializable
data class LibraryQuery(val text: String = "", val album: String? = null, val artist: String? = null,
    val albumArtist: String? = null, val genre: String? = null, val favoritesOnly: Boolean = false,
    val sort: LibrarySort = LibrarySort.TITLE, val descending: Boolean = false,
    val sourceKind: SourceKind? = null, val folder: String? = null,
    val yearFrom: Int? = null, val yearTo: Int? = null,
    val durationFromMs: Long? = null, val durationToMs: Long? = null,
    val availableOnly: Boolean = false, val limit: Int? = null,
    val ratingFrom: Int? = null, val ratingTo: Int? = null,
    val playCountFrom: Int? = null, val playCountTo: Int? = null,
    val lastPlayedWithinDays: Int? = null, val notPlayedWithinDays: Int? = null) {
    init {
        require(limit == null || limit > 0) { "Song limit must be positive" }
        require(yearFrom == null || yearFrom >= 0) { "Year cannot be negative" }
        require(yearTo == null || yearTo >= 0) { "Year cannot be negative" }
        require(yearFrom == null || yearTo == null || yearFrom <= yearTo) { "Start year must precede end year" }
        require(durationFromMs == null || durationFromMs >= 0) { "Duration cannot be negative" }
        require(durationToMs == null || durationToMs >= 0) { "Duration cannot be negative" }
        require(durationFromMs == null || durationToMs == null || durationFromMs <= durationToMs) { "Minimum duration must not exceed maximum duration" }
        require(ratingFrom == null || ratingFrom in 0..5) { "Rating must be between zero and five" }
        require(ratingTo == null || ratingTo in 0..5) { "Rating must be between zero and five" }
        require(ratingFrom == null || ratingTo == null || ratingFrom <= ratingTo) { "Minimum rating must not exceed maximum rating" }
        require(playCountFrom == null || playCountFrom >= 0) { "Play count cannot be negative" }
        require(playCountTo == null || playCountTo >= 0) { "Play count cannot be negative" }
        require(playCountFrom == null || playCountTo == null || playCountFrom <= playCountTo) { "Minimum play count must not exceed maximum play count" }
        require(lastPlayedWithinDays == null || lastPlayedWithinDays > 0) { "Choose a positive number of days" }
        require(notPlayedWithinDays == null || notPlayedWithinDays > 0) { "Choose a positive number of days" }
    }
}

fun filterTracks(tracks: List<Track>, query: LibraryQuery, statistics: Map<String, ListeningStatistics> = emptyMap(),
    now: Long = Clock.System.now().toEpochMilliseconds()): List<Track> = tracks.filter { track ->
    val metadata = track.metadata
    listOf(metadata.title, metadata.artist, metadata.album, metadata.albumArtist).plus(metadata.genres)
        .any { it.contains(query.text, ignoreCase = true) } &&
        (query.album == null || metadata.album.equals(query.album, ignoreCase = true)) &&
        (query.artist == null || metadata.artist.equals(query.artist, ignoreCase = true)) &&
        (query.albumArtist == null || metadata.albumArtist.ifBlank { metadata.artist }.equals(query.albumArtist, ignoreCase = true)) &&
        (query.genre == null || metadata.genres.any { it.equals(query.genre, ignoreCase = true) }) && (!query.favoritesOnly || track.favorite) &&
        (query.folder == null || metadata.folder == query.folder) &&
        (query.yearFrom == null || metadata.year >= query.yearFrom) &&
        (query.yearTo == null || metadata.year <= query.yearTo) &&
        (query.durationFromMs == null || metadata.durationMs >= query.durationFromMs) &&
        (query.durationToMs == null || metadata.durationMs <= query.durationToMs) &&
        (query.ratingFrom == null || track.rating >= query.ratingFrom) &&
        (query.ratingTo == null || track.rating <= query.ratingTo) &&
        (query.playCountFrom == null || (statistics[track.id]?.playCount ?: 0) >= query.playCountFrom) &&
        (query.playCountTo == null || (statistics[track.id]?.playCount ?: 0) <= query.playCountTo) &&
        (query.lastPlayedWithinDays == null || statistics[track.id]?.lastPlayedAt?.let { it >= now - query.lastPlayedWithinDays * 86_400_000L } == true) &&
        (query.notPlayedWithinDays == null || statistics[track.id]?.lastPlayedAt?.let { it < now - query.notPlayedWithinDays * 86_400_000L } != false) &&
        (!query.availableOnly || track.sources.any { it.available }) &&
        (query.sourceKind == null || track.sources.any { it.kind == query.sourceKind })
}.let { matching ->
    val comparator = when (query.sort) {
        LibrarySort.TITLE -> compareBy<Track> { it.metadata.title.lowercase() }
        LibrarySort.ARTIST -> compareBy<Track> { it.metadata.artist.lowercase() }.thenBy { it.metadata.title.lowercase() }
        LibrarySort.ALBUM -> compareBy<Track> { it.metadata.album.lowercase() }.thenBy { it.metadata.discNumber }.thenBy { it.metadata.trackNumber }
        LibrarySort.RECENT -> compareByDescending<Track> { it.recentAt }
        LibrarySort.RATING -> compareByDescending<Track> { it.rating }.thenBy { it.metadata.title.lowercase() }
        LibrarySort.PLAY_COUNT -> compareByDescending<Track> { statistics[it.id]?.playCount ?: 0 }.thenBy { it.metadata.title.lowercase() }
        LibrarySort.LAST_PLAYED -> compareByDescending<Track> { statistics[it.id]?.lastPlayedAt ?: Long.MIN_VALUE }.thenBy { it.metadata.title.lowercase() }
    }.thenBy { it.id }
    matching.sortedWith(if (query.descending) comparator.reversed() else comparator).let { ordered ->
        query.limit?.let { ordered.take(it) } ?: ordered
    }
}

class MusicLibrary(private val store: MusicStore) {
    private val decoded = kotlinx.coroutines.sync.Mutex()
    private var cachedRecords = emptyList<me.d3sox.nevumi.storage.StoredRecord>()
    private var cachedTracks = emptyList<Track>()
    private suspend fun decodeTracks(records: List<me.d3sox.nevumi.storage.StoredRecord>): List<Track> = decoded.withLock { decodeTracksLocked(records) }
    private fun decodeTracksLocked(records: List<me.d3sox.nevumi.storage.StoredRecord>): List<Track> {
        if (records != cachedRecords) {
            val previous = cachedRecords.associateBy { it.key }
            val tracks = cachedTracks.associateBy { "track:${it.id}" }
            cachedTracks = filterTracks(records.map { record ->
                if (previous[record.key] == record) requireNotNull(tracks[record.key])
                else store.json.decodeFromString<Track>(record.value)
            }, LibraryQuery())
            cachedRecords = records
        }
        return cachedTracks
    }
    internal suspend fun localFolderRules(): LocalFolderRules {
        val settings = store.read<me.d3sox.nevumi.settings.MusicSettings>("settings:music")
        return LocalFolderRules(settings?.includeFolders.orEmpty(), settings?.excludeFolders.orEmpty())
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun observe(): Flow<List<Track>> = store.database.records().observe("tracks").distinctUntilChanged().sample(100)
        .map { records -> decodeTracks(records) }

    internal suspend fun rememberLocalRoot(location: String) {
        store.put("library-root:${location.encodeUtf8().sha256().hex()}", "library-roots", LibraryRoot(location))
    }

    internal suspend fun localRoots(): List<String> = store.list<LibraryRoot>("library-roots").map { it.location }.sorted()

    internal suspend fun localIndexStamp(location: String): String? = store.read("local-index:$location")

    internal suspend fun saveLocalIndexStamp(location: String, stamp: String) {
        store.put("local-index:$location", "local-index", stamp)
    }

    internal suspend fun localFolderStamp(): String {
        val settings = store.read<me.d3sox.nevumi.settings.MusicSettings>("settings:music")
        return store.json.encodeToString(listOf(settings?.includeFolders.orEmpty(), settings?.excludeFolders.orEmpty()))
    }

    internal fun observeLocalRoots(): Flow<List<String>> = store.database.records().observe("library-roots").distinctUntilChanged()
        .map { records -> records.map { store.json.decodeFromString<LibraryRoot>(it.value).location }.sorted() }


    suspend fun tracks(query: String = ""): List<Track> = tracks(LibraryQuery(text = query))
    suspend fun tracks(query: LibraryQuery): List<Track> {
        val history = query.sort in setOf(LibrarySort.PLAY_COUNT, LibrarySort.LAST_PLAYED) || query.playCountFrom != null ||
            query.playCountTo != null || query.lastPlayedWithinDays != null || query.notPlayedWithinDays != null
        val tracks = decodeTracks(store.database.records().list("tracks"))
        return if (query == LibraryQuery()) tracks else filterTracks(tracks, query,
            if (history) listeningStatistics(store.list<Listen>("listens")) else emptyMap())
    }
    suspend fun importTrack(metadata: TrackMetadata, source: TrackSource, linkTo: String? = null): Track = store.transaction {
        val owner = store.read<SourceOwner>("source-owner:${source.id}")
        val existing = if (linkTo != null) requireNotNull(findTrack(linkTo)) { "Linked track is missing" } else
            owner?.let { findTrack(it.trackId) } ?: matchingTrack(metadata, source)
        val refreshed = existing == null || (source.kind != SourceKind.DOWNLOAD && existing.sources.any { it.id == source.id })
        val track = (existing ?: Track(Uuid.random().toString(), metadata, emptyList(), addedAt = Clock.System.now().toEpochMilliseconds())).copy(
            metadata = if (refreshed) metadata else requireNotNull(existing).metadata,
            sources = existing?.sources.orEmpty().filterNot { it.id == source.id } + source)
        store.put("track:${track.id}", "tracks", track)
        store.put("source-owner:${source.id}", "source-owners", SourceOwner(track.id))
        rememberRecording(track)
        track
    }

    // MusicBrainz recording id (lowercase) to track ids, so a first import does not scan the whole library per song.
    private var recordings: MutableMap<String, MutableSet<String>>? = null
    private suspend fun rememberRecording(track: Track) = decoded.withLock {
        val id = track.metadata.recordingId.takeIf { it.isNotBlank() }?.lowercase() ?: return@withLock
        recordings?.getOrPut(id) { mutableSetOf() }?.add(track.id)
    }
    private suspend fun recordingCandidates(recordingId: String): List<Track> {
        val ids = decoded.withLock {
            val index = recordings ?: HashMap<String, MutableSet<String>>().also { index ->
                for (track in decodeTracksLocked(store.database.records().list("tracks"))) {
                    track.metadata.recordingId.takeIf { it.isNotBlank() }?.let { index.getOrPut(it.lowercase()) { mutableSetOf() }.add(track.id) }
                }
                recordings = index
            }
            index[recordingId.lowercase()].orEmpty().toList()
        }
        return ids.mapNotNull { findTrack(it) }
    }

    private suspend fun matchingTrack(metadata: TrackMetadata, source: TrackSource): Track? {
        val counterpart = when (source.kind) {
            SourceKind.LOCAL -> SourceKind.SERVER
            SourceKind.SERVER -> SourceKind.LOCAL
            SourceKind.DOWNLOAD -> return null
        }
        fun identified(value: TrackMetadata) = value.recordingId.matches(musicBrainzId) && value.releaseId.matches(musicBrainzId) &&
            value.trackNumber > 0 && value.discNumber > 0 && value.durationMs > 0
        if (!identified(metadata)) return null
        return recordingCandidates(metadata.recordingId).singleOrNull { track ->
            val other = track.metadata
            track.sources.any { it.kind == counterpart } && identified(other) &&
                metadata.recordingId.equals(other.recordingId, ignoreCase = true) &&
                metadata.releaseId.equals(other.releaseId, ignoreCase = true) &&
                metadata.discNumber == other.discNumber && metadata.trackNumber == other.trackNumber &&
                abs(metadata.durationMs - other.durationMs) <= 2_000
        }
    }

    // A server song with the byte size, title, artist and length of a separate local song is the same file uploaded to the server.
    // Returns (server track, local track) pairs of available copies; a file with several possible copies on either side stays separate.
    internal suspend fun sameFileCopies(): List<Pair<String, String>> {
        val tracks = tracks()
        fun files(kind: SourceKind, other: SourceKind) = tracks.filter { track -> track.sources.none { it.kind == other } }
            .filter { it.metadata.durationMs > 0 }
            .mapNotNull { track -> track.sources.firstOrNull { it.kind == kind && it.available && it.indexedSize > 0 }?.let {
                Triple(it.indexedSize, track.metadata.title.trim().lowercase(), track.metadata.artist.trim().lowercase()) to track } }
            .groupBy({ it.first }, { it.second })
        val local = files(SourceKind.LOCAL, SourceKind.SERVER)
        return files(SourceKind.SERVER, SourceKind.LOCAL).mapNotNull { (file, servers) ->
            val server = servers.singleOrNull() ?: return@mapNotNull null
            val copy = local[file]?.singleOrNull() ?: return@mapNotNull null
            if (abs(server.metadata.durationMs - copy.metadata.durationMs) <= 2_000) server.id to copy.id else null
        }
    }

    suspend fun setFavorite(trackId: String, favorite: Boolean) = store.transaction {
        val track = requireNotNull(findTrack(trackId)) { "Track is missing" }
        store.put("track:$trackId", "tracks", track.copy(favorite = favorite))
    }

    suspend fun setRating(trackId: String, rating: Int) = store.transaction {
        require(rating in 0..5) { "Choose a rating from one to five stars, or zero to clear it" }
        val track = requireNotNull(findTrack(trackId)) { "Track is missing" }
        store.put("track:$trackId", "tracks", track.copy(rating = rating))
    }

    suspend fun findTrack(id: String): Track? = store.read("track:$id")
    internal suspend fun trackForSource(sourceId: String): Track? =
        store.read<SourceOwner>("source-owner:$sourceId")?.let { findTrack(it.trackId) }

    suspend fun linkTracks(keepId: String, otherId: String): Track = store.transaction {
        require(keepId != otherId) { "Choose another song to link" }
        val keep = requireNotNull(findTrack(keepId)) { "Track is missing" }
        val other = requireNotNull(findTrack(otherId)) { "Track is missing" }
        val fileEdits = store.list<LocalFileEdit>("file-edits").filter { it.trackId == keepId || it.trackId == otherId }
        check(fileEdits.none { it.writing }) { "Finish or recover the file edit before linking these songs" }
        val downloaded = remapDownloadedTrack(store, otherId, keepId)
        val sources = (keep.sources + other.sources).distinctBy { it.id }
        val linked = keep.copy(sources = sources.filterNot { it.kind == SourceKind.DOWNLOAD } +
            if (downloaded?.status == DownloadStatus.COMPLETE && downloaded.path != null)
                listOf(TrackSource("download:$keepId", SourceKind.DOWNLOAD, downloaded.path)) else emptyList(), favorite = keep.favorite || other.favorite,
            addedAt = minOf(keep.addedAt, other.addedAt), rating = keep.rating.takeIf { it > 0 } ?: other.rating)
        store.put("track:$keepId", "tracks", linked)
        linked.sources.forEach { source -> store.put("source-owner:${source.id}", "source-owners", SourceOwner(keepId)) }
        sources.filter { it.kind == SourceKind.DOWNLOAD && it.id != "download:$keepId" }.forEach {
            store.database.records().delete("source-owner:${it.id}")
        }
        remapQueueTracks(store, otherId, keepId)
        remapPlaylistTracks(store, otherId, keepId)
        remapListeningHistory(store, otherId, keepId)
        remapServerUploads(store, otherId, keepId)
        me.d3sox.nevumi.sync.remapPairedTracks(store, otherId, keepId)
        fileEdits.filter { it.trackId == otherId }.forEach { edit ->
            store.put("file-edit:${edit.id}", "file-edits", edit.copy(trackId = keepId))
        }
        store.database.records().delete("track:$otherId")
        linked
    }

    internal suspend fun reserveLocalMove(trackId: String, location: String) = store.transaction {
        requireNotNull(findTrack(trackId)) { "Track is missing" }
        val key = "source-owner:local:$location"
        val owner = store.read<SourceOwner>(key)
        check(owner == null || owner.trackId == trackId) { "Another song uses that file name" }
        store.put(key, "source-owners", SourceOwner(trackId))
    }

    internal suspend fun releaseLocalMove(location: String, trackId: String) = store.transaction {
        val key = "source-owner:local:$location"
        if (store.read<SourceOwner>(key)?.trackId == trackId) store.database.records().delete(key)
    }

    internal suspend fun relocateLocalSource(trackId: String, sourceId: String, location: String) = store.transaction {
        val track = requireNotNull(findTrack(trackId)) { "Track is missing" }
        val source = requireNotNull(track.sources.firstOrNull { it.kind == SourceKind.LOCAL && (it.id == sourceId || it.location == location) }) { "Local source is missing" }
        val moved = source.copy(id = "local:$location", location = location, available = true)
        store.put("track:$trackId", "tracks", track.copy(sources = track.sources.filterNot { it.id == sourceId || it.id == moved.id } + moved))
        if (sourceId != moved.id) store.database.records().delete("source-owner:$sourceId")
        store.put("source-owner:${moved.id}", "source-owners", SourceOwner(trackId))
    }

    internal suspend fun relocateServerFile(trackId: String, sourceId: String, serverId: String, libraryId: Long,
        from: String, to: String) = store.transaction {
        val track = requireNotNull(findTrack(trackId)) { "Track is missing" }
        val source = requireNotNull(track.sources.firstOrNull { it.kind == SourceKind.SERVER && it.id.startsWith("server:$serverId:") &&
            it.fileLibraryId == libraryId && (it.id == sourceId || it.filePath == from || it.filePath == to) }) { "Server source is missing" }
        store.put("track:$trackId", "tracks", track.copy(sources = track.sources.map {
            if (it.id == source.id) it.copy(filePath = to, available = true) else it
        }))
    }

    internal suspend fun setSourceAvailability(sourceIds: Set<String>, available: Boolean) = store.transaction {
        if (sourceIds.isEmpty()) return@transaction
        tracks().forEach { track ->
            val sources = track.sources.map { if (it.id in sourceIds) it.copy(available = available) else it }
            if (sources != track.sources) store.put("track:${track.id}", "tracks", track.copy(sources = sources))
        }
    }

    internal suspend fun removeSource(trackId: String, sourceId: String) = store.transaction {
        val track = findTrack(trackId) ?: return@transaction
        store.put("track:$trackId", "tracks", track.copy(sources = track.sources.filterNot { it.id == sourceId }))
        if (store.read<SourceOwner>("source-owner:$sourceId")?.trackId == trackId)
            store.database.records().delete("source-owner:$sourceId")
    }
}

@Serializable
private data class LibraryRoot(val location: String)

@Serializable
private data class SourceOwner(val trackId: String)

private val musicBrainzId = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
