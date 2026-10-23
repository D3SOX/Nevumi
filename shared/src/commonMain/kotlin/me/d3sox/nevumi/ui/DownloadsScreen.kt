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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.downloads.DownloadProblem
import me.d3sox.nevumi.soundcloud.SoundCloudJobState
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.downloads
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.sources_action_for_item
import me.d3sox.nevumi.resources.sources_cancel_download
import me.d3sox.nevumi.resources.sources_count_downloaded
import me.d3sox.nevumi.resources.sources_download_bytes
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
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DownloadsScreen(state: AppState, controller: MusicController, dismiss: () -> Unit) {
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
    val titles = remember(state.tracks) { state.tracks.associate { it.id to it.metadata.title } }
    val downloads = remember(state.downloads, titles) { state.downloads.values.sortedBy { titles[it.trackId] } }
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
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(playlists, key = { "playlist:${it.playlist.id}" }) { progress ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(progress.playlist.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (progress.complete) stringResource(Res.string.sources_count_downloaded, countLabel(progress.total, Res.plurals.songs))
                            else stringResource(Res.string.sources_progress_downloaded, progress.downloaded, countLabel(progress.total, Res.plurals.songs)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val playlistDescription = stringResource(Res.string.sources_playlist_download_progress, progress.playlist.name)
                        LinearProgressIndicator(progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = playlistDescription })
                    }
                }
                if (state.soundCloudJobs.isNotEmpty()) {
                    if (playlists.isNotEmpty()) item { HorizontalDivider() }
                    item(key = "soundcloud-heading") { Text("SoundCloud", style = MaterialTheme.typography.titleSmall) }
                    items(state.soundCloudJobs, key = { "soundcloud:${it.id}" }) { job ->
                        // Settings only know the viewer of the server they use now, which may not be the one running this job.
                        val viewer = state.soundCloud?.let { it.baseUrl == job.baseUrl && it.browserViewUrl != null } == true
                        SoundCloudJobItem(job, controller, viewer) { tagging = job.id }
                    }
                }
                if ((playlists.isNotEmpty() || state.soundCloudJobs.isNotEmpty()) && state.downloads.isNotEmpty()) item { HorizontalDivider() }
                items(downloads, key = { it.trackId }) { record ->
                    val title = titles[record.trackId] ?: stringResource(Res.string.sources_unavailable_song)
                    Column {
                        Text(title,
                            style = MaterialTheme.typography.titleMedium)
                        Text(when (record.status) {
                            DownloadStatus.COMPLETE -> stringResource(Res.string.sources_download_status_complete)
                            DownloadStatus.DOWNLOADING -> stringResource(Res.string.sources_download_status_downloading)
                            DownloadStatus.PENDING -> stringResource(Res.string.sources_download_status_pending)
                            DownloadStatus.PAUSED -> stringResource(Res.string.sources_download_status_paused)
                            DownloadStatus.FAILED -> if (record.problem == DownloadProblem.INSUFFICIENT_SPACE)
                                stringResource(Res.string.sources_download_status_insufficient_space) else stringResource(Res.string.sources_download_status_failed)
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.status == DownloadStatus.DOWNLOADING) {
                            if (record.sizeBytes > 0) LinearProgressIndicator(progress = {
                                (record.bytesDownloaded.toFloat() / record.sizeBytes).coerceIn(0f, 1f)
                            }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text(stringResource(Res.string.sources_download_bytes, record.bytesDownloaded, record.sizeBytes), style = MaterialTheme.typography.labelSmall)
                        val removal = if (record.status == DownloadStatus.COMPLETE) stringResource(Res.string.sources_remove_download) else stringResource(Res.string.sources_cancel_download)
                        val removalDescription = stringResource(Res.string.sources_action_for_item, removal, title)
                        ActionButton(removal, if (record.status == DownloadStatus.COMPLETE) AppIcon.REMOVE else AppIcon.CLOSE,
                            Modifier.semantics { contentDescription = removalDescription }, style = ActionStyle.OUTLINED) { controller.removeDownload(record.trackId) }
                    }
                }
                if (state.downloads.isEmpty() && playlists.isEmpty() && state.soundCloudJobs.isEmpty())
                    item { Text(stringResource(Res.string.sources_downloads_empty)) }
            }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
}
