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
import org.jetbrains.compose.resources.stringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.export_m3u
import me.d3sox.nevumi.resources.export_queue
import me.d3sox.nevumi.resources.player_could_not_export_queue
import me.d3sox.nevumi.resources.player_export_order_preserved
import me.d3sox.nevumi.resources.player_include
import me.d3sox.nevumi.resources.player_playlist_name
import me.d3sox.nevumi.resources.player_save_as_playlist
import me.d3sox.nevumi.resources.queue
import me.d3sox.nevumi.resources.songs

@Composable
internal fun QueueExportDialog(snapshot: QueueSnapshot, title: String, controller: MusicController, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(title) }
    var selection by remember { mutableStateOf(QueueExportScope.ALL) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ids = selection.trackIds(snapshot)
    val files = LocalPlaylistFiles.current
    val scope = rememberCoroutineScope()
    val fallbackName = stringResource(Res.string.queue)
    val exportFailed = stringResource(Res.string.player_could_not_export_queue)
    fun export(toFile: Boolean) {
        busy = true; error = null
        scope.launch {
            try {
                if (toFile) {
                    val safe = name.trim().replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").ifBlank { fallbackName }
                    if (files.save("$safe.m3u", controller.exportQueueM3u(snapshot, selection))) dismiss()
                } else {
                    controller.createQueuePlaylist(name.trim(), snapshot, selection)
                    dismiss()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: exportFailed }
            finally { busy = false }
        }
    }
    KeyboardAlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(Res.string.export_queue)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(Res.string.player_playlist_name)) }, singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth())
            SettingChoice(stringResource(Res.string.player_include), selection, QueueExportScope.entries.map { it to stringResource(it.label) }, card = false) { selection = it; error = null }
            Text(stringResource(Res.string.player_export_order_preserved, countLabel(ids.size, Res.plurals.songs)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            PageAction(stringResource(Res.string.player_save_as_playlist), AppIcon.QUEUE, enabled = !busy && name.isNotBlank() && ids.isNotEmpty()) { export(false) }
            PageAction(stringResource(Res.string.export_m3u), AppIcon.SAVE, secondary = true, enabled = !busy && name.isNotBlank() && ids.isNotEmpty()) { export(true) }
        }
    }, confirmButton = { TextButton(onClick = dismiss, enabled = !busy) { Text(stringResource(Res.string.cancel)) } })
}
