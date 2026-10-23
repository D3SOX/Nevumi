package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.player_download_failed
import me.d3sox.nevumi.resources.player_download_paused
import me.d3sox.nevumi.resources.player_download_queued
import me.d3sox.nevumi.resources.player_downloaded
import me.d3sox.nevumi.resources.player_downloading_bytes
import me.d3sox.nevumi.resources.player_duration
import me.d3sox.nevumi.resources.player_favorited
import me.d3sox.nevumi.resources.player_local_file_deleted
import me.d3sox.nevumi.resources.player_quick_action_description
import me.d3sox.nevumi.resources.player_rating_stars
import me.d3sox.nevumi.resources.player_select_track
import me.d3sox.nevumi.resources.player_server_file_deleted
import me.d3sox.nevumi.resources.player_song_artwork
import me.d3sox.nevumi.resources.player_track_options
import me.d3sox.nevumi.resources.sources_listening_statistics
import me.d3sox.nevumi.resources.unknown_artist
import me.d3sox.nevumi.settings.TrackAction
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text shared by every [TrackRow] of a list, resolved once by the list instead of per row so scrolling new rows in
 * does not load resources. Templates keep their `%1$s` placeholders and are filled with [withArgs].
 */
internal data class TrackRowStrings(
    private val actionLabels: Map<StringResource, String>,
    private val ratings: List<String>,
    val selectTemplate: String,
    val durationTemplate: String,
    val quickActionTemplate: String,
    val optionsTemplate: String,
    val artworkTemplate: String,
    val downloadingTemplate: String,
    val statisticsTemplate: String,
    val unknownArtist: String,
    val favorited: String,
    val downloaded: String,
    val downloadQueued: String,
    val downloadPaused: String,
    val downloadFailed: String,
    val localFileDeleted: String,
    val serverFileDeleted: String,
) {
    fun actionLabel(action: TrackAction, favorite: Boolean, availableOffline: Boolean) =
        actionLabels.getValue(action.labelResource(favorite, availableOffline))

    /** Ratings are whole stars from 1 to 5, so every plural form is resolved up front. */
    fun rating(stars: Int) = ratings[stars.coerceIn(0, ratings.lastIndex)]
}

/** Provided by song lists; rows outside a list resolve their own copy. */
internal val LocalTrackRowStrings = staticCompositionLocalOf<TrackRowStrings?> { null }

@Composable
internal fun trackRowStrings(): TrackRowStrings {
    val labelResources = TrackAction.entries.flatMap { action ->
        listOf(false, true).flatMap { favorite -> listOf(false, true).map { offline -> action.labelResource(favorite, offline) } }
    }.distinct()
    return TrackRowStrings(
        actionLabels = labelResources.associateWith { stringResource(it) },
        ratings = (0..5).map { pluralStringResource(Res.plurals.player_rating_stars, it, it) },
        selectTemplate = stringResource(Res.string.player_select_track),
        durationTemplate = stringResource(Res.string.player_duration),
        quickActionTemplate = stringResource(Res.string.player_quick_action_description),
        optionsTemplate = stringResource(Res.string.player_track_options),
        artworkTemplate = stringResource(Res.string.player_song_artwork),
        downloadingTemplate = stringResource(Res.string.player_downloading_bytes),
        statisticsTemplate = stringResource(Res.string.sources_listening_statistics),
        unknownArtist = stringResource(Res.string.unknown_artist),
        favorited = stringResource(Res.string.player_favorited),
        downloaded = stringResource(Res.string.player_downloaded),
        downloadQueued = stringResource(Res.string.player_download_queued),
        downloadPaused = stringResource(Res.string.player_download_paused),
        downloadFailed = stringResource(Res.string.player_download_failed),
        localFileDeleted = stringResource(Res.string.player_local_file_deleted),
        serverFileDeleted = stringResource(Res.string.player_server_file_deleted),
    )
}

/** Fills the `%1$s`/`%1$d` placeholders of a string resolved without arguments, for text built outside composition. */
internal fun String.withArgs(vararg args: Any): String =
    args.foldIndexed(this) { index, text, arg -> text.replace("%${index + 1}\$s", arg.toString()).replace("%${index + 1}\$d", arg.toString()) }
