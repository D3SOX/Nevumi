package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_audio_output
import me.d3sox.nevumi.resources.sources_close_audio_output
import me.d3sox.nevumi.resources.sources_move_playback_here
import me.d3sox.nevumi.resources.sources_pair_a_device
import me.d3sox.nevumi.resources.sources_pair_a_device_description
import me.d3sox.nevumi.resources.sources_system_output_failed
import me.d3sox.nevumi.resources.sources_this_device
import me.d3sox.nevumi.resources.sources_this_device_description
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioOutputPicker(controller: MusicController, dismiss: () -> Unit, pairDevices: () -> Unit) {
    val devices = controller.pairedDevices
    val state = devices?.state?.collectAsState()?.value
    val systemOutput = rememberSystemAudioOutput()
    var failed by remember { mutableStateOf(false) }
    AppBottomSheet(onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(Res.string.sources_audio_output), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            AppIconButton(AppIcon.CLOSE, stringResource(Res.string.sources_close_audio_output), onClick = dismiss)
        }
        ListItem(headlineContent = { Text(stringResource(Res.string.sources_this_device)) }, supportingContent = { Text(stringResource(Res.string.sources_this_device_description)) },
            leadingContent = { Icon(AppIcon.DEVICE.vector, null) },
            modifier = Modifier.clickable { if (systemOutput()) dismiss() else failed = true })
        state?.peers?.forEach { peer ->
            ListItem(headlineContent = { Text(peer.name) }, supportingContent = { Text(stringResource(Res.string.sources_move_playback_here)) },
                leadingContent = { Icon(AppIcon.DEVICES.vector, null) }, modifier = Modifier.clickable(enabled = !state.busy) {
                    controller.transferPlayback(peer.deviceId); dismiss()
                })
        }
        if (devices != null) ListItem(headlineContent = { Text(stringResource(Res.string.sources_pair_a_device)) }, supportingContent = { Text(stringResource(Res.string.sources_pair_a_device_description)) },
            leadingContent = { Icon(AppIcon.DEVICES.vector, null) }, modifier = Modifier.clickable { dismiss(); pairDevices() })
        if (failed) Text(stringResource(Res.string.sources_system_output_failed), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
    }
}
