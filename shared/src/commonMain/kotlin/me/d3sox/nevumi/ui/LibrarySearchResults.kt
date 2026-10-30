package me.d3sox.nevumi.ui

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.resources.*
import org.jetbrains.compose.resources.StringResource

internal enum class SearchType(val label: StringResource, val tab: LibraryTab?) {
    ALL(Res.string.search_type_all, null), SONGS(Res.string.search_type_songs, LibraryTab.SONGS),
    ALBUMS(Res.string.search_type_albums, LibraryTab.ALBUMS), ARTISTS(Res.string.search_type_artists, LibraryTab.ARTISTS),
    PLAYLISTS(Res.string.search_type_playlists, LibraryTab.PLAYLISTS), GENRES(Res.string.search_type_genres, LibraryTab.GENRES),
    FOLDERS(Res.string.search_type_folders, LibraryTab.FOLDERS),
    /** Searches SoundCloud through sc-gate-dl instead of the library. */
    SOUNDCLOUD(Res.string.search_type_soundcloud, null)
}

internal data class LibrarySearchResults(val songs: List<Track>, val collections: Map<SearchType, List<TrackCollection>>) {
    val count: Int get() = songs.size + collections.values.sumOf { it.size }
}

/**
 * The library prepared for searching: every song's searchable text in lowercase and the collections grouped and sorted
 * once, so each keystroke only matches text instead of grouping the whole library again.
 */
internal class LibrarySearchIndex(tracks: List<Track>, playlists: List<Playlist>) {
    private class Entry<T>(val value: T, val text: String)
    private fun text(vararg values: String) = values.joinToString("\u0000").lowercase()
    private val songs = tracks.map { track ->
        val metadata = track.metadata
        Triple(track, metadata.title.lowercase(), text(metadata.title, metadata.artist, metadata.album, metadata.albumArtist, *metadata.genres.toTypedArray()))
    }.sortedWith(compareBy<Triple<Track, String, String>> { it.second }.thenBy { it.first.id }).map { Entry(it.first, it.third) }
    private fun groups(values: Map<String, List<Track>>) = values.entries.filter { it.key.isNotBlank() }
        .sortedBy { it.key.lowercase() }.map { Entry(TrackCollection(it.key, it.key, it.value), it.key.lowercase()) }
    private val albums = tracks.groupBy { it.metadata.album to it.metadata.albumArtist.ifBlank { it.metadata.artist } }
        .filter { (key, _) -> key.first.isNotBlank() }
        .entries.sortedBy { it.key.first.lowercase() }.map { (key, songs) ->
            Entry(TrackCollection("${key.first.length}:${key.first}${key.second}", key.first,
                songs.sortedWith(compareBy<Track> { it.metadata.discNumber }.thenBy { it.metadata.trackNumber }.thenBy { it.metadata.title }), key.second),
                text(key.first, key.second))
        }
    private val artists = groups(tracks.groupBy { it.metadata.artist })
    private val genres = groups(tracks.flatMap { track -> track.metadata.genres.map { it to track } }.groupBy({ it.first }, { it.second }))
    private val folders = groups(tracks.groupBy { it.metadata.folder })
    private val playlists = tracks.associateBy { it.id }.let { catalog ->
        playlists.sortedBy { it.name.lowercase() }.map { playlist ->
            Entry(TrackCollection(playlist.id, playlist.name, playlist.orderedTracks(catalog).map { entry -> entry.value }, playlist.description),
                text(playlist.name, playlist.description))
        }
    }

    fun search(text: String): LibrarySearchResults {
        val query = text.trim().lowercase()
        if (query.isEmpty()) return LibrarySearchResults(emptyList(), emptyMap())
        fun <T> List<Entry<T>>.matching() = filter { query in it.text }.map { it.value }
        return LibrarySearchResults(songs.matching(), mapOf(
            SearchType.ALBUMS to albums.matching(),
            SearchType.ARTISTS to artists.matching(),
            SearchType.PLAYLISTS to playlists.matching(),
            SearchType.GENRES to genres.matching(),
            SearchType.FOLDERS to folders.matching(),
        ))
    }
}
