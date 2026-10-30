package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.downloads.DownloadRecord
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.downloads.PlaylistDownloadProgress
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.playlists.folder
import me.d3sox.nevumi.playlists.title
import me.d3sox.nevumi.downloads.DownloadProblem
import me.d3sox.nevumi.soundcloud.SoundCloudJobState
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.downloads
import me.d3sox.nevumi.resources.library_tab_playlists
import me.d3sox.nevumi.resources.library_tab_songs
import me.d3sox.nevumi.resources.player_search_songs
import me.d3sox.nevumi.resources.settings_soundcloud
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.sources_action_for_item
import me.d3sox.nevumi.resources.sources_cancel_download
import me.d3sox.nevumi.resources.sources_count_downloaded
import me.d3sox.nevumi.resources.sources_download_progress
import me.d3sox.nevumi.resources.sources_download_status_complete
import me.d3sox.nevumi.resources.sources_download_status_downloading
import me.d3sox.nevumi.resources.sources_download_status_failed
import me.d3sox.nevumi.resources.sources_download_status_insufficient_space
import me.d3sox.nevumi.resources.sources_download_status_paused
import me.d3sox.nevumi.resources.sources_download_status_pending
import me.d3sox.nevumi.resources.sources_downloads_empty
import me.d3sox.nevumi.resources.sources_downloads_paused
import me.d3sox.nevumi.resources.sources_pause_downloads
import me.d3sox.nevumi.resources.sources_playlist_download_progress
import me.d3sox.nevumi.resources.sources_progress_downloaded
import me.d3sox.nevumi.resources.sources_remove_download
import me.d3sox.nevumi.resources.sources_resume_downloads
import me.d3sox.nevumi.resources.sources_retry_failed_downloads
import me.d3sox.nevumi.resources.sources_unavailable_song
import me.d3sox.nevumi.resources.sources_waiting_for_network
import me.d3sox.nevumi.resources.sources_waiting_for_unmetered_network
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadsScreen(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    fun count(group: DownloadGroup) = when (group) {
        DownloadGroup.PLAYLISTS -> state.playlistDownloads.size
        DownloadGroup.SOUNDCLOUD -> state.soundCloudJobs.size
        DownloadGroup.SONGS -> state.downloads.size
    }
    val groups = DownloadGroup.entries.filter { count(it) > 0 }
    // Open where something needs the user; the choice stays put while downloads finish so the page does not jump.
    // It is kept above the tag editor so returning from it stays on SoundCloud.
    var selected by rememberSaveable {
        mutableStateOf(when {
            state.soundCloudJobs.any { it.attention || it.state in SOUNDCLOUD_NEEDS_USER } -> DownloadGroup.SOUNDCLOUD
            state.downloads.values.any { it.status == DownloadStatus.FAILED } -> DownloadGroup.SONGS
            else -> groups.firstOrNull()
        })
    }
    var tagging by rememberSaveable { mutableStateOf<String?>(null) }
    val taggedJob = tagging?.let { id -> state.soundCloudJobs.firstOrNull { it.id == id && it.state == SoundCloudJobState.NEEDS_TAGS } }
    if (taggedJob != null) {
        SoundCloudTagEditor(taggedJob, controller) { tagging = null }
        return
    }
    // The tags were written or the download was canceled elsewhere, so a later wait for tags does not open the editor by itself.
    if (tagging != null) SideEffect { tagging = null }
    val paused = state.preferences.downloadsPaused
    val playlists = state.playlistDownloads
    val batch = state.downloadBatch
    // Progress updates arrive several times a second, so avoid scanning the library for each song.
    val metadata = remember(state.tracks) { state.tracks.associate { it.id to it.metadata } }
    // Unfinished songs come first so failures and progress are not buried under thousands of finished ones.
    val downloads = remember(state.downloads, metadata) {
        state.downloads.values.sortedWith(compareBy({ it.status == DownloadStatus.COMPLETE }, { metadata[it.trackId]?.title }))
    }
    var query by rememberSaveable { mutableStateOf("") }
    val shownSongs = remember(downloads, query) {
        if (query.isBlank()) downloads else downloads.filter { record ->
            metadata[record.trackId]?.let { it.title.contains(query, true) || it.artist.contains(query, true) } == true
        }
    }
    val group = selected?.takeIf { it in groups } ?: groups.firstOrNull()
    // Remember the group on screen, so groups that appear later do not take over the page.
    if (group != selected) SideEffect { selected = group }
    val waiting = state.downloads.values.any { it.status != DownloadStatus.COMPLETE } || batch.total > 0
    SettingsScreen(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.downloads)) }, text = {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                paused -> stringResource(Res.string.sources_downloads_paused)
                waiting && !state.downloadNetwork.connected -> stringResource(Res.string.sources_waiting_for_network)
                waiting && state.downloadNetwork.metered && !state.preferences.downloadOnMetered -> stringResource(Res.string.sources_waiting_for_unmetered_network)
                else -> stringResource(Res.string.sources_count_downloaded, countLabel(state.downloads.count { it.value.status == DownloadStatus.COMPLETE }, Res.plurals.songs))
            })
            if (batch.total > 0) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(Res.string.sources_progress_downloaded, batch.downloaded, countLabel(batch.total, Res.plurals.songs)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                val progressDescription = stringResource(Res.string.sources_download_progress)
                LinearProgressIndicator(progress = { batch.fraction },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressDescription })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(if (paused) stringResource(Res.string.sources_resume_downloads) else stringResource(Res.string.sources_pause_downloads), if (paused) AppIcon.PLAY else AppIcon.PAUSE) {
                    controller.setDownloadsPaused(!paused)
                }
                if (state.downloads.values.any { it.status == DownloadStatus.FAILED })
                    ActionButton(stringResource(Res.string.sources_retry_failed_downloads), AppIcon.REFRESH, style = ActionStyle.OUTLINED, onClick = controller::retryDownloads)
            }
            if (groups.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { item ->
                    val label = stringResource(item.label)
                    FilterChip(group == item, { selected = item }, label = { Text("$label · ${count(item)}") },
                        modifier = Modifier.semantics { contentDescription = label })
                }
            }
            if (group == DownloadGroup.SONGS && (downloads.size > SONG_SEARCH_THRESHOLD || query.isNotEmpty()))
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.player_search_songs)) },
                    leadingIcon = { Icon(AppIcon.SEARCH.vector, null) }, singleLine = true)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (group) {
                    DownloadGroup.PLAYLISTS -> items(playlists, key = { "playlist:${it.playlist.id}" }) { PlaylistDownloadItem(it) }
                    DownloadGroup.SOUNDCLOUD -> items(state.soundCloudJobs, key = { "soundcloud:${it.id}" }) { job ->
                        // Settings only know the viewer of the server they use now, which may not be the one running this job.
                        val viewer = state.soundCloud?.let { it.baseUrl == job.baseUrl && it.browserViewUrl != null } == true
                        SoundCloudJobItem(job, controller, viewer) { tagging = job.id }
                    }
                    DownloadGroup.SONGS -> items(shownSongs, key = { it.trackId }) { record ->
                        SongDownloadItem(record, metadata[record.trackId], controller)
                    }
                    null -> item { Text(stringResource(Res.string.sources_downloads_empty)) }
                }
            }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
}

private enum class DownloadGroup(val label: StringResource) {
    PLAYLISTS(Res.string.library_tab_playlists), SOUNDCLOUD(Res.string.settings_soundcloud), SONGS(Res.string.library_tab_songs),
}

private val SOUNDCLOUD_NEEDS_USER = setOf(SoundCloudJobState.NEEDS_LINK, SoundCloudJobState.NEEDS_TRACK, SoundCloudJobState.NEEDS_TAGS,
    SoundCloudJobState.FAILED, SoundCloudJobState.LOST)
private const val SONG_SEARCH_THRESHOLD = 20
private const val MEBIBYTE = 1_048_576L

private fun mebibytes(bytes: Long): String {
    val tenths = (bytes * 10 + MEBIBYTE / 2) / MEBIBYTE
    return "${tenths / 10}.${tenths % 10} MiB"
}

@Composable
private fun PlaylistDownloadItem(progress: PlaylistDownloadProgress) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            progress.playlist.folder?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(progress.playlist.title, style = MaterialTheme.typography.titleMedium)
            Text(if (progress.complete) stringResource(Res.string.sources_count_downloaded, countLabel(progress.total, Res.plurals.songs))
                else stringResource(Res.string.sources_progress_downloaded, progress.downloaded, countLabel(progress.total, Res.plurals.songs)),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!progress.complete) {
                val playlistDescription = stringResource(Res.string.sources_playlist_download_progress, progress.playlist.name)
                LinearProgressIndicator(progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = playlistDescription })
            }
        }
        if (progress.complete) Icon(AppIcon.CHECK.vector, stringResource(Res.string.sources_download_status_complete), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SongDownloadItem(record: DownloadRecord, metadata: TrackMetadata?, controller: MusicController) {
    val title = metadata?.title ?: stringResource(Res.string.sources_unavailable_song)
    val complete = record.status == DownloadStatus.COMPLETE
    // Finished songs only show their size; the status is spelled out while it still needs attention.
    val status = when (record.status) {
        DownloadStatus.COMPLETE -> null
        DownloadStatus.DOWNLOADING -> stringResource(Res.string.sources_download_status_downloading)
        DownloadStatus.PENDING -> stringResource(Res.string.sources_download_status_pending)
        DownloadStatus.PAUSED -> stringResource(Res.string.sources_download_status_paused)
        DownloadStatus.FAILED -> if (record.problem == DownloadProblem.INSUFFICIENT_SPACE)
            stringResource(Res.string.sources_download_status_insufficient_space) else stringResource(Res.string.sources_download_status_failed)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(metadata?.artist?.ifBlank { null }, record.sizeBytes.takeIf { it > 0 }?.let(::mebibytes)).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            status?.let { Text(it, color = if (record.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (record.status == DownloadStatus.DOWNLOADING) {
                if (record.sizeBytes > 0) LinearProgressIndicator(progress = {
                    (record.bytesDownloaded.toFloat() / record.sizeBytes).coerceIn(0f, 1f)
                }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        val removal = if (complete) stringResource(Res.string.sources_remove_download) else stringResource(Res.string.sources_cancel_download)
        AppIconButton(if (complete) AppIcon.REMOVE else AppIcon.CLOSE, stringResource(Res.string.sources_action_for_item, removal, title)) {
            controller.removeDownload(record.trackId)
        }
    }
}
