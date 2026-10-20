package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioOutputPicker(controller: MusicController, dismiss: () -> Unit, pairDevices: () -> Unit) {
    val devices = controller.pairedDevices
    val state = devices?.state?.collectAsState()?.value
    val systemOutput = rememberSystemAudioOutput()
    var failed by remember { mutableStateOf(false) }
    AppBottomSheet(onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Audio output", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
            AppIconButton(AppIcon.CLOSE, "Close audio output", onClick = dismiss)
        }
        ListItem(headlineContent = { Text("This device") }, supportingContent = { Text("Choose speakers, headphones, or Bluetooth") },
            leadingContent = { Icon(AppIcon.DEVICE.vector, null) },
            modifier = Modifier.clickable { if (systemOutput()) dismiss() else failed = true })
        state?.peers?.forEach { peer ->
            ListItem(headlineContent = { Text(peer.name) }, supportingContent = { Text("Move playback here") },
                leadingContent = { Icon(AppIcon.DEVICES.vector, null) }, modifier = Modifier.clickable(enabled = !state.busy) {
                    controller.transferPlayback(peer.deviceId); dismiss()
                })
        }
        if (devices != null) ListItem(headlineContent = { Text("Pair a device") }, supportingContent = { Text("Connect another Nevumi phone or computer") },
            leadingContent = { Icon(AppIcon.DEVICES.vector, null) }, modifier = Modifier.clickable { dismiss(); pairDevices() })
        if (failed) Text("Could not open the system audio output selector", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
    }
}
