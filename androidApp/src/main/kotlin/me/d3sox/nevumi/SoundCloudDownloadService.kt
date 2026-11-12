package me.d3sox.nevumi

import kotlinx.coroutines.flow.map
import me.d3sox.nevumi.soundcloud.SoundCloudJob

/** Follows SoundCloud downloads until sc-gate-dl finishes them, so saving to this device continues in the background. */
class SoundCloudDownloadService : SourceDownloadService() {
    private val downloads get() = (application as MusicApplication).soundCloud
    override val notificationId = 2002
    override val defaultMessage = "Downloading from SoundCloud"
    override fun progress() = downloads.jobs.map { it.orEmpty().progress() }
    override fun currentProgress() = downloads.jobs.value.orEmpty().progress()
    override suspend fun runPending() = downloads.runPending()

    private fun List<SoundCloudJob>.progress() = firstOrNull { it.active }?.let { SourceDownloadProgress(it.track.title, it.message, it.percent) }
}
