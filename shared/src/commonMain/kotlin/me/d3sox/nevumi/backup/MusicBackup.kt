package me.d3sox.nevumi.backup

import kotlinx.serialization.SerializationException
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.listeningStatistics
import me.d3sox.nevumi.library.filterTracks
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.history.ScrobblingService
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.settings.HomeSectionId
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.ServerProfile
import me.d3sox.nevumi.playback.AutoEqProfiles
import me.d3sox.nevumi.playlists.PlaylistSort
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.SavedQueueKind
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.sftp.SftpConfiguration
import me.d3sox.nevumi.soundcloud.SoundCloudDownloads
import me.d3sox.nevumi.soundcloud.SoundCloudProfile
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.sync.LibrarySync
import kotlin.math.abs
import kotlin.time.Clock

/**
 * Writes the user's settings, playlists, saved queues, listening history, song flags and service profiles to one JSON
 * document and merges such a document back. A restore only adds and updates: it never removes anything.
 */
class MusicBackup(private val store: MusicStore, private val library: MusicLibrary = MusicLibrary(store),
    private val serverChanges: NavidromeChanges? = null, private val scrobbling: Scrobbling? = null,
    private val soundCloud: SoundCloudDownloads? = null, private val equalizer: AutoEqProfiles? = null,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val preferences = MusicPreferences(store)
    private val playlists = Playlists(store)
    private val queue = PlaybackQueue(store)
    private val history = ListeningHistory(store, library)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val lastExportedAt = store.database.records().observeRevision("backup-metadata").map { store.read<Long>("backup:last-exported-at") }

    internal suspend fun recordSuccessfulExport() {
        store.put("backup:last-exported-at", "backup-metadata", clock())
    }

    suspend fun export(appVersion: String = "", sections: Set<BackupSection> = BackupSection.entries.toSet()): String {
        require(sections.isNotEmpty()) { "Choose at least one backup section" }
        val tracks = library.tracks().associateBy { it.id }
        // Android names local folders relative to its storage, like "Music/Sky", which looks like a server folder; a folder that
        // holds a song from this device is local however it is written.
        // A folder that also holds server songs is the server's, and stays valid on another device connected to it.
        fun folders(kind: SourceKind) = tracks.values.filter { track -> track.sources.any { it.kind == kind } }
            .mapNotNullTo(HashSet()) { it.metadata.folder.ifBlank { null } }
        val localFolders = folders(SourceKind.LOCAL) - folders(SourceKind.SERVER)
        fun isDeviceFolder(folder: String) = isDevicePath(folder) || folder in localFolders
        val storedPlaylists = playlists.all()
        // Playlist IDs differ on every device, so a custom Playlists order names each playlist by its place in the backup.
        val playlistPositions = storedPlaylists.withIndex().associate { it.value.id to "$BACKUP_PLAYLIST${it.index}" }
        // Folder rules name paths and permissions on this device, and scroll positions are transient.
        val settings = if (BackupSection.SETTINGS in sections) preferences.load().let { it.copy(includeFolders = emptyList(), excludeFolders = emptyList(), libraryPositions = emptyMap(),
            libraryFilter = it.libraryFilter.copy(folder = it.libraryFilter.folder?.takeUnless(::isDeviceFolder)),
            // A custom Folders order names local folders by their paths.
            collectionLayouts = it.collectionLayouts.mapValues { (tab, layout) ->
                val order = if (tab == LibraryTab.PLAYLISTS && BackupSection.PLAYLISTS !in sections) emptyList()
                    else layout.order.filterNot(::isDeviceFolder)
                        .map { id -> if (tab == LibraryTab.PLAYLISTS) playlistPositions[id] ?: id else id }
                layout.copy(order = order)
            }) }
            else null
        val listened = if (BackupSection.LISTENING_HISTORY in sections || BackupSection.PLAYLISTS in sections) history.all() else emptyList()
        val playlists = if (BackupSection.PLAYLISTS in sections) storedPlaylists.map { playlist ->
            val rule = playlist.rule
            // A rule on a local folder names a path on this device and matches nothing elsewhere, so it is kept as its current songs.
            if (rule?.folder?.let(::isDeviceFolder) == true) BackupPlaylist(playlist.name,
                filterTracks(tracks.values.toList(), rule, listeningStatistics(listened)).map { it.id }, null,
                playlist.description, playlist.shuffle, playlist.sort, playlist.descending)
            else BackupPlaylist(playlist.name, if (rule == null) playlist.trackIds else emptyList(), rule,
                playlist.description, playlist.shuffle, playlist.sort, playlist.descending)
        } else emptyList()
        val savedQueues = if (BackupSection.SAVED_QUEUES in sections) queue.saved().filter { it.kind != SavedQueueKind.RECOVERY }.map { saved ->
            // A queue played from a local folder is named after its path, which device sync also replaces.
            val folder = saved.snapshot.origin?.tab == LibraryTab.FOLDERS || isDeviceFolder(saved.snapshot.contextName)
            BackupSavedQueue(saved.name, saved.snapshot.copy(selectedSavedQueueId = null, origin = null,
                contextName = if (folder) "Library" else saved.snapshot.contextName), saved.kind, saved.capturedAt)
        } else emptyList()
        val listens = if (BackupSection.LISTENING_HISTORY in sections) listened.map { it.copy(importedAt = null, metadata = it.metadata.copy(folder = "")) } else emptyList()
        val referenced = (tracks.values.filter { (BackupSection.FAVORITES in sections && it.favorite) ||
            (BackupSection.RATINGS in sections && it.rating != 0) }.map { it.id } + playlists.flatMap { it.trackKeys } +
            savedQueues.flatMap { saved -> saved.snapshot.entries.map { it.trackId } } + listens.map { it.trackId }).toSet()
        val profiles = BackupSection.SERVICE_PROFILES in sections
        val document = BackupDocument(createdAt = clock(), appVersion = appVersion, settings = settings,
            tracks = referenced.mapNotNull { tracks[it] }.associate { it.id to it.toBackup().copy(
                favorite = BackupSection.FAVORITES in sections && it.favorite, rating = if (BackupSection.RATINGS in sections) it.rating else 0) },
            playlists = playlists, savedQueues = savedQueues, listens = listens,
            scrobblingProfiles = if (profiles) scrobbling?.profiles().orEmpty() else emptyList(),
            server = if (profiles) store.read<ServerProfile>("server:navidrome") else null,
            sftpProfiles = if (profiles) store.list<SftpConfiguration>("sftp-profiles") else emptyList(),
            // A viewer address can carry a password; it is fetched from sc-gate-dl again on first use.
            soundCloud = if (profiles) store.read<SoundCloudProfile>("soundcloud:profile")?.copy(browserViewUrl = null) else null,
            equalizerProfiles = if (BackupSection.EQUALIZER_PROFILES in sections) equalizer?.saved().orEmpty() else emptyList())
        return json.encodeToString(document)
    }

    suspend fun restore(text: String): RestoreSummary {
        val document = decode(text)
        val matches = TrackMatcher(library.tracks())
        val local = document.tracks.mapNotNull { (key, track) -> matches.find(track)?.let { key to it } }.toMap()
        // A song missing here may be in several playlists, queues and listens; the summary counts it once.
        val unmatched = mutableSetOf<String>()
        fun localId(key: String): String? = local[key] ?: null.also { unmatched += key }

        val settings = document.settings?.let { restored ->
            // A backup from an older version may lack newer Home sections or sources; keep its order and add the missing ones.
            preferences.update { current -> restored.copy(includeFolders = current.includeFolders, excludeFolders = current.excludeFolders,
                libraryPositions = current.libraryPositions,
                homeSections = restored.homeSections.distinct() + HomeSectionId.entries.filterNot { it in restored.homeSections },
                sourcePriority = restored.sourcePriority.distinct() + SourceKind.entries.filterNot { it in restored.sourcePriority }) }
        } != null

        // The backup wins for the flags it sets; songs it leaves unflagged keep theirs.
        val current = library.tracks().associateBy { it.id }
        val favorites = mutableMapOf<String, Boolean>()
        val ratings = mutableMapOf<String, Int>()
        for ((key, track) in document.tracks.filter { it.value.favorite || it.value.rating != 0 }) {
            val id = localId(key) ?: continue
            val existing = current[id] ?: continue
            if (track.favorite && !existing.favorite) favorites[id] = true
            if (track.rating in 1..5 && track.rating != existing.rating) ratings[id] = track.rating
        }
        if (serverChanges != null) serverChanges.setFavorites(favorites) else library.setFavorites(favorites)
        for ((id, rating) in ratings) if (serverChanges != null) serverChanges.setRating(id, rating) else library.setRating(id, rating)

        var playlistsAdded = 0
        var playlistsMerged = 0
        val existingPlaylists = playlists.all()
        // Each compatible playlist already here stands in for one backed-up playlist of its name, so same-named playlists stay separate.
        // A smart playlist is compatible with the same rule. A server playlist is only compatible when it already has every song,
        // because adding to it would push songs removed since the backup back to the server. Anything else is restored on its own.
        val matched = mutableSetOf<String>()
        val restoredPlaylists = mutableMapOf<String, String>()
        for ((index, backup) in document.playlists.withIndex()) {
            val name = backup.name.trim().takeIf { it.isNotEmpty() } ?: continue
            val songs = if (backup.rule == null) backup.trackKeys.mapNotNull(::localId) else emptyList()
            // Among several candidates, the one with the same description and then the most shared songs is the same playlist.
            val existing = existingPlaylists.filter { playlist ->
                playlist.id !in matched && playlist.name.trim() == name && when {
                    backup.rule != null -> playlist.rule == backup.rule
                    playlist.rule != null -> false
                    playlist.server != null -> playlist.trackIds.containsAll(songs)
                    else -> true
                }
            }.maxWithOrNull(compareBy<me.d3sox.nevumi.playlists.Playlist> { it.description == backup.description }
                .thenBy { candidate -> songs.count { it in candidate.trackIds } })
            if (existing != null) {
                matched += existing.id
                restoredPlaylists["$BACKUP_PLAYLIST$index"] = existing.id
                if (existing.server != null) continue
                // The backup wins for the description, shuffle and order it sets, as it does for a playlist it creates.
                var changed = false
                if (backup.description.isNotBlank() && backup.description != existing.description) {
                    playlists.edit(existing.id, existing.name, backup.description); changed = true
                }
                if (backup.shuffle != existing.shuffle) { playlists.setShuffle(existing.id, backup.shuffle); changed = true }
                if (backup.sort != existing.sort || backup.descending != existing.descending) {
                    playlists.setSort(existing.id, backup.sort, backup.descending); changed = true
                }
                // Playlists may hold a song more than once, so only the occurrences the playlist lacks are added.
                val present = existing.trackIds.groupingBy { it }.eachCount().toMutableMap()
                val additions = songs.filter { id -> (present[id] ?: 0).let { count -> if (count > 0) { present[id] = count - 1; false } else true } }
                if (backup.rule == null && additions.isNotEmpty()) {
                    playlists.append(existing.id, additions); changed = true
                }
                if (changed) playlistsMerged++
                continue
            }
            val created = if (backup.rule != null) playlists.createSmart(name, backup.rule) else playlists.create(name, songs)
            if (backup.description.isNotBlank()) playlists.edit(created.id, name, backup.description)
            if (backup.shuffle) playlists.setShuffle(created.id, true)
            if (backup.sort != PlaylistSort.CUSTOM || backup.descending) playlists.setSort(created.id, backup.sort, backup.descending)
            restoredPlaylists["$BACKUP_PLAYLIST$index"] = created.id
            playlistsAdded++
        }
        // The restored custom Playlists order named playlists by their place in the backup; point it at the playlists here.
        if (settings) preferences.update { restored ->
            val layout = restored.collectionLayouts[LibraryTab.PLAYLISTS] ?: return@update restored
            restored.copy(collectionLayouts = restored.collectionLayouts + (LibraryTab.PLAYLISTS to layout.copy(order = layout.order.mapNotNull { id ->
                if (id.startsWith(BACKUP_PLAYLIST)) restoredPlaylists[id] else id
            })))
        }

        var savedQueues = 0
        val existingQueues = queue.saved()
        for (backup in document.savedQueues) {
            val entries = backup.snapshot.entries.mapNotNull { entry -> localId(entry.trackId)?.let { entry.copy(trackId = it) } }
            // An edited or damaged file may repeat entry IDs, which the queue screens use as keys.
            if (entries.isEmpty() || backup.name.isBlank() || entries.distinctBy { it.id }.size != entries.size) continue
            val ids = entries.map { it.id }.toSet()
            val snapshot = backup.snapshot
            val currentKept = snapshot.currentEntryId in ids
            val restored = snapshot.copy(entries = entries,
                currentEntryId = if (currentKept) snapshot.currentEntryId else entries.first().id,
                positionMs = if (currentKept) snapshot.positionMs else 0,
                lastInsertedEntryId = snapshot.lastInsertedEntryId?.takeIf { it in ids },
                unshuffledEntryIds = snapshot.unshuffledEntryIds?.filter { it in ids }, origin = null, selectedSavedQueueId = null)
            val kind = if (backup.kind == SavedQueueKind.RECOVERY) SavedQueueKind.SNAPSHOT else backup.kind
            // Only an identical saved queue counts as restored; its position, sections and order matter as much as its songs.
            if (existingQueues.any { saved -> saved.name == backup.name.trim() && saved.kind == kind &&
                    saved.snapshot.copy(origin = null, selectedSavedQueueId = null) == restored }) continue
            queue.addSaved(backup.name, restored, kind, backup.capturedAt)
            savedQueues++
        }

        // Listens keep their song's tags, so songs no longer in the backup's library can still be matched.
        val listens = history.import(document.listens.mapNotNull { listen ->
            (local[listen.trackId] ?: matches.find(listen.metadata.toBackup()) ?: null.also { unmatched += listen.trackId })
                // An origin keeps restored listens from being scrobbled again; the import time shares them with paired devices.
                ?.let { listen.copy(trackId = it, originDeviceId = listen.originDeviceId ?: BACKUP_ORIGIN, importedAt = clock()) }
        })

        val equalizerProfiles = equalizer?.let { profiles -> document.equalizerProfiles.count { profiles.restore(it) } } ?: 0

        val signIn = mutableListOf<SignInRequired>()
        var scrobblingProfiles = 0
        val existingScrobbling = scrobbling?.profiles().orEmpty().associateBy { it.id }
        for (profile in document.scrobblingProfiles) {
            val existing = existingScrobbling[profile.id]
            if (existing != null) {
                if (existing.enabled != profile.enabled) { scrobbling?.setEnabled(profile.id, profile.enabled); scrobblingProfiles++ }
            } else when (profile.service) {
                // Created again when the music server is connected.
                ScrobblingService.NAVIDROME -> Unit
                ScrobblingService.LISTENBRAINZ -> signIn += SignInRequired(BackupService.LISTENBRAINZ, profile.username)
                ScrobblingService.LASTFM -> signIn += SignInRequired(BackupService.LASTFM, profile.username)
            }
        }
        document.server?.takeIf { it != store.read<ServerProfile>("server:navidrome") }?.let {
            signIn += SignInRequired(BackupService.MUSIC_SERVER, "${it.username} · ${it.baseUrl}")
        }
        val sftp = store.list<SftpConfiguration>("sftp-profiles").map { it.id }.toSet()
        document.sftpProfiles.filter { it.id !in sftp }.forEach {
            signIn += SignInRequired(BackupService.SFTP, "${it.connection.username}@${it.connection.host}:${it.connection.port}")
        }
        val soundCloud = document.soundCloud?.let { soundCloud?.restore(it) } == true

        return RestoreSummary(settings, favorites.size, ratings.size, playlistsAdded, playlistsMerged, savedQueues, listens,
            equalizerProfiles, scrobblingProfiles, soundCloud, unmatched.size, signIn)
    }

    private fun decode(text: String): BackupDocument {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (_: SerializationException) { null }
            ?: throw BackupFormatException()
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != BACKUP_FORMAT) throw BackupFormatException()
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw BackupFormatException()
        if (version != BACKUP_VERSION) throw BackupFormatException(version)
        return try { json.decodeFromJsonElement<BackupDocument>(root) }
        catch (_: SerializationException) { throw BackupFormatException() }
        catch (_: IllegalArgumentException) { throw BackupFormatException() }
    }
}

/** Prefixes a playlist's place in the backup inside a backed-up custom Playlists order. */
private const val BACKUP_PLAYLIST = "backup-playlist:"

/** The origin of listens restored from a backup, which were already scrobbled where they were played. */
private const val BACKUP_ORIGIN = "backup"

/** Local folders are absolute paths, Windows drive or network share paths, or content URIs; server folders are relative to the music library. */
private fun isDevicePath(folder: String) = folder.startsWith("/") || folder.startsWith("\\\\") || "://" in folder ||
    Regex("^[A-Za-z]:").containsMatchIn(folder)

private fun Track.toBackup() = metadata.toBackup().copy(serverSources = sources.filter { it.kind == SourceKind.SERVER }.map { it.id },
    favorite = favorite, rating = rating)

private fun TrackMetadata.toBackup() = BackupTrack(title, artist, album, durationMs, LibrarySync.identity(this))

/** Finds a backup's song in this library by its MusicBrainz identity, then its server song, then its tags and length. */
private class TrackMatcher(tracks: List<Track>) {
    private val identities = tracks.mapNotNull { track -> LibrarySync.identity(track.metadata)?.let { it to track } }.groupBy({ it.first }, { it.second })
    private val servers = tracks.flatMap { track -> track.sources.filter { it.kind == SourceKind.SERVER }.map { it.id to track } }
        .groupBy({ it.first }, { it.second })
    private val tags = tracks.groupBy { tagKey(it.metadata.title, it.metadata.artist, it.metadata.album) }

    fun find(track: BackupTrack): String? {
        fun List<Track>.closest() = filter { it.metadata.durationMs <= 0 || track.durationMs <= 0 ||
            abs(it.metadata.durationMs - track.durationMs) <= 2_000 }.distinctBy { it.id }.singleOrNull()
        return (track.identity?.let { identities[it] }?.closest()
            ?: track.serverSources.flatMap { servers[it].orEmpty() }.distinctBy { it.id }.singleOrNull()
            ?: tags[tagKey(track.title, track.artist, track.album)]?.closest())?.id
    }

    private fun tagKey(vararg values: String) = values.joinToString("\u0000") { it.trim().lowercase() }
}
