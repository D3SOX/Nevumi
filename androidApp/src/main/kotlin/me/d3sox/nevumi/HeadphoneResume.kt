package me.d3sox.nevumi

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import me.d3sox.nevumi.app.MusicController

/** Resumes the paused song when headphones, a USB headset or Bluetooth audio connects, if Settings asks for it. */
class HeadphoneResume(context: Context, private val controller: MusicController) {
    private val audio = context.getSystemService(AudioManager::class.java)
    /** Outputs already connected; registering reports them as added, and so can a Bluetooth device that reconnects a profile. */
    private val connected = mutableSetOf<Int>()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) {
            val new = added.filter { it.isSink && connected.add(it.id) }
            if (new.any { it.type in headphoneTypes }) resumeIfPaused()
        }
        override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { removed.forEach { connected.remove(it.id) } }
    }

    fun start() {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapTo(connected) { it.id }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun resumeIfPaused() {
        val state = controller.state.value
        if (state.preferences.resumeOnHeadphones && state.queue.currentEntryId != null && !state.playback.playWhenReady &&
            // A finished queue would start its last song again.
            !state.playback.ended) controller.play()
    }

    private companion object {
        val headphoneTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
    }
}
