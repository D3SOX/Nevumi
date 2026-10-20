package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.d3sox.nevumi.navidrome.ServerRefreshProgress
import me.d3sox.nevumi.navidrome.ServerRefreshStage

@Composable
internal fun ServerRefreshStatus(progress: ServerRefreshProgress, cancel: () -> Unit, dismiss: () -> Unit,
    modifier: Modifier = Modifier) {
    var slow by remember(progress) { mutableStateOf(false) }
    LaunchedEffect(progress) {
        if (progress.running) { delay(10_000); slow = true }
    }
    val title = when (progress.stage) {
        ServerRefreshStage.CONNECTING -> "Connecting to the music server"
        ServerRefreshStage.WAITING -> "Waiting for another server task"
        ServerRefreshStage.SONGS -> "Loading songs"
        ServerRefreshStage.FILES -> "Loading file details"
        ServerRefreshStage.IMPORTING -> "Saving songs"
        ServerRefreshStage.PLAYLISTS -> "Syncing playlists"
        ServerRefreshStage.ARTWORK -> "Updating artwork"
        ServerRefreshStage.MATCHING -> "Matching local and server songs"
        ServerRefreshStage.COMPLETE -> "Library refreshed"
        ServerRefreshStage.CANCELLED -> "Refresh cancelled"
        ServerRefreshStage.FAILED -> "Refresh stopped"
    }
    val count = when (progress.stage) {
        ServerRefreshStage.SONGS -> "${countLabel(progress.processed, "song")} received"
        ServerRefreshStage.FILES -> "${countLabel(progress.processed, "file")} received"
        ServerRefreshStage.IMPORTING -> "${progress.processed} of ${countLabel(progress.total ?: 0, "song")} saved"
        ServerRefreshStage.PLAYLISTS -> progress.total?.let { "${progress.processed} of ${countLabel(it, "playlist")} checked" }
        ServerRefreshStage.ARTWORK -> progress.total?.let { "${progress.processed} of ${countLabel(it, "cover")} checked" }
        ServerRefreshStage.COMPLETE -> "${countLabel(progress.processed, "song")} updated"
        ServerRefreshStage.CANCELLED -> "Songs already saved are kept. You can refresh again later."
        else -> null
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (progress.stage == ServerRefreshStage.FAILED) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            count?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            progress.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (progress.running) {
                val bar = Modifier.fillMaxWidth().semantics { contentDescription = "Library refresh progress" }
                val total = progress.total
                if (total != null && total > 0) LinearProgressIndicator(
                    progress = { (progress.processed.toFloat() / total).coerceIn(0f, 1f) }, modifier = bar)
                else LinearProgressIndicator(bar)
                if (slow) Text("This step is taking longer than usual. You can cancel and try again.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = cancel) { Text("Cancel refresh") }
            } else TextButton(onClick = dismiss) { Text("Dismiss") }
        }
    }
}
