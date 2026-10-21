package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.soundcloud.ScGateFormat
import me.d3sox.nevumi.soundcloud.SoundCloudDestination

@Composable
internal fun SoundCloudSettings(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val profile = state.soundCloud
    var address by remember { mutableStateOf(profile?.baseUrl.orEmpty()) }
    var format by remember { mutableStateOf(profile?.format ?: ScGateFormat.MP3_320) }
    var destination by remember { mutableStateOf(profile?.destination ?: SoundCloudDestination.ASK) }
    var editing by remember { mutableStateOf(profile == null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun run(fallback: String, operation: suspend () -> Unit) {
        busy = true; error = null; checked = null
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: fallback }
            finally { busy = false }
        }
    }
    val connected = profile.takeUnless { editing }
    fun choose(nextFormat: ScGateFormat, nextDestination: SoundCloudDestination) {
        format = nextFormat; destination = nextDestination
        if (connected != null) controller.updateSoundCloud(nextFormat, nextDestination)
    }
    SettingsScreen(onDismissRequest = { if (!busy) dismiss() }, title = { Text("SoundCloud") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Search SoundCloud and download songs through your sc-gate-dl server. It runs the searches, so this device does not contact SoundCloud.")
            if (connected != null) SettingsCard(AppIcon.CLOUD) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Connected", style = MaterialTheme.typography.titleMedium)
                    Text(connected.baseUrl.substringAfter("://"), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (connected.libraryDestination) "Can save songs to your music server's library"
                        else "Saves songs to this device. Set SC_GATE_DL_LIBRARY_DIR on the server to add them to your music server.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Test connection", AppIcon.REFRESH, enabled = !busy, style = ActionStyle.OUTLINED) {
                            run("sc-gate-dl is unavailable") {
                                val capabilities = controller.testSoundCloud(connected.baseUrl)
                                checked = if (capabilities.libraryDestination) "sc-gate-dl is ready and can save to your music server"
                                    else "sc-gate-dl is ready"
                            }
                        }
                        ActionButton("Disconnect", AppIcon.UNLINK, enabled = !busy, style = ActionStyle.DESTRUCTIVE) {
                            controller.disconnectSoundCloud(); editing = true
                        }
                    }
                }
            } else OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text("sc-gate-dl address") },
                leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true, enabled = !busy,
                supportingText = { Text("The API address, such as http://homelan:3000") })
            SettingsGroup {
                SettingChoice("Download format", format, ScGateFormat.entries.map { it to it.label }) { choose(it, destination) }
                SettingChoice("When you pick a song", destination, listOf(SoundCloudDestination.ASK to "Ask every time",
                    SoundCloudDestination.SERVER to "Save to music server", SoundCloudDestination.DEVICE to "Save to this device")) {
                    choose(format, it)
                }
            }
            if (format == ScGateFormat.FLAC) Text("FLAC keeps lossless sources lossless. A lossy source does not gain quality in a FLAC file.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            checked?.let { Text(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        if (connected != null) PageAction("Done", enabled = !busy, onClick = dismiss)
        else PageAction("Connect", AppIcon.CLOUD, enabled = !busy && address.isNotBlank(), onClick = {
            run("sc-gate-dl is unavailable") { controller.connectSoundCloud(address, format, destination); editing = false }
        })
    }, dismissButton = {
        if (connected != null) PageAction("Change server", AppIcon.EDIT, enabled = !busy, secondary = true, onClick = { editing = true })
        else PageAction("Cancel", AppIcon.CLOSE, enabled = !busy, secondary = true, onClick = {
            // Choices made while changing servers are only saved by connecting.
            if (profile != null) { address = profile.baseUrl; format = profile.format; destination = profile.destination; editing = false }
            else dismiss()
        })
    })
}
