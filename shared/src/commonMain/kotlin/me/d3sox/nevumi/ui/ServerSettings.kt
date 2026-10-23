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
import me.d3sox.nevumi.navidrome.ServerRefreshStage
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.music_server
import me.d3sox.nevumi.resources.sources_change_server
import me.d3sox.nevumi.resources.sources_connect
import me.d3sox.nevumi.resources.sources_connected
import me.d3sox.nevumi.resources.sources_disconnect
import me.d3sox.nevumi.resources.sources_disconnect_music_server_description
import me.d3sox.nevumi.resources.sources_disconnect_music_server_question
import me.d3sox.nevumi.resources.sources_disconnect_music_server_sftp
import me.d3sox.nevumi.resources.sources_password
import me.d3sox.nevumi.resources.sources_refresh_library
import me.d3sox.nevumi.resources.sources_server_address
import me.d3sox.nevumi.resources.sources_server_intro
import me.d3sox.nevumi.resources.sources_user_on_server
import me.d3sox.nevumi.resources.sources_username
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ServerSettings(state: AppState, controller: MusicController, onClose: () -> Unit) {
    var address by remember { mutableStateOf(state.server?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(state.server?.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(state.server == null) }
    var disconnecting by remember { mutableStateOf(false) }
    LaunchedEffect(state.serverRefresh, state.server) {
        if (submitted && state.serverRefresh?.stage == ServerRefreshStage.COMPLETE && state.server != null) onClose()
    }
    val server = state.server.takeUnless { editing }
    SettingsScreen(onDismissRequest = onClose, title = { Text(stringResource(Res.string.music_server)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (server != null) SettingsCard(AppIcon.SERVER) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Res.string.sources_connected), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.sources_user_on_server, server.username, server.baseUrl.substringAfter("://")), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActionButton(stringResource(Res.string.sources_disconnect), AppIcon.UNLINK, enabled = !state.loading, style = ActionStyle.DESTRUCTIVE) { disconnecting = true }
                }
            } else {
                Text(stringResource(Res.string.sources_server_intro))
                OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_server_address)) }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true)
                OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_username)) }, leadingIcon = { Icon(AppIcon.ARTIST.vector, null) }, singleLine = true)
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_password)) }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            }
            val refresh = state.serverRefresh
            if (refresh != null) ServerRefreshStatus(refresh, controller::cancelServerRefresh, controller::dismissServerRefresh)
            else if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        if (server != null) PageAction(stringResource(Res.string.sources_refresh_library), AppIcon.LIBRARY, enabled = !state.loading, onClick = {
            controller.refreshServer()
        }) else PageAction(stringResource(Res.string.sources_connect), AppIcon.CLOUD, enabled = !state.loading, onClick = {
            submitted = true
            controller.connectServer(address, username, password)
        })
    }, dismissButton = {
        if (server != null) PageAction(stringResource(Res.string.sources_change_server), AppIcon.EDIT, enabled = !state.loading, secondary = true, onClick = { editing = true })
        else PageAction(stringResource(Res.string.cancel), AppIcon.CLOSE, secondary = true, onClick = { if (state.server != null) editing = false else onClose() })
    })
    if (disconnecting) AlertDialog(onDismissRequest = { disconnecting = false },
        title = { Text(stringResource(Res.string.sources_disconnect_music_server_question)) },
        text = { Text(stringResource(Res.string.sources_disconnect_music_server_description) +
            if (state.sftp?.serverId == state.server?.serverId) " " + stringResource(Res.string.sources_disconnect_music_server_sftp) else "") },
        confirmButton = { TextButton(onClick = { controller.disconnectServer(); disconnecting = false }) { Text(stringResource(Res.string.sources_disconnect)) } },
        dismissButton = { TextButton(onClick = { disconnecting = false }) { Text(stringResource(Res.string.cancel)) } })
}
