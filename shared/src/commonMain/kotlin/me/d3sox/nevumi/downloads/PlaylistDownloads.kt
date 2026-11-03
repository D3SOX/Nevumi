package me.d3sox.nevumi.downloads

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeException
import me.d3sox.nevumi.navidrome.ServerSong
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.library.FileAudioAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistDownloads(private val playlists: Playlists, private val library: MusicLibrary,
    private val downloads: AudioDownloads, private val client: NavidromeClient,
    private val sourceAccessible: (String) -> Boolean = FileAudioAccess::available,
    /** Shares one catalogue download with the rest of a download pass. */
    private val catalogue: suspend () -> Map<String, ServerSong> = { client.songs().associateBy { it.id } }) {
    /** Returns whether every song it had to download completed. */
    suspend fun synchronize(excluded: Set<String> = emptySet()): Boolean {
        val collections = playlists.all()
        // This already pins every song of a downloaded playlist, so the loop below only finds songs to download.
        downloads.replacePlaylistPins(collections, excluded)
        val missing = linkedMapOf<String, String>()
        val wanted = collections.filter { it.autoDownload }.flatMap { it.trackIds }.distinct().filterNot { it in excluded }
        val all = library.tracks()
        val tracks = all.associateBy { it.id }
        withContext(Dispatchers.IO) {
            for (trackId in wanted) {
                val track = tracks[trackId] ?: continue
                if (track.sources.any { it.kind != SourceKind.SERVER && it.available && accessible(it) }) continue
                val remote = track.sources.firstOrNull {
                    it.kind == SourceKind.SERVER && it.id.startsWith("server:${client.serverId}:")
                } ?: continue
                missing[trackId] = remote.location
            }
        }
        val serverSongs = all.count { track -> track.sources.any { it.kind == SourceKind.SERVER && it.id.startsWith("server:${client.serverId}:") } }
        val catalogue = if (catalogueCheaper(missing.size, serverSongs)) catalogue() else null
        val releases = mutableMapOf<String, String>()
        var completed = true
        for ((trackId, songId) in missing) {
            // A song the server lacks can return without any library change, so it does not count as done.
            val song = (if (catalogue != null) catalogue[songId]
                else try { client.song(songId, releases) } catch (gone: NavidromeException) { if (gone.code == 70) null else throw gone })
                ?: run { completed = false; continue }
            if (downloads.download(trackId, song).status != DownloadStatus.COMPLETE) completed = false
        }
        return completed
    }

    // Android's media scan records local music's file path; checking it is far cheaper than opening each content URI through
    // the media provider, which made a pass over large playlists take minutes.
    private fun accessible(source: TrackSource) = source.filePath?.let(FileAudioAccess::available) == true || sourceAccessible(source.location)

    internal companion object {
        /** Up to this many missing songs are always looked up individually. */
        const val SINGLE_LOOKUPS = 20

        /**
         * A catalogue download costs about one request per 500 songs plus one per album, roughly a tenth of the server's songs;
         * a single lookup costs one request. Beyond that share of the library, one catalogue download is cheaper.
         */
        fun catalogueCheaper(missing: Int, serverSongs: Int) = missing > SINGLE_LOOKUPS && missing > serverSongs / 10
    }
}
