package me.d3sox.nevumi

import android.app.job.JobParameters
import android.app.job.JobService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.app.AppState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update

@OptIn(FlowPreview::class)
class DownloadService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val running = mutableMapOf<Pair<Int, String?>, Job>()
    private val visibleJobs = MutableStateFlow(0)
    private val backgroundJobs = MutableStateFlow(0)
    private var backgroundNotification = false

    // Background jobs, such as those after a relaunch or playlist refreshes, post the notification once a song transfers.
    // A user-initiated job owns the same notification while it runs.
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        val progress = (application as MusicApplication).controller.state.transfers().map { transferProgress(it) }.distinctUntilChanged()
        scope.launch {
            combine(progress, visibleJobs, backgroundJobs) { update, visible, background -> update.takeIf { visible == 0 && background > 0 } to visible }
                .conflate().collect { (update, visible) ->
                    if (update == null) {
                        if (backgroundNotification && visible == 0) notifications.cancel(TRANSFER_NOTIFICATION)
                        backgroundNotification = false
                    } else if (backgroundNotification || update.active) {
                        backgroundNotification = true
                        notifications.notify(TRANSFER_NOTIFICATION, transferNotification(update))
                        delay(1_000)
                    }
                }
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        val key = requestKey(params)
        val visibleTransfer = Build.VERSION.SDK_INT >= 34 && params.isUserInitiatedJob
        if (visibleTransfer) showTransfer(params)
        val owners = if (visibleTransfer) visibleJobs else backgroundJobs
        owners.update { it + 1 }
        val app = application as MusicApplication
        val offline = app.offline
        val task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            val progress = if (visibleTransfer) launch {
                app.controller.state.transfers().map { transferProgress(it) }.distinctUntilChanged().sample(1_000)
                    .collect { progress -> showTransfer(params, progress) }
            } else null
            try {
                withContext(Dispatchers.IO) {
                    if (params.extras.getBoolean("refresh-playlists")) app.refreshOfflinePlaylists()
                    // Linking only avoids redundant downloads, so its failure must not block them.
                    try { app.controller.linkSameFiles() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The next scan, refresh or job links them. */ }
                    offline.runPending()
                }
                retry = offline.state.value.values.any { it.status != DownloadStatus.COMPLETE }
            } catch (_: CancellationException) {
                // The transfer retains its partial file and durable request.
            } catch (_: Exception) {
                retry = true
            } finally {
                progress?.cancel()
                owners.update { it - 1 }
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

    // A replacement can start before Android delivers the previous request's stop callback.
    private fun requestKey(params: JobParameters) = params.jobId to params.extras.getString(DOWNLOAD_SCHEDULE_ID)

    override fun onDestroy() {
        scope.cancel()
        if (backgroundNotification) getSystemService(NotificationManager::class.java).cancel(TRANSFER_NOTIFICATION)
        super.onDestroy()
    }

    // Playback status arrives many times per second; only download and library changes can move the notification.
    private fun kotlinx.coroutines.flow.Flow<AppState>.transfers() = distinctUntilChanged { previous, next ->
        previous.downloads === next.downloads && previous.downloadBatch == next.downloadBatch &&
            previous.playlistDownloads === next.playlistDownloads && previous.tracks === next.tracks
    }

    private data class TransferProgress(val title: String = "Nevumi", val text: String = "Downloading music", val percent: Int? = null,
        val active: Boolean = false)

    // Batches of several songs report how many are done; a single song reports its transferred bytes.
    private fun transferProgress(state: AppState): TransferProgress {
        val record = state.downloads.values.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
        val song = state.tracks.firstOrNull { it.id == record?.trackId }?.metadata?.title ?: "Nevumi"
        val batch = state.downloadBatch
        if (batch.total > 1) {
            val metadata = state.tracks.filter { it.id in batch.trackIds }.map { it.metadata }
            // Name the playlist, album, or artist when the whole batch comes from one.
            val title = state.playlistDownloads.firstOrNull { it.playlist.trackIds.containsAll(batch.trackIds) }?.playlist?.name
                ?: metadata.map { it.album }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }
                ?: metadata.map { it.artist }.distinct().singleOrNull()?.takeIf { it.isNotBlank() }
                ?: song
            return TransferProgress(title, "Downloading · ${batch.downloaded} of ${batch.total} songs",
                (batch.fraction * 100).toInt().coerceIn(0, 100), record != null)
        }
        if (record == null) return TransferProgress()
        val percent = if (record.sizeBytes > 0) (record.bytesDownloaded.toDouble() / record.sizeBytes * 100).toInt().coerceIn(0, 100) else null
        return TransferProgress(song, percent = percent, active = true)
    }

    @androidx.annotation.RequiresApi(34)
    private fun showTransfer(params: JobParameters, progress: TransferProgress = TransferProgress()) =
        setNotification(params, TRANSFER_NOTIFICATION, transferNotification(progress), JOB_END_NOTIFICATION_POLICY_REMOVE)

    private fun transferNotification(progress: TransferProgress): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("offline-downloads", "Music downloads", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "offline-downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(progress.title)
            .setContentText(progress.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(if (progress.percent != null) 100 else 0, progress.percent ?: 0, progress.percent == null)
            .addAction(Notification.Action.Builder(null, "Pause downloads", PendingIntent.getBroadcast(this, 0,
                Intent(this, DownloadActionReceiver::class.java).setAction(DownloadActionReceiver.PAUSE), PendingIntent.FLAG_IMMUTABLE)).build())
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private companion object { const val TRANSFER_NOTIFICATION = 2001 }
}
