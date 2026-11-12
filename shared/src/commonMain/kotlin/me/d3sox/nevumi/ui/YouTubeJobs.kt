package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.retry
import me.d3sox.nevumi.resources.sources_action_for_item
import me.d3sox.nevumi.resources.sources_add_folder_to_library
import me.d3sox.nevumi.resources.sources_cancel_download
import me.d3sox.nevumi.resources.sources_check_tags
import me.d3sox.nevumi.resources.sources_job_excluded_folder
import me.d3sox.nevumi.resources.sources_job_failed
import me.d3sox.nevumi.resources.sources_remove_from_list
import me.d3sox.nevumi.resources.sources_to_device_and_server
import me.d3sox.nevumi.resources.sources_to_this_device
import me.d3sox.nevumi.resources.sources_youtube_download_description
import me.d3sox.nevumi.resources.sources_youtube_job_needs_tags
import me.d3sox.nevumi.resources.sources_youtube_tags_intro
import me.d3sox.nevumi.youtube.YouTubeDestination
import me.d3sox.nevumi.youtube.YouTubeJob
import me.d3sox.nevumi.youtube.YouTubeJobState
import me.d3sox.nevumi.youtube.youTubeFileName
import org.jetbrains.compose.resources.stringResource

/** One YouTube download in the Downloads page, with its progress and the choices it waits for. */
@Composable
internal fun YouTubeJobItem(job: YouTubeJob, controller: MusicController, checkTags: () -> Unit) {
    val title = job.tags.title.ifBlank { job.track.title }
    val description = stringResource(Res.string.sources_youtube_download_description, title)
    val failed = job.state == YouTubeJobState.FAILED
    Column(Modifier.semantics(mergeDescendants = false) { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(listOf(job.tags.artist.ifBlank { job.track.artist }, stringResource(if (job.destination == YouTubeDestination.SERVER)
            Res.string.sources_to_device_and_server else Res.string.sources_to_this_device)).filter { it.isNotBlank() }.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when (job.state) {
            YouTubeJobState.NEEDS_TAGS -> stringResource(Res.string.sources_youtube_job_needs_tags)
            YouTubeJobState.FAILED -> job.message.ifBlank { stringResource(Res.string.sources_job_failed) }
            else -> job.message
        }, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (job.excludedFolder != null) Text(stringResource(Res.string.sources_job_excluded_folder, job.excludedFolder), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (job.state) {
            YouTubeJobState.DOWNLOADING -> if (job.percent > 0) LinearProgressIndicator(progress = { job.percent / 100f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            YouTubeJobState.SAVING, YouTubeJobState.UPLOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (job.state == YouTubeJobState.NEEDS_TAGS) JobButton(stringResource(Res.string.sources_check_tags), title, AppIcon.TAGS, ActionStyle.TONAL, checkTags)
            job.trackId?.takeIf { job.state == YouTubeJobState.COMPLETE }?.let { trackId ->
                JobButton(stringResource(Res.string.play), title, AppIcon.PLAY, ActionStyle.TONAL) { controller.playTrack(trackId, listOf(trackId), 0, "YouTube") }
            }
            if (job.excludedFolder != null) JobButton(stringResource(Res.string.sources_add_folder_to_library), title, AppIcon.FOLDER, ActionStyle.TONAL) {
                controller.addYouTubeFolder(job.id)
            }
            if (job.state == YouTubeJobState.DOWNLOADING || job.state == YouTubeJobState.NEEDS_TAGS)
                JobButton(stringResource(Res.string.sources_cancel_download), title, AppIcon.CLOSE) { controller.cancelYouTubeDownload(job.id) }
            // Trying a removed or private video again cannot help, so only a temporary failure offers it.
            if (failed && !job.permanent || job.state == YouTubeJobState.CANCELLED)
                JobButton(stringResource(Res.string.retry), title, AppIcon.REFRESH, ActionStyle.TONAL) { controller.retryYouTubeDownload(job.id) }
            if (!job.active && job.state != YouTubeJobState.NEEDS_TAGS)
                JobButton(stringResource(Res.string.sources_remove_from_list), title, AppIcon.REMOVE) { controller.dismissYouTubeDownload(job.id) }
        }
    }
}

@Composable
private fun JobButton(label: String, title: String, icon: AppIcon, style: ActionStyle = ActionStyle.OUTLINED, onClick: () -> Unit) {
    val description = stringResource(Res.string.sources_action_for_item, label, title)
    ActionButton(label, icon, Modifier.semantics { contentDescription = description }, style = style, onClick = onClick)
}

/** Checks a YouTube download's tags and cover before Nevumi writes them and saves the song. */
@Composable
internal fun YouTubeTagEditor(job: YouTubeJob, controller: MusicController, dismiss: () -> Unit) {
    DownloadTagEditor(DownloadTagReview(job.id, stringResource(Res.string.sources_youtube_tags_intro, job.track.title),
        job.tags, cover = job.chosenCover || job.track.artworkUrl != null,
        fileName = { artistTitle, tags -> youTubeFileName(tags, artistTitle) },
        loadCover = { controller.youTubeCover(job.id) },
        finish = { choice -> controller.finishYouTubeTags(job.id, choice) }), dismiss)
}
