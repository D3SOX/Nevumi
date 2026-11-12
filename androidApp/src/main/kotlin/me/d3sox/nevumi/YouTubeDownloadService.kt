package me.d3sox.nevumi

import kotlinx.coroutines.flow.map
import me.d3sox.nevumi.youtube.YouTubeJob

/** Runs YouTube downloads on this device, so they continue in the background. */
class YouTubeDownloadService : SourceDownloadService() {
    private val downloads get() = (application as MusicApplication).youTubeDownloads
    override val notificationId = 2003
    override val defaultMessage = "Downloading from YouTube"
    override fun progress() = downloads.jobs.map { it.orEmpty().progress() }
    override fun currentProgress() = downloads.jobs.value.orEmpty().progress()
    override suspend fun runPending() = downloads.runPending()

    private fun List<YouTubeJob>.progress() = firstOrNull { it.active }?.let { SourceDownloadProgress(it.tags.title, it.message, it.percent) }
}
