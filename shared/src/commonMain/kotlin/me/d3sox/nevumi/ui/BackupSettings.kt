package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.backup.BackupService
import me.d3sox.nevumi.backup.BackupSection
import me.d3sox.nevumi.backup.RestoreSummary
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.backup_export
import me.d3sox.nevumi.resources.backup_export_description
import me.d3sox.nevumi.resources.backup_last_export
import me.d3sox.nevumi.resources.backup_never_exported
import me.d3sox.nevumi.resources.backup_choose_export
import me.d3sox.nevumi.resources.backup_export_empty
import me.d3sox.nevumi.resources.backup_section_settings
import me.d3sox.nevumi.resources.backup_section_playlists
import me.d3sox.nevumi.resources.backup_section_saved_queues
import me.d3sox.nevumi.resources.backup_section_favorites
import me.d3sox.nevumi.resources.backup_section_ratings
import me.d3sox.nevumi.resources.backup_section_listening_history
import me.d3sox.nevumi.resources.backup_section_service_profiles
import me.d3sox.nevumi.resources.backup_section_equalizer_profiles
import me.d3sox.nevumi.resources.backup_in_progress
import me.d3sox.nevumi.resources.backup_restore
import me.d3sox.nevumi.resources.backup_restore_confirm
import me.d3sox.nevumi.resources.backup_restore_confirm_text
import me.d3sox.nevumi.resources.backup_restore_confirm_title
import me.d3sox.nevumi.resources.backup_restore_description
import me.d3sox.nevumi.resources.backup_service_lastfm
import me.d3sox.nevumi.resources.backup_service_listenbrainz
import me.d3sox.nevumi.resources.backup_service_music_server
import me.d3sox.nevumi.resources.backup_service_sftp
import me.d3sox.nevumi.resources.backup_summary_equalizer
import me.d3sox.nevumi.resources.backup_summary_favorites
import me.d3sox.nevumi.resources.backup_summary_listens
import me.d3sox.nevumi.resources.backup_summary_nothing
import me.d3sox.nevumi.resources.backup_summary_playlists_added
import me.d3sox.nevumi.resources.backup_summary_playlists_merged
import me.d3sox.nevumi.resources.backup_summary_ratings
import me.d3sox.nevumi.resources.backup_summary_saved_queues
import me.d3sox.nevumi.resources.backup_summary_scrobbling
import me.d3sox.nevumi.resources.backup_summary_settings
import me.d3sox.nevumi.resources.backup_summary_settings_restored
import me.d3sox.nevumi.resources.backup_summary_sign_in
import me.d3sox.nevumi.resources.backup_summary_soundcloud
import me.d3sox.nevumi.resources.backup_summary_soundcloud_restored
import me.d3sox.nevumi.resources.backup_summary_title
import me.d3sox.nevumi.resources.backup_summary_unmatched
import me.d3sox.nevumi.resources.backup_summary_unmatched_description
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.done
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The Backup settings group: exporting a backup file and restoring one, with a confirmation before and a summary after. */
@Composable
internal fun BackupSettings(controller: MusicController) {
    val backups = controller.backupController
    if (!backups.backupAvailable) return
    val files = LocalBackupFiles.current
    val version = appVersion()
    val status by backups.status.collectAsState()
    val lastExportedAt by backups.lastExportedAt.collectAsState(initial = null)
    var confirming by remember { mutableStateOf(false) }
    var choosingExport by remember { mutableStateOf(false) }
    val working = stringResource(Res.string.backup_in_progress)
    SettingsGroup {
        SettingsCard(AppIcon.HISTORY) {
            Column {
                Text(stringResource(Res.string.backup_last_export), style = MaterialTheme.typography.bodyLarge)
                Text(lastExportedAt?.let(::formatListenTime) ?: stringResource(Res.string.backup_never_exported),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SettingsGroup {
        SettingsLink(stringResource(Res.string.backup_export), AppIcon.SAVE,
            if (status.working) working else stringResource(Res.string.backup_export_description)) {
            if (!status.working) choosingExport = true
        }
        SettingsLink(stringResource(Res.string.backup_restore), AppIcon.BACKUP_FILES,
            if (status.working) working else stringResource(Res.string.backup_restore_description)) {
            if (!status.working) confirming = true
        }
    }
    if (choosingExport) BackupExportDialog(onDismiss = { choosingExport = false }) { sections ->
        choosingExport = false
        backups.export(files, version, sections)
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(stringResource(Res.string.backup_restore_confirm_title)) },
        text = { Text(stringResource(Res.string.backup_restore_confirm_text)) },
        confirmButton = { TextButton(onClick = { confirming = false; backups.restore(files) }) { Text(stringResource(Res.string.backup_restore_confirm)) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.cancel)) } })
    status.summary?.let { summary ->
        AlertDialog(onDismissRequest = backups::dismissSummary,
            title = { Text(stringResource(Res.string.backup_summary_title)) },
            text = { RestoreSummaryContent(summary) },
            confirmButton = { TextButton(onClick = backups::dismissSummary) { Text(stringResource(Res.string.done)) } })
    }
}

@Composable
private fun BackupExportDialog(onDismiss: () -> Unit, onExport: (Set<BackupSection>) -> Unit) {
    var selected by remember { mutableStateOf(BackupSection.entries.toSet()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.backup_choose_export)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                BackupSection.entries.forEach { section ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = section in selected, role = Role.Checkbox,
                        onValueChange = { checked -> selected = if (checked) selected + section else selected - section }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(section.label()), Modifier.weight(1f))
                        Checkbox(section in selected, onCheckedChange = null)
                    }
                }
                if (selected.isEmpty()) Text(stringResource(Res.string.backup_export_empty),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onExport(selected) }) { Text(stringResource(Res.string.backup_export)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } })
}

private fun BackupSection.label() = when (this) {
    BackupSection.SETTINGS -> Res.string.backup_section_settings
    BackupSection.PLAYLISTS -> Res.string.backup_section_playlists
    BackupSection.SAVED_QUEUES -> Res.string.backup_section_saved_queues
    BackupSection.FAVORITES -> Res.string.backup_section_favorites
    BackupSection.RATINGS -> Res.string.backup_section_ratings
    BackupSection.LISTENING_HISTORY -> Res.string.backup_section_listening_history
    BackupSection.SERVICE_PROFILES -> Res.string.backup_section_service_profiles
    BackupSection.EQUALIZER_PROFILES -> Res.string.backup_section_equalizer_profiles
}

@Composable
private fun RestoreSummaryContent(summary: RestoreSummary) {
    val rows = listOfNotNull(
        if (summary.settings) Res.string.backup_summary_settings to stringResource(Res.string.backup_summary_settings_restored) else null,
        count(Res.string.backup_summary_favorites, summary.favorites),
        count(Res.string.backup_summary_ratings, summary.ratings),
        count(Res.string.backup_summary_playlists_added, summary.playlistsAdded),
        count(Res.string.backup_summary_playlists_merged, summary.playlistsMerged),
        count(Res.string.backup_summary_saved_queues, summary.savedQueues),
        count(Res.string.backup_summary_listens, summary.listens),
        count(Res.string.backup_summary_equalizer, summary.equalizerProfiles),
        count(Res.string.backup_summary_scrobbling, summary.scrobblingProfiles),
        if (summary.soundCloud) Res.string.backup_summary_soundcloud to stringResource(Res.string.backup_summary_soundcloud_restored) else null,
    )
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) Text(stringResource(Res.string.backup_summary_nothing))
        rows.forEach { (label, value) -> SummaryRow(stringResource(label), value) }
        if (summary.unmatchedTracks > 0) {
            SummaryRow(stringResource(Res.string.backup_summary_unmatched), summary.unmatchedTracks.toString())
            Text(stringResource(Res.string.backup_summary_unmatched_description), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (summary.signInRequired.isNotEmpty()) {
            Text(stringResource(Res.string.backup_summary_sign_in), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            summary.signInRequired.forEach { SummaryRow(stringResource(it.service.label()), it.account) }
        }
    }
}

private fun count(label: StringResource, value: Int) = if (value > 0) label to value.toString() else null

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun BackupService.label() = when (this) {
    BackupService.MUSIC_SERVER -> Res.string.backup_service_music_server
    BackupService.SFTP -> Res.string.backup_service_sftp
    BackupService.LISTENBRAINZ -> Res.string.backup_service_listenbrainz
    BackupService.LASTFM -> Res.string.backup_service_lastfm
}
