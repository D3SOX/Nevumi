package me.d3sox.nevumi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.res.painterResource
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.library.WatchingLocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.LocalFileEdits
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playback.CrossfadePlaybackEngine
import me.d3sox.nevumi.playback.LinuxMediaSession
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.SecretServiceVault
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.ui.MusicPlayerApp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.downloads.LinuxDownloadNetwork
import me.d3sox.nevumi.downloads.DownloadNetwork
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.artwork.ArtworkLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

fun main(arguments: Array<String>) {
    fun option(name: String) = arguments.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    val base = System.getenv("XDG_DATA_HOME") ?: File(System.getProperty("user.home"), ".local/share").path
    val directory = File(option("--data-dir") ?: "$base/me.d3sox.nevumi").apply { mkdirs() }
    val store = openMusicStore(File(directory, "music-player.db").path)
    val library = MusicLibrary(store)
    val http = HttpClient(OkHttp)
    val credentials = SecretServiceVault()
    val tunnel = me.d3sox.nevumi.tunnel.TailscaleTunnel(store, me.d3sox.nevumi.tunnel.Tailscale(File(directory, "tailscale"), "nevumi-linux"))
    val connection = NavidromeConnection(store, http, credentials, tunnel::musicServerUrl)
    val artwork = ArtworkLibrary(File(directory, "artwork").path)
    val serverChanges = NavidromeChanges(store, library, artwork, connection::client)
    val history = ListeningHistory(store, library)
    val playlists = Playlists(store, library = library, history = history)
    val downloadNetwork = try { LinuxDownloadNetwork() }
        catch (error: Exception) { System.err.println("NetworkManager monitoring unavailable: ${error.message}"); null }
    val offline = OfflineMusic(store, library, connection::client, File(directory, "audio").path, playlists,
        downloadNetwork?.state ?: MutableStateFlow(DownloadNetwork()))
    val localFiles = WatchingLocalFiles(library, artwork)
    val soundCloud = me.d3sox.nevumi.soundcloud.SoundCloudDownloads(store, http, tunnel::scGateUrl,
        me.d3sox.nevumi.soundcloud.DirectorySoundCloudFiles("${me.d3sox.nevumi.soundcloud.musicDirectory()}/SoundCloud"), localFiles,
        connection::client, serverChanges, artwork, offline)
    val scrobbling = Scrobbling(store, library, http, credentials, offline.network, connection::client)
    val equalizerProfiles = me.d3sox.nevumi.playback.AutoEqProfiles(store, http)
    val controller = MusicController(library, PlaybackQueue(store),
        CrossfadePlaybackEngine { MpvPlaybackEngine(option("--audio-output") ?: "auto") },
        connection, localFiles, offline, playlists, preferences = MusicPreferences(store), artwork = artwork,
        serverChanges = serverChanges,
        fileEdits = LocalFileEdits(store, library, File(directory, "file-edits").path, localSources = LocalFiles(library, artwork)),
        listeningHistory = history,
        scrobbling = scrobbling, equalizerProfiles = equalizerProfiles,
        serverFiles = me.d3sox.nevumi.sftp.SftpConnection(store, library, connection::client, credentials,
            File(directory, "server-edits").path, changes = serverChanges, artwork = artwork, tailscale = tunnel::serverFiles),
        pairedDevices = me.d3sox.nevumi.sync.JvmPairedDevices(store,
            me.d3sox.nevumi.sync.LibrarySync(store, library, serverChanges), credentials, "Linux desktop"),
        tunnel = tunnel, soundCloud = soundCloud, discovery = me.d3sox.nevumi.navidrome.ServerDiscovery(store, connection::client),
        backup = me.d3sox.nevumi.backup.MusicBackup(store, library, serverChanges, scrobbling, soundCloud, equalizerProfiles),
        lyrics = me.d3sox.nevumi.lyrics.LyricsSources(store, me.d3sox.nevumi.lyrics.FileLyrics(), connection::client,
            me.d3sox.nevumi.lyrics.LrcLibClient(http, me.d3sox.nevumi.ui.NEVUMI_VERSION)))
    option("--import-folder")?.let { controller.importSource(it) }
    val mediaSession = try { LinuxMediaSession(controller) }
        catch (error: Exception) { System.err.println("Desktop media controls unavailable: ${error.message}"); null }
    application {
        val lifecycle = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        Window(title = "Nevumi", icon = painterResource("nevumi.png"), state = rememberWindowState(size = DpSize(1100.dp, 800.dp)),
            onCloseRequest = {
                if (!closing) {
                    closing = true
                    lifecycle.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                try { mediaSession?.close(); controller.closeAndJoin() }
                                finally { downloadNetwork?.close(); http.close(); store.close() }
                            }
                        } finally { exitApplication() }
                    }
                }
            }) {
            MusicPlayerApp(controller)
        }
    }
}
