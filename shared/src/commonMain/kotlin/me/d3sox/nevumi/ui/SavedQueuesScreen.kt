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

internal fun SavedQueueKind.label() = when (this) {
    SavedQueueKind.LIVE -> "Live queues"
    SavedQueueKind.SNAPSHOT -> "Snapshots"
    SavedQueueKind.RECOVERY -> "Recovery"
}
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
            AppIconButton(AppIcon.MORE, "Saved queue options: ${saved.name}") { expanded = true }
            DropdownMenu(expanded, { expanded = false }) {
                AppMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) },
                    onClick = { expanded = false; edit = QueueNameEdit(saved, false) })
                AppMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(AppIcon.DUPLICATES.vector, null) },
                    onClick = { expanded = false; edit = QueueNameEdit(saved, true) })
                val converted = if (saved.kind == SavedQueueKind.LIVE) SavedQueueKind.SNAPSHOT else SavedQueueKind.LIVE
                AppMenuItem(text = { Text(if (converted == SavedQueueKind.LIVE) "Convert to live queue" else "Convert to snapshot") },
                    leadingIcon = { Icon(converted.icon().vector, null) }, onClick = {
                        expanded = false; controller.convertSavedQueue(saved.id, converted); filter = converted
                    })
                if (saved.kind == SavedQueueKind.RECOVERY) AppMenuItem(text = { Text("Keep as snapshot") },
                    leadingIcon = { Icon(AppIcon.SAVE.vector, null) }, onClick = {
                        expanded = false; controller.convertSavedQueue(saved.id, SavedQueueKind.SNAPSHOT); filter = SavedQueueKind.SNAPSHOT
                    })
                if (saved.kind != SavedQueueKind.RECOVERY) AppMenuItem(text = { Text("Overwrite with current queue") },
                    modifier = Modifier.semantics { contentDescription = "Overwrite saved queue: ${saved.name}" },
                    enabled = state.queue.entries.isNotEmpty(), leadingIcon = { Icon(AppIcon.SAVE.vector, null) },
                    onClick = { expanded = false; overwrite = saved })
                AppMenuItem(text = { Text("Export") }, leadingIcon = { Icon(AppIcon.SAVE.vector, null) },
                    onClick = { expanded = false; exporting = saved })
                AppMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) },
                    modifier = Modifier.semantics { contentDescription = "Remove saved queue: ${saved.name}" },
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
                                Text(countLabel(saved.snapshot.entries.size, "song"), style = MaterialTheme.typography.titleLarge)
                                val current = catalog[saved.snapshot.currentTrackId]
                                Text(if (current == null) "No current song" else "${current.metadata.title} · ${playbackTime(saved.snapshot.positionMs)}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (saved.capturedAt > 0) Text(formatListenTime(saved.capturedAt), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (saved.kind == SavedQueueKind.LIVE) "Selecting this queue saves edits and your playback position automatically."
                                    else "This snapshot stays fixed. Restore it into a temporary queue whenever you need it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    itemsIndexed(saved.snapshot.entries, key = { _, entry -> entry.id }) { index, entry ->
                        val current = index == saved.snapshot.currentIndex
                        val previous = saved.snapshot.entries.getOrNull(index - 1)
                        val heading = when {
                            index == 0 && index < saved.snapshot.currentIndex -> "Previously played"
                            index == saved.snapshot.currentIndex -> "Now playing"
                            index > saved.snapshot.currentIndex && (index == saved.snapshot.currentIndex + 1 || previous?.section != entry.section) ->
                                if (entry.section == QueueSection.MANUAL) "Up next" else "Playing from ${saved.snapshot.contextName}"
                            else -> null
                        }
                        Column {
                            heading?.let { Text(it, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary) }
                            val track = catalog[entry.trackId]
                            Surface(shape = MaterialTheme.shapes.medium, color = if (current)
                                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                                ListItem(modifier = Modifier.semantics { contentDescription = "Snapshot song: ${entry.id}" },
                                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        headlineColor = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                        supportingColor = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
                                    headlineContent = { Text(track?.metadata?.title ?: "Song no longer in your library", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(track?.metadata?.artist?.ifBlank { "Unknown artist" } ?: "Unavailable on this device") },
                                    leadingContent = { CollectionArtwork(state.artwork[entry.trackId], track?.metadata?.title.orEmpty(), AppIcon.MUSIC, Modifier.size(48.dp)) })
                            }
                        }
                    }
                }
            }, confirmButton = { PageAction(if (saved.kind == SavedQueueKind.LIVE) "Select queue" else "Restore snapshot", AppIcon.PLAY,
                enabled = saved.snapshot.entries.isNotEmpty()) { load(saved) } },
            dismissButton = { PageAction("Export queue", AppIcon.SAVE, secondary = true, enabled = saved.snapshot.entries.isNotEmpty()) { exporting = saved } })
        else SettingsScreen(onDismissRequest = dismiss, title = { Text("Saved queues") },
            actions = { AppIconButton(AppIcon.UNDO, "Recovery settings") { page = "recovery-settings" } }, text = {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SavedQueueKind.entries, key = { it }) { group ->
                        FilterChip(filter == group, { filter = group }, label = { Text("${group.label()} · ${state.savedQueues.count { it.kind == group }}") },
                            leadingIcon = { Icon(group.icon().vector, null, Modifier.size(18.dp)) },
                            modifier = Modifier.semantics { contentDescription = group.label() })
                    }
                }
                PageAction("Save current queue", AppIcon.PLUS, enabled = state.queue.entries.isNotEmpty()) { saving = true }
                Text(when (filter) {
                    SavedQueueKind.LIVE -> "Selected live queues save edits and playback position automatically."
                    SavedQueueKind.SNAPSHOT -> "Fixed copies you can preview and restore into a temporary queue."
                    SavedQueueKind.RECOVERY -> "Automatic copies kept on this device."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.queue.selectedSavedQueueId != null) ActionButton("Use temporary queue", AppIcon.UNLINK, style = ActionStyle.OUTLINED) { controller.detachSavedQueue() }
                if (filter == SavedQueueKind.RECOVERY) SettingsLink("Recovery snapshots", AppIcon.UNDO,
                    if (state.preferences.queueRecoveryEnabled) "On · Keeps 15 unique queues" else "Off · Enable automatic capture") { page = "recovery-settings" }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (visible.isEmpty()) item {
                        Text(when (filter) {
                            SavedQueueKind.LIVE -> "Save a live queue to keep your edits and return to it later."
                            SavedQueueKind.SNAPSHOT -> "Save a fixed snapshot to keep a copy you can restore."
                            SavedQueueKind.RECOVERY -> "Recovery snapshots appear here when automatic capture is enabled."
                        }, Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(visible, key = { it.id }) { saved ->
                        val active = saved.id == state.queue.selectedSavedQueueId
                        Card(Modifier.fillMaxWidth()) {
                            ListItem(modifier = Modifier.clickable { page = saved.id }.semantics { selected = active },
                                colors = ListItemDefaults.colors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                    headlineColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    supportingColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
                                headlineContent = { Text(saved.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(listOfNotNull(if (active) "Selected" else null, catalog[saved.snapshot.currentTrackId]?.metadata?.title,
                                    countLabel(saved.snapshot.entries.size, "song"),
                                    saved.snapshot.positionMs.takeIf { it > 0 }?.let(::playbackTime),
                                    saved.capturedAt.takeIf { it > 0 && saved.kind != SavedQueueKind.LIVE }?.let(::formatListenTime)).joinToString(" · "), maxLines = 2) },
                                leadingContent = { CollectionArtwork(saved.snapshot.entries.firstNotNullOfOrNull { state.artwork[it.trackId] }, saved.name,
                                    saved.kind.icon(), Modifier.size(48.dp)) },
                                trailingContent = {
                                    Row {
                                        AppIconButton(if (active) AppIcon.CHECK else AppIcon.PLAY, "Load queue: ${saved.name}") { load(saved) }
                                        options(saved)
                                    }
                                })
                        }
                    }
                }
            }
        }, confirmButton = { PageAction("Done", onClick = dismiss) })
    }
    if (saving) KeyboardAlertDialog(onDismissRequest = { saving = false }, title = { Text("Save current queue") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Queue name") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Queue name" })
            SettingChoice("Save as", kind, listOf(SavedQueueKind.LIVE to "Live queue", SavedQueueKind.SNAPSHOT to "Fixed snapshot"), card = false) { kind = it }
            Text(if (kind == SavedQueueKind.LIVE) "Select this queue and keep saving your edits and playback position automatically."
                else "Keep the songs, history and playback position exactly as they are now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        controller.saveQueue(name, kind); name = ""; filter = kind; saving = false; focus.clearFocus(); keyboard?.hide()
    }) { Text("Save queue") } }, dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } })
    edit?.let { request ->
        var value by remember(request) { mutableStateOf(if (request.duplicate) "${request.queue.name} copy" else request.queue.name) }
        KeyboardAlertDialog(onDismissRequest = { edit = null }, title = { Text(if (request.duplicate) "Duplicate queue" else "Rename queue") }, text = {
            OutlinedTextField(value, { value = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }, confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = {
            if (request.duplicate) controller.duplicateSavedQueue(request.queue.id, value) else controller.renameSavedQueue(request.queue.id, value)
            edit = null
        }) { Text(if (request.duplicate) "Duplicate" else "Save") } }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    }
    overwrite?.let { saved ->
        AlertDialog(onDismissRequest = { overwrite = null }, title = { Text("Overwrite ${saved.name}?") },
            text = { Text(if (saved.kind == SavedQueueKind.LIVE) "Replace this saved queue with the current songs and playback position, and select it for automatic saving."
                else "Replace this fixed snapshot with the current songs and playback position.") },
            confirmButton = { TextButton(onClick = { controller.overwriteSavedQueue(saved.id); overwrite = null }) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = { overwrite = null }) { Text("Cancel") } })
    }
    exporting?.let { QueueExportDialog(it.snapshot, it.name, controller) { exporting = null } }
}

@Composable
private fun QueueRecoverySettings(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val settings = state.preferences
    SettingsScreen(dismiss, title = { Text("Recovery snapshots") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsToggle("Capture automatically", AppIcon.UNDO, "Keep up to 15 unique queues on this device", settings.queueRecoveryEnabled) { enabled ->
                controller.updatePreferences { it.copy(queueRecoveryEnabled = enabled) }
            }
            if (settings.queueRecoveryEnabled) {
                SettingChoice("Capture", settings.queueRecoveryMode, QueueRecoveryMode.entries.map { it to it.label }) { mode ->
                    controller.updatePreferences { it.copy(queueRecoveryMode = mode) }
                }
                if (settings.queueRecoveryMode == QueueRecoveryMode.TIMER) SettingChoice("Every", settings.queueRecoveryIntervalMinutes,
                    listOf(1, 5, 10, 15, 30, 60).map { it to "$it minutes" }) { minutes ->
                    controller.updatePreferences { it.copy(queueRecoveryIntervalMinutes = minutes) }
                }
            }
            Text("Enabled capture also keeps a copy before replacing or clearing a queue. Repeated queue contents update their existing recovery checkpoint. Manual snapshots and live queues are never removed by the recovery limit.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Recovery history and playback positions stay on this device. Manual snapshots share their songs with paired devices.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    })
}
