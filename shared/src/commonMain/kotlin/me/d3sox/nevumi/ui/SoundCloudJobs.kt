package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.soundcloud.ScGateDestination
import me.d3sox.nevumi.soundcloud.SoundCloudJob
import me.d3sox.nevumi.soundcloud.SoundCloudJobState
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.retry
import me.d3sox.nevumi.resources.sources_action_for_item
import me.d3sox.nevumi.resources.sources_add_folder_to_library
import me.d3sox.nevumi.resources.sources_browser_open_failed
import me.d3sox.nevumi.resources.sources_cancel_download
import me.d3sox.nevumi.resources.sources_check_tags
import me.d3sox.nevumi.resources.sources_choose_bandcamp_song
import me.d3sox.nevumi.resources.sources_choose_song
import me.d3sox.nevumi.resources.sources_choose_the_song
import me.d3sox.nevumi.resources.sources_continue
import me.d3sox.nevumi.resources.sources_gate_link_hint
import me.d3sox.nevumi.resources.sources_gate_or_download_link
import me.d3sox.nevumi.resources.sources_job_attention
import me.d3sox.nevumi.resources.sources_job_attention_viewer
import me.d3sox.nevumi.resources.sources_job_excluded_folder
import me.d3sox.nevumi.resources.sources_job_failed
import me.d3sox.nevumi.resources.sources_job_needs_link
import me.d3sox.nevumi.resources.sources_job_needs_tags
import me.d3sox.nevumi.resources.sources_paste_download_link
import me.d3sox.nevumi.resources.sources_paste_link
import me.d3sox.nevumi.resources.sources_remove_from_list
import me.d3sox.nevumi.resources.sources_save_soundcloud_stream
import me.d3sox.nevumi.resources.sources_soundcloud_download_description
import me.d3sox.nevumi.resources.sources_to_music_server
import me.d3sox.nevumi.resources.sources_to_this_device
import me.d3sox.nevumi.resources.sources_view_browser
import org.jetbrains.compose.resources.stringResource

/** One SoundCloud download in the Downloads page, with the choices sc-gate-dl waits for; [viewer] is sc-gate-dl's browser viewer. */
@Composable
internal fun SoundCloudJobItem(job: SoundCloudJob, controller: MusicController, viewer: Boolean, checkTags: () -> Unit) {
    var pastingLink by remember { mutableStateOf(false) }
    var choosingTrack by remember { mutableStateOf(false) }
    var viewerError by remember { mutableStateOf<String?>(null) }
    val browser = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val watchable = viewer && job.state == SoundCloudJobState.WORKING && job.browser
    val title = job.track.title
    val description = stringResource(Res.string.sources_soundcloud_download_description, title)
    val browserOpenFailed = stringResource(Res.string.sources_browser_open_failed)
    Column(Modifier.semantics(mergeDescendants = false) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(listOf(job.track.artist, if (job.destination == ScGateDestination.LIBRARY) stringResource(Res.string.sources_to_music_server) else stringResource(Res.string.sources_to_this_device))
            .filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val failed = stringResource(Res.string.sources_job_failed)
        Text(when (job.state) {
            SoundCloudJobState.NEEDS_LINK -> stringResource(Res.string.sources_job_needs_link)
            SoundCloudJobState.NEEDS_TAGS -> stringResource(Res.string.sources_job_needs_tags)
            SoundCloudJobState.LOST, SoundCloudJobState.FAILED -> job.message.ifBlank { failed }
            else -> job.message
        }, color = if (job.state == SoundCloudJobState.FAILED || job.state == SoundCloudJobState.LOST) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
        if (job.attention) Text(if (watchable) stringResource(Res.string.sources_job_attention_viewer)
            else stringResource(Res.string.sources_job_attention),
            color = MaterialTheme.colorScheme.error)
        viewerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (job.excludedFolder != null) Text(stringResource(Res.string.sources_job_excluded_folder, job.excludedFolder), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (job.state) {
            SoundCloudJobState.WORKING -> if (job.percent > 0) LinearProgressIndicator(progress = { job.percent / 100f },
                modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            SoundCloudJobState.STARTING, SoundCloudJobState.TRANSFERRING, SoundCloudJobState.IMPORTING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.state == SoundCloudJobState.NEEDS_LINK) {
                JobButton(stringResource(Res.string.sources_paste_link), title, AppIcon.LINK, ActionStyle.TONAL) { pastingLink = true }
                JobButton(stringResource(Res.string.sources_save_soundcloud_stream), title, AppIcon.DOWNLOAD) { controller.useSoundCloudStream(job.id) }
            }
            if (job.state == SoundCloudJobState.NEEDS_TRACK) JobButton(stringResource(Res.string.sources_choose_song), title, AppIcon.MUSIC, ActionStyle.TONAL) { choosingTrack = true }
            if (job.state == SoundCloudJobState.NEEDS_TAGS) JobButton(stringResource(Res.string.sources_check_tags), title, AppIcon.TAGS, ActionStyle.TONAL, checkTags)
            if (watchable) JobButton(stringResource(Res.string.sources_view_browser), title, AppIcon.BROWSER, if (job.attention) ActionStyle.TONAL else ActionStyle.OUTLINED) {
                viewerError = null
                scope.launch {
                    try { browser.openUri(controller.soundCloudBrowserView(job.id)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { viewerError = failure.message ?: browserOpenFailed }
                }
            }
            job.trackId?.let { trackId -> JobButton(stringResource(Res.string.play), title, AppIcon.PLAY, ActionStyle.TONAL) { controller.playTrack(trackId, listOf(trackId), 0, "SoundCloud") } }
            if (job.excludedFolder != null) JobButton(stringResource(Res.string.sources_add_folder_to_library), title, AppIcon.FOLDER, ActionStyle.TONAL) { controller.addSoundCloudFolder(job.id) }
            if (job.state in setOf(SoundCloudJobState.WORKING, SoundCloudJobState.NEEDS_LINK, SoundCloudJobState.NEEDS_TRACK, SoundCloudJobState.NEEDS_TAGS))
                JobButton(stringResource(Res.string.sources_cancel_download), title, AppIcon.CLOSE) { controller.cancelSoundCloudDownload(job.id) }
            if (job.state in setOf(SoundCloudJobState.FAILED, SoundCloudJobState.LOST, SoundCloudJobState.CANCELLED))
                JobButton(stringResource(Res.string.retry), title, AppIcon.REFRESH, ActionStyle.TONAL) { controller.retrySoundCloudDownload(job.id) }
            if (!job.active && job.state !in setOf(SoundCloudJobState.NEEDS_LINK, SoundCloudJobState.NEEDS_TRACK, SoundCloudJobState.NEEDS_TAGS))
                JobButton(stringResource(Res.string.sources_remove_from_list), title, AppIcon.REMOVE) { controller.dismissSoundCloudDownload(job.id) }
        }
    }
    if (pastingLink) {
        var link by remember { mutableStateOf("") }
        KeyboardAlertDialog(onDismissRequest = { pastingLink = false }, title = { Text(stringResource(Res.string.sources_paste_download_link)) },
            text = {
                OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_gate_or_download_link)) }, singleLine = true,
                    supportingText = { Text(stringResource(Res.string.sources_gate_link_hint)) })
            },
            confirmButton = { TextButton(onClick = { controller.submitSoundCloudLink(job.id, link); pastingLink = false }, enabled = link.isNotBlank()) { Text(stringResource(Res.string.sources_continue)) } },
            dismissButton = { TextButton(onClick = { pastingLink = false }) { Text(stringResource(Res.string.cancel)) } })
    }
    if (choosingTrack) AlertDialog(onDismissRequest = { choosingTrack = false }, title = { Text(stringResource(Res.string.sources_choose_the_song)) },
        text = {
            Column {
                Text(stringResource(Res.string.sources_choose_bandcamp_song), Modifier.padding(bottom = 8.dp))
                job.bandcampTracks.forEach { choice ->
                    ListItem(headlineContent = { Text(choice.title) }, modifier = Modifier.clickable {
                        controller.chooseSoundCloudTrack(job.id, choice.url); choosingTrack = false
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingTrack = false }) { Text(stringResource(Res.string.cancel)) } })
}

@Composable
private fun JobButton(label: String, title: String, icon: AppIcon, style: ActionStyle = ActionStyle.OUTLINED, onClick: () -> Unit) {
    val description = stringResource(Res.string.sources_action_for_item, label, title)
    ActionButton(label, icon, Modifier.semantics { contentDescription = description }, style = style, onClick = onClick)
}
