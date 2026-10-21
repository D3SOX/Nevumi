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
import me.d3sox.nevumi.soundcloud.ScGateBrowser
import me.d3sox.nevumi.soundcloud.ScGateFormat
import me.d3sox.nevumi.soundcloud.SoundCloudDestination

@Composable
internal fun SoundCloudSettings(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val profile = state.soundCloud
    var address by remember { mutableStateOf(profile?.baseUrl.orEmpty()) }
    var format by remember { mutableStateOf(profile?.format ?: ScGateFormat.MP3_320) }
    var destination by remember { mutableStateOf(profile?.destination ?: SoundCloudDestination.ASK) }
    var browser by remember { mutableStateOf(profile?.browser) }
    var reviewTags by remember { mutableStateOf(profile?.reviewTags ?: false) }
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
    // Servers set up before Nevumi knew sc-gate-dl's browser modes list them once asked again.
    LaunchedEffect(connected?.baseUrl) {
        if (connected != null && connected.browserModes.isEmpty()) try { controller.testSoundCloud(connected.baseUrl) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Test connection reports the problem. */ }
    }
    fun choose(nextFormat: ScGateFormat = format, nextDestination: SoundCloudDestination = destination, nextBrowser: ScGateBrowser? = browser,
        nextReviewTags: Boolean = reviewTags) {
        format = nextFormat; destination = nextDestination; browser = nextBrowser; reviewTags = nextReviewTags
        if (connected != null) controller.updateSoundCloud(nextFormat, nextDestination, nextBrowser, nextReviewTags)
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
                            // A connection made before the deletion finished would be removed by it.
                            run("Could not disconnect sc-gate-dl") { controller.disconnectSoundCloud(); editing = true }
                        }
                    }
                }
            } else OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text("sc-gate-dl address") },
                leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true, enabled = !busy,
                supportingText = { Text("The API address, such as http://homelan:3000") })
            SettingsGroup {
                SettingChoice("Download format", format, ScGateFormat.entries.map { it to it.label }) { choose(nextFormat = it) }
                SettingChoice("When you pick a song", destination, listOf(SoundCloudDestination.ASK to "Ask every time",
                    SoundCloudDestination.SERVER to "Save to music server", SoundCloudDestination.DEVICE to "Save to this device")) {
                    choose(nextDestination = it)
                }
                // The modes are known once sc-gate-dl is connected.
                connected?.browserModes?.takeIf { it.isNotEmpty() }?.let { modes ->
                    SettingChoice("Gate browser", browser, listOf<Pair<ScGateBrowser?, String>>(null to "sc-gate-dl's default") +
                        modes.map { it to it.label }) { choose(nextBrowser = it) }
                }
                SettingsToggle("Check tags before saving", AppIcon.TAGS, "Downloads wait for you to edit their tags, like in sc-gate-dl's web interface",
                    reviewTags) { choose(nextReviewTags = it) }
            }
            if (format == ScGateFormat.FLAC) Text("FLAC keeps lossless sources lossless. A lossy source does not gain quality in a FLAC file.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connected != null && browser == ScGateBrowser.HEADED) Text(if (connected.browserViewUrl != null)
                "Watch sc-gate-dl's browser from a download and take over when a gate needs you."
                else "Set BROWSER_VIEW_URL on sc-gate-dl to watch its browser from Nevumi.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            checked?.let { Text(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        if (connected != null) PageAction("Done", enabled = !busy, onClick = dismiss)
        else PageAction("Connect", AppIcon.CLOUD, enabled = !busy && address.isNotBlank(), onClick = {
            run("sc-gate-dl is unavailable") {
                // The new server may not offer the chosen browser mode.
                browser = controller.connectSoundCloud(address, format, destination, browser, reviewTags).browser
                editing = false
            }
        })
    }, dismissButton = {
        if (connected != null) PageAction("Change server", AppIcon.EDIT, enabled = !busy, secondary = true, onClick = { editing = true })
        else PageAction("Cancel", AppIcon.CLOSE, enabled = !busy, secondary = true, onClick = {
            // Choices made while changing servers are only saved by connecting.
            if (profile != null) {
                address = profile.baseUrl; format = profile.format; destination = profile.destination; browser = profile.browser
                reviewTags = profile.reviewTags; editing = false
            }
            else dismiss()
        })
    })
}
