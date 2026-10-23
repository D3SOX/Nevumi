package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.sources_apply_prepared_edit
import me.d3sox.nevumi.resources.sources_discard_prepared_edit
import me.d3sox.nevumi.resources.sources_file_recovery
import me.d3sox.nevumi.resources.sources_file_recovery_intro
import me.d3sox.nevumi.resources.sources_file_recovery_restore_hint
import me.d3sox.nevumi.resources.sources_file_unchanged
import me.d3sox.nevumi.resources.sources_music_file
import me.d3sox.nevumi.resources.sources_restore_original_file
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FileRecovery(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    var restoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingsScreen(onDismissRequest = { if (!restoring) dismiss() }, title = { Text(stringResource(Res.string.sources_file_recovery)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(Res.string.sources_file_recovery_intro))
            Text(stringResource(Res.string.sources_file_recovery_restore_hint))
            state.fileEdits.filter { it.needsRecovery || (!it.applied && !it.undone && !it.writing) }.forEach { edit ->
                val track = state.tracks.firstOrNull { it.id == edit.trackId }
                Text(track?.metadata?.title ?: stringResource(Res.string.sources_music_file), style = MaterialTheme.typography.titleMedium)
                track?.metadata?.artist?.takeIf { it.isNotBlank() }?.let { Text(it) }
                track?.metadata?.folder?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (edit.needsRecovery) ActionButton(stringResource(Res.string.sources_restore_original_file), AppIcon.UNDO, enabled = !restoring) {
                    restoring = true
                    scope.launch {
                        controller.restoreOriginalFile(edit.id).join()
                        restoring = false
                        if (controller.state.value.fileEdits.none { it.needsRecovery }) dismiss()
                    }
                }
                else {
                    Text(stringResource(Res.string.sources_file_unchanged))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(Res.string.sources_apply_prepared_edit), AppIcon.CHECK, enabled = !restoring) {
                            restoring = true
                            scope.launch {
                                controller.applyFileEdit(edit.id).join()
                                restoring = false
                            }
                        }
                        ActionButton(stringResource(Res.string.sources_discard_prepared_edit), AppIcon.REMOVE, enabled = !restoring, style = ActionStyle.DESTRUCTIVE) {
                            restoring = true
                            scope.launch {
                                controller.discardFileEdit(edit.id).join()
                                restoring = false
                            }
                        }
                    }
                }
            }
            if (restoring) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), enabled = !restoring, onClick = dismiss) })
}
