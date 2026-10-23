package me.d3sox.nevumi.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.history.ScrobblingService
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

    suspend fun export(appVersion: String = ""): String {
        val tracks = library.tracks().associateBy { it.id }
        // Folder rules name paths and permissions on this device, and scroll positions are transient.
        val settings = preferences.load().copy(includeFolders = emptyList(), excludeFolders = emptyList(), libraryPositions = emptyMap())
        val playlists = playlists.all().map { playlist ->
            BackupPlaylist(playlist.name, if (playlist.rule == null) playlist.trackIds else emptyList(), playlist.rule,
                playlist.description, playlist.shuffle, playlist.sort, playlist.descending)
        }
        val savedQueues = queue.saved().filter { it.kind != SavedQueueKind.RECOVERY }.map { saved ->
            BackupSavedQueue(saved.name, saved.snapshot.copy(selectedSavedQueueId = null, origin = null), saved.kind, saved.capturedAt)
        }
        val listens = history.all().map { it.copy(importedAt = null, metadata = it.metadata.copy(folder = "")) }
        val referenced = (tracks.values.filter { it.favorite || it.rating != 0 }.map { it.id } + playlists.flatMap { it.trackKeys } +
            savedQueues.flatMap { saved -> saved.snapshot.entries.map { it.trackId } } + listens.map { it.trackId }).toSet()
        val document = BackupDocument(createdAt = clock(), appVersion = appVersion, settings = settings,
            tracks = referenced.mapNotNull { tracks[it] }.associate { it.id to it.toBackup() },
            playlists = playlists, savedQueues = savedQueues, listens = listens,
            scrobblingProfiles = scrobbling?.profiles().orEmpty(),
            server = store.read<ServerProfile>("server:navidrome"),
            sftpProfiles = store.list<SftpConfiguration>("sftp-profiles"),
            // A viewer address can carry a password; it is fetched from sc-gate-dl again on first use.
            soundCloud = store.read<SoundCloudProfile>("soundcloud:profile")?.copy(browserViewUrl = null),
            equalizerProfiles = equalizer?.saved().orEmpty())
        return json.encodeToString(document)
    }

    suspend fun restore(text: String): RestoreSummary {
        val document = decode(text)
        val matches = TrackMatcher(library.tracks())
        val local = document.tracks.mapNotNull { (key, track) -> matches.find(track)?.let { key to it } }.toMap()
        var unmatched = 0
        fun localId(key: String): String? = local[key] ?: null.also { unmatched++ }

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
        val existingPlaylists = playlists.all().toMutableList()
        for (backup in document.playlists) {
            val name = backup.name.trim().takeIf { it.isNotEmpty() } ?: continue
            val existing = existingPlaylists.firstOrNull { it.name.trim() == name }
            if (existing != null) {
                // Merging into a server playlist would push songs removed since the backup back to the server.
                if (backup.rule != null || existing.rule != null || existing.server != null) continue
                val additions = backup.trackKeys.mapNotNull(::localId).filterNot { it in existing.trackIds }
                if (additions.isEmpty()) continue
                try { playlists.append(existing.id, additions) }
                // Server playlists only take songs from their server.
                catch (_: IllegalArgumentException) { continue }
                playlistsMerged++
                existingPlaylists[existingPlaylists.indexOf(existing)] = existing.copy(trackIds = existing.trackIds + additions)
                continue
            }
            val created = if (backup.rule != null) playlists.createSmart(name, backup.rule)
                else playlists.create(name, backup.trackKeys.mapNotNull(::localId))
            if (backup.description.isNotBlank()) playlists.edit(created.id, name, backup.description)
            if (backup.shuffle) playlists.setShuffle(created.id, true)
            if (backup.sort != PlaylistSort.CUSTOM || backup.descending) playlists.setSort(created.id, backup.sort, backup.descending)
            existingPlaylists += created
            playlistsAdded++
        }

        var savedQueues = 0
        val existingQueues = queue.saved()
        for (backup in document.savedQueues) {
            val entries = backup.snapshot.entries.mapNotNull { entry -> localId(entry.trackId)?.let { entry.copy(trackId = it) } }
            if (entries.isEmpty() || backup.name.isBlank()) continue
            if (existingQueues.any { saved -> saved.name == backup.name.trim() && saved.snapshot.entries.map { it.trackId } == entries.map { it.trackId } }) continue
            val ids = entries.map { it.id }.toSet()
            val snapshot = backup.snapshot
            val currentKept = snapshot.currentEntryId in ids
            queue.addSaved(backup.name, snapshot.copy(entries = entries,
                currentEntryId = if (currentKept) snapshot.currentEntryId else entries.first().id,
                positionMs = if (currentKept) snapshot.positionMs else 0,
                lastInsertedEntryId = snapshot.lastInsertedEntryId?.takeIf { it in ids },
                unshuffledEntryIds = snapshot.unshuffledEntryIds?.filter { it in ids }, origin = null, selectedSavedQueueId = null),
                if (backup.kind == SavedQueueKind.RECOVERY) SavedQueueKind.SNAPSHOT else backup.kind, backup.capturedAt)
            savedQueues++
        }

        // Listens keep their song's tags, so songs no longer in the backup's library can still be matched.
        val listens = history.import(document.listens.mapNotNull { listen ->
            (local[listen.trackId] ?: matches.find(listen.metadata.toBackup()) ?: null.also { unmatched++ })
                ?.let { listen.copy(trackId = it, importedAt = null) }
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
            equalizerProfiles, scrobblingProfiles, soundCloud, unmatched, signIn)
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
