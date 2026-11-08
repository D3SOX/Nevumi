package me.d3sox.nevumi.playback

import android.app.ActivityManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import me.d3sox.nevumi.diagnostics.DiagnosticLog
import java.io.File
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

/**
 * Records each gap in our audio output with what surrounded it, so a stall during background playback names its cause.
 * The log stays in the app's private storage, which backups exclude.
 */
@UnstableApi
internal class AudioUnderrunRecorder(context: Context, private val player: Player) : AnalyticsListener {
    private val context = context.applicationContext

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long) {
        val item = player.currentMediaItem
        // A gap longer than the output buffer since the last write means the decoder thread did not feed the output.
        val line = listOf("${OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS)} underrun",
            "sinceLastFeedMs=$elapsedSinceLastFeedMs", "bufferMs=$bufferSizeMs", "bufferedAheadMs=${player.totalBufferedDuration}",
            "positionMs=${eventTime.currentPlaybackPositionMs}", "entry=${item?.mediaId}",
            "track=${item?.mediaMetadata?.extras?.getString("trackId")}", "volume=${player.volume}", "output=${output()}",
            "screenOn=${context.getSystemService(PowerManager::class.java)?.isInteractive}",
            "importance=${ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance}",
            "gc=${stat("gc-count")}/${stat("gc-time")}ms", "blockingGc=${stat("blocking-gc-count")}/${stat("blocking-gc-time")}ms")
            .joinToString(" ")
        Log.w(TAG, line)
        writer.execute { runCatching { log(context).append(line) } }
    }

    private fun output(): String {
        if (Build.VERSION.SDK_INT < 33) return "unknown"
        val audio = context.getSystemService(AudioManager::class.java) ?: return "unknown"
        val media = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
        return audio.getAudioDevicesForAttributes(media).joinToString(",") { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "bluetooth-le"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
                else -> "type-${device.type}"
            }
        }.ifEmpty { "none" }
    }

    private fun stat(name: String): String? = Debug.getRuntimeStat("art.gc.$name")

    private companion object {
        const val TAG = "NevumiAudio"
        val writer = Executors.newSingleThreadExecutor { Thread(it, "Nevumi audio diagnostics").apply { isDaemon = true } }
        @Volatile var shared: DiagnosticLog? = null
        // Every decoder records into one file, so one log serializes the writes.
        fun log(context: Context) = shared ?: synchronized(this) {
            shared ?: DiagnosticLog(File(context.filesDir, "diagnostics/audio-underruns.log")).also { shared = it }
        }
    }
}
