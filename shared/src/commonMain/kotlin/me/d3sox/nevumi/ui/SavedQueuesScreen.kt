package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.queue.*
import me.d3sox.nevumi.settings.QueueRecoveryMode
import org.jetbrains.compose.resources.stringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.export_queue
import me.d3sox.nevumi.resources.name
import me.d3sox.nevumi.resources.player_capture
import me.d3sox.nevumi.resources.player_capture_automatically
import me.d3sox.nevumi.resources.player_capture_automatically_text
import me.d3sox.nevumi.resources.player_convert_to_live_queue
import me.d3sox.nevumi.resources.player_convert_to_snapshot
import me.d3sox.nevumi.resources.player_duplicate
import me.d3sox.nevumi.resources.player_duplicate_queue
import me.d3sox.nevumi.resources.player_every
import me.d3sox.nevumi.resources.player_export
import me.d3sox.nevumi.resources.player_fixed_snapshot
import me.d3sox.nevumi.resources.player_keep_as_snapshot
import me.d3sox.nevumi.resources.player_live_queue
import me.d3sox.nevumi.resources.player_live_queue_detail
import me.d3sox.nevumi.resources.player_live_queues
import me.d3sox.nevumi.resources.player_live_queues_description
import me.d3sox.nevumi.resources.player_live_queues_empty
import me.d3sox.nevumi.resources.player_load_queue
import me.d3sox.nevumi.resources.player_minutes
import me.d3sox.nevumi.resources.player_no_current_song
import me.d3sox.nevumi.resources.player_now_playing
import me.d3sox.nevumi.resources.player_overwrite
import me.d3sox.nevumi.resources.player_overwrite_live_text
import me.d3sox.nevumi.resources.player_overwrite_saved_queue
import me.d3sox.nevumi.resources.player_overwrite_snapshot_text
import me.d3sox.nevumi.resources.player_overwrite_title
import me.d3sox.nevumi.resources.player_overwrite_with_current_queue
import me.d3sox.nevumi.resources.player_playing_from_context
import me.d3sox.nevumi.resources.player_previously_played
import me.d3sox.nevumi.resources.player_queue_copy_name
import me.d3sox.nevumi.resources.player_queue_name
import me.d3sox.nevumi.resources.player_recovery
import me.d3sox.nevumi.resources.player_recovery_capture_text
import me.d3sox.nevumi.resources.player_recovery_description
import me.d3sox.nevumi.resources.player_recovery_empty
import me.d3sox.nevumi.resources.player_recovery_off_summary
import me.d3sox.nevumi.resources.player_recovery_on_summary
import me.d3sox.nevumi.resources.player_recovery_privacy_text
import me.d3sox.nevumi.resources.player_recovery_settings
import me.d3sox.nevumi.resources.player_recovery_snapshots
import me.d3sox.nevumi.resources.player_remove_saved_queue
import me.d3sox.nevumi.resources.player_rename_queue
import me.d3sox.nevumi.resources.player_restore_snapshot
import me.d3sox.nevumi.resources.player_save_as
import me.d3sox.nevumi.resources.player_save_current_queue
import me.d3sox.nevumi.resources.player_save_live_queue_description
import me.d3sox.nevumi.resources.player_save_queue
import me.d3sox.nevumi.resources.player_save_snapshot_description
import me.d3sox.nevumi.resources.player_saved_queue_options
import me.d3sox.nevumi.resources.player_select_queue
import me.d3sox.nevumi.resources.player_selected
import me.d3sox.nevumi.resources.player_snapshot_detail
import me.d3sox.nevumi.resources.player_snapshot_song
import me.d3sox.nevumi.resources.player_snapshots
import me.d3sox.nevumi.resources.player_snapshots_description
import me.d3sox.nevumi.resources.player_snapshots_empty
import me.d3sox.nevumi.resources.player_song_not_in_library
import me.d3sox.nevumi.resources.player_unavailable_on_device
import me.d3sox.nevumi.resources.queue_export_up_next
import me.d3sox.nevumi.resources.player_use_temporary_queue
import me.d3sox.nevumi.resources.remove
import me.d3sox.nevumi.resources.rename
import me.d3sox.nevumi.resources.save
import me.d3sox.nevumi.resources.saved_queues
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.unknown_artist

@Composable
internal fun SavedQueueKind.label() = stringResource(when (this) {
    SavedQueueKind.LIVE -> Res.string.player_live_queues
    SavedQueueKind.SNAPSHOT -> Res.string.player_snapshots
    SavedQueueKind.RECOVERY -> Res.string.player_recovery
})
private fun SavedQueueKind.icon() = when (this) {
    SavedQueueKind.LIVE -> AppIcon.QUEUE
    SavedQueueKind.SNAPSHOT -> AppIcon.SAVE
    SavedQueueKind.RECOVERY -> AppIcon.UNDO
}
private data class QueueNameEdit(val queue: SavedQueue, val duplicate: Boolean)

@Composable
internal fun SavedQueuesScreen(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(SavedQueueKind.LIVE) }
    var filter by rememberSaveable { mutableStateOf(SavedQueueKind.LIVE) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf<QueueNameEdit?>(null) }
    var overwrite by remember { mutableStateOf<SavedQueue?>(null) }
    var exporting by remember { mutableStateOf<SavedQueue?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val catalog = remember(state.tracks) { state.tracks.associateBy { it.id } }
    val visible = remember(state.savedQueues, filter) {
        state.savedQueues.filter { it.kind == filter }.let { if (filter == SavedQueueKind.LIVE) it else it.sortedByDescending { it.capturedAt } }
    }
    fun load(saved: SavedQueue) { controller.loadSavedQueue(saved.id); dismiss() }
    val options: @Composable (SavedQueue) -> Unit = { saved ->
        Box {
            var expanded by remember { mutableStateOf(false) }
            AppIconButton(AppIcon.MORE, stringResource(Res.string.player_saved_queue_options, saved.name)) { expanded = true }
            DropdownMenu(expanded, { expanded = false }) {
                AppMenuItem(text = { Text(stringResource(Res.string.rename)) }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) },
                    onClick = { expanded = false; edit = QueueNameEdit(saved, false) })
                AppMenuItem(text = { Text(stringResource(Res.string.player_duplicate)) }, leadingIcon = { Icon(AppIcon.DUPLICATES.vector, null) },
                    onClick = { expanded = false; edit = QueueNameEdit(saved, true) })
                val converted = if (saved.kind == SavedQueueKind.LIVE) SavedQueueKind.SNAPSHOT else SavedQueueKind.LIVE
                AppMenuItem(text = { Text(stringResource(if (converted == SavedQueueKind.LIVE) Res.string.player_convert_to_live_queue else Res.string.player_convert_to_snapshot)) },
                    leadingIcon = { Icon(converted.icon().vector, null) }, onClick = {
                        expanded = false; controller.convertSavedQueue(saved.id, converted); filter = converted
                    })
                if (saved.kind == SavedQueueKind.RECOVERY) AppMenuItem(text = { Text(stringResource(Res.string.player_keep_as_snapshot)) },
                    leadingIcon = { Icon(AppIcon.SAVE.vector, null) }, onClick = {
                        expanded = false; controller.convertSavedQueue(saved.id, SavedQueueKind.SNAPSHOT); filter = SavedQueueKind.SNAPSHOT
                    })
                val overwriteDescription = stringResource(Res.string.player_overwrite_saved_queue, saved.name)
                val removeDescription = stringResource(Res.string.player_remove_saved_queue, saved.name)
                if (saved.kind != SavedQueueKind.RECOVERY) AppMenuItem(text = { Text(stringResource(Res.string.player_overwrite_with_current_queue)) },
                    modifier = Modifier.semantics { contentDescription = overwriteDescription },
                    enabled = state.queue.entries.isNotEmpty(), leadingIcon = { Icon(AppIcon.SAVE.vector, null) },
                    onClick = { expanded = false; overwrite = saved })
                AppMenuItem(text = { Text(stringResource(Res.string.player_export)) }, leadingIcon = { Icon(AppIcon.SAVE.vector, null) },
                    onClick = { expanded = false; exporting = saved })
                AppMenuItem(text = { Text(stringResource(Res.string.remove)) }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) },
                    modifier = Modifier.semantics { contentDescription = removeDescription },
                    onClick = { expanded = false; controller.removeSavedQueue(saved.id); if (page == saved.id) page = null })
            }
        }
    }
    AnimatedNavigation(page, null, canGoBack = page != null, onBack = { page = null }, depth = { if (it == null) 0 else 1 }) { shown ->
        val saved = state.savedQueues.firstOrNull { it.id == shown }
        if (shown == "recovery-settings") QueueRecoverySettings(state, controller) { page = null }
        else if (saved != null) SettingsScreen(onDismissRequest = { page = null }, title = { Text(saved.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = { options(saved) }, text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(saved.kind.label(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                Text(countLabel(saved.snapshot.entries.size, Res.plurals.songs), style = MaterialTheme.typography.titleLarge)
                                val current = catalog[saved.snapshot.currentTrackId]
                                Text(if (current == null) stringResource(Res.string.player_no_current_song) else "${current.metadata.title} · ${playbackTime(saved.snapshot.positionMs)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (saved.capturedAt > 0) Text(formatListenTime(saved.capturedAt), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(if (saved.kind == SavedQueueKind.LIVE) Res.string.player_live_queue_detail
                                    else Res.string.player_snapshot_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    itemsIndexed(saved.snapshot.entries, key = { _, entry -> entry.id }) { index, entry ->
                        val current = index == saved.snapshot.currentIndex
                        val previous = saved.snapshot.entries.getOrNull(index - 1)
                        val heading = when {
                            index == 0 && index < saved.snapshot.currentIndex -> stringResource(Res.string.player_previously_played)
                            index == saved.snapshot.currentIndex -> stringResource(Res.string.player_now_playing)
                            index > saved.snapshot.currentIndex && (index == saved.snapshot.currentIndex + 1 || previous?.section != entry.section) ->
                                if (entry.section == QueueSection.MANUAL) stringResource(Res.string.queue_export_up_next) else stringResource(Res.string.player_playing_from_context, saved.snapshot.contextName)
                            else -> null
                        }
                        Column {
                            heading?.let { Text(it, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary) }
                            val track = catalog[entry.trackId]
                            val snapshotDescription = stringResource(Res.string.player_snapshot_song, entry.id)
                            Surface(shape = MaterialTheme.shapes.medium, color = if (current)
                                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                                ListItem(modifier = Modifier.semantics { contentDescription = snapshotDescription },
                                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        headlineColor = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                        supportingColor = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
                                    headlineContent = { Text(track?.metadata?.title ?: stringResource(Res.string.player_song_not_in_library), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(track?.metadata?.artist?.ifBlank { stringResource(Res.string.unknown_artist) } ?: stringResource(Res.string.player_unavailable_on_device)) },
                                    leadingContent = { CollectionArtwork(state.artwork[entry.trackId], track?.metadata?.title.orEmpty(), AppIcon.MUSIC, Modifier.size(48.dp)) })
                            }
                        }
                    }
                }
            }, confirmButton = { PageAction(stringResource(if (saved.kind == SavedQueueKind.LIVE) Res.string.player_select_queue else Res.string.player_restore_snapshot), AppIcon.PLAY,
                enabled = saved.snapshot.entries.isNotEmpty()) { load(saved) } },
            dismissButton = { PageAction(stringResource(Res.string.export_queue), AppIcon.SAVE, secondary = true, enabled = saved.snapshot.entries.isNotEmpty()) { exporting = saved } })
        else SettingsScreen(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.saved_queues)) },
            actions = { AppIconButton(AppIcon.UNDO, stringResource(Res.string.player_recovery_settings)) { page = "recovery-settings" } }, text = {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SavedQueueKind.entries, key = { it }) { group ->
                        val groupLabel = group.label()
                        FilterChip(filter == group, { filter = group }, label = { Text("$groupLabel · ${state.savedQueues.count { it.kind == group }}") },
                            leadingIcon = { Icon(group.icon().vector, null, Modifier.size(18.dp)) },
                            modifier = Modifier.semantics { contentDescription = groupLabel })
                    }
                }
                PageAction(stringResource(Res.string.player_save_current_queue), AppIcon.PLUS, enabled = state.queue.entries.isNotEmpty()) { saving = true }
                Text(stringResource(when (filter) {
                    SavedQueueKind.LIVE -> Res.string.player_live_queues_description
                    SavedQueueKind.SNAPSHOT -> Res.string.player_snapshots_description
                    SavedQueueKind.RECOVERY -> Res.string.player_recovery_description
                }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.queue.selectedSavedQueueId != null) ActionButton(stringResource(Res.string.player_use_temporary_queue), AppIcon.UNLINK, style = ActionStyle.OUTLINED) { controller.detachSavedQueue() }
                if (filter == SavedQueueKind.RECOVERY) SettingsLink(stringResource(Res.string.player_recovery_snapshots), AppIcon.UNDO,
                    stringResource(if (state.preferences.queueRecoveryEnabled) Res.string.player_recovery_on_summary else Res.string.player_recovery_off_summary)) { page = "recovery-settings" }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (visible.isEmpty()) item {
                        Text(stringResource(when (filter) {
                            SavedQueueKind.LIVE -> Res.string.player_live_queues_empty
                            SavedQueueKind.SNAPSHOT -> Res.string.player_snapshots_empty
                            SavedQueueKind.RECOVERY -> Res.string.player_recovery_empty
                        }), Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(visible, key = { it.id }) { saved ->
                        val active = saved.id == state.queue.selectedSavedQueueId
                        Card(Modifier.fillMaxWidth()) {
                            ListItem(modifier = Modifier.clickable { page = saved.id }.semantics { selected = active },
                                colors = ListItemDefaults.colors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    headlineColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    supportingColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
                                headlineContent = { Text(saved.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(listOfNotNull(if (active) stringResource(Res.string.player_selected) else null, catalog[saved.snapshot.currentTrackId]?.metadata?.title,
                                    countLabel(saved.snapshot.entries.size, Res.plurals.songs),
                                    saved.snapshot.positionMs.takeIf { it > 0 }?.let(::playbackTime),
                                    saved.capturedAt.takeIf { it > 0 && saved.kind != SavedQueueKind.LIVE }?.let(::formatListenTime)).joinToString(" · "), maxLines = 2) },
                                leadingContent = { CollectionArtwork(saved.snapshot.entries.firstNotNullOfOrNull { state.artwork[it.trackId] }, saved.name,
                                    saved.kind.icon(), Modifier.size(48.dp)) },
                                trailingContent = {
                                    Row {
                                        AppIconButton(if (active) AppIcon.CHECK else AppIcon.PLAY, stringResource(Res.string.player_load_queue, saved.name)) { load(saved) }
                                        options(saved)
                                    }
                                })
                        }
                    }
                }
            }
        }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
    }
    if (saving) KeyboardAlertDialog(onDismissRequest = { saving = false }, title = { Text(stringResource(Res.string.player_save_current_queue)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val queueName = stringResource(Res.string.player_queue_name)
            OutlinedTextField(name, { name = it }, label = { Text(queueName) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = queueName })
            SettingChoice(stringResource(Res.string.player_save_as), kind, listOf(SavedQueueKind.LIVE to stringResource(Res.string.player_live_queue), SavedQueueKind.SNAPSHOT to stringResource(Res.string.player_fixed_snapshot)), card = false) { kind = it }
            Text(stringResource(if (kind == SavedQueueKind.LIVE) Res.string.player_save_live_queue_description
                else Res.string.player_save_snapshot_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        controller.saveQueue(name, kind); name = ""; filter = kind; saving = false; focus.clearFocus(); keyboard?.hide()
    }) { Text(stringResource(Res.string.player_save_queue)) } }, dismissButton = { TextButton(onClick = { saving = false }) { Text(stringResource(Res.string.cancel)) } })
    edit?.let { request ->
        val copyName = stringResource(Res.string.player_queue_copy_name, request.queue.name)
        var value by remember(request) { mutableStateOf(if (request.duplicate) copyName else request.queue.name) }
        KeyboardAlertDialog(onDismissRequest = { edit = null }, title = { Text(stringResource(if (request.duplicate) Res.string.player_duplicate_queue else Res.string.player_rename_queue)) }, text = {
            OutlinedTextField(value, { value = it }, label = { Text(stringResource(Res.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }, confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = {
            if (request.duplicate) controller.duplicateSavedQueue(request.queue.id, value) else controller.renameSavedQueue(request.queue.id, value)
            edit = null
        }) { Text(stringResource(if (request.duplicate) Res.string.player_duplicate else Res.string.save)) } }, dismissButton = { TextButton(onClick = { edit = null }) { Text(stringResource(Res.string.cancel)) } })
    }
    overwrite?.let { saved ->
        AlertDialog(onDismissRequest = { overwrite = null }, title = { Text(stringResource(Res.string.player_overwrite_title, saved.name)) },
            text = { Text(stringResource(if (saved.kind == SavedQueueKind.LIVE) Res.string.player_overwrite_live_text
                else Res.string.player_overwrite_snapshot_text)) },
            confirmButton = { TextButton(onClick = { controller.overwriteSavedQueue(saved.id); overwrite = null }) { Text(stringResource(Res.string.player_overwrite)) } },
            dismissButton = { TextButton(onClick = { overwrite = null }) { Text(stringResource(Res.string.cancel)) } })
    }
    exporting?.let { QueueExportDialog(it.snapshot, it.name, controller) { exporting = null } }
}

@Composable
private fun QueueRecoverySettings(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val settings = state.preferences
    SettingsScreen(dismiss, title = { Text(stringResource(Res.string.player_recovery_snapshots)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsToggle(stringResource(Res.string.player_capture_automatically), AppIcon.UNDO, stringResource(Res.string.player_capture_automatically_text), settings.queueRecoveryEnabled) { enabled ->
                controller.updatePreferences { it.copy(queueRecoveryEnabled = enabled) }
            }
            if (settings.queueRecoveryEnabled) {
                SettingChoice(stringResource(Res.string.player_capture), settings.queueRecoveryMode, QueueRecoveryMode.entries.map { it to stringResource(it.label) }) { mode ->
                    controller.updatePreferences { it.copy(queueRecoveryMode = mode) }
                }
                if (settings.queueRecoveryMode == QueueRecoveryMode.TIMER) SettingChoice(stringResource(Res.string.player_every), settings.queueRecoveryIntervalMinutes,
                    listOf(1, 5, 10, 15, 30, 60).map { it to stringResource(Res.string.player_minutes, it) }) { minutes ->
                    controller.updatePreferences { it.copy(queueRecoveryIntervalMinutes = minutes) }
                }
            }
            Text(stringResource(Res.string.player_recovery_capture_text),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.player_recovery_privacy_text),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    })
}
