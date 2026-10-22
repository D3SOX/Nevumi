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

@Composable
internal fun FileRename(track: Track, controller: MusicController, target: FileEditTarget = FileEditTarget.LOCAL, dismiss: () -> Unit) {
    var original by remember(track.id) { mutableStateOf("") }
    var name by remember(track.id) { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(track.id) {
        try { original = controller.musicFileName(track.id, target); name = original }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadError = error.message ?: "The music file is unavailable" }
    }
    val valid = name.isNotBlank() && name.none { it == '/' || it == '\\' || it == '\u0000' } &&
        name.substringAfterLast('.').equals(original.substringAfterLast('.'), ignoreCase = true)
    KeyboardAlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(if (target == FileEditTarget.SERVER) "Rename server file" else "Rename file") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("You can undo this rename.")
            OutlinedTextField(name, { name = it }, label = { Text("File name") }, singleLine = true,
                enabled = original.isNotEmpty() && !saving, isError = original.isNotEmpty() && !valid,
                supportingText = { Text("Keep the .${original.substringAfterLast('.')} extension") }, modifier = Modifier.fillMaxWidth())
            if (saving || (original.isEmpty() && loadError == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
            (loadError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = original.isNotEmpty() && valid && name != original && !saving, onClick = {
            saving = true
            scope.launch {
                controller.renameFile(track.id, name, target).join()
                saving = false
                if (controller.state.value.error == null) dismiss()
            }
        }) { Text("Rename") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("Cancel") } })
}
