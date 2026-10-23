package me.d3sox.nevumi.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import kotlinx.coroutines.Job
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.navidrome.ServerDiscovery
import me.d3sox.nevumi.navidrome.ServerSong
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.discovery_nothing_similar
import me.d3sox.nevumi.resources.discovery_playing_similar_song
import me.d3sox.nevumi.resources.discovery_playing_similar_songs
import me.d3sox.nevumi.resources.discovery_similar_to
import org.jetbrains.compose.resources.getString

/**
 * What the server knows about an artist, matched against the library: similar artists split by whether the library has them,
 * by their library names, and the artist's top songs that the library has.
 */
/** An artist in the library, with the tab whose collection holds it: Artists, or Album artists for artists only credited on albums. */
data class LibraryArtist(val name: String, val tab: me.d3sox.nevumi.library.LibraryTab)

data class ArtistDiscovery(val biography: String = "", val lastFmUrl: String? = null, val similarInLibrary: List<LibraryArtist> = emptyList(),
    val similarElsewhere: List<String> = emptyList(), val topSongs: List<Track> = emptyList())

/** Finds music related to an artist or a song through the music server and plays what of it the library has. */
class DiscoveryController internal constructor(private val context: ControllerContext, private val queueController: QueueController,
    private val discovery: ServerDiscovery?) {

    /** Null when no server is connected, the server offers no discovery or it knows nothing about [artistName]. */
    suspend fun artistInfo(artistName: String): ArtistDiscovery? = withContext(Dispatchers.Default) { artistDetails(artistName) }

    /** Goes over the whole library, so [artistInfo] keeps it off the UI thread. */
    private suspend fun artistDetails(artistName: String): ArtistDiscovery? {
        val discovery = discovery ?: return null
        val serverId = discovery.serverId() ?: return null
        val tracks = context.state.value.tracks
        val details = discovery.artist(artistName, serverSongIds(tracks, serverId, artistName)) ?: return null
        // Track artists first, then artists only credited as album artists, such as on compilations.
        val libraryArtists = tracks.map { it.metadata.albumArtist }.filter { it.isNotBlank() }
            .associate { it.lowercase() to LibraryArtist(it, me.d3sox.nevumi.library.LibraryTab.ALBUM_ARTISTS) } +
            tracks.map { it.metadata.artist }.filter { it.isNotBlank() }
                .associate { it.lowercase() to LibraryArtist(it, me.d3sox.nevumi.library.LibraryTab.ARTISTS) }
        val (inLibrary, elsewhere) = details.info?.similarArtists.orEmpty().map { it.name }
            .filterNot { it.equals(artistName, ignoreCase = true) }.distinctBy { it.lowercase() }
            .partition { it.lowercase() in libraryArtists }
        val songs = librarySongs(tracks, serverId)
        return ArtistDiscovery(details.info?.biography.orEmpty(), details.info?.lastFmUrl?.ifBlank { null },
            inLibrary.map { libraryArtists.getValue(it.lowercase()) }, elsewhere,
            details.topSongIds.mapNotNull { songs[it] }.distinctBy { it.id })
            .takeUnless { it.biography.isBlank() && it.similarInLibrary.isEmpty() && it.similarElsewhere.isEmpty() && it.topSongs.isEmpty() }
    }

    fun playSimilarToTrack(trackId: String): Job = context.action(serialized = false) {
        val track = context.state.value.tracks.firstOrNull { it.id == trackId } ?: return@action
        val serverId = discovery?.serverId()
        val songId = serverId?.let { id -> track.sources.firstNotNullOfOrNull { it.serverSongId(id) } }
        val similar = songId?.let { discovery.similarToSong(it) }
        play(similar, serverId, getString(Res.string.discovery_similar_to, track.metadata.title), excluded = track.id)
    }

    fun playSimilarToArtist(artistName: String): Job = context.action(serialized = false) {
        val serverId = discovery?.serverId()
        val similar = serverId?.let { discovery.similarToArtist(artistName, serverSongIds(context.state.value.tracks, it, artistName)) }
        play(similar, serverId, getString(Res.string.discovery_similar_to, artistName))
    }

    private suspend fun play(similar: List<ServerSong>?, serverId: String?, contextName: String, excluded: String? = null) {
        val songs = serverId?.let { librarySongs(context.state.value.tracks, it) }.orEmpty()
        val ids = similar.orEmpty().mapNotNull { songs[it.id]?.id }.filter { it != excluded }.distinct()
        if (ids.isEmpty()) return context.confirm(Res.string.discovery_nothing_similar)
        queueController.playTrack(ids.first(), ids, 0, contextName).join()
        // playTrack may only ask for confirmation or report a failure in the app state; only a started queue gets a notice.
        val started = context.state.value
        if (started.playbackConfirmation != null || started.queue.currentTrackId != ids.first()) return
        context.confirm(if (ids.size == 1) Res.string.discovery_playing_similar_song else Res.string.discovery_playing_similar_songs,
            ActionDestination.QUEUE, arguments = listOf(ids.size))
    }

    private fun me.d3sox.nevumi.library.TrackSource.serverSongId(serverId: String) =
        id.takeIf { kind == SourceKind.SERVER && it.startsWith("server:$serverId:") }?.removePrefix("server:$serverId:")

    /** Maps the connected server's song IDs to the library tracks that hold them. */
    private fun librarySongs(tracks: List<Track>, serverId: String): Map<String, Track> =
        tracks.flatMap { track -> track.sources.mapNotNull { it.serverSongId(serverId) }.map { it to track } }.toMap()

    private fun serverSongIds(tracks: List<Track>, serverId: String, artistName: String) =
        tracks.filter { it.metadata.artist.equals(artistName, ignoreCase = true) || it.metadata.albumArtist.equals(artistName, ignoreCase = true) }
            .flatMap { track -> track.sources.mapNotNull { it.serverSongId(serverId) } }.take(1)
}
