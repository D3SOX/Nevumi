package me.d3sox.nevumi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import me.d3sox.nevumi.soundcloud.SoundCloudJob

/** Follows SoundCloud downloads until sc-gate-dl finishes them, so saving to this device continues in the background. */
@OptIn(FlowPreview::class)
class SoundCloudDownloadService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Pair<Int, String?>, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val key = requestKey(params)
        val visible = Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob
        val downloads = (application as MusicApplication).soundCloud
        if (visible) showProgress(params, downloads.jobs.value.orEmpty())
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            val progress = if (visible) launch {
                downloads.jobs.map { it.orEmpty() }.distinctUntilChanged().sample(1_000).collect { showProgress(params, it) }
            } else null
            try { withContext(Dispatchers.IO) { downloads.runPending() } }
            catch (_: CancellationException) { /* The job state is stored, so the next run continues it. */ }
            catch (_: Exception) { retry = true }
            finally {
                progress?.cancel()
                if (isActive) {
                    running.remove(key)
                    jobFinished(params, retry)
                }
            }
        }
        running[key] = task
        task.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(requestKey(params))?.cancel()
        return true
    }

    private fun requestKey(params: JobParameters) = params.jobId to params.extras.getString(DOWNLOAD_SCHEDULE_ID)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @androidx.annotation.RequiresApi(34)
    private fun showProgress(params: JobParameters, jobs: List<SoundCloudJob>) =
        setNotification(params, NOTIFICATION, notification(jobs.firstOrNull { it.active }), JOB_END_NOTIFICATION_POLICY_REMOVE)

    private fun notification(job: SoundCloudJob?): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("offline-downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW))
        val percent = job?.percent?.takeIf { it in 1..99 }
        return Notification.Builder(this, "offline-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job?.track?.title ?: "Nevumi")
            .setContentText(job?.message?.ifBlank { null } ?: "Downloading from SoundCloud")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (percent != null) 100 else 0, percent ?: 0, percent == null)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private companion object { const val NOTIFICATION = 2002 }
}
