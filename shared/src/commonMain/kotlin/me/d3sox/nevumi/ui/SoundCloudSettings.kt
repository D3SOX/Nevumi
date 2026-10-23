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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.sources_ask_every_time
import me.d3sox.nevumi.resources.sources_browser_view_hint
import me.d3sox.nevumi.resources.sources_browser_view_setup_hint
import me.d3sox.nevumi.resources.sources_change_server
import me.d3sox.nevumi.resources.sources_check_tags_before_saving
import me.d3sox.nevumi.resources.sources_check_tags_before_saving_description
import me.d3sox.nevumi.resources.sources_connect
import me.d3sox.nevumi.resources.sources_connected
import me.d3sox.nevumi.resources.sources_disconnect
import me.d3sox.nevumi.resources.sources_download_format
import me.d3sox.nevumi.resources.sources_flac_hint
import me.d3sox.nevumi.resources.sources_gate_browser
import me.d3sox.nevumi.resources.sources_gate_browser_default
import me.d3sox.nevumi.resources.sources_gate_browser_headed
import me.d3sox.nevumi.resources.sources_gate_browser_headless
import me.d3sox.nevumi.resources.sources_gate_browser_xvfb
import me.d3sox.nevumi.resources.sources_save_to_music_server
import me.d3sox.nevumi.resources.sources_save_to_this_device
import me.d3sox.nevumi.resources.sources_sc_gate_address
import me.d3sox.nevumi.resources.sources_sc_gate_address_hint
import me.d3sox.nevumi.resources.sources_sc_gate_disconnect_failed
import me.d3sox.nevumi.resources.sources_sc_gate_ready
import me.d3sox.nevumi.resources.sources_sc_gate_ready_library
import me.d3sox.nevumi.resources.sources_sc_gate_unavailable
import me.d3sox.nevumi.resources.sources_soundcloud_device_destination
import me.d3sox.nevumi.resources.sources_soundcloud_intro
import me.d3sox.nevumi.resources.sources_soundcloud_library_destination
import me.d3sox.nevumi.resources.sources_test_connection
import me.d3sox.nevumi.resources.sources_when_you_pick_a_song
import org.jetbrains.compose.resources.stringResource

@Composable
private fun ScGateBrowser.displayName() = stringResource(when (this) {
    ScGateBrowser.HEADLESS -> Res.string.sources_gate_browser_headless
    ScGateBrowser.XVFB -> Res.string.sources_gate_browser_xvfb
    ScGateBrowser.HEADED -> Res.string.sources_gate_browser_headed
})

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
    val unavailable = stringResource(Res.string.sources_sc_gate_unavailable)
    val disconnectFailed = stringResource(Res.string.sources_sc_gate_disconnect_failed)
    val readyLibrary = stringResource(Res.string.sources_sc_gate_ready_library)
    val ready = stringResource(Res.string.sources_sc_gate_ready)
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
            Text(stringResource(Res.string.sources_soundcloud_intro))
            if (connected != null) SettingsCard(AppIcon.CLOUD) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(Res.string.sources_connected), style = MaterialTheme.typography.titleMedium)
                    Text(connected.baseUrl.substringAfter("://"), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (connected.libraryDestination) stringResource(Res.string.sources_soundcloud_library_destination)
                        else stringResource(Res.string.sources_soundcloud_device_destination),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(Res.string.sources_test_connection), AppIcon.REFRESH, enabled = !busy, style = ActionStyle.OUTLINED) {
                            run(unavailable) {
                                val capabilities = controller.testSoundCloud(connected.baseUrl)
                                checked = if (capabilities.libraryDestination) readyLibrary
                                    else ready
                            }
                        }
                        ActionButton(stringResource(Res.string.sources_disconnect), AppIcon.UNLINK, enabled = !busy, style = ActionStyle.DESTRUCTIVE) {
                            // A connection made before the deletion finished would be removed by it.
                            run(disconnectFailed) { controller.disconnectSoundCloud(); editing = true }
                        }
                    }
                }
            } else OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_sc_gate_address)) },
                leadingIcon = { Icon(AppIcon.CLOUD.vector, null) }, singleLine = true, enabled = !busy,
                supportingText = { Text(stringResource(Res.string.sources_sc_gate_address_hint)) })
            SettingsGroup {
                SettingChoice(stringResource(Res.string.sources_download_format), format, ScGateFormat.entries.map { it to it.label }) { choose(nextFormat = it) }
                SettingChoice(stringResource(Res.string.sources_when_you_pick_a_song), destination, listOf(SoundCloudDestination.ASK to stringResource(Res.string.sources_ask_every_time),
                    SoundCloudDestination.SERVER to stringResource(Res.string.sources_save_to_music_server), SoundCloudDestination.DEVICE to stringResource(Res.string.sources_save_to_this_device))) {
                    choose(nextDestination = it)
                }
                // The modes are known once sc-gate-dl is connected.
                connected?.browserModes?.takeIf { it.isNotEmpty() }?.let { modes ->
                    SettingChoice(stringResource(Res.string.sources_gate_browser), browser, listOf<Pair<ScGateBrowser?, String>>(null to stringResource(Res.string.sources_gate_browser_default)) +
                        modes.map { it to it.displayName() }) { choose(nextBrowser = it) }
                }
                SettingsToggle(stringResource(Res.string.sources_check_tags_before_saving), AppIcon.TAGS, stringResource(Res.string.sources_check_tags_before_saving_description),
                    reviewTags) { choose(nextReviewTags = it) }
            }
            if (format == ScGateFormat.FLAC) Text(stringResource(Res.string.sources_flac_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connected != null && browser == ScGateBrowser.HEADED) Text(if (connected.browserViewUrl != null)
                stringResource(Res.string.sources_browser_view_hint)
                else stringResource(Res.string.sources_browser_view_setup_hint),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            checked?.let { Text(it) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        if (connected != null) PageAction(stringResource(Res.string.done), enabled = !busy, onClick = dismiss)
        else PageAction(stringResource(Res.string.sources_connect), AppIcon.CLOUD, enabled = !busy && address.isNotBlank(), onClick = {
            run(unavailable) {
                // The new server may not offer the chosen browser mode.
                browser = controller.connectSoundCloud(address, format, destination, browser, reviewTags).browser
                editing = false
            }
        })
    }, dismissButton = {
        if (connected != null) PageAction(stringResource(Res.string.sources_change_server), AppIcon.EDIT, enabled = !busy, secondary = true, onClick = { editing = true })
        else PageAction(stringResource(Res.string.cancel), AppIcon.CLOSE, enabled = !busy, secondary = true, onClick = {
            // Choices made while changing servers are only saved by connecting.
            if (profile != null) {
                address = profile.baseUrl; format = profile.format; destination = profile.destination; browser = profile.browser
                reviewTags = profile.reviewTags; editing = false
            }
            else dismiss()
        })
    })
}
