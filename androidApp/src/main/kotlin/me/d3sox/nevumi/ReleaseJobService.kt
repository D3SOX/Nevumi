package me.d3sox.nevumi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.ReleaseNotifier
import me.d3sox.nevumi.app.ReleasesController

/** Checks the followed artists for new releases in the background, so their notifications arrive without opening the app. */
class ReleaseJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val controller = (application as MusicApplication).controller
        task = scope.launch {
            var retry = false
            try {
                withContext(Dispatchers.IO) {
                    val state = controller.state.first { it.libraryReady }
                    // Turning notifications off leaves the job to cancel itself instead of racing the settings as they load.
                    if (!state.preferences.notifyNewReleases) getSystemService(JobScheduler::class.java).cancel(JOB)
                    else {
                        controller.releasesController.refreshIfStale()
                        retry = controller.state.value.releaseRefresh.error != null
                    }
                }
            } catch (_: CancellationException) {
                // The next run checks again.
            } finally { if (isActive) jobFinished(params, retry) }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        task?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB = 1004

        /** Schedules the check once notifications are on and an artist is followed. */
        fun scheduleWhileNeeded(context: Context, controller: MusicController) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                controller.state.map { it.preferences.notifyNewReleases && it.followedArtists.isNotEmpty() }.distinctUntilChanged().collect { needed ->
                    if (needed && scheduler.getPendingJob(JOB) == null) scheduler.schedule(
                        JobInfo.Builder(JOB, ComponentName(context, ReleaseJobService::class.java))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setPersisted(true)
                            .setPeriodic(ReleasesController.REFRESH_INTERVAL_MS)
                            .build())
                }
            }
        }
    }
}

/** Posts each announcement as its own notification, so a newer one does not replace one not yet seen. */
class AndroidReleaseNotifier(private val context: Context) : ReleaseNotifier {
    override suspend fun notify(title: String, text: String) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "New releases", NotificationManager.IMPORTANCE_DEFAULT))
        notifications.notify((System.currentTimeMillis() / 1_000).toInt(), Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build())
    }

    private companion object { const val CHANNEL = "new-releases" }
}
