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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.music_library
import me.d3sox.nevumi.resources.server_files_sftp
import me.d3sox.nevumi.resources.sources_connect_sftp
import me.d3sox.nevumi.resources.sources_discard_unfinished_upload_description
import me.d3sox.nevumi.resources.sources_discard_unfinished_upload_question
import me.d3sox.nevumi.resources.sources_discard_upload
import me.d3sox.nevumi.resources.sources_disconnect_sftp
import me.d3sox.nevumi.resources.sources_host_key_fingerprint
import me.d3sox.nevumi.resources.sources_host_key_fingerprint_hint
import me.d3sox.nevumi.resources.sources_key_passphrase_optional
import me.d3sox.nevumi.resources.sources_password
import me.d3sox.nevumi.resources.sources_retry_upload
import me.d3sox.nevumi.resources.sources_server_libraries_read_failed
import me.d3sox.nevumi.resources.sources_server_music_directory
import me.d3sox.nevumi.resources.sources_sftp_connect_failed
import me.d3sox.nevumi.resources.sources_sftp_connect_navidrome_first
import me.d3sox.nevumi.resources.sources_sftp_connected
import me.d3sox.nevumi.resources.sources_sftp_disconnect_failed
import me.d3sox.nevumi.resources.sources_sftp_host
import me.d3sox.nevumi.resources.sources_sftp_intro
import me.d3sox.nevumi.resources.sources_sftp_port
import me.d3sox.nevumi.resources.sources_sftp_tunnel_hint
import me.d3sox.nevumi.resources.sources_ssh_password
import me.d3sox.nevumi.resources.sources_ssh_private_key
import me.d3sox.nevumi.resources.sources_ssh_username
import me.d3sox.nevumi.resources.sources_unfinished_uploads
import me.d3sox.nevumi.resources.sources_upload_reconnect_hint
import me.d3sox.nevumi.resources.ssh_authentication
import org.jetbrains.compose.resources.stringResource

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
    val librariesReadFailed = stringResource(Res.string.sources_server_libraries_read_failed)
    val disconnectFailed = stringResource(Res.string.sources_sftp_disconnect_failed)
    val connectFailed = stringResource(Res.string.sources_sftp_connect_failed)
    LaunchedEffect(state.server) {
        if (state.server == null) return@LaunchedEffect
        loading = true
        try {
            libraries = controller.serverMusicDirectories()
            if (libraries.none { it.id == libraryId }) libraryId = libraries.firstOrNull()?.id
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: librariesReadFailed }
        finally { loading = false }
    }
    val valid = state.server != null && libraryId != null && host.isNotBlank() && port.toIntOrNull() in 1..65535 &&
        username.isNotBlank() && directory.startsWith('/') && directory != "/" && fingerprint.startsWith("SHA256:") &&
        (if (method == "SSH private key") privateKey.isNotBlank() else password.isNotBlank())
    SettingsScreen(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(Res.string.server_files_sftp)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.server == null) Text(stringResource(Res.string.sources_sftp_connect_navidrome_first))
            state.sftp?.let { configuration ->
                Text(stringResource(Res.string.sources_sftp_connected), style = MaterialTheme.typography.titleMedium)
                Text("${configuration.libraryName} · ${configuration.connection.host}")
                ActionButton(stringResource(Res.string.sources_disconnect_sftp), AppIcon.UNLINK, enabled = !busy, style = ActionStyle.DESTRUCTIVE) {
                    busy = true; error = null
                    scope.launch {
                        try { controller.disconnectServerFiles() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: disconnectFailed }
                        finally { busy = false }
                    }
                }
            }
            if (state.serverUploads.isNotEmpty()) {
                Text(stringResource(Res.string.sources_unfinished_uploads), style = MaterialTheme.typography.titleMedium)
                state.serverUploads.forEach { upload ->
                    Text(upload.relativePath)
                    if (state.sftp?.id != upload.configurationId) Text(stringResource(Res.string.sources_upload_reconnect_hint))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(Res.string.sources_retry_upload), AppIcon.REFRESH, enabled = !busy && state.sftp?.id == upload.configurationId) {
                            busy = true; error = null
                            scope.launch {
                                try { controller.retryServerUpload(upload.id).join() }
                                finally { busy = false }
                            }
                        }
                        ActionButton(stringResource(Res.string.sources_discard_upload), AppIcon.REMOVE, enabled = !busy, style = ActionStyle.DESTRUCTIVE) { discard = upload }
                    }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Text(stringResource(Res.string.sources_sftp_intro))
            if (controller.tunnel != null) Text(stringResource(Res.string.sources_sftp_tunnel_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (libraries.isNotEmpty()) SettingChoice(stringResource(Res.string.music_library), libraryId, libraries.map { it.id as Long? to it.name }) { libraryId = it }
            OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_sftp_host)) }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true, enabled = !busy)
            OutlinedTextField(port, { port = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_sftp_port)) }, leadingIcon = { Icon(AppIcon.LINK.vector, null) }, singleLine = true, enabled = !busy,
                isError = port.toIntOrNull() !in 1..65535)
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_ssh_username)) }, leadingIcon = { Icon(AppIcon.ARTIST.vector, null) }, singleLine = true, enabled = !busy)
            OutlinedTextField(directory, { directory = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_server_music_directory)) }, leadingIcon = { Icon(AppIcon.FOLDER.vector, null) }, singleLine = true, enabled = !busy)
            OutlinedTextField(fingerprint, { fingerprint = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_host_key_fingerprint)) }, leadingIcon = { Icon(AppIcon.KEY.vector, null) }, singleLine = true, enabled = !busy,
                supportingText = { Text(stringResource(Res.string.sources_host_key_fingerprint_hint)) })
            SettingChoice(stringResource(Res.string.ssh_authentication), method, listOf("SSH private key" to stringResource(Res.string.sources_ssh_private_key), "Password" to stringResource(Res.string.sources_password)), card = false) { method = it }
            if (method == "SSH private key") {
                OutlinedTextField(privateKey, { privateKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_ssh_private_key)) }, leadingIcon = { Icon(AppIcon.KEY.vector, null) }, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), maxLines = 3)
                OutlinedTextField(passphrase, { passphrase = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_key_passphrase_optional)) }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) }, singleLine = true,
                    enabled = !busy, visualTransformation = PasswordVisualTransformation())
            } else OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_ssh_password)) }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) }, singleLine = true,
                enabled = !busy, visualTransformation = PasswordVisualTransformation())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        PageAction(stringResource(Res.string.sources_connect_sftp), AppIcon.KEY, enabled = valid && !loading && !busy, onClick = {
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
                catch (failure: Exception) { error = failure.message ?: connectFailed }
                finally { busy = false }
            }
        })
    }, dismissButton = { PageAction(stringResource(Res.string.done), enabled = !busy, secondary = true, onClick = dismiss) })
    discard?.let { upload ->
        AlertDialog(onDismissRequest = { if (!busy) discard = null }, title = { Text(stringResource(Res.string.sources_discard_unfinished_upload_question)) },
            text = { Text(stringResource(Res.string.sources_discard_unfinished_upload_description)) },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { controller.discardServerUpload(upload.id).join(); discard = null }
                    finally { busy = false }
                }
            }) { Text(stringResource(Res.string.sources_discard_upload)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { discard = null }) { Text(stringResource(Res.string.cancel)) } })
    }
}
