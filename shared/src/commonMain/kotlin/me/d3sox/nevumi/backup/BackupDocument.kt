package me.d3sox.nevumi.backup

import kotlinx.serialization.Serializable
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.ScrobblingProfile
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.navidrome.ServerProfile
import me.d3sox.nevumi.playback.EqualizerProfile
import me.d3sox.nevumi.playlists.PlaylistSort
import me.d3sox.nevumi.youtube.YouTubeLink
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.queue.SavedQueueKind
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.sftp.SftpConfiguration
import me.d3sox.nevumi.soundcloud.SoundCloudProfile

internal const val BACKUP_FORMAT = "nevumi-backup"
internal const val BACKUP_VERSION = 1

enum class BackupSection { SETTINGS, PLAYLISTS, SAVED_QUEUES, FAVORITES, RATINGS, LISTENING_HISTORY, SERVICE_PROFILES, EQUALIZER_PROFILES, DOWNLOADS }

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
    /** Null preserves the destination's order; empty clears it. Older full backups store their order in settings. */
    val playlistOrder: List<String>? = settings?.collectionLayout(LibraryTab.PLAYLISTS)?.order,
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

/** How a song is recognized on another device, with its favorite, rating and whether it was downloaded on its own. */
@Serializable
data class BackupTrack(val title: String, val artist: String = "", val album: String = "", val durationMs: Long = 0,
    /** The MusicBrainz identity from [me.d3sox.nevumi.sync.LibrarySync.identity], when the tags have one. */
    val identity: String? = null,
    /** Server source IDs, which name the server account and its song ID. */
    val serverSources: List<String> = emptyList(),
    val favorite: Boolean = false, val rating: Int = 0, val downloaded: Boolean = false)

/**
 * A playlist; folders are part of [name]. Smart playlists keep their [rule] and no songs, and followed YouTube Music playlists keep
 * their [youTube] link, which brings their songs back when opened.
 */
@Serializable
data class BackupPlaylist(val name: String, val trackKeys: List<String> = emptyList(), val rule: LibraryQuery? = null,
    val description: String = "", val shuffle: Boolean = false, val sort: PlaylistSort = PlaylistSort.CUSTOM,
    val descending: Boolean = false, val youTube: YouTubeLink? = null, val autoDownload: Boolean = false)

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

/**
 * What a restore added or changed. [playlistDownloads] counts playlists it set to download automatically, and [songDownloads]
 * songs it asked to download. [unmatchedTracks] counts song references whose song is not in this library, and
 * [playlistsSkipped] counts playlists left out because none of their songs is. [serverPlaylistsLackingSongs] counts music server
 * playlists that stood in for a backed-up playlist and were kept as the server has them, without some of its songs.
 */
data class RestoreSummary(val settings: Boolean = false, val favorites: Int = 0, val ratings: Int = 0, val playlistsAdded: Int = 0,
    val playlistsMerged: Int = 0, val savedQueues: Int = 0, val listens: Int = 0, val equalizerProfiles: Int = 0,
    val scrobblingProfiles: Int = 0, val soundCloud: Boolean = false, val unmatchedTracks: Int = 0,
    val signInRequired: List<SignInRequired> = emptyList(), val playlistsSkipped: Int = 0,
    val serverPlaylistsLackingSongs: Int = 0, val playlistDownloads: Int = 0, val songDownloads: Int = 0)

/** The sections this backup holds data for. */
fun BackupDocument.sections(): Set<BackupSection> = buildSet {
    if (settings != null) add(BackupSection.SETTINGS)
    if (playlists.isNotEmpty()) add(BackupSection.PLAYLISTS)
    if (savedQueues.isNotEmpty()) add(BackupSection.SAVED_QUEUES)
    if (tracks.values.any { it.favorite }) add(BackupSection.FAVORITES)
    if (tracks.values.any { it.rating != 0 }) add(BackupSection.RATINGS)
    if (listens.isNotEmpty()) add(BackupSection.LISTENING_HISTORY)
    if (scrobblingProfiles.isNotEmpty() || server != null || sftpProfiles.isNotEmpty() || soundCloud != null) add(BackupSection.SERVICE_PROFILES)
    if (equalizerProfiles.isNotEmpty()) add(BackupSection.EQUALIZER_PROFILES)
    if (tracks.values.any { it.downloaded } || playlists.any { it.autoDownload }) add(BackupSection.DOWNLOADS)
}

/** This backup without the data of the sections it leaves out, as if it had been exported with only [sections]. */
internal fun BackupDocument.only(sections: Set<BackupSection>): BackupDocument {
    val downloads = BackupSection.DOWNLOADS in sections
    val profiles = BackupSection.SERVICE_PROFILES in sections
    return copy(settings = settings.takeIf { BackupSection.SETTINGS in sections },
        // Without playlists, the Playlists order is the destination's.
        playlistOrder = playlistOrder.takeIf { BackupSection.PLAYLISTS in sections },
        tracks = tracks.mapValues { (_, track) -> track.copy(favorite = track.favorite && BackupSection.FAVORITES in sections,
            rating = if (BackupSection.RATINGS in sections) track.rating else 0, downloaded = track.downloaded && downloads) },
        playlists = if (BackupSection.PLAYLISTS in sections) playlists.map { it.copy(autoDownload = it.autoDownload && downloads) } else emptyList(),
        savedQueues = savedQueues.takeIf { BackupSection.SAVED_QUEUES in sections }.orEmpty(),
        listens = listens.takeIf { BackupSection.LISTENING_HISTORY in sections }.orEmpty(),
        scrobblingProfiles = scrobblingProfiles.takeIf { profiles }.orEmpty(), server = server.takeIf { profiles },
        sftpProfiles = sftpProfiles.takeIf { profiles }.orEmpty(), soundCloud = soundCloud.takeIf { profiles },
        equalizerProfiles = equalizerProfiles.takeIf { BackupSection.EQUALIZER_PROFILES in sections }.orEmpty())
}
