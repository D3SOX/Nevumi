package me.d3sox.nevumi.ui

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playlists.Playlist

internal enum class SearchType(val label: String, val tab: LibraryTab?) {
    ALL("All", null), SONGS("Songs", LibraryTab.SONGS), ALBUMS("Albums", LibraryTab.ALBUMS),
    ARTISTS("Artists", LibraryTab.ARTISTS), PLAYLISTS("Playlists", LibraryTab.PLAYLISTS),
    GENRES("Genres", LibraryTab.GENRES), FOLDERS("Folders", LibraryTab.FOLDERS),
    /** Searches SoundCloud through sc-gate-dl instead of the library. */
    SOUNDCLOUD("SoundCloud", null)
}

internal data class LibrarySearchResults(val songs: List<Track>, val collections: Map<SearchType, List<TrackCollection>>) {
    val count: Int get() = songs.size + collections.values.sumOf { it.size }
}

internal fun searchLibrary(tracks: List<Track>, playlists: List<Playlist>, text: String): LibrarySearchResults {
    val query = text.trim()
    if (query.isEmpty()) return LibrarySearchResults(emptyList(), emptyMap())
    fun matches(vararg values: String) = values.any { it.contains(query, ignoreCase = true) }
    fun groups(values: Map<String, List<Track>>) = values.entries.filter { it.key.isNotBlank() && matches(it.key) }
        .sortedBy { it.key.lowercase() }.map { TrackCollection(it.key, it.key, it.value) }
    val albums = tracks.groupBy { it.metadata.album to it.metadata.albumArtist.ifBlank { it.metadata.artist } }
        .filter { (key, _) -> key.first.isNotBlank() && matches(key.first, key.second) }
        .entries.sortedBy { it.key.first.lowercase() }.map { (key, songs) ->
            TrackCollection("${key.first.length}:${key.first}${key.second}", key.first,
                songs.sortedWith(compareBy<Track> { it.metadata.discNumber }.thenBy { it.metadata.trackNumber }.thenBy { it.metadata.title }), key.second)
        }
    val catalog = tracks.associateBy { it.id }
    return LibrarySearchResults(filterTracks(tracks, LibraryQuery(text = query)), mapOf(
        SearchType.ALBUMS to albums,
        SearchType.ARTISTS to groups(tracks.groupBy { it.metadata.artist }),
        SearchType.PLAYLISTS to playlists.filter { matches(it.name, it.description) }.sortedBy { it.name.lowercase() }
            .map { TrackCollection(it.id, it.name, it.orderedTracks(catalog).map { entry -> entry.value }, it.description) },
        SearchType.GENRES to groups(tracks.flatMap { track -> track.metadata.genres.map { it to track } }.groupBy({ it.first }, { it.second })),
        SearchType.FOLDERS to groups(tracks.groupBy { it.metadata.folder }),
    ))
}
