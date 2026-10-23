package me.d3sox.nevumi.ui

import me.d3sox.nevumi.settings.TrackAction
import me.d3sox.nevumi.library.LibraryTab
import androidx.compose.runtime.*
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.add_to_queue
import me.d3sox.nevumi.resources.download
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.play_after
import me.d3sox.nevumi.resources.play_next
import me.d3sox.nevumi.resources.player_action_none
import me.d3sox.nevumi.resources.player_add_to_playlist
import me.d3sox.nevumi.resources.player_available_offline
import me.d3sox.nevumi.resources.player_favorite
import me.d3sox.nevumi.resources.player_go_to_album
import me.d3sox.nevumi.resources.player_go_to_album_artist
import me.d3sox.nevumi.resources.player_go_to_artist
import me.d3sox.nevumi.resources.player_go_to_folder
import me.d3sox.nevumi.resources.player_go_to_genre
import me.d3sox.nevumi.resources.player_rate_song
import me.d3sox.nevumi.resources.player_remove_favorite
import me.d3sox.nevumi.resources.player_song_sources
import me.d3sox.nevumi.resources.select_song
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun TrackAction.labelResource(favorite: Boolean = false, availableOffline: Boolean = false): StringResource = when (this) {
    TrackAction.NONE -> Res.string.player_action_none
    TrackAction.PLAY_NEXT -> Res.string.play_next
    TrackAction.PLAY_AFTER -> Res.string.play_after
    TrackAction.APPEND -> Res.string.add_to_queue
    TrackAction.PLAY -> Res.string.play
    TrackAction.FAVORITE -> if (favorite) Res.string.player_remove_favorite else Res.string.player_favorite
    TrackAction.RATE -> Res.string.player_rate_song
    TrackAction.ADD_TO_PLAYLIST -> Res.string.player_add_to_playlist
    TrackAction.DOWNLOAD -> if (availableOffline) Res.string.player_available_offline else Res.string.download
    TrackAction.GO_ARTIST -> Res.string.player_go_to_artist
    TrackAction.GO_ALBUM -> Res.string.player_go_to_album
    TrackAction.GO_ALBUM_ARTIST -> Res.string.player_go_to_album_artist
    TrackAction.GO_GENRE -> Res.string.player_go_to_genre
    TrackAction.GO_FOLDER -> Res.string.player_go_to_folder
    TrackAction.SELECT -> Res.string.select_song
    TrackAction.SHOW_SOURCES -> Res.string.player_song_sources
}

@Composable
internal fun TrackAction.displayLabel(favorite: Boolean = false, availableOffline: Boolean = false) =
    stringResource(labelResource(favorite, availableOffline))

internal fun TrackAction.icon(favorite: Boolean = false, rating: Int = 0, inPlaylist: Boolean = false, availableOffline: Boolean = false) = when (this) {
    TrackAction.NONE -> AppIcon.CLOSE
    TrackAction.PLAY_NEXT -> AppIcon.PLAY_NEXT
    TrackAction.PLAY_AFTER -> AppIcon.PLAY_AFTER
    TrackAction.APPEND -> AppIcon.APPEND
    TrackAction.PLAY -> AppIcon.PLAY
    TrackAction.FAVORITE -> if (favorite) AppIcon.FAVORITE_FILLED else AppIcon.FAVORITE
    TrackAction.RATE -> if (rating > 0) AppIcon.RATING_FILLED else AppIcon.RATING
    TrackAction.ADD_TO_PLAYLIST -> if (inPlaylist) AppIcon.PLAYLIST_CHECK else AppIcon.PLAYLIST_PLUS
    TrackAction.DOWNLOAD -> if (availableOffline) AppIcon.AVAILABLE_OFFLINE else AppIcon.DOWNLOAD
    TrackAction.GO_ARTIST -> AppIcon.ARTIST
    TrackAction.GO_ALBUM -> AppIcon.ALBUM
    TrackAction.GO_ALBUM_ARTIST -> AppIcon.ALBUM_ARTIST
    TrackAction.GO_GENRE -> AppIcon.GENRE
    TrackAction.GO_FOLDER -> AppIcon.FOLDER
    TrackAction.SELECT -> AppIcon.CHECK
    TrackAction.SHOW_SOURCES -> AppIcon.LIBRARY
}

/** Tracks whether the current library page shows songs or a collection browser. */
internal class SongGesturePages { val activeLists = mutableStateMapOf<LibraryTab, Int>() }
internal val LocalSongGesturePages = compositionLocalOf<SongGesturePages?> { null }
