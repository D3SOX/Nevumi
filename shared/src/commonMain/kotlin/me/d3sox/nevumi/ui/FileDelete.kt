package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.FileEditTarget
import me.d3sox.nevumi.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.delete
import me.d3sox.nevumi.resources.library_delete_local_file
import me.d3sox.nevumi.resources.library_delete_local_file_description
import me.d3sox.nevumi.resources.library_delete_server_file
import me.d3sox.nevumi.resources.library_delete_server_file_description
import me.d3sox.nevumi.resources.library_music_file_unavailable
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

@Composable
internal fun FileDelete(track: Track, controller: MusicController, target: FileEditTarget = FileEditTarget.LOCAL, dismiss: () -> Unit) {
    var name by remember(track.id) { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(track.id) {
        try { name = controller.musicFileName(track.id, target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadError = error.message ?: getString(Res.string.library_music_file_unavailable) }
    }
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(stringResource(if (target == FileEditTarget.SERVER) Res.string.library_delete_server_file else Res.string.library_delete_local_file)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            name?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(stringResource(if (target == FileEditTarget.SERVER) Res.string.library_delete_server_file_description
                else Res.string.library_delete_local_file_description))
            if (saving || (name == null && loadError == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
            (loadError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = name != null && !saving, onClick = {
            saving = true
            scope.launch {
                controller.deleteFile(track.id, target).join()
                saving = false
                if (controller.state.value.error == null) dismiss()
            }
        }) { Text(stringResource(Res.string.delete), color = MaterialTheme.colorScheme.error) }
    }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text(stringResource(Res.string.cancel)) } })
}
