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
    Column(Modifier.semantics(mergeDescendants = false) { contentDescription = "SoundCloud download: $title" },
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(listOf(job.track.artist, if (job.destination == ScGateDestination.LIBRARY) "To music server" else "To this device")
            .filter { it.isNotBlank() }.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (job.state) {
            SoundCloudJobState.NEEDS_LINK -> "sc-gate-dl found no download link. Paste a gate or download link, or save the SoundCloud stream, " +
                "which has lower quality than a download."
            SoundCloudJobState.NEEDS_TAGS -> "Downloaded. sc-gate-dl saves it once you check its tags."
            SoundCloudJobState.LOST, SoundCloudJobState.FAILED -> job.message.ifBlank { "The download failed" }
            else -> job.message
        }, color = if (job.state == SoundCloudJobState.FAILED || job.state == SoundCloudJobState.LOST) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
        if (job.attention) Text(if (watchable) "The gate needs a manual step, such as a captcha. Finish it in sc-gate-dl's browser or cancel."
            else "The gate needs a manual step, such as a captcha, in sc-gate-dl's browser. Finish it in the sc-gate-dl web interface or cancel.",
            color = MaterialTheme.colorScheme.error)
        viewerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (job.excludedFolder != null) Text("Your local folder rules leave out ${job.excludedFolder}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (job.state) {
            SoundCloudJobState.WORKING -> if (job.percent > 0) LinearProgressIndicator(progress = { job.percent / 100f },
                modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            SoundCloudJobState.STARTING, SoundCloudJobState.TRANSFERRING, SoundCloudJobState.IMPORTING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.state == SoundCloudJobState.NEEDS_LINK) {
                JobButton("Paste link", title, AppIcon.LINK, ActionStyle.TONAL) { pastingLink = true }
                JobButton("Save SoundCloud stream", title, AppIcon.DOWNLOAD) { controller.useSoundCloudStream(job.id) }
            }
            if (job.state == SoundCloudJobState.NEEDS_TRACK) JobButton("Choose song", title, AppIcon.MUSIC, ActionStyle.TONAL) { choosingTrack = true }
            if (job.state == SoundCloudJobState.NEEDS_TAGS) JobButton("Check tags", title, AppIcon.TAGS, ActionStyle.TONAL, checkTags)
            if (watchable) JobButton("View browser", title, AppIcon.BROWSER, if (job.attention) ActionStyle.TONAL else ActionStyle.OUTLINED) {
                viewerError = null
                scope.launch {
                    try { browser.openUri(controller.soundCloudBrowserView(job.id)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { viewerError = failure.message ?: "sc-gate-dl's browser could not be opened" }
                }
            }
            job.trackId?.let { trackId -> JobButton("Play", title, AppIcon.PLAY, ActionStyle.TONAL) { controller.playTrack(trackId, listOf(trackId), 0, "SoundCloud") } }
            if (job.excludedFolder != null) JobButton("Add folder to library", title, AppIcon.FOLDER, ActionStyle.TONAL) { controller.addSoundCloudFolder(job.id) }
            if (job.state in setOf(SoundCloudJobState.WORKING, SoundCloudJobState.NEEDS_LINK, SoundCloudJobState.NEEDS_TRACK, SoundCloudJobState.NEEDS_TAGS))
                JobButton("Cancel download", title, AppIcon.CLOSE) { controller.cancelSoundCloudDownload(job.id) }
            if (job.state in setOf(SoundCloudJobState.FAILED, SoundCloudJobState.LOST, SoundCloudJobState.CANCELLED))
                JobButton("Retry", title, AppIcon.REFRESH, ActionStyle.TONAL) { controller.retrySoundCloudDownload(job.id) }
            if (!job.active && job.state !in setOf(SoundCloudJobState.NEEDS_LINK, SoundCloudJobState.NEEDS_TRACK, SoundCloudJobState.NEEDS_TAGS))
                JobButton("Remove from list", title, AppIcon.REMOVE) { controller.dismissSoundCloudDownload(job.id) }
        }
    }
    if (pastingLink) {
        var link by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pastingLink = false }, title = { Text("Paste download link") },
            text = {
                OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Gate or download link") }, singleLine = true,
                    supportingText = { Text("Hypeddit, Bandcamp and other links sc-gate-dl supports") })
            },
            confirmButton = { TextButton(onClick = { controller.submitSoundCloudLink(job.id, link); pastingLink = false }, enabled = link.isNotBlank()) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { pastingLink = false }) { Text("Cancel") } })
    }
    if (choosingTrack) AlertDialog(onDismissRequest = { choosingTrack = false }, title = { Text("Choose the song") },
        text = {
            Column {
                Text("sc-gate-dl could not tell which song on the Bandcamp album this is.", Modifier.padding(bottom = 8.dp))
                job.bandcampTracks.forEach { choice ->
                    ListItem(headlineContent = { Text(choice.title) }, modifier = Modifier.clickable {
                        controller.chooseSoundCloudTrack(job.id, choice.url); choosingTrack = false
                    })
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingTrack = false }) { Text("Cancel") } })
}

@Composable
private fun JobButton(label: String, title: String, icon: AppIcon, style: ActionStyle = ActionStyle.OUTLINED, onClick: () -> Unit) {
    ActionButton(label, icon, Modifier.semantics { contentDescription = "$label: $title" }, style = style, onClick = onClick)
}
