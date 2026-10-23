package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.history.listeningStatistics
import me.d3sox.nevumi.playlists.playlistedTrackIds
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.album
import me.d3sox.nevumi.resources.album_artist
import me.d3sox.nevumi.resources.artist
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.downloads
import me.d3sox.nevumi.resources.library_all_sources
import me.d3sox.nevumi.resources.library_clear_all_filters
import me.d3sox.nevumi.resources.library_device
import me.d3sox.nevumi.resources.library_filter_plays
import me.d3sox.nevumi.resources.library_filter_range
import me.d3sox.nevumi.resources.library_filter_seconds
import me.d3sox.nevumi.resources.library_filter_year
import me.d3sox.nevumi.resources.library_filter_value
import me.d3sox.nevumi.resources.library_remove_filter
import me.d3sox.nevumi.resources.matching_songs
import me.d3sox.nevumi.resources.most_played
import me.d3sox.nevumi.resources.music_sources
import me.d3sox.nevumi.resources.recently_added
import me.d3sox.nevumi.resources.server
import me.d3sox.nevumi.resources.sources_all_sources
import me.d3sox.nevumi.resources.sources_apply_filters
import me.d3sox.nevumi.resources.sources_available_sources_only
import me.d3sox.nevumi.resources.sources_clear_filters
import me.d3sox.nevumi.resources.sources_device
import me.d3sox.nevumi.resources.sources_enter_valid_duration
import me.d3sox.nevumi.resources.sources_enter_whole_number
import me.d3sox.nevumi.resources.sources_enter_whole_seconds
import me.d3sox.nevumi.resources.sources_favorites_only
import me.d3sox.nevumi.resources.sources_filter_library
import me.d3sox.nevumi.resources.sources_folder
import me.d3sox.nevumi.resources.sources_genre
import me.d3sox.nevumi.resources.sources_last_played
import me.d3sox.nevumi.resources.sources_maximum_plays
import me.d3sox.nevumi.resources.sources_maximum_rating
import me.d3sox.nevumi.resources.sources_maximum_seconds
import me.d3sox.nevumi.resources.sources_minimum_plays
import me.d3sox.nevumi.resources.sources_minimum_rating
import me.d3sox.nevumi.resources.sources_minimum_seconds
import me.d3sox.nevumi.resources.sources_not_in_any_playlist
import me.d3sox.nevumi.resources.sources_not_on_server
import me.d3sox.nevumi.resources.sources_not_played_within_days
import me.d3sox.nevumi.resources.sources_played_within_days
import me.d3sox.nevumi.resources.sources_rating
import me.d3sox.nevumi.resources.sources_rating_range_hint
import me.d3sox.nevumi.resources.sources_reverse
import me.d3sox.nevumi.resources.sources_search_text
import me.d3sox.nevumi.resources.sources_song_limit
import me.d3sox.nevumi.resources.sources_song_order
import me.d3sox.nevumi.resources.sources_year_from
import me.d3sox.nevumi.resources.sources_year_to
import me.d3sox.nevumi.resources.title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryFiltersDialog(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    var query by remember { mutableStateOf<LibraryQuery?>(state.preferences.libraryFilter) }
    KeyboardAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.sources_filter_library)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryQueryFields(state.preferences.libraryFilter, includeSearchAndOrder = false) { query = it }
            query?.let { Text(countLabel(filterTracks(state.tracks, it, listeningStatistics(state.listens),
                playlisted = playlistedTrackIds(state.playlists)).size, Res.plurals.matching_songs)) }
        }
    }, confirmButton = { TextButton(enabled = query != null, onClick = {
        val valid = requireNotNull(query)
        controller.updatePreferences { it.copy(libraryFilter = valid) }; dismiss()
    }) { Text(stringResource(Res.string.sources_apply_filters)) } }, dismissButton = {
        Row {
            TextButton(onClick = { controller.updatePreferences { it.copy(libraryFilter = LibraryQuery()) }; dismiss() }) { Text(stringResource(Res.string.sources_clear_filters)) }
            TextButton(onClick = dismiss) { Text(stringResource(Res.string.cancel)) }
        }
    })
}

/**
 * Shows the chosen [source] and each active library filter as chips. A chip opens its picker with [editSource] or [edit],
 * and its close icon removes it through [changeSource] or [change].
 */
@Composable
internal fun ActiveLibraryFilters(filter: LibraryQuery, source: SourceKind?, edit: () -> Unit, editSource: () -> Unit,
    change: (LibraryQuery) -> Unit, changeSource: (SourceKind?) -> Unit) {
    val active = activeFilters(filter)
    if (active.isEmpty() && source == null) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable
        fun FilterChip(label: String, open: () -> Unit, remove: () -> Unit, icon: AppIcon? = null) {
            InputChip(true, open, label = { Text(label, maxLines = 1) }, leadingIcon = icon?.let { { Icon(it.vector, null, Modifier.size(InputChipDefaults.IconSize)) } },
                // The default primary icon color fades on a selected chip; match the label instead.
                colors = InputChipDefaults.inputChipColors(selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer),
                trailingIcon = {
                    Icon(AppIcon.CLOSE.vector, stringResource(Res.string.library_remove_filter, label),
                        Modifier.size(InputChipDefaults.AvatarSize).clickable(onClick = remove))
                })
        }
        if (source != null) FilterChip(stringResource(source.sourceName()), editSource, { changeSource(null) }, source.sourceIcon())
        active.forEach { item ->
            val name = stringResource(item.label)
            val value = item.to?.let { stringResource(Res.string.library_filter_range, item.value.orEmpty(), it) } ?: item.value
            FilterChip(value?.let { stringResource(Res.string.library_filter_value, name, it) } ?: name, edit, { change(item.clear(filter)) })
        }
        TextButton({ change(LibraryQuery()); changeSource(null) }) { Text(stringResource(Res.string.library_clear_all_filters)) }
    }
}

internal fun LibraryQuery.hasActiveFilters() = activeFilters(this).isNotEmpty()

internal fun SourceKind?.sourceName(): StringResource = when (this) {
    SourceKind.LOCAL -> Res.string.library_device
    SourceKind.SERVER -> Res.string.server
    SourceKind.DOWNLOAD -> Res.string.downloads
    null -> Res.string.library_all_sources
}

internal fun SourceKind?.sourceIcon(): AppIcon = when (this) {
    SourceKind.LOCAL -> AppIcon.DEVICE
    SourceKind.SERVER -> AppIcon.CLOUD
    SourceKind.DOWNLOAD -> AppIcon.DOWNLOAD
    null -> AppIcon.LIBRARY
}

/** A filter chip; a range with both ends shows [value] to [to] in one chip. */
private class ActiveFilter(val label: StringResource, val value: String?, val clear: (LibraryQuery) -> LibraryQuery, val to: String? = null)

/** Lists the fields of [LibraryFiltersDialog] that differ from their defaults, in the dialog's order. */
private fun activeFilters(filter: LibraryQuery): List<ActiveFilter> = buildList {
    fun value(label: StringResource, value: Any?, clear: (LibraryQuery) -> LibraryQuery) { if (value != null) add(ActiveFilter(label, value.toString(), clear)) }
    fun toggle(label: StringResource, on: Boolean, clear: (LibraryQuery) -> LibraryQuery) { if (on) add(ActiveFilter(label, null, clear)) }
    fun range(label: StringResource, from: Pair<StringResource, Any?>, to: Pair<StringResource, Any?>,
        clearFrom: (LibraryQuery) -> LibraryQuery, clearTo: (LibraryQuery) -> LibraryQuery) {
        if (from.second != null && to.second != null) add(ActiveFilter(label, from.second.toString(), { clearTo(clearFrom(it)) }, to.second.toString()))
        else { value(from.first, from.second, clearFrom); value(to.first, to.second, clearTo) }
    }
    value(Res.string.sources_genre, filter.genre) { it.copy(genre = null) }
    value(Res.string.artist, filter.artist) { it.copy(artist = null) }
    value(Res.string.album, filter.album) { it.copy(album = null) }
    value(Res.string.album_artist, filter.albumArtist) { it.copy(albumArtist = null) }
    value(Res.string.sources_folder, filter.folder) { it.copy(folder = null) }
    range(Res.string.library_filter_year, Res.string.sources_year_from to filter.yearFrom, Res.string.sources_year_to to filter.yearTo,
        { it.copy(yearFrom = null) }) { it.copy(yearTo = null) }
    range(Res.string.library_filter_seconds, Res.string.sources_minimum_seconds to filter.durationFromMs?.div(1000),
        Res.string.sources_maximum_seconds to filter.durationToMs?.div(1000), { it.copy(durationFromMs = null) }) { it.copy(durationToMs = null) }
    range(Res.string.sources_rating, Res.string.sources_minimum_rating to filter.ratingFrom, Res.string.sources_maximum_rating to filter.ratingTo,
        { it.copy(ratingFrom = null) }) { it.copy(ratingTo = null) }
    range(Res.string.library_filter_plays, Res.string.sources_minimum_plays to filter.playCountFrom, Res.string.sources_maximum_plays to filter.playCountTo,
        { it.copy(playCountFrom = null) }) { it.copy(playCountTo = null) }
    value(Res.string.sources_played_within_days, filter.lastPlayedWithinDays) { it.copy(lastPlayedWithinDays = null) }
    value(Res.string.sources_not_played_within_days, filter.notPlayedWithinDays) { it.copy(notPlayedWithinDays = null) }
    toggle(Res.string.sources_available_sources_only, filter.availableOnly) { it.copy(availableOnly = false) }
    toggle(Res.string.sources_not_on_server, filter.notOnServer) { it.copy(notOnServer = false) }
    toggle(Res.string.sources_not_in_any_playlist, filter.notInPlaylist) { it.copy(notInPlaylist = false) }
    value(Res.string.sources_song_limit, filter.limit) { it.copy(limit = null) }
}

@Composable
internal fun LibraryQueryFields(initial: LibraryQuery, includeSearchAndOrder: Boolean = true, change: (LibraryQuery?) -> Unit) {
    var base by remember { mutableStateOf(initial) }
    var yearFrom by remember { mutableStateOf(initial.yearFrom?.toString().orEmpty()) }
    var yearTo by remember { mutableStateOf(initial.yearTo?.toString().orEmpty()) }
    var durationFrom by remember { mutableStateOf(initial.durationFromMs?.div(1000)?.toString().orEmpty()) }
    var durationTo by remember { mutableStateOf(initial.durationToMs?.div(1000)?.toString().orEmpty()) }
    var limit by remember { mutableStateOf(initial.limit?.toString().orEmpty()) }
    var ratingFrom by remember { mutableStateOf(initial.ratingFrom?.toString().orEmpty()) }
    var ratingTo by remember { mutableStateOf(initial.ratingTo?.toString().orEmpty()) }
    var playsFrom by remember { mutableStateOf(initial.playCountFrom?.toString().orEmpty()) }
    var playsTo by remember { mutableStateOf(initial.playCountTo?.toString().orEmpty()) }
    var playedDays by remember { mutableStateOf(initial.lastPlayedWithinDays?.toString().orEmpty()) }
    var unplayedDays by remember { mutableStateOf(initial.notPlayedWithinDays?.toString().orEmpty()) }
    val wholeNumber = stringResource(Res.string.sources_enter_whole_number)
    val wholeSeconds = stringResource(Res.string.sources_enter_whole_seconds)
    val validDuration = stringResource(Res.string.sources_enter_valid_duration)
    val result = runCatching {
        fun integer(value: String): Int? = if (value.isBlank()) null else requireNotNull(value.toIntOrNull()) { wholeNumber }
        fun duration(value: String): Long? {
            if (value.isBlank()) return null
            val seconds = requireNotNull(value.toLongOrNull()) { wholeSeconds }
            require(seconds in 0..Long.MAX_VALUE / 1000) { validDuration }
            return seconds * 1000
        }
        base.copy(yearFrom = integer(yearFrom), yearTo = integer(yearTo), durationFromMs = duration(durationFrom),
            durationToMs = duration(durationTo), limit = integer(limit), ratingFrom = integer(ratingFrom), ratingTo = integer(ratingTo),
            playCountFrom = integer(playsFrom), playCountTo = integer(playsTo), lastPlayedWithinDays = integer(playedDays),
            notPlayedWithinDays = integer(unplayedDays))
    }
    SideEffect { change(result.getOrNull()) }
    if (includeSearchAndOrder) OutlinedTextField(base.text, { base = base.copy(text = it) }, label = { Text(stringResource(Res.string.sources_search_text)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(base.genre.orEmpty(), { base = base.copy(genre = it.takeIf(String::isNotBlank)) }, label = { Text(stringResource(Res.string.sources_genre)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (includeSearchAndOrder) {
        QueryToggle(stringResource(Res.string.sources_favorites_only), base.favoritesOnly) { base = base.copy(favoritesOnly = it) }
        Box {
            SettingChoice(stringResource(Res.string.music_sources), base.sourceKind, listOf<Pair<SourceKind?, String>>(
                null to stringResource(Res.string.sources_all_sources), SourceKind.LOCAL to stringResource(Res.string.sources_device), SourceKind.SERVER to stringResource(Res.string.server), SourceKind.DOWNLOAD to stringResource(Res.string.downloads))) {
                base = base.copy(sourceKind = it)
            }
        }
        Box {
            SettingChoice(stringResource(Res.string.sources_song_order), base.sort, listOf(LibrarySort.TITLE to stringResource(Res.string.title), LibrarySort.ARTIST to stringResource(Res.string.artist),
                LibrarySort.ALBUM to stringResource(Res.string.album), LibrarySort.RECENT to stringResource(Res.string.recently_added), LibrarySort.RATING to stringResource(Res.string.sources_rating),
                LibrarySort.PLAY_COUNT to stringResource(Res.string.most_played), LibrarySort.LAST_PLAYED to stringResource(Res.string.sources_last_played))) { base = base.copy(sort = it) }
        }
        FilterChip(base.descending, { base = base.copy(descending = !base.descending) }, label = { Text(stringResource(Res.string.sources_reverse)) })
    }
    OutlinedTextField(base.artist.orEmpty(), { base = base.copy(artist = it.takeIf(String::isNotBlank)) }, label = { Text(stringResource(Res.string.artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(base.album.orEmpty(), { base = base.copy(album = it.takeIf(String::isNotBlank)) }, label = { Text(stringResource(Res.string.album)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(base.albumArtist.orEmpty(), { base = base.copy(albumArtist = it.takeIf(String::isNotBlank)) }, label = { Text(stringResource(Res.string.album_artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(base.folder.orEmpty(), { base = base.copy(folder = it.takeIf(String::isNotBlank)) }, label = { Text(stringResource(Res.string.sources_folder)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(yearFrom, { yearFrom = it }, label = { Text(stringResource(Res.string.sources_year_from)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(yearTo, { yearTo = it }, label = { Text(stringResource(Res.string.sources_year_to)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(durationFrom, { durationFrom = it }, label = { Text(stringResource(Res.string.sources_minimum_seconds)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(durationTo, { durationTo = it }, label = { Text(stringResource(Res.string.sources_maximum_seconds)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(ratingFrom, { ratingFrom = it }, label = { Text(stringResource(Res.string.sources_minimum_rating)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(ratingTo, { ratingTo = it }, label = { Text(stringResource(Res.string.sources_maximum_rating)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(stringResource(Res.string.sources_rating_range_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(playsFrom, { playsFrom = it }, label = { Text(stringResource(Res.string.sources_minimum_plays)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(playsTo, { playsTo = it }, label = { Text(stringResource(Res.string.sources_maximum_plays)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(playedDays, { playedDays = it }, label = { Text(stringResource(Res.string.sources_played_within_days)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(unplayedDays, { unplayedDays = it }, label = { Text(stringResource(Res.string.sources_not_played_within_days)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    QueryToggle(stringResource(Res.string.sources_available_sources_only), base.availableOnly) { base = base.copy(availableOnly = it) }
    QueryToggle(stringResource(Res.string.sources_not_on_server), base.notOnServer) { base = base.copy(notOnServer = it) }
    QueryToggle(stringResource(Res.string.sources_not_in_any_playlist), base.notInPlaylist) { base = base.copy(notInPlaylist = it) }
    OutlinedTextField(limit, { limit = it }, label = { Text(stringResource(Res.string.sources_song_limit)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    result.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun QueryToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { change(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Checkbox(checked, null)
    }
}
