package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.downloads.DownloadProblem

@Composable
internal fun DownloadsScreen(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val paused = state.preferences.downloadsPaused
    val playlists = state.playlistDownloads
    val batch = state.downloadBatch
    // Progress updates arrive several times a second, so avoid scanning the library for each song.
    val titles = remember(state.tracks) { state.tracks.associate { it.id to it.metadata.title } }
    val downloads = remember(state.downloads, titles) { state.downloads.values.sortedBy { titles[it.trackId] } }
    val waiting = state.downloads.values.any { it.status != DownloadStatus.COMPLETE } || batch.total > 0
    SettingsScreen(onDismissRequest = dismiss, title = { Text("Downloads") }, text = {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                paused -> "Downloads paused"
                waiting && !state.downloadNetwork.connected -> "Waiting for a network"
                waiting && state.downloadNetwork.metered && !state.preferences.downloadOnMetered -> "Waiting for an unmetered network"
                else -> "${countLabel(state.downloads.count { it.value.status == DownloadStatus.COMPLETE }, "song")} downloaded"
            })
            if (batch.total > 0) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${batch.downloaded} of ${countLabel(batch.total, "song")} downloaded",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { batch.fraction },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Download progress" })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(if (paused) "Resume downloads" else "Pause downloads", if (paused) AppIcon.PLAY else AppIcon.PAUSE) {
                    controller.setDownloadsPaused(!paused)
                }
                if (state.downloads.values.any { it.status == DownloadStatus.FAILED })
                    ActionButton("Retry failed downloads", AppIcon.REFRESH, style = ActionStyle.OUTLINED, onClick = controller::retryDownloads)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(playlists, key = { "playlist:${it.playlist.id}" }) { progress ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(progress.playlist.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (progress.complete) "${countLabel(progress.total, "song")} downloaded"
                            else "${progress.downloaded} of ${countLabel(progress.total, "song")} downloaded",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Playlist download progress: ${progress.playlist.name}" })
                    }
                }
                if (state.soundCloudJobs.isNotEmpty()) {
                    if (playlists.isNotEmpty()) item { HorizontalDivider() }
                    item(key = "soundcloud-heading") { Text("SoundCloud", style = MaterialTheme.typography.titleSmall) }
                    items(state.soundCloudJobs, key = { "soundcloud:${it.id}" }) { job -> SoundCloudJobItem(job, controller) }
                }
                if ((playlists.isNotEmpty() || state.soundCloudJobs.isNotEmpty()) && state.downloads.isNotEmpty()) item { HorizontalDivider() }
                items(downloads, key = { it.trackId }) { record ->
                    val title = titles[record.trackId] ?: "Unavailable song"
                    Column {
                        Text(title,
                            style = MaterialTheme.typography.titleMedium)
                        Text(when (record.status) {
                            DownloadStatus.COMPLETE -> "Downloaded"
                            DownloadStatus.DOWNLOADING -> "Downloading"
                            DownloadStatus.PENDING -> "Queued"
                            DownloadStatus.PAUSED -> "Paused"
                            DownloadStatus.FAILED -> if (record.problem == DownloadProblem.INSUFFICIENT_SPACE)
                                "Not enough storage — free space and retry" else "Failed — retry to continue"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (record.status == DownloadStatus.DOWNLOADING) {
                            if (record.sizeBytes > 0) LinearProgressIndicator(progress = {
                                (record.bytesDownloaded.toFloat() / record.sizeBytes).coerceIn(0f, 1f)
                            }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Text("${record.bytesDownloaded} / ${record.sizeBytes} bytes", style = MaterialTheme.typography.labelSmall)
                        val removal = if (record.status == DownloadStatus.COMPLETE) "Remove download" else "Cancel download"
                        ActionButton(removal, if (record.status == DownloadStatus.COMPLETE) AppIcon.REMOVE else AppIcon.CLOSE,
                            Modifier.semantics { contentDescription = "$removal: $title" }, style = ActionStyle.OUTLINED) { controller.removeDownload(record.trackId) }
                    }
                }
                if (state.downloads.isEmpty() && playlists.isEmpty() && state.soundCloudJobs.isEmpty())
                    item { Text("Download songs or enable automatic downloads on a playlist.") }
            }
        }
    }, confirmButton = { PageAction("Done", onClick = dismiss) })
}
