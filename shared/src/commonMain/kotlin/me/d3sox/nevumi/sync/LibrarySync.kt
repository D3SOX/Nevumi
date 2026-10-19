package me.d3sox.nevumi.sync

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.library.AudioContentId
import me.d3sox.nevumi.library.FileAudioAccess
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.queue.SavedQueue
import me.d3sox.nevumi.queue.QueueEntry
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.queue.SavedQueueKind
import me.d3sox.nevumi.queue.QueueSection
import me.d3sox.nevumi.queue.QueueOrigin
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.history.Listen
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Serializable
data class SyncTrack(val key: String, val metadata: TrackMetadata, val serverSourceIds: List<String> = emptyList(),
    val deviceKeys: Map<String, String> = emptyMap(), val contentIds: List<AudioContentId> = emptyList())

@Serializable
data class SyncRevision(val counter: Long, val deviceId: String) : Comparable<SyncRevision> {
    override fun compareTo(other: SyncRevision): Int = counter.compareTo(other.counter).takeIf { it != 0 }
        ?: deviceId.compareTo(other.deviceId)
}

@Serializable
data class SyncFavorite(val trackKey: String, val favorite: Boolean, val revision: SyncRevision)

@Serializable
data class SyncRating(val trackKey: String, val rating: Int, val revision: SyncRevision)

@Serializable
data class SyncPlaylist(val id: String, val name: String, val trackKeys: List<String>, val rule: LibraryQuery?, val revision: SyncRevision,
    val folderToken: String? = null, val deleted: Boolean = false, val seen: Map<String, Long> = emptyMap(),
    val description: String = "", val shuffle: Boolean = false,
    val sort: me.d3sox.nevumi.playlists.PlaylistSort = me.d3sox.nevumi.playlists.PlaylistSort.CUSTOM,
    val descending: Boolean = false)

@Serializable
data class SyncSavedQueue(val id: String, val name: String, val trackKeys: List<String>, val repeatMode: RepeatMode,
    val repeatPlays: Int?, val revision: SyncRevision, val deleted: Boolean = false,
    val unshuffledIndices: List<Int>? = null, val albumShuffled: Boolean = false,
    val kind: SavedQueueKind = SavedQueueKind.LIVE, val capturedAt: Long = 0,
    val sections: List<QueueSection> = trackKeys.map { QueueSection.CONTEXT },
    val contextName: String = "Library", val origin: QueueOrigin? = null, val lastInsertedIndex: Int? = null)

@Serializable
data class SyncUnmatched(val peerDeviceId: String, val track: SyncTrack)

@Serializable
data class SyncFolderRequest(val peerDeviceId: String, val playlistId: String, val playlistName: String, val folderToken: String)

@Serializable
private data class TrackMapping(val peerDeviceId: String, val remoteTrackKey: String, val localTrackId: String)

@Serializable
private data class FolderMapping(val token: String, val folder: String)

internal suspend fun remapPairedTracks(store: MusicStore, from: String, to: String) {
    val track = requireNotNull(MusicLibrary(store).findTrack(to))
    val favorite = listOfNotNull(store.read<SyncFavorite>("sync:favorite:$from"), store.read<SyncFavorite>("sync:favorite:$to"))
        .filter { it.favorite == track.favorite }.maxByOrNull { it.revision }
    if (favorite != null) store.put("sync:favorite:$to", "sync-favorites", favorite.copy(trackKey = to))
    store.database.records().delete("sync:favorite:$from")
    val rating = listOfNotNull(store.read<SyncRating>("sync:rating:$from"), store.read<SyncRating>("sync:rating:$to"))
        .filter { it.rating == track.rating }.maxByOrNull { it.revision }
    if (rating != null) store.put("sync:rating:$to", "sync-ratings", rating.copy(trackKey = to))
    store.database.records().delete("sync:rating:$from")
    for (mapping in store.list<TrackMapping>("sync-mappings").filter { it.localTrackId == from })
        store.put("sync:mapping:${mapping.peerDeviceId}:${mapping.remoteTrackKey}", "sync-mappings", mapping.copy(localTrackId = to))
    for (playlist in store.list<SyncPlaylist>("sync-playlists").filter { from in it.trackKeys })
        store.put("sync:playlist:${playlist.id}", "sync-playlists", playlist.copy(trackKeys = playlist.trackKeys.map { if (it == from) to else it }))
    for (queue in store.list<SyncSavedQueue>("sync-saved-queues").filter { from in it.trackKeys })
        store.put("sync:saved-queue:${queue.id}", "sync-saved-queues", queue.copy(trackKeys = queue.trackKeys.map { if (it == from) to else it }))
}

@Serializable
data class SyncSnapshot(val version: Int = 1, val deviceId: String, val tracks: List<SyncTrack> = emptyList(),
    val favorites: List<SyncFavorite> = emptyList(), val ratings: List<SyncRating> = emptyList(), val listens: List<Listen> = emptyList(),
    val playlists: List<SyncPlaylist> = emptyList(), val savedQueues: List<SyncSavedQueue> = emptyList())

class LibrarySync(private val store: MusicStore, private val library: MusicLibrary,
    private val serverChanges: me.d3sox.nevumi.navidrome.NavidromeChanges? = null,
    private val audioContentId: (String) -> AudioContentId? = FileAudioAccess::contentId) {
    private val work = Mutex()
    val unmatchedSongs = store.database.records().observe("sync-unmatched").distinctUntilChanged().map { records ->
        records.map { store.json.decodeFromString<SyncUnmatched>(it.value) }
    }
    val folderRequests = store.database.records().observe("sync-pending-folders").distinctUntilChanged().map { records ->
        records.map { store.json.decodeFromString<SyncFolderRequest>(it.value) }
    }
    suspend fun pendingFolders(): List<SyncFolderRequest> = store.list("sync-pending-folders")
    suspend fun mapFolder(folderToken: String, localFolder: String) = work.withLock {
        val snapshots = store.list<SyncSnapshot>("sync-received").filter { it.playlists.any { playlist -> playlist.folderToken == folderToken } }
        require(snapshots.isNotEmpty()) { "The shared folder rule is missing" }
        require(library.tracks().any { it.metadata.folder == localFolder }) { "Choose a folder in this library" }
        store.put("sync:folder:$folderToken", "sync-folder-mappings", FolderMapping(folderToken, localFolder))
        for (snapshot in snapshots) mergeSnapshot(snapshot)
    }
    suspend fun unmatched(): List<SyncUnmatched> = store.list("sync-unmatched")
    suspend fun mapTrack(peerDeviceId: String, remoteTrackKey: String, localTrackId: String) = work.withLock {
        requireNotNull(library.findTrack(localTrackId)) { "Choose a song in this library" }
        val snapshot = requireNotNull(store.read<SyncSnapshot>("sync:received:$peerDeviceId")) { "The peer has not shared its library" }
        require(snapshot.tracks.any { it.key == remoteTrackKey }) { "The peer song is missing" }
        store.put("sync:mapping:$peerDeviceId:$remoteTrackKey", "sync-mappings", TrackMapping(peerDeviceId, remoteTrackKey, localTrackId))
        mergeSnapshot(snapshot)
    }
    suspend fun deviceId(): String = store.transaction {
        store.read<String>("sync:device-id") ?: Uuid.random().toString().also { store.put("sync:device-id", "sync-settings", it) }
    }
    /**
     * [listensSince] limits the shared history to listens that started, or arrived from another paired device, at or after that
     * time, plus listens still in progress; the first sync shares everything.
     */
    suspend fun snapshot(peerDeviceId: String? = null, additionalTracks: Set<String> = emptySet(), listensSince: Long? = null): SyncSnapshot = work.withLock {
        val snapshot = createSnapshot().let { full ->
            val shared = if (listensSince == null) full.listens
                else full.listens.filter { it.startedAt >= listensSince || (it.importedAt ?: 0) >= listensSince || !it.ended }
            full.copy(listens = shared.map { it.copy(importedAt = null) })
        }
        val favorites = snapshot.favorites.filter { it.revision.counter > 0 }
        val ratings = snapshot.ratings.filter { it.revision.counter > 0 }
        val references = (favorites.map { it.trackKey } + ratings.map { it.trackKey } + snapshot.listens.map { it.trackId } +
            snapshot.playlists.flatMap { it.trackKeys } + snapshot.savedQueues.flatMap { it.trackKeys }).toSet() + additionalTracks
        val mappings = store.list<TrackMapping>("sync-mappings").filter { it.peerDeviceId == peerDeviceId }.associateBy { it.localTrackId }
        val tracksById = library.tracks().associateBy { it.id }
        val contentIds = mutableMapOf<String, AudioContentId?>()
        snapshot.copy(tracks = snapshot.tracks.filter { it.key in references }.map { track ->
            track.copy(deviceKeys = mappings[track.key]?.let { mapOf(it.peerDeviceId to it.remoteTrackKey) }.orEmpty(),
                contentIds = if (mappings[track.key] == null && identity(track.metadata) == null)
                    contentIds(requireNotNull(tracksById[track.key]), contentIds) else emptyList())
        }, favorites = favorites, ratings = ratings)
    }

    suspend fun remapPlayback(peerDeviceId: String, playback: PlaybackTransfer): PlaybackTransfer = work.withLock {
        val mappings = store.list<TrackMapping>("sync-mappings").filter { it.peerDeviceId == peerDeviceId }.associateBy { it.remoteTrackKey }
        val entries = playback.queue.entries.mapIndexedNotNull { index, entry ->
            val localId = mappings[entry.trackId]?.localTrackId
            require(localId != null || (entry.id != playback.queue.currentEntryId &&
                (index < playback.queue.currentIndex || entry.section != me.d3sox.nevumi.queue.QueueSection.MANUAL))) {
                "The current song or a manually queued song is missing on this device. Sync and match your libraries first."
            }
            localId?.let { entry.copy(trackId = it) }
        }
        playback.withAvailableEntries(entries)
    }

    private suspend fun contentIds(track: Track, cache: MutableMap<String, AudioContentId?>): List<AudioContentId> = withContext(Dispatchers.IO) {
        track.sources.filter { it.kind != SourceKind.SERVER && it.available }.mapNotNull { source ->
            cache.getOrPut(source.location) { audioContentId(source.location) }
        }.distinct()
    }

    private suspend fun createSnapshot(): SyncSnapshot = store.transaction {
        val tracks = library.tracks()
        val device = deviceId()
        var counter = store.read<Long>("sync:clock") ?: 0
        val recorded = store.list<SyncFavorite>("sync-favorites").associateBy { it.trackKey }
        val favorites = tracks.map { track ->
            val previous = recorded[track.id]
            when {
                previous?.favorite == track.favorite -> previous
                previous == null && !track.favorite -> SyncFavorite(track.id, false, SyncRevision(0, device))
                else -> {
                    check(counter < Long.MAX_VALUE) { "Sync revision limit reached" }
                    SyncFavorite(track.id, track.favorite, SyncRevision(++counter, device)).also {
                        store.put("sync:favorite:${track.id}", "sync-favorites", it)
                    }
                }
            }
        }
        val recordedRatings = store.list<SyncRating>("sync-ratings").associateBy { it.trackKey }
        val ratings = tracks.map { track ->
            val previous = recordedRatings[track.id]
            when {
                previous?.rating == track.rating -> previous
                previous == null && track.rating == 0 -> SyncRating(track.id, 0, SyncRevision(0, device))
                else -> {
                    check(counter < Long.MAX_VALUE) { "Sync revision limit reached" }
                    SyncRating(track.id, track.rating, SyncRevision(++counter, device)).also {
                        store.put("sync:rating:${track.id}", "sync-ratings", it)
                    }
                }
            }
        }
        val recordedPlaylists = store.list<SyncPlaylist>("sync-playlists").associateBy { it.id }
        val currentPlaylists = store.list<Playlist>("playlists").filter { it.server == null }
        val playlists = currentPlaylists.map { playlist ->
            val previous = recordedPlaylists[playlist.id]
            val folderToken = playlist.rule?.folder?.let { folder ->
                previous?.folderToken?.takeIf { store.read<FolderMapping>("sync:folder:$it")?.folder == folder }
                    ?: Uuid.random().toString().also { store.put("sync:folder:$it", "sync-folder-mappings", FolderMapping(it, folder)) }
            }
            val payload = SyncPlaylist(playlist.id, playlist.name, if (playlist.rule == null) playlist.trackIds else emptyList(),
                playlist.rule?.copy(folder = null), SyncRevision(0, device), folderToken, seen = previous?.seen.orEmpty(),
                description = playlist.description, shuffle = playlist.shuffle, sort = playlist.sort, descending = playlist.descending)
            if (previous != null && previous.copy(revision = payload.revision) == payload) previous
            else {
                check(counter < Long.MAX_VALUE) { "Sync revision limit reached" }
                payload.copy(revision = SyncRevision(++counter, device), seen = previous?.let { observed(it.seen, it.revision) }.orEmpty()).also {
                    store.put("sync:playlist:${playlist.id}", "sync-playlists", it)
                }
            }
        } + recordedPlaylists.values.filter { previous -> currentPlaylists.none { it.id == previous.id } }.map { previous ->
            if (previous.deleted) previous else {
                check(counter < Long.MAX_VALUE) { "Sync revision limit reached" }
                previous.copy(name = "", trackKeys = emptyList(), rule = null, folderToken = null, deleted = true,
                    revision = SyncRevision(++counter, device), seen = observed(previous.seen, previous.revision)).also {
                    store.put("sync:playlist:${it.id}", "sync-playlists", it)
                }
            }
        }
        val recordedQueues = store.list<SyncSavedQueue>("sync-saved-queues").associateBy { it.id }
        val currentQueues = store.list<SavedQueue>("saved-queues").filter { it.kind != SavedQueueKind.RECOVERY }
        val savedQueues = currentQueues.map { queue ->
            val indices = queue.snapshot.entries.withIndex().associate { it.value.id to it.index }
            val payload = SyncSavedQueue(queue.id, queue.name, queue.snapshot.entries.map { it.trackId },
                queue.snapshot.repeatMode, queue.snapshot.repeatPlays, SyncRevision(0, device),
                unshuffledIndices = queue.snapshot.unshuffledEntryIds?.mapNotNull { indices[it] }, albumShuffled = queue.snapshot.albumShuffled,
                kind = queue.kind, capturedAt = queue.capturedAt, sections = queue.snapshot.entries.map { it.section },
                contextName = if (queue.snapshot.origin?.tab == LibraryTab.FOLDERS) "Library" else queue.snapshot.contextName,
                origin = queue.snapshot.origin?.takeIf { it.tab != LibraryTab.FOLDERS }, lastInsertedIndex = indices[queue.snapshot.lastInsertedEntryId])
            val previous = recordedQueues[queue.id]
            if (previous != null && previous.copy(revision = payload.revision) == payload) previous
            else {
                check(counter < Long.MAX_VALUE) { "Sync revision limit reached" }
                payload.copy(revision = SyncRevision(++counter, device)).also {
                    store.put("sync:saved-queue:${queue.id}", "sync-saved-queues", it)
                }
            }
        } + recordedQueues.values.filter { previous -> currentQueues.none { it.id == previous.id } }.map { previous ->
            if (previous.deleted) previous else {
                check(counter < Long.MAX_VALUE) { "Sync revision limit reached" }
                previous.copy(name = "", trackKeys = emptyList(), repeatMode = RepeatMode.OFF, repeatPlays = null, deleted = true,
                    unshuffledIndices = null, albumShuffled = false, sections = emptyList(), contextName = "Library", origin = null, lastInsertedIndex = null,
                    revision = SyncRevision(++counter, device)).also {
                    store.put("sync:saved-queue:${it.id}", "sync-saved-queues", it)
                }
            }
        }
        store.put("sync:clock", "sync-settings", counter)
        SyncSnapshot(deviceId = device, tracks = tracks.map { track ->
            SyncTrack(track.id, track.metadata.copy(folder = ""), track.sources.filter { it.kind == SourceKind.SERVER }.map { it.id }.sorted())
        }, favorites = favorites, ratings = ratings, playlists = playlists, savedQueues = savedQueues, listens = store.list<Listen>("listens").map {
            it.copy(metadata = it.metadata.copy(folder = ""), originDeviceId = it.originDeviceId ?: device)
        })
    }

    suspend fun merge(snapshot: SyncSnapshot) = work.withLock { mergeSnapshot(snapshot) }

    private suspend fun mergeSnapshot(incoming: SyncSnapshot) {
        require(incoming.version == 1) { "Unsupported sync protocol" }
        require(incoming.deviceId != deviceId()) { "Cannot synchronize this device with itself" }
        val retained = store.read<SyncSnapshot>("sync:received:${incoming.deviceId}")
        val merged = incoming.copy(
            tracks = (incoming.tracks + retained?.tracks.orEmpty()).distinctBy { it.key },
            favorites = (retained?.favorites.orEmpty() + incoming.favorites).groupBy { it.trackKey }.values.map { versions ->
                versions.maxBy { it.revision }
            },
            ratings = (retained?.ratings.orEmpty() + incoming.ratings).groupBy { it.trackKey }.values.map { versions ->
                versions.maxBy { it.revision }
            },
            playlists = (retained?.playlists.orEmpty() + incoming.playlists).groupBy { it.id }.values.map { versions ->
                versions.maxBy { it.revision }
            },
            savedQueues = (retained?.savedQueues.orEmpty() + incoming.savedQueues).groupBy { it.id }.values.map { versions ->
                versions.maxBy { it.revision }
            },
            listens = (retained?.listens.orEmpty() + incoming.listens).groupBy { it.id }.values.map { versions ->
                versions.reduce { previous, listen -> previous.copy(listenedMs = maxOf(previous.listenedMs, listen.listenedMs),
                    playedMs = maxOf(previous.playedMs, listen.playedMs), ended = previous.ended || listen.ended) }
            })
        // Songs shared only for a playback transfer are dropped once nothing synced refers to them.
        val references = (merged.favorites.map { it.trackKey } + merged.ratings.map { it.trackKey } + merged.listens.map { it.trackId } +
            merged.playlists.flatMap { it.trackKeys } + merged.savedQueues.flatMap { it.trackKeys }).toSet() + incoming.tracks.map { it.key }
        val snapshot = merged.copy(tracks = merged.tracks.filter { it.key in references })
        val tracks = library.tracks()
        val ownDeviceId = deviceId()
        val tracksById = tracks.associateBy { it.id }
        val mappings = store.list<TrackMapping>("sync-mappings").filter { it.peerDeviceId == snapshot.deviceId }.associateBy { it.remoteTrackKey }
        val serverTracks = tracks.flatMap { track -> track.sources.filter { it.kind == SourceKind.SERVER }.map { it.id to track } }
            .groupBy({ it.first }, { it.second })
        val identifiedTracks = tracks.mapNotNull { track -> identity(track.metadata)?.let { it to track } }
            .groupBy({ it.first }, { it.second })
        val contentIds = mutableMapOf<String, AudioContentId?>()
        val matched = snapshot.tracks.associate { remote ->
            val serverMatches = remote.serverSourceIds.flatMap { serverTracks[it].orEmpty() }.distinctBy { it.id }
            val match = when {
                mappings[remote.key] != null -> tracksById[mappings.getValue(remote.key).localTrackId]
                remote.deviceKeys[ownDeviceId] != null -> tracksById[remote.deviceKeys.getValue(ownDeviceId)]
                serverMatches.size == 1 -> serverMatches.single()
                serverMatches.isNotEmpty() -> null
                else -> identity(remote.metadata)?.let { identifiedTracks[it] }.orEmpty()
                    .filter { abs(it.metadata.durationMs - remote.metadata.durationMs) <= 2_000 }.singleOrNull()
                    ?: if (remote.contentIds.isEmpty()) null else tracks.filter { track ->
                        abs(track.metadata.durationMs - remote.metadata.durationMs) <= 2_000 &&
                            track.sources.any { source -> source.kind != SourceKind.SERVER && source.available &&
                                (source.indexedSize == 0L || remote.contentIds.any { it.size == source.indexedSize }) } &&
                            contentIds(track, contentIds).any { it in remote.contentIds }
                    }.singleOrNull()
            }
            remote.key to match?.id
        }
        store.transaction {
            val local = createSnapshot()
            val favorites = local.favorites.associateBy { it.trackKey }.toMutableMap()
            val ratings = local.ratings.associateBy { it.trackKey }.toMutableMap()
            val playlists = local.playlists.associateBy { it.id }.toMutableMap()
            val savedQueues = local.savedQueues.associateBy { it.id }.toMutableMap()
            val revisions = snapshot.favorites.map { it.revision } + snapshot.ratings.map { it.revision } + snapshot.playlists.map { it.revision } +
                snapshot.savedQueues.map { it.revision }
            require(revisions.all { it.counter in 0 until Long.MAX_VALUE && it.deviceId.isNotBlank() } &&
                snapshot.ratings.all { it.rating in 0..5 }) {
                "Invalid sync revision"
            }
            val counter = maxOf(store.read<Long>("sync:clock") ?: 0, revisions.maxOfOrNull { it.counter } ?: 0)
            store.put("sync:clock", "sync-settings", counter)
            val suppressedListens = store.list<String>("listen-suppressed").toSet()
            val sharedListens = snapshot.listens.filter { it.id !in suppressedListens }
            // Matched listens are imported below; only listens waiting for a song match need to be kept, so the record stays bounded.
            store.put("sync:received:${snapshot.deviceId}", "sync-received", snapshot.copy(
                tracks = snapshot.tracks.map { it.copy(metadata = it.metadata.copy(folder = "")) },
                listens = sharedListens.filter { matched[it.trackId] == null }.map { it.copy(metadata = it.metadata.copy(folder = "")) }))
            for (previous in unmatched().filter { it.peerDeviceId == snapshot.deviceId })
                store.database.records().delete("sync:unmatched:${snapshot.deviceId}:${previous.track.key}")
            for (previous in pendingFolders().filter { it.peerDeviceId == snapshot.deviceId })
                store.database.records().delete("sync:pending-folder:${snapshot.deviceId}:${previous.playlistId}")
            for (remote in snapshot.tracks.filter { matched[it.key] == null })
                store.put("sync:unmatched:${snapshot.deviceId}:${remote.key}", "sync-unmatched",
                    SyncUnmatched(snapshot.deviceId, remote.copy(metadata = remote.metadata.copy(folder = ""))))
            for (remote in snapshot.tracks) matched[remote.key]?.let { localId ->
                store.put("sync:mapping:${snapshot.deviceId}:${remote.key}", "sync-mappings", TrackMapping(snapshot.deviceId, remote.key, localId))
            }
            for (favorite in snapshot.favorites) matched[favorite.trackKey]?.let { localId ->
                val previous = favorites[localId]
                if (previous != null && favorite.revision > previous.revision) {
                    if (previous.favorite != favorite.favorite) {
                        if (serverChanges != null) serverChanges.setFavorite(localId, favorite.favorite)
                        else library.setFavorite(localId, favorite.favorite)
                    }
                    val applied = favorite.copy(trackKey = localId)
                    store.put("sync:favorite:$localId", "sync-favorites", applied)
                    favorites[localId] = applied
                }
            }
            for (rating in snapshot.ratings) matched[rating.trackKey]?.let { localId ->
                val previous = ratings[localId]
                if (previous != null && rating.revision > previous.revision) {
                    if (previous.rating != rating.rating) {
                        if (serverChanges != null) serverChanges.setRating(localId, rating.rating)
                        else library.setRating(localId, rating.rating)
                    }
                    val applied = rating.copy(trackKey = localId)
                    store.put("sync:rating:$localId", "sync-ratings", applied)
                    ratings[localId] = applied
                }
            }
            for (listen in sharedListens) matched[listen.trackId]?.let { localId ->
                require(listen.listenedMs >= 0 && listen.playedMs >= 0 && listen.durationMs >= 0 && listen.startedAt >= 0) {
                    "Invalid listening history"
                }
                val previous = store.read<Listen>("listen:${listen.id}")
                val imported = if (previous == null) listen.copy(trackId = localId, metadata = listen.metadata.copy(folder = ""),
                    originDeviceId = listen.originDeviceId ?: snapshot.deviceId, importedAt = kotlin.time.Clock.System.now().toEpochMilliseconds())
                else previous.copy(listenedMs = maxOf(previous.listenedMs, listen.listenedMs),
                    playedMs = maxOf(previous.playedMs, listen.playedMs), ended = previous.ended || listen.ended).let { advanced ->
                        // Progress relayed from the origin must reach other peers too, so it counts as newly arrived.
                        if (advanced == previous) previous else advanced.copy(importedAt = kotlin.time.Clock.System.now().toEpochMilliseconds())
                    }
                if (imported != previous) store.put("listen:${listen.id}", "listens", imported)
            }
            suspend fun applyPlaylist(playlist: SyncPlaylist, localTrackKeys: List<String>): Boolean {
                val existing = store.read<Playlist>("playlist:${playlist.id}")
                if (existing?.server != null) return false
                if (playlist.deleted) {
                    store.database.records().delete("playlist:${playlist.id}")
                    store.put("sync:playlist:${playlist.id}", "sync-playlists", playlist)
                    playlists[playlist.id] = playlist
                    return true
                }
                val folder = playlist.folderToken?.let { store.read<FolderMapping>("sync:folder:$it")?.folder }
                if (playlist.folderToken != null && folder == null) {
                    store.put("sync:pending-folder:${snapshot.deviceId}:${playlist.id}", "sync-pending-folders",
                        SyncFolderRequest(snapshot.deviceId, playlist.id, playlist.name, playlist.folderToken))
                    return false
                }
                val localPlaylist = Playlist(playlist.id, playlist.name, localTrackKeys,
                    autoDownload = existing?.autoDownload ?: false, rule = playlist.rule?.copy(folder = folder),
                    description = playlist.description, shuffle = playlist.shuffle, sort = playlist.sort, descending = playlist.descending).let { local ->
                        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
                        val changed = existing == null || existing.name != local.name || existing.trackIds != local.trackIds ||
                            existing.rule != local.rule || existing.description != local.description
                        local.copy(createdAt = existing?.createdAt ?: now, updatedAt = if (changed) now else existing?.updatedAt ?: now,
                            playedAt = existing?.playedAt ?: 0)
                    }
                store.put("playlist:${playlist.id}", "playlists", localPlaylist)
                val applied = playlist.copy(trackKeys = if (playlist.rule == null) localPlaylist.trackIds else emptyList())
                store.put("sync:playlist:${playlist.id}", "sync-playlists", applied)
                playlists[playlist.id] = applied
                return true
            }
            for (playlist in snapshot.playlists) {
                val previous = playlists[playlist.id]
                if (previous?.revision == playlist.revision) continue
                val mappedTracks = playlist.trackKeys.map { matched[it] }
                if (mappedTracks.any { it == null } || playlist.rule?.folder != null) continue
                val concurrent = previous != null && previous.revision.deviceId != playlist.revision.deviceId &&
                    (previous.seen[playlist.revision.deviceId] ?: 0) < playlist.revision.counter &&
                    (playlist.seen[previous.revision.deviceId] ?: 0) < previous.revision.counter
                if (concurrent) {
                    val loser = if (playlist.revision > previous!!.revision) previous else playlist
                    val localKeys = if (loser === previous) previous.trackKeys else mappedTracks.filterNotNull()
                    if (!loser.deleted) applyPlaylist(loser.copy(
                        id = "${playlist.id}-conflict-${loser.revision.deviceId}-${loser.revision.counter}",
                        name = "${loser.name} (conflict copy)"), localKeys)
                }
                if (previous == null || playlist.revision > previous.revision) {
                    val winner = if (concurrent) playlist.copy(seen = observed(playlist.seen, previous!!.revision)) else playlist
                    applyPlaylist(winner, mappedTracks.filterNotNull())
                } else if (concurrent) {
                    val winner = previous.copy(seen = observed(previous.seen, playlist.revision))
                    store.put("sync:playlist:${winner.id}", "sync-playlists", winner)
                    playlists[winner.id] = winner
                }
            }
            for (queue in snapshot.savedQueues) {
                require(queue.kind != SavedQueueKind.RECOVERY && queue.capturedAt >= 0 && queue.sections.size == queue.trackKeys.size &&
                    (queue.lastInsertedIndex == null || queue.lastInsertedIndex in queue.trackKeys.indices)) { "Invalid shared queue snapshot" }
                val previous = savedQueues[queue.id]
                if (previous != null && queue.revision <= previous.revision) continue
                if (queue.deleted) {
                    store.database.records().delete("saved-queue:${queue.id}")
                    store.put("sync:saved-queue:${queue.id}", "sync-saved-queues", queue)
                    savedQueues[queue.id] = queue
                    continue
                }
                val mappedTracks = queue.trackKeys.map { matched[it] }
                if (mappedTracks.any { it == null }) continue
                require(queue.repeatPlays == null || queue.repeatMode == RepeatMode.ONE && queue.repeatPlays > 0) {
                    "Invalid queue repeat count"
                }
                require((!queue.albumShuffled || queue.unshuffledIndices != null) && queue.unshuffledIndices?.let { indices ->
                    indices.size == queue.trackKeys.size && indices.toSet() == queue.trackKeys.indices.toSet()
                } != false) { "Invalid saved queue shuffle order" }
                val existing = store.read<SavedQueue>("saved-queue:${queue.id}")?.snapshot
                val occurrences = existing?.entries.orEmpty().groupBy { it.trackId }.mapValues { it.value.toMutableList() }
                val entries = mappedTracks.filterNotNull().mapIndexed { index, trackId ->
                    (occurrences[trackId]?.takeIf { it.isNotEmpty() }?.removeAt(0) ?: QueueEntry(Uuid.random().toString(), trackId))
                        .copy(section = queue.sections[index])
                }
                val retainedCurrent = existing?.currentEntryId?.takeIf { id -> entries.any { it.id == id } }
                val localQueue = SavedQueue(queue.id, queue.name, QueueSnapshot(entries, currentEntryId = retainedCurrent ?: entries.firstOrNull()?.id,
                    positionMs = if (retainedCurrent != null) existing.positionMs else 0,
                    repeatMode = queue.repeatMode, repeatPlays = queue.repeatPlays,
                    unshuffledEntryIds = queue.unshuffledIndices?.map { entries[it].id }, albumShuffled = queue.albumShuffled,
                    contextName = queue.contextName, origin = queue.origin?.takeIf { it.tab != LibraryTab.FOLDERS },
                    lastInsertedEntryId = queue.lastInsertedIndex?.let { entries[it].id }), kind = queue.kind, capturedAt = queue.capturedAt)
                store.put("saved-queue:${queue.id}", "saved-queues", localQueue)
                for (record in store.database.records().list("queues")) {
                    val active = store.json.decodeFromString<QueueSnapshot>(record.value)
                    if (active.selectedSavedQueueId == queue.id) store.put(record.key, "queues",
                        localQueue.snapshot.copy(selectedSavedQueueId = queue.id.takeIf { queue.kind == SavedQueueKind.LIVE }))
                }
                val applied = queue.copy(trackKeys = entries.map { it.trackId })
                store.put("sync:saved-queue:${queue.id}", "sync-saved-queues", applied)
                savedQueues[queue.id] = applied
            }
        }
    }

    private fun identity(metadata: TrackMetadata): String? {
        val identifier = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        if (!metadata.recordingId.matches(identifier) || !metadata.releaseId.matches(identifier) || metadata.trackNumber <= 0 ||
            metadata.discNumber <= 0 || metadata.durationMs <= 0) return null
        return listOf(metadata.recordingId.lowercase(), metadata.releaseId.lowercase(), metadata.discNumber, metadata.trackNumber).joinToString(":")
    }

    private fun observed(seen: Map<String, Long>, revision: SyncRevision): Map<String, Long> =
        seen + (revision.deviceId to maxOf(seen[revision.deviceId] ?: 0, revision.counter))
}
