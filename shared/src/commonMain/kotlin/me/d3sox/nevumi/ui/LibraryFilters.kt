package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
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

@Composable
internal fun LibraryFiltersDialog(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    var query by remember { mutableStateOf<LibraryQuery?>(state.preferences.libraryFilter) }
    KeyboardAlertDialog(onDismissRequest = dismiss, title = { Text("Filter library") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryQueryFields(state.preferences.libraryFilter, includeSearchAndOrder = false) { query = it }
            query?.let { Text(countLabel(filterTracks(state.tracks, it, listeningStatistics(state.listens),
                playlisted = playlistedTrackIds(state.playlists)).size, "matching song")) }
        }
    }, confirmButton = { TextButton(enabled = query != null, onClick = {
        val valid = requireNotNull(query)
        controller.updatePreferences { it.copy(libraryFilter = valid) }; dismiss()
    }) { Text("Apply filters") } }, dismissButton = {
        Row {
            TextButton(onClick = { controller.updatePreferences { it.copy(libraryFilter = LibraryQuery()) }; dismiss() }) { Text("Clear filters") }
            TextButton(onClick = dismiss) { Text("Cancel") }
        }
    })
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
    val result = runCatching {
        fun integer(value: String): Int? = if (value.isBlank()) null else requireNotNull(value.toIntOrNull()) { "Enter a whole number" }
        fun duration(value: String): Long? {
            if (value.isBlank()) return null
            val seconds = requireNotNull(value.toLongOrNull()) { "Enter a whole number of seconds" }
            require(seconds in 0..Long.MAX_VALUE / 1000) { "Enter a valid duration" }
            return seconds * 1000
        }
        base.copy(yearFrom = integer(yearFrom), yearTo = integer(yearTo), durationFromMs = duration(durationFrom),
            durationToMs = duration(durationTo), limit = integer(limit), ratingFrom = integer(ratingFrom), ratingTo = integer(ratingTo),
            playCountFrom = integer(playsFrom), playCountTo = integer(playsTo), lastPlayedWithinDays = integer(playedDays),
            notPlayedWithinDays = integer(unplayedDays))
    }
    SideEffect { change(result.getOrNull()) }
    if (includeSearchAndOrder) OutlinedTextField(base.text, { base = base.copy(text = it) }, label = { Text("Search text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(base.genre.orEmpty(), { base = base.copy(genre = it.takeIf(String::isNotBlank)) }, label = { Text("Genre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (includeSearchAndOrder) {
        QueryToggle("Favorites only", base.favoritesOnly) { base = base.copy(favoritesOnly = it) }
        Box {
            SettingChoice("Music sources", base.sourceKind, listOf<Pair<SourceKind?, String>>(
                null to "All sources", SourceKind.LOCAL to "Device", SourceKind.SERVER to "Server", SourceKind.DOWNLOAD to "Downloads")) {
                base = base.copy(sourceKind = it)
            }
        }
        Box {
            SettingChoice("Song order", base.sort, listOf(LibrarySort.TITLE to "Title", LibrarySort.ARTIST to "Artist",
                LibrarySort.ALBUM to "Album", LibrarySort.RECENT to "Recently added", LibrarySort.RATING to "Rating",
                LibrarySort.PLAY_COUNT to "Most played", LibrarySort.LAST_PLAYED to "Last played")) { base = base.copy(sort = it) }
        }
        FilterChip(base.descending, { base = base.copy(descending = !base.descending) }, label = { Text("Reverse") })
    }
    OutlinedTextField(base.artist.orEmpty(), { base = base.copy(artist = it.takeIf(String::isNotBlank)) }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(base.album.orEmpty(), { base = base.copy(album = it.takeIf(String::isNotBlank)) }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(base.albumArtist.orEmpty(), { base = base.copy(albumArtist = it.takeIf(String::isNotBlank)) }, label = { Text("Album artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(base.folder.orEmpty(), { base = base.copy(folder = it.takeIf(String::isNotBlank)) }, label = { Text("Folder") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(yearFrom, { yearFrom = it }, label = { Text("Year from") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(yearTo, { yearTo = it }, label = { Text("Year to") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(durationFrom, { durationFrom = it }, label = { Text("Minimum seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(durationTo, { durationTo = it }, label = { Text("Maximum seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(ratingFrom, { ratingFrom = it }, label = { Text("Minimum rating") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(ratingTo, { ratingTo = it }, label = { Text("Maximum rating") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Ratings range from 1 to 5. Use 0 for unrated songs.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(playsFrom, { playsFrom = it }, label = { Text("Minimum plays") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(playsTo, { playsTo = it }, label = { Text("Maximum plays") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(playedDays, { playedDays = it }, label = { Text("Played within days") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(unplayedDays, { unplayedDays = it }, label = { Text("Not played within days") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    QueryToggle("Available sources only", base.availableOnly) { base = base.copy(availableOnly = it) }
    QueryToggle("Not on server", base.notOnServer) { base = base.copy(notOnServer = it) }
    QueryToggle("Not in any playlist", base.notInPlaylist) { base = base.copy(notInPlaylist = it) }
    OutlinedTextField(limit, { limit = it }, label = { Text("Song limit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    result.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun QueryToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { change(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Checkbox(checked, null)
    }
}
