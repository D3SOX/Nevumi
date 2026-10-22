package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.sync.PairedDevices
import me.d3sox.nevumi.sync.SyncPeer
import me.d3sox.nevumi.sync.SyncFolderRequest
import me.d3sox.nevumi.library.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PairingSettings(devices: PairedDevices, tracks: List<Track>, dismiss: () -> Unit, artwork: Map<String, String> = emptyMap()) {
    val state by devices.state.collectAsState()
    val unmatched by devices.library.unmatchedSongs.collectAsState(emptyList())
    val folders by devices.library.folderRequests.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var invitation by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var enteringCode by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(devices.localAddresses.firstOrNull().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf<SyncPeer?>(null) }
    var matching by rememberSaveable { mutableStateOf(false) }
    var folder by remember { mutableStateOf<SyncFolderRequest?>(null) }
    fun run(block: suspend () -> Unit) {
        error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = devices.state.value.error ?: failure.message ?: "Device sync failed" }
        }
    }
    AnimatedNavigation(matching, false, canGoBack = matching, onBack = { matching = false }, depth = { if (it) 1 else 0 }) { matchingPage ->
    if (matchingPage) SongsToMatch(devices, unmatched, tracks, artwork) { matching = false }
    else SettingsScreen(onDismissRequest = dismiss, title = { Text("Paired devices") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Share favorites, ratings, listening history, app playlists and saved queues over an encrypted connection. Music files, credentials and playback position stay on each device.")
            Text("Open Nevumi on both devices, then choose Sync now.")
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.peers.forEach { peer ->
                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                Text(if (peer.lastSyncedAt == null) "Ready to sync" else "Library synced", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Sync now", AppIcon.SYNC, Modifier.semantics { contentDescription = "Sync with ${peer.name}" },
                        enabled = !state.busy) { run { devices.synchronize(peer.deviceId) } }
                    ActionButton("Remove", AppIcon.REMOVE, Modifier.semantics { contentDescription = "Remove ${peer.name}" },
                        enabled = !state.busy, style = ActionStyle.DESTRUCTIVE) { remove = peer }
                }
            }
            SettingsLink("Songs to match", AppIcon.LINK,
                if (unmatched.isEmpty()) "All shared songs matched" else "${countLabel(unmatched.size, "song")} need your copy") { matching = true }
            folders.forEach { request ->
                ActionButton("Choose folder: ${request.playlistName}", AppIcon.FOLDER, Modifier.semantics {
                    contentDescription = "Choose folder for ${request.playlistName}"
                }, style = ActionStyle.OUTLINED) { folder = request }
            }
            Text("Pair another device", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !state.busy, onClick = { scanning = true }) {
                    Icon(AppIcon.QR_SCAN.vector, null); Spacer(Modifier.width(8.dp)); Text("Scan QR code")
                }
                OutlinedButton(enabled = !state.busy, onClick = { error = null; enteringCode = true }) {
                    Icon(AppIcon.EDIT.vector, null); Spacer(Modifier.width(8.dp)); Text("Enter code")
                }
            }
            OutlinedTextField(address, { address = it }, label = { Text("This device’s network address") },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            if (state.invitation == null) ActionButton("Create invitation", AppIcon.PLUS,
                enabled = address.isNotBlank() && !state.busy) { run { devices.createInvitation(address.trim()) } }
            else {
                Text("Scan this QR code or copy the invitation to your other device. It expires in five minutes.")
                Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    PairingQr(requireNotNull(state.invitation))
                }
                OutlinedTextField(requireNotNull(state.invitation), {}, readOnly = true, label = { Text("Your invitation") },
                    maxLines = 3, modifier = Modifier.fillMaxWidth())
                ActionButton("Cancel invitation", AppIcon.CLOSE, style = ActionStyle.OUTLINED) { run { devices.cancelInvitation() } }
            }
        }
    }, confirmButton = { PageAction("Done", onClick = dismiss) })
    }
    if (scanning) PairingScanner(onScanned = { code ->
        scanning = false
        invitation = code
        run { devices.pair(code); invitation = "" }
    }, dismiss = { scanning = false }, useManualCode = { scanning = false; error = null; enteringCode = true })
    if (enteringCode) {
        val focus = remember { FocusRequester() }
        KeyboardAlertDialog(onDismissRequest = { enteringCode = false }, title = { Text("Enter pairing code") }, text = {
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Paste the pairing invitation from your other device.")
                OutlinedTextField(invitation, { invitation = it; error = null }, label = { Text("Pairing invitation") },
                    enabled = !state.busy, isError = error != null, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = invitation.isNotBlank() && !state.busy, onClick = { run {
                devices.pair(invitation.trim()); invitation = ""; enteringCode = false
            } }) { Text("Pair device") }
        }, dismissButton = { TextButton(onClick = { enteringCode = false }) { Text("Cancel") } })
    }
    remove?.let { peer ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${peer.name}?") },
            text = { Text("This device will no longer accept sync from ${peer.name}. Your library changes remain on both devices.") },
            confirmButton = { TextButton(onClick = { run { devices.forget(peer.deviceId); remove = null } }) { Text("Remove device") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancel") } })
    }
    folder?.let { request ->
        AlertDialog(onDismissRequest = { folder = null }, title = { Text("Folder for ${request.playlistName}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose the folder this smart playlist should use on this device.")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(tracks.map { it.metadata.folder }.filter { it.isNotBlank() }.distinct().sorted()) { path ->
                        TextButton(onClick = { run { devices.library.mapFolder(request.folderToken, path); folder = null } }) { Text(path) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { folder = null }) { Text("Cancel") } })
    }
}
