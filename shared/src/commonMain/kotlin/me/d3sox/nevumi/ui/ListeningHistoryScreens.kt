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

@Composable
internal fun ListeningHistoryScreen(state: AppState, controller: MusicController, tracks: List<Track>) {
    val catalog = rememberDerived("history-catalog", listOf(tracks)) { tracks.associateBy { it.id } }
    val events = remember(state.listens, catalog) { state.listens.filter { it.trackId in catalog } }
    val songs = remember(events, catalog) { events.map { catalog.getValue(it.trackId) } }
    if (events.isEmpty()) Text("Your listening history will appear here.", Modifier.padding(24.dp))
    else SongList(songs, controller, Modifier.testTag("listening-history"),
        entryInfo = { index ->
            val event = events[index]
            "${formatListenTime(event.startedAt)} · ${formatListeningDuration(event.listenedMs)} listened · ${if (event.qualified) "Played" else "Partial listen"}"
        }, scrollIndicator = { formatListenTime(events[it].startedAt) }, contextName = "History")
}

@Composable
internal fun ListeningStatisticsScreen(state: AppState, controller: MusicController, tracks: List<Track>, backEnabled: Boolean = true) {
    var period by remember { mutableStateOf(ListeningPeriod.ALL_TIME) }
    var grouping by remember { mutableStateOf("Songs") }
    var selected by rememberSaveable(period, grouping) { mutableStateOf<String?>(null) }
    val now = rememberLibraryTime()
    val report = rememberDerived("statistics-report", listOf(tracks, period), listOf(state.listens, now)) { listeningReport(state.listens, tracks, period, now) }
    val ranks = when (grouping) { "Artists" -> report.artists; "Albums" -> report.albums; "Genres" -> report.genres; else -> report.songs }
    val catalog = rememberDerived("statistics-catalog", listOf(tracks)) { tracks.associateBy { it.id } }
    val songStatistics = remember(report) { report.songs.associate { it.trackIds.single() to ListeningStatistics(it.playCount, it.listenedMs, 0) } }
    AnimatedNavigation(selected, null, canGoBack = backEnabled && selected != null,
        onBack = { selected = null }, depth = { if (it == null) 0 else 1 }) { page ->
    val group = ranks.firstOrNull { "${it.name.length}:${it.name}${it.detail}" == page }
    val header: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingChoice("Statistics period", period, listOf(ListeningPeriod.ALL_TIME to "All time",
                ListeningPeriod.LAST_7_DAYS to "Last 7 days", ListeningPeriod.LAST_30_DAYS to "Last 30 days",
                ListeningPeriod.LAST_365_DAYS to "Last 365 days")) { period = it }
            SettingChoice("Statistics grouping", grouping, listOf("Songs", "Artists", "Albums", "Genres").map { it to it }) { grouping = it }
            Text(if (report.playCount == 1) "1 play" else "${report.playCount} plays", style = MaterialTheme.typography.titleLarge)
            Text("Listening time: ${formatListeningDuration(report.listenedMs)}")
            Text("Most played", style = MaterialTheme.typography.titleMedium)
        }
    }
    Column(Modifier.fillMaxSize()) {
    if (group != null) CollectionHeader(group.name) { selected = null }
    if (grouping == "Songs" || group != null) SongList((group?.trackIds ?: report.songs.flatMap { it.trackIds }).mapNotNull(catalog::get),
        controller, Modifier.weight(1f).fillMaxWidth().testTag("statistics-most-played"), statistics = songStatistics, scrollSort = me.d3sox.nevumi.library.LibrarySort.PLAY_COUNT, header = header, contextName = group?.name ?: "Statistics")
    else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("statistics-ranking"), state = rememberLibraryListState(controller, "statistics:$grouping:$period")) {
            item(key = "statistics-header") { header() }
            if (ranks.isEmpty()) item { Text("Play some music to build your statistics.", Modifier.padding(24.dp)) }
            if (grouping == "Genres") item { Text("Songs with multiple genres contribute to each genre.", Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall) }
            items(ranks, key = { "${it.name.length}:${it.name}${it.detail}" }) { rank ->
                ListItem(modifier = Modifier.clickable { selected = "${rank.name.length}:${rank.name}${rank.detail}" }, headlineContent = { Text(rank.name) },
                    leadingContent = { CollectionArtwork(rank.trackIds.firstNotNullOfOrNull { state.artwork[it] }, rank.name,
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
    val suggestions = rememberDerived("recommendations", listOf(tracks), listOf(state.listens, now)) { listeningRecommendations(state.listens, tracks, now = now) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("From your listening history. Songs played in the last 7 days are left out.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (suggestions.isEmpty()) Text("Play some music to discover more from your library.", Modifier.padding(24.dp))
        else SongList(suggestions.map { it.track }, controller, Modifier.weight(1f).testTag("listening-recommendations"),
            extraInfo = suggestions.associate { it.track.id to it.reason }, scrollIndicator = { "${it + 1} / ${suggestions.size}" }, contextName = "Recommendations")
    }
}

@Composable
internal fun rememberLibraryTime(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Clock.System.now().toEpochMilliseconds() } }
    return now
}

internal fun formatListeningStatistics(plays: Int, milliseconds: Long): String =
    (if (plays == 1) "1 play" else "$plays plays") + " · ${formatListeningDuration(milliseconds)} listened"

internal fun formatListeningDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return when {
        seconds >= 3_600 -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

internal expect fun formatListenTime(epochMillis: Long): String
