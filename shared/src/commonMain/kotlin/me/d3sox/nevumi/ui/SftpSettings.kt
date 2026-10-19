package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.sftp.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SftpSettings(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val saved = state.sftp?.connection
    var host by remember { mutableStateOf(saved?.host.orEmpty()) }
    var port by remember { mutableStateOf((saved?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(saved?.username.orEmpty()) }
    var directory by remember { mutableStateOf(saved?.musicDirectory.orEmpty()) }
    var fingerprint by remember { mutableStateOf(saved?.hostKeySha256.orEmpty()) }
    var method by remember { mutableStateOf("SSH private key") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var libraries by remember { mutableStateOf<List<ServerMusicDirectory>>(emptyList()) }
    var libraryId by remember { mutableStateOf(state.sftp?.libraryId) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf<PendingServerUpload?>(null) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(state.server) {
        if (state.server == null) return@LaunchedEffect
        loading = true
        try {
            libraries = controller.serverMusicDirectories()
            if (libraries.none { it.id == libraryId }) libraryId = libraries.firstOrNull()?.id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "The server libraries could not be read" }
        finally { loading = false }
    }
    val valid = state.server != null && libraryId != null && host.isNotBlank() && port.toIntOrNull() in 1..65535 &&
        username.isNotBlank() && directory.startsWith('/') && directory != "/" && fingerprint.startsWith("SHA256:") &&
        (if (method == "SSH private key") privateKey.isNotBlank() else password.isNotBlank())
    SettingsScreen(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Server files (SFTP)") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.server == null) Text("Connect to Navidrome in Music server first.")
            state.sftp?.let { configuration ->
                Text("SFTP connected", style = MaterialTheme.typography.titleMedium)
                Text("${configuration.libraryName} · ${configuration.connection.host}")
                ActionButton("Disconnect SFTP", AppIcon.UNLINK, enabled = !busy, style = ActionStyle.DESTRUCTIVE) {
                    busy = true; error = null
                    scope.launch {
                        try { controller.disconnectServerFiles() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "SFTP could not be disconnected" }
                        finally { busy = false }
                    }
                }
            }
            if (state.serverUploads.isNotEmpty()) {
                Text("Unfinished uploads", style = MaterialTheme.typography.titleMedium)
                state.serverUploads.forEach { upload ->
                    Text(upload.relativePath)
                    if (state.sftp?.id != upload.configurationId) Text("Reconnect to this upload’s original SFTP directory to retry it.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Retry upload", AppIcon.REFRESH, enabled = !busy && state.sftp?.id == upload.configurationId) {
                            busy = true; error = null
                            scope.launch {
                                try { controller.retryServerUpload(upload.id).join() }
                                finally { busy = false }
                            }
                        }
                        ActionButton("Discard upload", AppIcon.REMOVE, enabled = !busy, style = ActionStyle.DESTRUCTIVE) { discard = upload }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Text("Edit and upload the actual music files. Use the SSH account’s absolute path to your Navidrome music directory.")
            if (controller.tunnel != null) Text("To reach a server on your tailnet, turn on Server files in Tunnel settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (libraries.isNotEmpty()) SettingChoice("Music library", libraryId, libraries.map { it.id as Long? to it.name }) { libraryId = it }
            OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SFTP host") }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true, enabled = !busy)
            OutlinedTextField(port, { port = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SFTP port") }, leadingIcon = { Icon(AppIcon.LINK.vector, null) }, singleLine = true, enabled = !busy,
                isError = port.toIntOrNull() !in 1..65535)
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH username") }, leadingIcon = { Icon(AppIcon.ARTIST.vector, null) }, singleLine = true, enabled = !busy)
            OutlinedTextField(directory, { directory = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Server music directory") }, leadingIcon = { Icon(AppIcon.FOLDER.vector, null) }, singleLine = true, enabled = !busy)
            OutlinedTextField(fingerprint, { fingerprint = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Host-key fingerprint") }, leadingIcon = { Icon(AppIcon.KEY.vector, null) }, singleLine = true, enabled = !busy,
                supportingText = { Text("Verify the SHA256 fingerprint with your server administrator before connecting.") })
            SettingChoice("SSH authentication", method, listOf("SSH private key" to "SSH private key", "Password" to "Password"), card = false) { method = it }
            if (method == "SSH private key") {
                OutlinedTextField(privateKey, { privateKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH private key") }, leadingIcon = { Icon(AppIcon.KEY.vector, null) }, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), maxLines = 3)
                OutlinedTextField(passphrase, { passphrase = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Key passphrase (optional)") }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) }, singleLine = true,
                    enabled = !busy, visualTransformation = PasswordVisualTransformation())
            } else OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH password") }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) }, singleLine = true,
                enabled = !busy, visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        PageAction("Connect SFTP", AppIcon.KEY, enabled = valid && !loading && !busy, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    val authentication = if (method == "SSH private key") SftpAuthentication(privateKey = privateKey,
                        passphrase = passphrase.takeIf { it.isNotEmpty() }) else SftpAuthentication(password = password)
                    controller.connectServerFiles(SftpProfile(host.trim(), port.toInt(), username.trim(), directory.trim(), fingerprint.trim()),
                        requireNotNull(libraryId), authentication)
                    privateKey = ""; passphrase = ""; password = ""
                    scroll.animateScrollTo(0)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "SFTP could not be connected" }
                finally { busy = false }
            }
        })
    }, dismissButton = { PageAction("Done", enabled = !busy, secondary = true, onClick = dismiss) })
    discard?.let { upload ->
        AlertDialog(onDismissRequest = { if (!busy) discard = null }, title = { Text("Discard unfinished upload?") },
            text = { Text("Nevumi will remove its staged copy and stop retrying. Any file already uploaded to the server is kept.") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { controller.discardServerUpload(upload.id).join(); discard = null }
                    finally { busy = false }
                }
            }) { Text("Discard upload") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { discard = null }) { Text("Cancel") } })
    }
}
