package me.d3sox.nevumi.downloads

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeException
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
        val missing = linkedMapOf<String, String>()
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
                missing[trackId] = remote.location
            }
        }
        // A few songs are cheaper to look up one by one; many songs are cheaper as one catalogue download.
        val catalogue = if (missing.size > SINGLE_LOOKUPS) client.songs().associateBy { it.id } else null
        val releases = mutableMapOf<String, String>()
        for ((trackId, songId) in missing) {
            val song = if (catalogue != null) catalogue[songId] ?: continue
                else try { client.song(songId, releases) } catch (gone: NavidromeException) { if (gone.code == 70) continue else throw gone }
            downloads.download(trackId, song)
        }
    }

    internal companion object {
        /** Up to this many missing songs are looked up individually; beyond it one catalogue download is cheaper. */
        const val SINGLE_LOOKUPS = 20
    }
}
