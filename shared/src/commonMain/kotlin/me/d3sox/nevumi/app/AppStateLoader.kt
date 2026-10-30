package me.d3sox.nevumi.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.library.MusicFileEdits
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.sftp.ServerFiles

/** Reloads the app state from the stores. The domains share one loader so a refresh publishes every detail in a single state change. */
internal class AppStateLoader(private val context: ControllerContext, private val library: MusicLibrary, private val queue: PlaybackQueue,
    private val preferences: MusicPreferences?, private val navidrome: NavidromeConnection?, private val playlists: Playlists?,
    private val fileEdits: MusicFileEdits?, private val serverFiles: ServerFiles?, private val listeningHistory: ListeningHistory?,
    private val artwork: ArtworkLibrary?) {
    /** Keeps a batch of cover changes and a full refresh from interleaving their artwork maps. */
    val artworkUpdates = Mutex()
    /** Keeps a refresh from publishing settings it loaded before a remembered library tab or position was saved. */
    val preferenceUpdates = Mutex()

    suspend fun refreshLibrary() {
        val tracks = library.tracks()
        val snapshot = queue.snapshot()
        preferenceUpdates.withLock {
            val settings = preferences?.load() ?: MusicSettings()
            context.update { it.copy(tracks = tracks, queue = snapshot, preferences = settings,
                loading = it.serverRefresh?.running == true, libraryReady = true) }
        }
    }

    suspend fun refresh(loading: Boolean = false) {
        refreshLibrary()
        refreshDetails(loading)
    }

    suspend fun refreshDetails(loading: Boolean) {
        val tracks = context.state.value.tracks
        val savedQueues = queue.saved()
        val profile = navidrome?.profile()
        val collections = playlists?.all().orEmpty()
        val editHistory = (fileEdits?.history().orEmpty() + serverFiles?.history().orEmpty()).sortedBy { it.createdAt }
        val listens = listeningHistory?.all().orEmpty()
        val sftp = serverFiles?.configuration()
        val uploads = serverFiles?.pendingUploads().orEmpty()
        artworkUpdates.withLock {
            val covers = artwork?.paths(tracks.map { it.id } + collections.flatMap { listOf("playlist:${it.id}", "server-playlist-cover:${it.id}") }).orEmpty()
            context.update { it.copy(savedQueues = savedQueues, loading = loading || it.serverRefresh?.running == true, server = profile,
                playlists = collections, artwork = covers, fileEdits = editHistory, listens = listens,
                sftp = sftp, serverUploads = uploads) }
        }
    }
}
