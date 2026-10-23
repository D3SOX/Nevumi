package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.sources_local_file_read_failed
import me.d3sox.nevumi.resources.sources_server_filename
import me.d3sox.nevumi.resources.sources_server_filename_hint
import me.d3sox.nevumi.resources.sources_upload
import me.d3sox.nevumi.resources.sources_upload_intro
import me.d3sox.nevumi.resources.sources_upload_to_server
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ServerUploadDialog(track: Track, controller: MusicController, dismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    var original by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val readFailed = stringResource(Res.string.sources_local_file_read_failed)
    LaunchedEffect(track.id) {
        try {
            val filename = controller.musicFileName(track.id)
            original = filename
            fun folder(value: String) = value.map { if (it in "/\\\u0000\r\n") '_' else it }.joinToString("").trim().takeUnless { it in setOf("", ".", "..") }
            path = listOfNotNull(folder(track.metadata.albumArtist.ifBlank { track.metadata.artist }), folder(track.metadata.album), filename).joinToString("/")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: readFailed }
        finally { loading = false }
    }
    val valid = original != null && path.isNotBlank() && !path.startsWith('/') && path.none { it in "\\\u0000\r\n" } &&
        path.split('/').none { it in setOf("", ".", "..") } && path.substringAfterLast('.').equals(original?.substringAfterLast('.'), ignoreCase = true)
    KeyboardAlertDialog(onDismissRequest = { if (!uploading) dismiss() }, title = { Text(stringResource(Res.string.sources_upload_to_server)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(track.metadata.title, style = MaterialTheme.typography.titleMedium)
            state.sftp?.let { Text("${it.libraryName} · ${it.connection.host}") }
            Text(stringResource(Res.string.sources_upload_intro))
            OutlinedTextField(path, { path = it }, label = { Text(stringResource(Res.string.sources_server_filename)) }, enabled = !loading && !uploading,
                isError = !loading && !valid, singleLine = true,
                supportingText = { Text(stringResource(Res.string.sources_server_filename_hint)) }, modifier = Modifier.fillMaxWidth())
            if (loading || uploading) LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = valid && !loading && !uploading, onClick = {
            uploading = true; error = null
            scope.launch {
                try {
                    controller.uploadTrack(track.id, path).join()
                    if (controller.state.value.error == null) dismiss()
                } finally { uploading = false }
            }
        }) { Text(stringResource(Res.string.sources_upload)) }
    }, dismissButton = { TextButton(enabled = !uploading, onClick = dismiss) { Text(stringResource(Res.string.cancel)) } })
}
