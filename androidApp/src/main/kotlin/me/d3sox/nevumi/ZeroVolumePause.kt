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
    /** The queue entry paused here; playing any other way clears it, and another selected song keeps it from resuming. */
    private var pausedEntry: String? = null

    // Recent Android versions no longer save volumes as system settings, so only AudioManager's volume broadcast reports changes.
    // It has no public constant but is sent to registered receivers on every Android version.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            val previous = volume
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val state = controller.state.value
            if (!state.preferences.pauseAtZeroVolume) { pausedEntry = null; return }
            if (volume == 0 && previous > 0 && state.playback.playWhenReady) {
                pausedEntry = state.queue.currentEntryId
                controller.pause()
            } else if (volume > 0 && previous == 0 && pausedEntry != null) {
                val entry = pausedEntry
                pausedEntry = null
                // Playing is harmless when the pause has not been applied yet; a finished queue would start its last song again.
                if (entry == state.queue.currentEntryId && !state.playback.ended) controller.play()
            }
        }
    }

    fun start() {
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        MainScope().launch {
            // Playing any other way, or turning the setting off, forgets the song paused here.
            controller.state.map { it.playback.playWhenReady || !it.preferences.pauseAtZeroVolume }.distinctUntilChanged()
                .collect { if (it) pausedEntry = null }
        }
        // A sleep timer that ends or is turned off decides itself whether music plays, so raising the volume later must not resume it.
        MainScope().launch {
            controller.state.map { it.sleepTimerEndsAt }.distinctUntilChanged().collect { if (it == null) pausedEntry = null }
        }
    }
}
