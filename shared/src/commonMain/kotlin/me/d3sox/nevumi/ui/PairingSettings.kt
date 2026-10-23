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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.paired_devices
import me.d3sox.nevumi.resources.remove
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.sources_all_shared_songs_matched
import me.d3sox.nevumi.resources.sources_cancel_invitation
import me.d3sox.nevumi.resources.sources_choose_folder_for_playlist
import me.d3sox.nevumi.resources.sources_choose_folder_for_playlist_description
import me.d3sox.nevumi.resources.sources_choose_smart_playlist_folder
import me.d3sox.nevumi.resources.sources_create_invitation
import me.d3sox.nevumi.resources.sources_device_network_address
import me.d3sox.nevumi.resources.sources_device_sync_failed
import me.d3sox.nevumi.resources.sources_enter_code
import me.d3sox.nevumi.resources.sources_enter_pairing_code
import me.d3sox.nevumi.resources.sources_folder_for_playlist
import me.d3sox.nevumi.resources.sources_invitation_hint
import me.d3sox.nevumi.resources.sources_library_synced
import me.d3sox.nevumi.resources.sources_pair_another_device
import me.d3sox.nevumi.resources.sources_pair_device
import me.d3sox.nevumi.resources.sources_pairing_intro
import me.d3sox.nevumi.resources.sources_pairing_invitation
import me.d3sox.nevumi.resources.sources_pairing_sync_hint
import me.d3sox.nevumi.resources.sources_paste_pairing_invitation
import me.d3sox.nevumi.resources.sources_ready_to_sync
import me.d3sox.nevumi.resources.sources_remove_device
import me.d3sox.nevumi.resources.sources_remove_device_description
import me.d3sox.nevumi.resources.sources_remove_device_named
import me.d3sox.nevumi.resources.sources_remove_device_question
import me.d3sox.nevumi.resources.sources_scan_qr_code
import me.d3sox.nevumi.resources.sources_songs_need_your_copy
import me.d3sox.nevumi.resources.sources_songs_to_match
import me.d3sox.nevumi.resources.sources_sync_now
import me.d3sox.nevumi.resources.sources_sync_with_device
import me.d3sox.nevumi.resources.sources_your_invitation
import org.jetbrains.compose.resources.stringResource

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
    val syncFailed = stringResource(Res.string.sources_device_sync_failed)
    fun run(block: suspend () -> Unit) {
        error = null
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = devices.state.value.error ?: failure.message ?: syncFailed }
        }
    }
    AnimatedNavigation(matching, false, canGoBack = matching, onBack = { matching = false }, depth = { if (it) 1 else 0 }) { matchingPage ->
    if (matchingPage) SongsToMatch(devices, unmatched, tracks, artwork) { matching = false }
    else SettingsScreen(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.paired_devices)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.sources_pairing_intro))
            Text(stringResource(Res.string.sources_pairing_sync_hint))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.peers.forEach { peer ->
                val syncDescription = stringResource(Res.string.sources_sync_with_device, peer.name)
                val removeDescription = stringResource(Res.string.sources_remove_device_named, peer.name)
                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                Text(if (peer.lastSyncedAt == null) stringResource(Res.string.sources_ready_to_sync) else stringResource(Res.string.sources_library_synced), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(Res.string.sources_sync_now), AppIcon.SYNC, Modifier.semantics { contentDescription = syncDescription },
                        enabled = !state.busy) { run { devices.synchronize(peer.deviceId) } }
                    ActionButton(stringResource(Res.string.remove), AppIcon.REMOVE, Modifier.semantics { contentDescription = removeDescription },
                        enabled = !state.busy, style = ActionStyle.DESTRUCTIVE) { remove = peer }
                }
            }
            SettingsLink(stringResource(Res.string.sources_songs_to_match), AppIcon.LINK,
                if (unmatched.isEmpty()) stringResource(Res.string.sources_all_shared_songs_matched) else stringResource(Res.string.sources_songs_need_your_copy, countLabel(unmatched.size, Res.plurals.songs))) { matching = true }
            folders.forEach { request ->
                val chooseFolderDescription = stringResource(Res.string.sources_choose_folder_for_playlist_description, request.playlistName)
                ActionButton(stringResource(Res.string.sources_choose_folder_for_playlist, request.playlistName), AppIcon.FOLDER, Modifier.semantics {
                    contentDescription = chooseFolderDescription
                }, style = ActionStyle.OUTLINED) { folder = request }
            }
            Text(stringResource(Res.string.sources_pair_another_device), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !state.busy, onClick = { scanning = true }) {
                    Icon(AppIcon.QR_SCAN.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.sources_scan_qr_code))
                }
                OutlinedButton(enabled = !state.busy, onClick = { error = null; enteringCode = true }) {
                    Icon(AppIcon.EDIT.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.sources_enter_code))
                }
            }
            OutlinedTextField(address, { address = it }, label = { Text(stringResource(Res.string.sources_device_network_address)) },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            if (state.invitation == null) ActionButton(stringResource(Res.string.sources_create_invitation), AppIcon.PLUS,
                enabled = address.isNotBlank() && !state.busy) { run { devices.createInvitation(address.trim()) } }
            else {
                Text(stringResource(Res.string.sources_invitation_hint))
                Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    PairingQr(requireNotNull(state.invitation))
                }
                OutlinedTextField(requireNotNull(state.invitation), {}, readOnly = true, label = { Text(stringResource(Res.string.sources_your_invitation)) },
                    maxLines = 3, modifier = Modifier.fillMaxWidth())
                ActionButton(stringResource(Res.string.sources_cancel_invitation), AppIcon.CLOSE, style = ActionStyle.OUTLINED) { run { devices.cancelInvitation() } }
            }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
    }
    if (scanning) PairingScanner(onScanned = { code ->
        scanning = false
        invitation = code
        run { devices.pair(code); invitation = "" }
    }, dismiss = { scanning = false }, useManualCode = { scanning = false; error = null; enteringCode = true })
    if (enteringCode) {
        val focus = remember { FocusRequester() }
        KeyboardAlertDialog(onDismissRequest = { enteringCode = false }, title = { Text(stringResource(Res.string.sources_enter_pairing_code)) }, text = {
            LaunchedEffect(Unit) { focus.requestFocus() }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.sources_paste_pairing_invitation))
                OutlinedTextField(invitation, { invitation = it; error = null }, label = { Text(stringResource(Res.string.sources_pairing_invitation)) },
                    enabled = !state.busy, isError = error != null, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = invitation.isNotBlank() && !state.busy, onClick = { run {
                devices.pair(invitation.trim()); invitation = ""; enteringCode = false
            } }) { Text(stringResource(Res.string.sources_pair_device)) }
        }, dismissButton = { TextButton(onClick = { enteringCode = false }) { Text(stringResource(Res.string.cancel)) } })
    }
    remove?.let { peer ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text(stringResource(Res.string.sources_remove_device_question, peer.name)) },
            text = { Text(stringResource(Res.string.sources_remove_device_description, peer.name)) },
            confirmButton = { TextButton(onClick = { run { devices.forget(peer.deviceId); remove = null } }) { Text(stringResource(Res.string.sources_remove_device)) } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text(stringResource(Res.string.cancel)) } })
    }
    folder?.let { request ->
        AlertDialog(onDismissRequest = { folder = null }, title = { Text(stringResource(Res.string.sources_folder_for_playlist, request.playlistName)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.sources_choose_smart_playlist_folder))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(tracks.map { it.metadata.folder }.filter { it.isNotBlank() }.distinct().sorted()) { path ->
                        TextButton(onClick = { run { devices.library.mapFolder(request.folderToken, path); folder = null } }) { Text(path) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { folder = null }) { Text(stringResource(Res.string.cancel)) } })
    }
}
