package me.d3sox.nevumi.playback

import android.app.Service
import android.content.Context
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidPlaybackEngine(context: Context, service: Class<out Service>) : PlaybackEngine {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { main.post(it) }
    private val mutableStatus = MutableStateFlow(PlaybackStatus())
    override val status = mutableStatus.asStateFlow()
    override val audioLevel = SessionAudioLevel.level
    override fun setAudioLevelEnabled(enabled: Boolean) { SessionAudioLevel.requested.value = enabled; if (!enabled) SessionAudioLevel.publish(0f) }
    private var player: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var statusRequest: ListenableFuture<SessionResult>? = null
    private var closed = false
    private val pending = mutableListOf<MediaController.() -> Unit>()
    private val loadRevision = AtomicLong()
    private data class PlaybackIntention(val play: Boolean)
    private val pendingPlayback = AtomicReference<PlaybackIntention?>()
    private val desiredParameters = AtomicReference<PlaybackParameters?>()
    private data class RepeatIntention(val mode: RepeatMode, val plays: Int?)
    private val pendingRepeat = AtomicReference<RepeatIntention?>()
    private val repeatRevision = AtomicLong()
    private data class LoadGate(val entry: String, val source: String?, val previousId: String?, val previousSource: String?)
    private var loadGate: LoadGate? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { requestStatus() }
    }
    // Only a playing song moves its position; a paused one is polled slowly and refreshed by player events and commands.
    private val progress = object : Runnable {
        override fun run() {
            if (!closed) {
                requestStatus()
                main.postDelayed(this, if (mutableStatus.value.let { it.playWhenReady || it.playing } || pendingPlayback.get() != null) 100 else 1_000)
            }
        }
    }
    private fun wakeProgress() { if (player != null) { main.removeCallbacks(progress); progress.run() } }
    init {
        onMain {
            val connection = MediaController.Builder(context.applicationContext,
                SessionToken(context, ComponentName(context, service))).buildAsync()
            future = connection
            connection.addListener({
                if (!closed) try {
                    val connected = connection.get()
                    player = connected
                    connected.addListener(listener)
                    pending.forEach { it(connected) }
                    pending.clear()
                    progress.run()
                } catch (_: Exception) {
                    mutableStatus.update { it.copy(error = "Could not connect to the audio service") }
                }
            }, executor)
        }
    }
    private fun requestStatus() {
        if (closed || statusRequest != null) return
        val connected = player ?: return
        val revision = loadRevision.get()
        val repeatVersion = repeatRevision.get()
        val intentionAtDispatch = pendingPlayback.get()
        val response = connected.sendCustomCommand(SessionPlaybackCommands.command(SessionPlaybackCommands.STATUS), Bundle.EMPTY)
        statusRequest = response
        response.addListener({
            if (statusRequest === response) statusRequest = null
            if (!closed && loadRevision.get() == revision && repeatRevision.get() == repeatVersion) try {
                val reply = response.get()
                if (reply.resultCode != SessionResult.RESULT_SUCCESS) return@addListener
                val playback = SessionPlaybackCommands.decodeStatus(reply.extras)
                val gate = loadGate
                if (gate != null) {
                    val loaded = playback.entryId == gate.entry && playback.playbackId != null &&
                        (playback.playbackId != gate.previousId || gate.source != gate.previousSource && playback.sourceId == gate.source)
                    if (!loaded) return@addListener
                    loadGate = null
                }
                if (intentionAtDispatch != null && playback.playWhenReady == intentionAtDispatch.play)
                    pendingPlayback.compareAndSet(intentionAtDispatch, null)
                val parameters = desiredParameters.get()
                if (parameters != null && playback.speed.toFloat() == parameters.speed && playback.pitch.toFloat() == parameters.pitch)
                    desiredParameters.compareAndSet(parameters, null)
                val intention = pendingPlayback.get()?.play
                val repeat = pendingRepeat.get()
                // A load can start off the main thread while this reply is being decoded.
                mutableStatus.update { current ->
                    if (loadRevision.get() != revision || repeatRevision.get() != repeatVersion) current
                    else playback.copy(playWhenReady = intention ?: playback.playWhenReady,
                        playing = playback.playing && intention != false,
                        repeatMode = repeat?.mode ?: playback.repeatMode,
                        repeatPlays = if (repeat != null) repeat.plays else playback.repeatPlays,
                        fadingOut = playback.fadingOut?.let { if (intention == false) it.copy(playing = false, playWhenReady = false) else it })
                }
            } catch (_: Exception) {
                mutableStatus.update { it.copy(error = "Could not read audio playback state") }
            }
        }, executor)
    }
    private fun mediaItem(track: PlayableTrack) = MediaItem.Builder().setMediaId(track.entryId).setUri(track.location)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setExtras(Bundle().apply { putString("trackId", track.id); putString("sourceId", track.sourceId) }).build()).build()
    override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)
    override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
        require(startIndex in tracks.indices)
        val selected = tracks[startIndex]
        val previous = mutableStatus.value
        loadRevision.incrementAndGet()
        pendingPlayback.set(PlaybackIntention(play))
        mutableStatus.update { it.copy(trackId = selected.id, entryId = selected.entryId, sourceId = selected.sourceId,
            positionMs = positionMs.coerceAtLeast(0), durationMs = 0, playing = false, playWhenReady = play,
            playbackId = null, ended = false, error = null, fadingOut = null, audioInfo = null) }
        command {
            loadGate = LoadGate(selected.entryId, selected.sourceId, previous.playbackId, previous.sourceId)
            setMediaItems(tracks.map(::mediaItem), startIndex, positionMs.coerceAtLeast(0))
            prepare()
            playWhenReady = play
        }
    }
    override fun play() {
        pendingPlayback.set(PlaybackIntention(true))
        mutableStatus.update { it.copy(playWhenReady = true) }
        command { play() }
    }
    override fun pause() {
        pendingPlayback.set(PlaybackIntention(false))
        mutableStatus.update { it.copy(playWhenReady = false, playing = false,
            fadingOut = it.fadingOut?.copy(playing = false, playWhenReady = false)) }
        command { pause() }
    }
    override fun updateQueue(tracks: List<PlayableTrack>) = command {
        // One session operation prevents hundreds of intermediate timelines and decoder updates.
        // AndroidSessionPlayer updates the queue without reloading the current occurrence.
        replaceMediaItems(0, mediaItemCount, tracks.map(::mediaItem))
    }
    override fun seek(positionMs: Long) = command {
        // A pending timeline replacement can temporarily reset MediaController's index.
        // Seek the intended occurrence explicitly, including when the same song appears twice.
        val entry = mutableStatus.value.entryId
        val index = (0 until mediaItemCount).firstOrNull { getMediaItemAt(it).mediaId == entry }
        if (index != null) seekTo(index, positionMs.coerceAtLeast(0)) else seekTo(positionMs.coerceAtLeast(0))
    }
    override fun setVolume(volume: Double) {
        require(volume.isFinite())
        command { this.volume = volume.coerceIn(0.0, 1.0).toFloat() }
    }
    override fun setSpeed(speed: Double) {
        require(speed in 0.5..2.0)
        val parameters = requireNotNull(desiredParameters.updateAndGet { pending ->
            PlaybackParameters(speed.toFloat(), pending?.pitch ?: mutableStatus.value.pitch.toFloat())
        })
        command { playbackParameters = parameters }
    }
    override fun setPitch(pitch: Double) {
        require(pitch in 0.5..2.0)
        val parameters = requireNotNull(desiredParameters.updateAndGet { pending ->
            PlaybackParameters(pending?.speed ?: mutableStatus.value.speed.toFloat(), pitch.toFloat())
        })
        command { playbackParameters = parameters }
    }
    override fun setCrossfadeDuration(milliseconds: Long) {
        require(milliseconds in 0..12_000)
        command { sendCustomCommand(SessionPlaybackCommands.command(SessionPlaybackCommands.CROSSFADE), Bundle().apply { putLong("durationMs", milliseconds) }) }
    }
    override fun setGaplessPlayback(enabled: Boolean) = command {
        sendCustomCommand(SessionPlaybackCommands.command(SessionPlaybackCommands.GAPLESS), Bundle().apply { putBoolean("enabled", enabled) })
    }
    override fun setReplayGain(replayGain: ReplayGain) {
        replayGain.validate()
        command { sendCustomCommand(SessionPlaybackCommands.command(SessionPlaybackCommands.REPLAY_GAIN), SessionPlaybackCommands.encodeReplayGain(replayGain)) }
    }
    override suspend fun setAudioEffects(effects: AudioEffects) {
        effects.validate()
        withTimeout(10_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                command {
                    if (!continuation.isActive) return@command
                    try {
                        val response = sendCustomCommand(AudioEffectsCommand.command, AudioEffectsCommand.encode(effects))
                        continuation.invokeOnCancellation { response.cancel(false) }
                        response.addListener({
                            if (continuation.isActive) try {
                                check(response.get().resultCode == SessionResult.RESULT_SUCCESS)
                                mutableStatus.update { it.copy(audioEffects = effects) }
                                continuation.resume(Unit)
                            } catch (_: Exception) {
                                continuation.resumeWithException(IllegalStateException("Could not apply audio effects"))
                            }
                        }, executor)
                    } catch (_: Exception) {
                        continuation.resumeWithException(IllegalStateException("Could not apply audio effects"))
                    }
                }
            }
        }
    }
    override fun setRepeat(mode: RepeatMode, playCount: Int?) {
        require(playCount == null || mode == RepeatMode.ONE && playCount > 0)
        val request = RepeatIntention(mode, playCount)
        pendingRepeat.set(request)
        repeatRevision.incrementAndGet()
        mutableStatus.update { it.copy(repeatMode = mode, repeatPlays = playCount) }
        command {
            val response = sendCustomCommand(SessionPlaybackCommands.command(SessionPlaybackCommands.REPEAT), Bundle().apply {
                putString("mode", mode.name); playCount?.let { putInt("plays", it) }
            })
            response.addListener({
                if (!closed && pendingRepeat.get() === request) try {
                    if (response.get().resultCode == SessionResult.RESULT_SUCCESS && pendingRepeat.compareAndSet(request, null)) {
                        repeatRevision.incrementAndGet()
                        mutableStatus.update { it.copy(repeatMode = mode, repeatPlays = playCount) }
                        requestStatus()
                    }
                } catch (_: Exception) { /* The next service response can recover the actual playback state. */ }
            }, executor)
        }
    }
    private fun command(operation: MediaController.() -> Unit) = onMain {
        if (!closed) {
            player?.let(operation) ?: run { pending += operation }
            wakeProgress()
        }
    }
    private fun onMain(operation: () -> Unit) {
        if (Looper.myLooper() == main.looper) operation() else main.post { operation() }
    }
    override fun close() = onMain {
        if (!closed) {
            closed = true
            main.removeCallbacks(progress)
            pending.clear()
            statusRequest?.cancel(false)
            player?.removeListener(listener)
            future?.let(MediaController::releaseFuture)
            player = null
        }
    }
}
