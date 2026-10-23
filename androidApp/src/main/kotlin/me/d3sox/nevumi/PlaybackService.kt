package me.d3sox.nevumi

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import me.d3sox.nevumi.playback.AndroidSessionPlayer
import me.d3sox.nevumi.playback.AudioEffectsCommand
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.playback.SessionPlaybackCommands
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_launcher_monochrome)
        })
        val player = AndroidSessionPlayer(this)
        scope.launch { me.d3sox.nevumi.playback.SessionAudioLevel.requested.collect(player.engine::setAudioLevelEnabled) }
        scope.launch { player.engine.audioLevel.collect(me.d3sox.nevumi.playback.SessionAudioLevel::publish) }
        scope.launch {
            (application as MusicApplication).controller.state.map { it.artwork }.distinctUntilChanged().collect(player::updateArtworkPaths)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val connection = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
                val ownApp = controller.packageName == packageName && controller.uid == applicationInfo.uid
                val commands = connection.availableSessionCommands.buildUpon()
                if (ownApp) {
                    commands.add(AudioEffectsCommand.command)
                    SessionPlaybackCommands.actions.forEach { commands.add(SessionPlaybackCommands.command(it)) }
                }
                val playerCommands = (if (ownApp) player.availableCommands else connection.availablePlayerCommands).buildUpon()
                if (!ownApp) playerCommands.remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(commands.build()).setAvailablePlayerCommands(playerCommands.build()).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                command: SessionCommand, arguments: Bundle): ListenableFuture<SessionResult> {
                if (command.customAction !in SessionPlaybackCommands.actions && command.customAction != AudioEffectsCommand.ACTION)
                    return super.onCustomCommand(session, controller, command, arguments)
                if (controller.packageName != packageName || controller.uid != applicationInfo.uid)
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                if (command.customAction == SessionPlaybackCommands.STATUS)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, SessionPlaybackCommands.encodeStatus(player.privateStatus())))
                val result = SettableFuture.create<SessionResult>()
                scope.launch {
                    try {
                        when (command.customAction) {
                            AudioEffectsCommand.ACTION -> player.engine.setAudioEffects(AudioEffectsCommand.decode(arguments))
                            SessionPlaybackCommands.GAPLESS -> player.engine.setGaplessPlayback(arguments.getBoolean("enabled", true))
                            SessionPlaybackCommands.REPLAY_GAIN -> player.engine.setReplayGain(SessionPlaybackCommands.decodeReplayGain(arguments))
                            SessionPlaybackCommands.CROSSFADE -> {
                                val duration = arguments.getLong("durationMs", -1)
                                player.engine.setCrossfadeDuration(duration)
                                withTimeout(10_000) { player.engine.status.first { it.crossfadeDurationMs == duration } }
                            }
                            SessionPlaybackCommands.REPEAT -> {
                                val mode = RepeatMode.valueOf(requireNotNull(arguments.getString("mode")))
                                val plays = arguments.getInt("plays", 0).takeIf { it != 0 }
                                player.engine.setRepeat(mode, plays)
                                withTimeout(10_000) { player.engine.status.first { it.repeatMode == mode && it.repeatPlays == plays } }
                            }
                        }
                        result.set(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (_: IllegalArgumentException) { result.set(SessionResult(SessionError.ERROR_BAD_VALUE)) }
                    catch (_: Exception) { result.set(SessionResult(SessionError.ERROR_UNKNOWN)) }
                }
                return result
            }
        }).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        session?.let { it.player.release(); it.release() }
        session = null
        me.d3sox.nevumi.playback.SessionAudioLevel.publish(0f)
        scope.cancel()
        super.onDestroy()
    }
}
