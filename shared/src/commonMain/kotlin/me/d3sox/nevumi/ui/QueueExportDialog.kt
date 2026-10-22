package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.queue.QueueExportScope
import me.d3sox.nevumi.queue.QueueSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun QueueExportDialog(snapshot: QueueSnapshot, title: String, controller: MusicController, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(title) }
    var selection by remember { mutableStateOf(QueueExportScope.ALL) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ids = selection.trackIds(snapshot)
    val files = LocalPlaylistFiles.current
    val scope = rememberCoroutineScope()
    fun export(toFile: Boolean) {
        busy = true; error = null
        scope.launch {
            try {
                if (toFile) {
                    val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").ifBlank { "Queue" }
                    if (files.save("$safe.m3u", controller.exportQueueM3u(snapshot, selection))) dismiss()
                } else {
                    controller.createQueuePlaylist(name.trim(), snapshot, selection)
                    dismiss()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not export this queue" }
            finally { busy = false }
        }
    }
    KeyboardAlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Export queue") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Playlist name") }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth())
            SettingChoice("Include", selection, QueueExportScope.entries.map { it to it.label }, card = false) { selection = it; error = null }
            Text("${countLabel(ids.size, "song")} · Original order and repeated songs are preserved.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PageAction("Save as playlist", AppIcon.QUEUE, enabled = !busy && name.isNotBlank() && ids.isNotEmpty()) { export(false) }
            PageAction("Export M3U", AppIcon.SAVE, secondary = true, enabled = !busy && name.isNotBlank() && ids.isNotEmpty()) { export(true) }
        }
    }, confirmButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
}
