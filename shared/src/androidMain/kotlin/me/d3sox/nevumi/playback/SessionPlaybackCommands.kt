package me.d3sox.nevumi.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand
import kotlinx.serialization.json.Json

/** Private app/session messages. They contain identifiers and controls, never audio locations. */
object SessionPlaybackCommands {
    const val STATUS = "me.d3sox.nevumi.PLAYBACK_STATUS"
    const val CROSSFADE = "me.d3sox.nevumi.CROSSFADE"
    const val GAPLESS = "me.d3sox.nevumi.GAPLESS"
    const val REPEAT = "me.d3sox.nevumi.REPEAT"
    const val REPLAY_GAIN = "me.d3sox.nevumi.REPLAY_GAIN"
    val actions = listOf(STATUS, CROSSFADE, GAPLESS, REPEAT, REPLAY_GAIN)
    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)
    fun encodeStatus(status: PlaybackStatus) = Bundle().apply { putString("status", Json.encodeToString(status)) }
    fun encodeReplayGain(replayGain: ReplayGain) = Bundle().apply { putString("replayGain", Json.encodeToString(replayGain)) }
    fun decodeReplayGain(bundle: Bundle): ReplayGain {
        val text = requireNotNull(bundle.getString("replayGain"))
        require(text.length <= 1_024)
        return Json.decodeFromString<ReplayGain>(text).also { it.validate() }
    }
    fun decodeStatus(bundle: Bundle): PlaybackStatus {
        val text = requireNotNull(bundle.getString("status"))
        require(text.length <= 65_536)
        return Json.decodeFromString<PlaybackStatus>(text)
    }
}
