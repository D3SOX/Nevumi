package me.d3sox.nevumi.playback

import android.app.ActivityManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * Records each gap in our audio output with what surrounded it, so a stall during background playback names its cause.
 * [PlaybackStallWatchdog] records what held the playback thread during the gap.
 */
@UnstableApi
internal class AudioUnderrunRecorder(context: Context, private val player: Player) : AnalyticsListener {
    private val context = context.applicationContext

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long) {
        // Listeners run on the main thread, where a failed system query would otherwise crash playback.
        runCatching { record(eventTime, bufferSizeMs, elapsedSinceLastFeedMs) }
    }

    private fun record(eventTime: AnalyticsListener.EventTime, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        val item = player.currentMediaItem
        // A gap longer than the output buffer since the last write means the decoder thread did not feed the output.
        val event = listOf("underrun",
            "sinceLastFeedMs=$elapsedSinceLastFeedMs", "bufferMs=$bufferSizeMs", "bufferedAheadMs=${player.totalBufferedDuration}",
            "positionMs=${eventTime.currentPlaybackPositionMs}", "entry=${item?.mediaId}",
            "track=${item?.mediaMetadata?.extras?.getString("trackId")}", "volume=${player.volume}", "output=${output()}",
            "screenOn=${context.getSystemService(PowerManager::class.java)?.isInteractive}",
            "importance=${ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance}",
            "gc=${stat("gc-count")}/${stat("gc-time")}ms", "blockingGc=${stat("blocking-gc-count")}/${stat("blocking-gc-time")}ms")
            .joinToString(" ")
        AudioDiagnostics.record(context, event)
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
}
