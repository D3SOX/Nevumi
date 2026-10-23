package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.history.*
import me.d3sox.nevumi.library.Track
import kotlin.time.Clock
import kotlinx.coroutines.delay
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.albums
import me.d3sox.nevumi.resources.artists
import me.d3sox.nevumi.resources.genres
import me.d3sox.nevumi.resources.most_played
import me.d3sox.nevumi.resources.plays
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.library_unknown_album
import me.d3sox.nevumi.resources.library_unknown_genre
import me.d3sox.nevumi.resources.sources_recommendation_artist
import me.d3sox.nevumi.resources.sources_recommendation_genre
import me.d3sox.nevumi.resources.unknown_artist
import me.d3sox.nevumi.resources.sources_history_empty
import me.d3sox.nevumi.resources.sources_history_entry_partial
import me.d3sox.nevumi.resources.sources_history_entry_played
import me.d3sox.nevumi.resources.sources_listening_statistics
import me.d3sox.nevumi.resources.sources_listening_time
import me.d3sox.nevumi.resources.sources_period_all_time
import me.d3sox.nevumi.resources.sources_period_last_30_days
import me.d3sox.nevumi.resources.sources_period_last_365_days
import me.d3sox.nevumi.resources.sources_period_last_7_days
import me.d3sox.nevumi.resources.sources_recommendations_empty
import me.d3sox.nevumi.resources.sources_recommendations_intro
import me.d3sox.nevumi.resources.sources_statistics_empty
import me.d3sox.nevumi.resources.sources_statistics_genres_hint
import me.d3sox.nevumi.resources.sources_statistics_period
import me.d3sox.nevumi.resources.statistics_grouping
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ListeningHistoryScreen(state: AppState, controller: MusicController, tracks: List<Track>) {
    val catalog = rememberDerived("history-catalog", emptyList(), listOf(tracks)) { tracks.associateBy { it.id } }
    val events = remember(state.listens, catalog) { state.listens.filter { it.trackId in catalog } }
    val songs = remember(events, catalog) { events.map { catalog.getValue(it.trackId) } }
    // Resolved here because the entry text is built outside composition.
    val played = stringResource(Res.string.sources_history_entry_played)
    val partial = stringResource(Res.string.sources_history_entry_partial)
    if (events.isEmpty()) Text(stringResource(Res.string.sources_history_empty), Modifier.padding(24.dp))
    else SongList(songs, controller, Modifier.testTag("listening-history"),
        entryInfo = { index ->
            val event = events[index]
            (if (event.qualified) played else partial).withArgs(formatListenTime(event.startedAt), formatListeningDuration(event.listenedMs))
        }, scrollIndicator = { formatListenTime(events[it].startedAt) }, contextName = "History")
}

@Composable
internal fun ListeningStatisticsScreen(state: AppState, controller: MusicController, tracks: List<Track>, backEnabled: Boolean = true) {
    var period by remember { mutableStateOf(ListeningPeriod.ALL_TIME) }
    var grouping by remember { mutableStateOf("Songs") }
    var selected by rememberSaveable(period, grouping) { mutableStateOf<String?>(null) }
    val now = rememberLibraryTime()
    val report = rememberDerived("statistics-report", listOf(period), listOf(tracks, state.listens, now)) { listeningReport(state.listens, tracks, period, now) }
    val ranks = when (grouping) { "Artists" -> report.artists; "Albums" -> report.albums; "Genres" -> report.genres; else -> report.songs }
    val catalog = rememberDerived("statistics-catalog", emptyList(), listOf(tracks)) { tracks.associateBy { it.id } }
    val unknownName = when (grouping) {
        "Artists" -> Res.string.unknown_artist; "Albums" -> Res.string.library_unknown_album; "Genres" -> Res.string.library_unknown_genre; else -> null
    }?.let { stringResource(it) }
    fun ListeningRank.displayName() = name.ifBlank { unknownName ?: name }
    val songStatistics = remember(report) { report.songs.associate { it.trackIds.single() to ListeningStatistics(it.playCount, it.listenedMs, 0) } }
    AnimatedNavigation(selected, null, canGoBack = backEnabled && selected != null,
        onBack = { selected = null }, depth = { if (it == null) 0 else 1 }) { page ->
    val group = ranks.firstOrNull { "${it.name.length}:${it.name}${it.detail}" == page }
    val header: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingChoice(stringResource(Res.string.sources_statistics_period), period, listOf(ListeningPeriod.ALL_TIME to stringResource(Res.string.sources_period_all_time),
                ListeningPeriod.LAST_7_DAYS to stringResource(Res.string.sources_period_last_7_days), ListeningPeriod.LAST_30_DAYS to stringResource(Res.string.sources_period_last_30_days),
                ListeningPeriod.LAST_365_DAYS to stringResource(Res.string.sources_period_last_365_days))) { period = it }
            SettingChoice(stringResource(Res.string.statistics_grouping), grouping, listOf("Songs" to stringResource(Res.string.songs), "Artists" to stringResource(Res.string.artists), "Albums" to stringResource(Res.string.albums), "Genres" to stringResource(Res.string.genres))) { grouping = it }
            Text(countLabel(report.playCount, Res.plurals.plays), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(Res.string.sources_listening_time, formatListeningDuration(report.listenedMs)))
            Text(stringResource(Res.string.most_played), style = MaterialTheme.typography.titleMedium)
        }
    }
    Column(Modifier.fillMaxSize()) {
    if (group != null) CollectionHeader(group.displayName()) { selected = null }
    if (grouping == "Songs" || group != null) SongList((group?.trackIds ?: report.songs.flatMap { it.trackIds }).mapNotNull(catalog::get),
        controller, Modifier.weight(1f).fillMaxWidth().testTag("statistics-most-played"), statistics = songStatistics, scrollSort = me.d3sox.nevumi.library.LibrarySort.PLAY_COUNT, header = header, contextName = group?.displayName() ?: "Statistics")
    else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("statistics-ranking"), state = rememberLibraryListState(controller, "statistics:$grouping:$period")) {
            item(key = "statistics-header") { header() }
            if (ranks.isEmpty()) item { Text(stringResource(Res.string.sources_statistics_empty), Modifier.padding(24.dp)) }
            if (grouping == "Genres") item { Text(stringResource(Res.string.sources_statistics_genres_hint), Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall) }
            items(ranks, key = { "${it.name.length}:${it.name}${it.detail}" }) { rank ->
                ListItem(modifier = Modifier.clickable { selected = "${rank.name.length}:${rank.name}${rank.detail}" }, headlineContent = { Text(rank.displayName()) },
                    leadingContent = { CollectionArtwork(rank.trackIds.firstNotNullOfOrNull { state.artwork[it] }, rank.displayName(),
                        when (grouping) { "Artists" -> AppIcon.ARTIST; "Albums" -> AppIcon.ALBUM; else -> AppIcon.GENRE }, Modifier.size(48.dp)) },
                    supportingContent = { Column {
                        if (rank.detail.isNotBlank()) Text(rank.detail)
                        Text(formatListeningStatistics(rank.playCount, rank.listenedMs))
                    } })
            }
    }
    }
    }
}

@Composable
internal fun ListeningRecommendationsScreen(state: AppState, controller: MusicController, tracks: List<Track>) {
    val now = rememberLibraryTime()
    val fromArtist = stringResource(Res.string.sources_recommendation_artist)
    val fromGenre = stringResource(Res.string.sources_recommendation_genre)
    val suggestions = rememberDerived("recommendations", emptyList(), listOf(tracks, state.listens, now)) { listeningRecommendations(state.listens, tracks, now = now) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.sources_recommendations_intro),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (suggestions.isEmpty()) Text(stringResource(Res.string.sources_recommendations_empty), Modifier.padding(24.dp))
        else SongList(suggestions.map { it.track }, controller, Modifier.weight(1f).testTag("listening-recommendations"),
            extraInfo = suggestions.associate { it.track.id to (if (it.reason == ListeningRecommendation.Reason.ARTIST) fromArtist else fromGenre).withArgs(it.name) }, scrollIndicator = { "${it + 1} / ${suggestions.size}" }, contextName = "Recommendations")
    }
}

@Composable
internal fun rememberLibraryTime(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Clock.System.now().toEpochMilliseconds() } }
    return now
}

@Composable
internal fun formatListeningStatistics(plays: Int, milliseconds: Long): String =
    stringResource(Res.string.sources_listening_statistics, countLabel(plays, Res.plurals.plays), formatListeningDuration(milliseconds))

internal fun formatListeningDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return when {
        seconds >= 3_600 -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

internal expect fun formatListenTime(epochMillis: Long): String
