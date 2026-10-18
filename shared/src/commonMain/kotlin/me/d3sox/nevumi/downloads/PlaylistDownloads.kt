package me.d3sox.nevumi.downloads

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.SourceResolver
import me.d3sox.nevumi.library.FileAudioAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistDownloads(private val playlists: Playlists, private val library: MusicLibrary,
    private val downloads: AudioDownloads, private val client: NavidromeClient,
    private val sourceAccessible: (String) -> Boolean = FileAudioAccess::available) {
    suspend fun synchronize(excluded: Set<String> = emptySet()) {
        val collections = playlists.all()
        downloads.replacePlaylistPins(collections, excluded)
        for (playlist in collections.filter { it.autoDownload }) {
            for (trackId in playlist.trackIds.distinct().filterNot { it in excluded }) {
                val track = library.findTrack(trackId) ?: continue
                downloads.pin(trackId, "playlist:${playlist.id}")
                val accessible = track.sources.map { source ->
                    if (source.kind == SourceKind.SERVER) source
                    else source.copy(available = source.available && withContext(Dispatchers.IO) { sourceAccessible(source.location) })
                }
                if (SourceResolver.resolve(accessible, online = false) != null) continue
                val remote = track.sources.firstOrNull {
                    it.kind == SourceKind.SERVER && it.id.startsWith("server:${client.serverId}:")
                } ?: continue
                val song = try { client.song(remote.location) }
                    catch (gone: me.d3sox.nevumi.navidrome.NavidromeException) { if (gone.code == 70) continue else throw gone }
                downloads.download(trackId, song)
            }
        }
    }
}
