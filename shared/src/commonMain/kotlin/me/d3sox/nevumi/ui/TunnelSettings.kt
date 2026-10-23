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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.app_name
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.music_server
import me.d3sox.nevumi.resources.server_files_sftp
import me.d3sox.nevumi.resources.sources_auth_key_hint
import me.d3sox.nevumi.resources.sources_auth_key_optional
import me.d3sox.nevumi.resources.sources_cancel_sign_in
import me.d3sox.nevumi.resources.sources_connect_through_tailscale
import me.d3sox.nevumi.resources.sources_control_server_hint
import me.d3sox.nevumi.resources.sources_control_server_optional
import me.d3sox.nevumi.resources.sources_open_sign_in_page
import me.d3sox.nevumi.resources.sources_reset_tailscale
import me.d3sox.nevumi.resources.sources_sign_in_to_tailscale
import me.d3sox.nevumi.resources.sources_sign_out_of_tailscale
import me.d3sox.nevumi.resources.sources_soundcloud_downloads
import me.d3sox.nevumi.resources.sources_tailscale_reset_failed
import me.d3sox.nevumi.resources.sources_tailscale_sign_out_failed
import me.d3sox.nevumi.resources.sources_tailscale_start_failed
import me.d3sox.nevumi.resources.sources_tailscale_unavailable
import me.d3sox.nevumi.resources.sources_tunnel_connected_as
import me.d3sox.nevumi.resources.sources_tunnel_connecting
import me.d3sox.nevumi.resources.sources_tunnel_intro
import me.d3sox.nevumi.resources.sources_tunnel_music_server_description
import me.d3sox.nevumi.resources.sources_tunnel_server_files_description
import me.d3sox.nevumi.resources.sources_tunnel_settings_save_failed
import me.d3sox.nevumi.resources.sources_tunnel_sign_in_hint
import me.d3sox.nevumi.resources.sources_tunnel_sign_in_required
import me.d3sox.nevumi.resources.sources_tunnel_soundcloud_description
import me.d3sox.nevumi.resources.sources_tunnel_waiting_for_approval
import me.d3sox.nevumi.resources.tunnel
import org.jetbrains.compose.resources.stringResource

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
    val unavailable = stringResource(Res.string.sources_tailscale_unavailable)
    val resetFailed = stringResource(Res.string.sources_tailscale_reset_failed)
    val startFailed = stringResource(Res.string.sources_tailscale_start_failed)
    val signOutFailed = stringResource(Res.string.sources_tailscale_sign_out_failed)
    val saveFailed = stringResource(Res.string.sources_tunnel_settings_save_failed)
    LaunchedEffect(Unit) {
        routes = controller.tunnelRoutes()
        while (true) {
            try {
                status = controller.tailscaleStatus().also { if (it.controlUrl.isNotEmpty()) controlUrl = it.controlUrl }
                pollError = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { pollError = failure.message ?: unavailable }
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
    SettingsScreen(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(Res.string.tunnel)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.sources_tunnel_intro))
            if (busy || status == null && pollError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status == null && pollError != null) ActionButton(stringResource(Res.string.sources_reset_tailscale), AppIcon.RESET, enabled = !busy, style = ActionStyle.OUTLINED) {
                run(resetFailed) { controller.signOutOfTailscale(); status = controller.tailscaleStatus(); pollError = null }
            }
            status?.let { current ->
                if (current.state == "Stopped") {
                    OutlinedTextField(controlUrl, { controlUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_control_server_optional)) },
                        leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true, enabled = !busy,
                        supportingText = { Text(stringResource(Res.string.sources_control_server_hint)) })
                    OutlinedTextField(authKey, { authKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_auth_key_optional)) },
                        leadingIcon = { Icon(AppIcon.KEY.vector, null) }, singleLine = true, enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(), supportingText = { Text(stringResource(Res.string.sources_auth_key_hint)) })
                    ActionButton(stringResource(Res.string.sources_sign_in_to_tailscale), AppIcon.SIGN_IN, enabled = !busy && (controlUrl.isBlank() || controlUrl.trim().startsWith("http"))) {
                        run(startFailed) {
                            status = controller.signInToTailscale(controlUrl.trim().trimEnd('/'), authKey.trim().takeIf { it.isNotEmpty() })
                            authKey = ""
                        }
                    }
                } else {
                    Text(when {
                        current.connected -> stringResource(Res.string.sources_tunnel_connected_as, current.name ?: stringResource(Res.string.app_name))
                        current.authUrl != null -> stringResource(Res.string.sources_tunnel_sign_in_hint)
                        current.state == "NeedsMachineAuth" -> stringResource(Res.string.sources_tunnel_waiting_for_approval)
                        else -> stringResource(Res.string.sources_tunnel_connecting)
                    })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        current.authUrl?.let { url -> ActionButton(stringResource(Res.string.sources_open_sign_in_page), AppIcon.OPEN_EXTERNAL) { browser.openUri(url) } }
                        ActionButton(if (current.connected) stringResource(Res.string.sources_sign_out_of_tailscale) else stringResource(Res.string.sources_cancel_sign_in),
                            if (current.connected) AppIcon.SIGN_OUT else AppIcon.CLOSE, enabled = !busy, style = ActionStyle.OUTLINED) {
                            run(signOutFailed) { controller.signOutOfTailscale(); status = controller.tailscaleStatus() }
                        }
                    }
                }
            }
            routes?.let { current ->
                Text(stringResource(Res.string.sources_connect_through_tailscale), style = MaterialTheme.typography.titleMedium)
                fun change(updated: TunnelRoutes) = run(saveFailed) {
                    controller.updateTunnelRoutes(updated); routes = updated
                }
                SettingsToggle(stringResource(Res.string.music_server), AppIcon.SERVER, stringResource(Res.string.sources_tunnel_music_server_description), current.musicServer) {
                    if (!busy) change(current.copy(musicServer = it))
                }
                if (controller.serverFilesAvailable) SettingsToggle(stringResource(Res.string.server_files_sftp), AppIcon.SERVER_FILES, stringResource(Res.string.sources_tunnel_server_files_description),
                    current.serverFiles) { if (!busy) change(current.copy(serverFiles = it)) }
                if (controller.soundCloudAvailable) SettingsToggle(stringResource(Res.string.sources_soundcloud_downloads), AppIcon.CLOUD, stringResource(Res.string.sources_tunnel_soundcloud_description),
                    current.scGate) { if (!busy) change(current.copy(scGate = it)) }
                if ((current.musicServer || current.serverFiles || current.scGate) && status?.state == "Stopped")
                    Text(stringResource(Res.string.sources_tunnel_sign_in_required), color = MaterialTheme.colorScheme.error)
            }
            (error ?: pollError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), enabled = !busy, onClick = dismiss) })
}
