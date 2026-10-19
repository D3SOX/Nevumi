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

@Composable
internal fun FileRecovery(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    var restoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SettingsScreen(onDismissRequest = { if (!restoring) dismiss() }, title = { Text("File recovery") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("A file edit stopped before finishing. Nevumi kept the original and the prepared edit.")
            Text("Restoring replaces the interrupted file with its original backup.")
            state.fileEdits.filter { it.needsRecovery || (!it.applied && !it.undone && !it.writing) }.forEach { edit ->
                val track = state.tracks.firstOrNull { it.id == edit.trackId }
                Text(track?.metadata?.title ?: "Music file", style = MaterialTheme.typography.titleMedium)
                track?.metadata?.artist?.takeIf { it.isNotBlank() }?.let { Text(it) }
                track?.metadata?.folder?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (edit.needsRecovery) ActionButton("Restore original file", AppIcon.UNDO, enabled = !restoring) {
                    restoring = true
                    scope.launch {
                        controller.restoreOriginalFile(edit.id).join()
                        restoring = false
                        if (controller.state.value.fileEdits.none { it.needsRecovery }) dismiss()
                    }
                }
                else {
                    Text("The music file is unchanged. Apply the prepared changes or discard them.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Apply prepared edit", AppIcon.CHECK, enabled = !restoring) {
                            restoring = true
                            scope.launch {
                                controller.applyFileEdit(edit.id).join()
                                restoring = false
                            }
                        }
                        ActionButton("Discard prepared edit", AppIcon.REMOVE, enabled = !restoring, style = ActionStyle.DESTRUCTIVE) {
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
    }, confirmButton = { PageAction("Done", enabled = !restoring, onClick = dismiss) })
}
