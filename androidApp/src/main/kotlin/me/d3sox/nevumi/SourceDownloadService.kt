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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample

/** What the notification of a running download shows. */
data class SourceDownloadProgress(val title: String, val message: String, val percent: Int)

/**
 * Runs the downloads of one music source, such as SoundCloud, until none needs this device, so they continue in the background.
 * The source stores its jobs, so a run stopped by the system continues in the next one.
 */
@OptIn(FlowPreview::class)
abstract class SourceDownloadService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Pair<Int, String?>, Job>()
    protected abstract val notificationId: Int
    protected abstract val defaultMessage: String
    /** The first active download, or null once none is running. */
    protected abstract fun progress(): Flow<SourceDownloadProgress?>
    protected abstract fun currentProgress(): SourceDownloadProgress?
    protected abstract suspend fun runPending()

    override fun onStartJob(params: JobParameters): Boolean {
        val key = requestKey(params)
        val visible = Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob
        if (visible) showProgress(params, currentProgress())
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            val progress = if (visible) launch {
                progress().distinctUntilChanged().sample(1_000).collect { showProgress(params, it) }
            } else null
            try { withContext(Dispatchers.IO) { runPending() } }
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
    private fun showProgress(params: JobParameters, progress: SourceDownloadProgress?) =
        setNotification(params, notificationId, notification(progress), JOB_END_NOTIFICATION_POLICY_REMOVE)

    private fun notification(progress: SourceDownloadProgress?): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("offline-downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW))
        val percent = progress?.percent?.takeIf { it in 1..99 }
        return Notification.Builder(this, "offline-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(progress?.title ?: "Nevumi")
            .setContentText(progress?.message?.ifBlank { null } ?: defaultMessage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (percent != null) 100 else 0, percent ?: 0, percent == null)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
}
