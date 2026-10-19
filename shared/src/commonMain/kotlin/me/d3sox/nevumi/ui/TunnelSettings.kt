package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.tunnel.TailscaleStatus
import me.d3sox.nevumi.tunnel.TunnelRoutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TunnelSettings(controller: MusicController, dismiss: () -> Unit) {
    var status by remember { mutableStateOf<TailscaleStatus?>(null) }
    var routes by remember { mutableStateOf<TunnelRoutes?>(null) }
    var controlUrl by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pollError by remember { mutableStateOf<String?>(null) }
    val browser = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        routes = controller.tunnelRoutes()
        while (true) {
            try {
                status = controller.tailscaleStatus().also { if (it.controlUrl.isNotEmpty()) controlUrl = it.controlUrl }
                pollError = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { pollError = failure.message ?: "Tailscale is unavailable" }
            delay(if (status?.connected == true) 10_000 else 2_000)
        }
    }
    fun run(fallback: String, operation: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: fallback }
            finally { busy = false }
        }
    }
    SettingsScreen(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Tunnel") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Nevumi can join your tailnet itself, so it reaches servers there without the Tailscale app or a VPN.")
            if (busy || status == null && pollError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status == null && pollError != null) ActionButton("Reset Tailscale", AppIcon.RESET, enabled = !busy, style = ActionStyle.OUTLINED) {
                run("Tailscale could not be reset") { controller.signOutOfTailscale(); status = controller.tailscaleStatus(); pollError = null }
            }
            status?.let { current ->
                if (current.state == "Stopped") {
                    OutlinedTextField(controlUrl, { controlUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Control server (optional)") },
                        leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true, enabled = !busy,
                        supportingText = { Text("Leave empty for Tailscale, or enter your Headscale URL.") })
                    OutlinedTextField(authKey, { authKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Auth key (optional)") },
                        leadingIcon = { Icon(AppIcon.KEY.vector, null) }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(), supportingText = { Text("Without a key, sign in through your browser.") })
                    ActionButton("Sign in to Tailscale", AppIcon.SIGN_IN, enabled = !busy && (controlUrl.isBlank() || controlUrl.trim().startsWith("http"))) {
                        run("Tailscale could not start") {
                            status = controller.signInToTailscale(controlUrl.trim().trimEnd('/'), authKey.trim().takeIf { it.isNotEmpty() })
                            authKey = ""
                        }
                    }
                } else {
                    Text(when {
                        current.connected -> "Connected to your tailnet as ${current.name ?: "Nevumi"}"
                        current.authUrl != null -> "Sign in to add Nevumi to your tailnet."
                        current.state == "NeedsMachineAuth" -> "Waiting for your tailnet admin to approve this device."
                        else -> "Connecting to Tailscale…"
                    })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        current.authUrl?.let { url -> ActionButton("Open sign-in page", AppIcon.OPEN_EXTERNAL) { browser.openUri(url) } }
                        ActionButton(if (current.connected) "Sign out of Tailscale" else "Cancel sign-in",
                            if (current.connected) AppIcon.SIGN_OUT else AppIcon.CLOSE, enabled = !busy, style = ActionStyle.OUTLINED) {
                            run("Tailscale could not sign out") { controller.signOutOfTailscale(); status = controller.tailscaleStatus() }
                        }
                    }
                }
            }
            routes?.let { current ->
                Text("Connect through Tailscale", style = MaterialTheme.typography.titleMedium)
                fun change(updated: TunnelRoutes) = run("The tunnel settings could not be saved") {
                    controller.updateTunnelRoutes(updated); routes = updated
                }
                SettingsToggle("Music server", AppIcon.SERVER, "Navidrome requests, streaming and downloads", current.musicServer) {
                    if (!busy) change(current.copy(musicServer = it))
                }
                if (controller.serverFilesAvailable) SettingsToggle("Server files (SFTP)", AppIcon.SERVER_FILES, "File editing and uploads",
                    current.serverFiles) { if (!busy) change(current.copy(serverFiles = it)) }
                if ((current.musicServer || current.serverFiles) && status?.state == "Stopped")
                    Text("Sign in to Tailscale to use these connections.", color = MaterialTheme.colorScheme.error)
            }
            (error ?: pollError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction("Done", enabled = !busy, onClick = dismiss) })
}
