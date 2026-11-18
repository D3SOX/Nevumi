package me.d3sox.nevumi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.d3sox.nevumi.app.MusicController

/**
 * Pauses when the media volume is turned down to zero and resumes when it is turned up again, if Settings asks for it.
 * It only resumes a song it paused itself.
 */
class ZeroVolumePause(private val context: Context, private val controller: MusicController) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    /** Whether the paused song was paused here; playing it any other way clears this. */
    private var pausedHere = false

    // Recent Android versions no longer save volumes as system settings, so only AudioManager's volume broadcast reports changes.
    // It has no public constant but is sent to registered receivers on every Android version.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val previous = volume
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val state = controller.state.value
            if (!state.preferences.pauseAtZeroVolume) { pausedHere = false; return }
            if (volume == 0 && previous > 0 && state.playback.playWhenReady) {
                pausedHere = true
                controller.pause()
            } else if (volume > 0 && previous == 0 && pausedHere) {
                pausedHere = false
                // A finished queue would start its last song again.
                if (state.queue.currentEntryId != null && !state.playback.playWhenReady && !state.playback.ended) controller.play()
            }
        }
    }

    fun start() {
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        MainScope().launch {
            controller.state.map { it.playback.playWhenReady }.distinctUntilChanged().collect { if (it) pausedHere = false }
        }
    }
}
