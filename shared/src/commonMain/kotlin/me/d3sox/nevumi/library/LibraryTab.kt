package me.d3sox.nevumi.library

import kotlinx.serialization.Serializable

@Serializable
enum class LibraryTab(val label: String) {
    SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), ALBUM_ARTISTS("Album artists"),
    GENRES("Genres"), FOLDERS("Folders"), FAVORITES("Favorites"), RECENT("Recently added"), PLAYLISTS("Playlists"),
    HISTORY("History"), STATISTICS("Statistics"), RECOMMENDATIONS("Recommendations"), HOME("Home"), SEARCH("Search")
}
