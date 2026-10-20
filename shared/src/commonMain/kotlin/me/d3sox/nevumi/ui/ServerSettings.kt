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
    SettingsScreen(onDismissRequest = onClose, title = { Text("Music server") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (server != null) SettingsCard(AppIcon.SERVER) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Connected", style = MaterialTheme.typography.titleMedium)
                    Text("${server.username} on ${server.baseUrl.substringAfter("://")}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActionButton("Disconnect", AppIcon.UNLINK, enabled = !state.loading, style = ActionStyle.DESTRUCTIVE) { disconnecting = true }
                }
            } else {
                Text("Connect to Navidrome or a Subsonic-compatible server.")
                OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Server address") }, leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true)
                OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Username") }, leadingIcon = { Icon(AppIcon.ARTIST.vector, null) }, singleLine = true)
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Password") }, leadingIcon = { Icon(AppIcon.LOCK.vector, null) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            }
            val refresh = state.serverRefresh
            if (refresh != null) ServerRefreshStatus(refresh, controller::cancelServerRefresh, controller::dismissServerRefresh)
            else if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        if (server != null) PageAction("Refresh library", AppIcon.LIBRARY, enabled = !state.loading, onClick = {
            controller.refreshServer()
        }) else PageAction("Connect", AppIcon.CLOUD, enabled = !state.loading, onClick = {
            submitted = true
            controller.connectServer(address, username, password)
        })
    }, dismissButton = {
        if (server != null) PageAction("Change server", AppIcon.EDIT, enabled = !state.loading, secondary = true, onClick = { editing = true })
        else PageAction("Cancel", AppIcon.CLOSE, secondary = true, onClick = { if (state.server != null) editing = false else onClose() })
    })
    if (disconnecting) AlertDialog(onDismissRequest = { disconnecting = false },
        title = { Text("Disconnect music server?") },
        text = { Text("Songs from this server stay in your library but can't be streamed until you reconnect. Downloaded songs keep playing." +
            if (state.sftp?.serverId == state.server?.serverId) " Server file editing (SFTP) is disconnected too." else "") },
        confirmButton = { TextButton(onClick = { controller.disconnectServer(); disconnecting = false }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { disconnecting = false }) { Text("Cancel") } })
}
