package me.d3sox.nevumi

import android.app.Application
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.library.AndroidLocalSources
import me.d3sox.nevumi.library.AndroidFileEdits
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.playback.AndroidPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.AndroidCredentialVault
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.downloads.AndroidDownloadNetwork
import me.d3sox.nevumi.playlists.Playlists
import java.io.File
import me.d3sox.nevumi.library.AndroidAudioAccess
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.artwork.ArtworkLibrary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.d3sox.nevumi.sftp.SftpConnection
import me.d3sox.nevumi.tunnel.Tailscale
import me.d3sox.nevumi.tunnel.TailscaleTunnel
import android.net.Uri
import me.d3sox.nevumi.soundcloud.MediaStoreSoundCloudFiles
import me.d3sox.nevumi.soundcloud.SoundCloudDownloads

class MusicApplication : Application() {
    lateinit var controller: MusicController
        private set
    lateinit var widgetUpdates: NowPlayingWidgetUpdates
        private set
    lateinit var store: MusicStore
        private set
    lateinit var offline: OfflineMusic
        private set
    lateinit var soundCloud: SoundCloudDownloads
        private set
    private lateinit var http: HttpClient
    private lateinit var downloadNetwork: AndroidDownloadNetwork
    private lateinit var connection: NavidromeConnection
    private lateinit var serverChanges: NavidromeChanges
    private lateinit var downloadJobs: AndroidDownloadJobs
    private lateinit var serverFiles: SftpConnection
    lateinit var mediaWriteRequests: MediaWriteRequests
        private set
    lateinit var localNetworkRequests: LocalNetworkRequests
        private set
    private val mutableDownloadNotifications = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val downloadNotificationsRequested = mutableDownloadNotifications.asSharedFlow()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        mediaWriteRequests = MediaWriteRequests(this)
        localNetworkRequests = LocalNetworkRequests(this)
        store = openMusicStore(this)
        http = HttpClient(OkHttp)
        val library = MusicLibrary(store)
        val credentials = AndroidCredentialVault(this)
        val artwork = ArtworkLibrary(File(filesDir, "artwork").path)
        val tunnel = TailscaleTunnel(store, Tailscale(File(noBackupFilesDir, "tailscale"), "nevumi-android"))
        connection = NavidromeConnection(store, http, credentials, tunnel::musicServerUrl)
        serverChanges = NavidromeChanges(store, library, artwork, connection::client)
        serverFiles = SftpConnection(store, library, connection::client, credentials, File(filesDir, "server-edits").path,
            readLocal = { location ->
                if (location.startsWith("content:")) requireNotNull(contentResolver.openInputStream(Uri.parse(location))) { "The local music file is unavailable" }
                else File(location).inputStream()
            }, changes = serverChanges, artwork = artwork, tailscale = tunnel::serverFiles)
        val history = ListeningHistory(store, library)
        val playlists = Playlists(store, library = library, history = history)
        downloadNetwork = AndroidDownloadNetwork(this)
        downloadJobs = AndroidDownloadJobs(this, MusicPreferences(store), playlists) { mutableDownloadNotifications.tryEmit(Unit) }
        offline = OfflineMusic(store, library, connection::client, File(filesDir, "audio").path, playlists, downloadNetwork.state,
            sourceAccessible = AndroidAudioAccess(this)::available, scheduleWork = downloadJobs::schedule)
        val localSources = AndroidLocalSources(this, library, artwork)
        soundCloud = SoundCloudDownloads(store, http, tunnel::scGateUrl, MediaStoreSoundCloudFiles(this), localSources,
            connection::client, serverChanges, artwork, offline, scheduleWork = downloadJobs::scheduleSoundCloud)
        val scrobbling = Scrobbling(store, library, http, credentials, downloadNetwork.state, connection::client)
        val equalizerProfiles = me.d3sox.nevumi.playback.AutoEqProfiles(store, http)
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        controller = MusicController(library, PlaybackQueue(store),
            AndroidPlaybackEngine(this, PlaybackService::class.java),
            connection, localSources, offline, playlists, AndroidAudioAccess(this)::available,
            MusicPreferences(store), artwork, serverChanges,
            AndroidFileEdits(this, store, library, File(filesDir, "file-edits").path, mediaWriteRequests::request, artwork),
            listeningHistory = history,
            scrobbling = scrobbling, equalizerProfiles = equalizerProfiles, serverFiles = serverFiles,
            pairedDevices = me.d3sox.nevumi.sync.JvmPairedDevices(store,
                me.d3sox.nevumi.sync.LibrarySync(store, library, serverChanges, AndroidAudioAccess(this)::contentId), credentials, "Android phone",
                networkAccess = localNetworkRequests::request),
            tunnel = tunnel, networkAccess = localNetworkRequests::request, soundCloud = soundCloud,
            discovery = me.d3sox.nevumi.navidrome.ServerDiscovery(store, connection::client),
            backup = me.d3sox.nevumi.backup.MusicBackup(store, library, serverChanges, scrobbling, soundCloud, equalizerProfiles),
            lyrics = me.d3sox.nevumi.lyrics.LyricsSources(store, me.d3sox.nevumi.lyrics.FileLyrics { contentResolver.openInputStream(Uri.parse(it)) },
                connection::client, me.d3sox.nevumi.lyrics.LrcLibClient(http, version)),
            releases = me.d3sox.nevumi.releases.ArtistReleases(store, me.d3sox.nevumi.releases.MusicBrainzClient(http, version)),
            releaseNotifier = AndroidReleaseNotifier(this))
        widgetUpdates = NowPlayingWidgetUpdates(this, controller).apply { resume() }
        ReleaseJobService.scheduleWhileNeeded(this, controller)
    }

    suspend fun refreshOfflinePlaylists() {
        connection.client()?.let { serverChanges.refreshLibrary(it, includeFilePaths = serverFiles.configuration()?.serverId == it.serverId) }
    }

    fun retryUserDownloads() { downloadJobs.schedule(userInitiated = true) }
}
