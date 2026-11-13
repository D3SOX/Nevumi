package me.d3sox.nevumi

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.playlists.Playlists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal const val DOWNLOAD_SCHEDULE_ID = "schedule-id"

class AndroidDownloadJobs(context: Context, private val preferences: MusicPreferences, private val playlists: Playlists,
    private val onUserDownload: () -> Unit) {
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val service = ComponentName(context, DownloadService::class.java)
    private val soundCloudService = ComponentName(context, SoundCloudDownloadService::class.java)
    private val youTubeService = ComponentName(context, YouTubeDownloadService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduling = Mutex()
    // JobScheduler keeps listing a job for a moment after it finishes, so only this process knows whether a download job runs.
    private val jobs = Any()
    private var running = 0
    // Whether new work, and any of it user initiated, found a job running; that job starts another when it finishes.
    private var missed: Boolean? = null

    fun schedule(userInitiated: Boolean = false, newWork: Boolean = false) {
        if (userInitiated) onUserDownload()
        enqueue(userInitiated, newWork)
    }

    private fun enqueue(userInitiated: Boolean, newWork: Boolean) {
        scope.launch { scheduling.withLock {
            val settings = preferences.load()
            if (settings.downloadsPaused) {
                scheduler.cancel(DOWNLOAD_JOB)
                scheduler.cancel(PLAYLIST_JOB)
                return@withLock
            }
            val networkType = if (settings.downloadOnMetered) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED
            if (playlists.anyAutoDownload()) {
                val periodic = JobInfo.Builder(PLAYLIST_JOB, service)
                    .setRequiredNetworkType(networkType)
                    .setRequiresStorageNotLow(true)
                    .setPersisted(true)
                    .setPeriodic(15 * 60_000L)
                    .setExtras(PersistableBundle().apply {
                        putBoolean("refresh-playlists", true)
                        putString(DOWNLOAD_SCHEDULE_ID, UUID.randomUUID().toString())
                    })
                    .build()
                if (scheduler.getPendingJob(PLAYLIST_JOB)?.requiredNetwork != periodic.requiredNetwork)
                    scheduler.schedule(periodic)
            } else scheduler.cancel(PLAYLIST_JOB)
            val builder = JobInfo.Builder(DOWNLOAD_JOB, service)
                .setRequiredNetworkType(networkType)
                .setRequiresStorageNotLow(true)
                .setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(PersistableBundle().apply {
                    putString(DOWNLOAD_SCHEDULE_ID, UUID.randomUUID().toString())
                })
            val foregroundTransfer = Build.VERSION.SDK_INT >= 34 && userInitiated
            if (Build.VERSION.SDK_INT >= 34) builder.setUserInitiated(foregroundTransfer)
            val job = builder.build()
            val pending = scheduler.getPendingJob(DOWNLOAD_JOB)
            val upgrade = foregroundTransfer && pending?.isUserInitiated != true
            // Without new work, keep a waiting job and its backoff. New work replaces a job that waits or has just finished.
            if (pending?.requiredNetwork == job.requiredNetwork && !upgrade && (!newWork || handedToRunningJob(userInitiated))) return@withLock
            if (scheduler.schedule(job) == JobScheduler.RESULT_FAILURE && foregroundTransfer)
                scheduler.schedule(builder.setUserInitiated(false).build())
        } }
    }

    // Replacing a running job would stop its transfer, so it schedules a job for this work when it finishes.
    private fun handedToRunningJob(userInitiated: Boolean) = synchronized(jobs) {
        if (running > 0) missed = missed == true || userInitiated
        running > 0
    }

    fun started() { synchronized(jobs) { running++ } }

    /** Called when a download job ends, after it reports its result. The last running job schedules a job for any work it missed. */
    fun finished() {
        val work = synchronized(jobs) { if (--running > 0) null else missed.also { missed = null } }
        if (work != null) enqueue(work, newWork = true)
    }

    /** Follows SoundCloud downloads; they ignore the offline download pause and network settings, because each one was requested directly. */
    fun scheduleSoundCloud(userInitiated: Boolean = false) = scheduleSource(SOUNDCLOUD_JOB, soundCloudService, userInitiated)

    /** Runs YouTube downloads, which like SoundCloud's were each requested directly. */
    fun scheduleYouTube(userInitiated: Boolean = false) = scheduleSource(YOUTUBE_JOB, youTubeService, userInitiated)

    private fun scheduleSource(id: Int, service: ComponentName, userInitiated: Boolean) {
        if (userInitiated) onUserDownload()
        scope.launch { scheduling.withLock {
            val builder = JobInfo.Builder(id, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(PersistableBundle().apply { putString(DOWNLOAD_SCHEDULE_ID, UUID.randomUUID().toString()) })
            val foregroundTransfer = Build.VERSION.SDK_INT >= 34 && userInitiated
            if (Build.VERSION.SDK_INT >= 34) builder.setUserInitiated(foregroundTransfer)
            val pending = scheduler.getPendingJob(id)
            if (pending != null && (Build.VERSION.SDK_INT < 34 || !userInitiated || pending.isUserInitiated)) return@withLock
            if (scheduler.schedule(builder.build()) == JobScheduler.RESULT_FAILURE && foregroundTransfer)
                scheduler.schedule(builder.setUserInitiated(false).build())
        } }
    }

    private companion object {
        const val DOWNLOAD_JOB = 1001
        const val PLAYLIST_JOB = 1002
        const val SOUNDCLOUD_JOB = 1003
        const val YOUTUBE_JOB = 1005 // ReleaseJobService uses 1004.
    }
}
