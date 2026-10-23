package me.d3sox.nevumi.library

import kotlinx.serialization.Serializable
import me.d3sox.nevumi.resources.*
import org.jetbrains.compose.resources.StringResource

@Serializable
enum class LibraryTab(val label: StringResource) {
    SONGS(Res.string.library_tab_songs), ALBUMS(Res.string.library_tab_albums), ARTISTS(Res.string.library_tab_artists),
    ALBUM_ARTISTS(Res.string.library_tab_album_artists), GENRES(Res.string.library_tab_genres), FOLDERS(Res.string.library_tab_folders),
    FAVORITES(Res.string.library_tab_favorites), RECENT(Res.string.library_tab_recent), PLAYLISTS(Res.string.library_tab_playlists),
    HISTORY(Res.string.library_tab_history), STATISTICS(Res.string.library_tab_statistics),
    RECOMMENDATIONS(Res.string.library_tab_recommendations), HOME(Res.string.library_tab_home), SEARCH(Res.string.library_tab_search)
}
