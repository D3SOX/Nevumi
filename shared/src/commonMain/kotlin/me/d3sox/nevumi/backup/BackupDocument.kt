package me.d3sox.nevumi.backup

import kotlinx.serialization.Serializable
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.ScrobblingProfile
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.navidrome.ServerProfile
import me.d3sox.nevumi.playback.EqualizerProfile
import me.d3sox.nevumi.playlists.PlaylistSort
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.queue.SavedQueueKind
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.sftp.SftpConfiguration
import me.d3sox.nevumi.soundcloud.SoundCloudProfile

internal const val BACKUP_FORMAT = "nevumi-backup"
internal const val BACKUP_VERSION = 1

/**
 * Everything a backup file holds. Songs are listed once in [tracks] under a key that only has meaning inside the file;
 * playlists, saved queues, listens and flags refer to songs by that key, and a restore matches each song to the library.
 * No passwords, tokens or keys are included: they stay in the device's credential store.
 */
@Serializable
data class BackupDocument(
    val format: String = BACKUP_FORMAT,
    val version: Int = BACKUP_VERSION,
    val createdAt: Long,
    val appVersion: String = "",
    val settings: MusicSettings? = null,
    val tracks: Map<String, BackupTrack> = emptyMap(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val savedQueues: List<BackupSavedQueue> = emptyList(),
    /** Listens whose track ID is a key of [tracks]. */
    val listens: List<Listen> = emptyList(),
    val scrobblingProfiles: List<ScrobblingProfile> = emptyList(),
    val server: ServerProfile? = null,
    val sftpProfiles: List<SftpConfiguration> = emptyList(),
    val soundCloud: SoundCloudProfile? = null,
    val equalizerProfiles: List<EqualizerProfile> = emptyList(),
)

/** How a song is recognized on another device, with its favorite and rating. */
@Serializable
data class BackupTrack(val title: String, val artist: String = "", val album: String = "", val durationMs: Long = 0,
    /** The MusicBrainz identity from [me.d3sox.nevumi.sync.LibrarySync.identity], when the tags have one. */
    val identity: String? = null,
    /** Server source IDs, which name the server account and its song ID. */
    val serverSources: List<String> = emptyList(),
    val favorite: Boolean = false, val rating: Int = 0)

/** A playlist; folders are part of [name]. Smart playlists keep their [rule] and no songs. */
@Serializable
data class BackupPlaylist(val name: String, val trackKeys: List<String> = emptyList(), val rule: LibraryQuery? = null,
    val description: String = "", val shuffle: Boolean = false, val sort: PlaylistSort = PlaylistSort.CUSTOM,
    val descending: Boolean = false)

/** A saved queue whose entries' track IDs are keys of [BackupDocument.tracks]. */
@Serializable
data class BackupSavedQueue(val name: String, val snapshot: QueueSnapshot, val kind: SavedQueueKind = SavedQueueKind.LIVE,
    val capturedAt: Long = 0)

/** The file is not a backup, or was written by a newer version of the app when [version] is set. */
class BackupFormatException(val version: Int? = null) : IllegalArgumentException(
    if (version == null) "This file is not a Nevumi backup" else "This backup uses format $version, which this version of Nevumi cannot read")

/** An account the backup names but that needs its password or token again; [account] is the user and address. */
data class SignInRequired(val service: BackupService, val account: String)

enum class BackupService { MUSIC_SERVER, SFTP, LISTENBRAINZ, LASTFM }

/** What a restore added or changed. [unmatchedTracks] counts song references whose song is not in this library. */
data class RestoreSummary(val settings: Boolean = false, val favorites: Int = 0, val ratings: Int = 0, val playlistsAdded: Int = 0,
    val playlistsMerged: Int = 0, val savedQueues: Int = 0, val listens: Int = 0, val equalizerProfiles: Int = 0,
    val scrobblingProfiles: Int = 0, val soundCloud: Boolean = false, val unmatchedTracks: Int = 0,
    val signInRequired: List<SignInRequired> = emptyList())
