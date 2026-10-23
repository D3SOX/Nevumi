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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.playlists
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.sources_cancel_refresh
import me.d3sox.nevumi.resources.sources_covers
import me.d3sox.nevumi.resources.sources_dismiss
import me.d3sox.nevumi.resources.sources_files
import me.d3sox.nevumi.resources.sources_library_refresh_progress
import me.d3sox.nevumi.resources.sources_refresh_cancelled_description
import me.d3sox.nevumi.resources.sources_refresh_checked
import me.d3sox.nevumi.resources.sources_refresh_received
import me.d3sox.nevumi.resources.sources_refresh_saved
import me.d3sox.nevumi.resources.sources_refresh_slow
import me.d3sox.nevumi.resources.sources_refresh_stage_artwork
import me.d3sox.nevumi.resources.sources_refresh_stage_cancelled
import me.d3sox.nevumi.resources.sources_refresh_stage_complete
import me.d3sox.nevumi.resources.sources_refresh_stage_connecting
import me.d3sox.nevumi.resources.sources_refresh_stage_failed
import me.d3sox.nevumi.resources.sources_refresh_stage_files
import me.d3sox.nevumi.resources.sources_refresh_stage_importing
import me.d3sox.nevumi.resources.sources_refresh_stage_matching
import me.d3sox.nevumi.resources.sources_refresh_stage_playlists
import me.d3sox.nevumi.resources.sources_refresh_stage_songs
import me.d3sox.nevumi.resources.sources_refresh_stage_waiting
import me.d3sox.nevumi.resources.sources_refresh_updated
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ServerRefreshStatus(progress: ServerRefreshProgress, cancel: () -> Unit, dismiss: () -> Unit,
    modifier: Modifier = Modifier) {
    var slow by remember(progress) { mutableStateOf(false) }
    LaunchedEffect(progress) {
        if (progress.running) { delay(10_000); slow = true }
    }
    val title = when (progress.stage) {
        ServerRefreshStage.CONNECTING -> stringResource(Res.string.sources_refresh_stage_connecting)
        ServerRefreshStage.WAITING -> stringResource(Res.string.sources_refresh_stage_waiting)
        ServerRefreshStage.SONGS -> stringResource(Res.string.sources_refresh_stage_songs)
        ServerRefreshStage.FILES -> stringResource(Res.string.sources_refresh_stage_files)
        ServerRefreshStage.IMPORTING -> stringResource(Res.string.sources_refresh_stage_importing)
        ServerRefreshStage.PLAYLISTS -> stringResource(Res.string.sources_refresh_stage_playlists)
        ServerRefreshStage.ARTWORK -> stringResource(Res.string.sources_refresh_stage_artwork)
        ServerRefreshStage.MATCHING -> stringResource(Res.string.sources_refresh_stage_matching)
        ServerRefreshStage.COMPLETE -> stringResource(Res.string.sources_refresh_stage_complete)
        ServerRefreshStage.CANCELLED -> stringResource(Res.string.sources_refresh_stage_cancelled)
        ServerRefreshStage.FAILED -> stringResource(Res.string.sources_refresh_stage_failed)
    }
    val count = when (progress.stage) {
        ServerRefreshStage.SONGS -> stringResource(Res.string.sources_refresh_received, countLabel(progress.processed, Res.plurals.songs))
        ServerRefreshStage.FILES -> stringResource(Res.string.sources_refresh_received, countLabel(progress.processed, Res.plurals.sources_files))
        ServerRefreshStage.IMPORTING -> stringResource(Res.string.sources_refresh_saved, progress.processed, countLabel(progress.total ?: 0, Res.plurals.songs))
        ServerRefreshStage.PLAYLISTS -> progress.total?.let { stringResource(Res.string.sources_refresh_checked, progress.processed, countLabel(it, Res.plurals.playlists)) }
        ServerRefreshStage.ARTWORK -> progress.total?.let { stringResource(Res.string.sources_refresh_checked, progress.processed, countLabel(it, Res.plurals.sources_covers)) }
        ServerRefreshStage.COMPLETE -> stringResource(Res.string.sources_refresh_updated, countLabel(progress.processed, Res.plurals.songs))
        ServerRefreshStage.CANCELLED -> stringResource(Res.string.sources_refresh_cancelled_description)
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
                val barDescription = stringResource(Res.string.sources_library_refresh_progress)
                val bar = Modifier.fillMaxWidth().semantics { contentDescription = barDescription }
                val total = progress.total
                if (total != null && total > 0) LinearProgressIndicator(
                    progress = { (progress.processed.toFloat() / total).coerceIn(0f, 1f) }, modifier = bar)
                else LinearProgressIndicator(bar)
                if (slow) Text(stringResource(Res.string.sources_refresh_slow),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = cancel) { Text(stringResource(Res.string.sources_cancel_refresh)) }
            } else TextButton(onClick = dismiss) { Text(stringResource(Res.string.sources_dismiss)) }
        }
    }
}
