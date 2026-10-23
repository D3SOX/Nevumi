package me.d3sox.nevumi.playback

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid
import kotlinx.coroutines.*

/** A local decoder. The owning session coordinates audio focus for all overlapping decoders. */
@UnstableApi
class AndroidExoPlaybackEngine(context: Context, outputMonitor: AudioProcessor? = null) : PlaybackEngine {
    private val appContext = context.applicationContext
    private val detailsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var detailsJob: Job? = null
    private var detailsSource: String? = null
    private var fileAudioInfo: AudioInfo? = null
    private val main = Handler(Looper.getMainLooper())
    private val effects = AtomicReference(AudioEffects())
    private val replayGain = AtomicReference(ReplayGain())
    private val mutableStatus = MutableStateFlow(PlaybackStatus())
    override val status = mutableStatus.asStateFlow()
    private val mutableAudioLevel = MutableStateFlow(0f)
    override val audioLevel = mutableAudioLevel.asStateFlow()
    private val meter = AudioLevelProcessor { mutableStatus.value.positionMs }
    override fun setAudioLevelEnabled(enabled: Boolean) { meter.enabled = enabled; if (!enabled) mutableAudioLevel.value = 0f }
    private var player: ExoPlayer? = null
    private val pending = mutableListOf<ExoPlayer.() -> Unit>()
    private var closed = false
    private var playbackId: String? = null
    private var playbackEntry: String? = null
    private var repeatMode = RepeatMode.OFF
    private var repeatPlays: Int? = null
    private var repeatedEntry: String? = null
    private var repeatStarted = false
    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val entry = item?.mediaId
            if (entry != playbackEntry || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                playbackEntry = entry
                playbackId = entry?.let { Uuid.random().toString() }
            }
            val remaining = repeatPlays ?: return
            if (entry != repeatedEntry) {
                repeatPlays = null
                repeatMode = RepeatMode.OFF
            } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                repeatPlays = (remaining - 1).coerceAtLeast(1)
            }
        }
        override fun onEvents(player: Player, events: Player.Events) {
            if ((repeatMode == RepeatMode.OFF || repeatPlays == 1) && player.repeatMode != Player.REPEAT_MODE_OFF)
                player.repeatMode = Player.REPEAT_MODE_OFF
            publish(player)
        }
    }
    // Only a playing song moves its position; a paused decoder is polled slowly and published by events and commands.
    private val progress = object : Runnable {
        override fun run() {
            if (!closed) {
                val current = player?.also(::publish)
                main.postDelayed(this, if (current == null || current.isPlaying || current.playWhenReady) 50 else 500)
            }
        }
    }

    init {
        onMain {
            if (!closed) try {
                val created = ExoPlayer.Builder(context.applicationContext,
                    NevumiAudioRenderersFactory(context.applicationContext, outputMonitor, effects::get, meter, replayGain::get))
                    .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA).build(), false).build()
                player = created
                created.addListener(listener)
                pending.forEach { it(created) }
                pending.clear()
                progress.run()
            } catch (_: Exception) {
                mutableStatus.update { it.copy(error = "Could not start the audio decoder") }
            }
        }
    }

    private fun publish(player: Player) {
        if (repeatPlays != null && player.playbackState == Player.STATE_READY) repeatStarted = true
        if (repeatStarted && player.playbackState == Player.STATE_ENDED) {
            repeatPlays = null
            repeatMode = RepeatMode.OFF
            repeatStarted = false
        }
        val item = player.currentMediaItem
        val uri = item?.localConfiguration?.uri
        if (uri?.toString() != detailsSource) {
            detailsJob?.cancel()
            detailsSource = uri?.toString()
            fileAudioInfo = null
            if (uri != null && uri.scheme in listOf(null, "file", "content")) {
                val source = detailsSource
                detailsJob = detailsScope.launch {
                    val info = withContext(Dispatchers.IO) { readFileAudioInfo(uri) }
                    if (!closed && detailsSource == source) { fileAudioInfo = info; this@AndroidExoPlaybackEngine.player?.let(::publish) }
                }
            }
        }
        val format = this.player?.audioFormat
        val info = if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED) {
            (fileAudioInfo?.format ?: audioFormatName(format?.sampleMimeType, uri?.toString()))?.let { name ->
                AudioInfo(name, fileAudioInfo?.bitrateBitsPerSecond ?: format?.averageBitrate?.toLong()?.takeIf { it > 0 },
                    format?.sampleRate?.takeIf { it > 0 } ?: fileAudioInfo?.sampleRateHz)
            }
        } else null
        mutableAudioLevel.value = if (meter.enabled && player.isPlaying) meter.levelAt(player.currentPosition) else 0f
        mutableStatus.value = PlaybackStatus(trackId = item?.mediaMetadata?.extras?.getString("trackId"),
            playing = player.isPlaying, positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeUnless { it == C.TIME_UNSET } ?: 0,
            ended = player.playbackState == Player.STATE_ENDED, error = player.playerError?.errorCodeName,
            entryId = item?.mediaId, sourceId = item?.mediaMetadata?.extras?.getString("sourceId"),
            volume = player.volume.toDouble(), speed = player.playbackParameters.speed.toDouble(),
            pitch = player.playbackParameters.pitch.toDouble(), playWhenReady = player.playWhenReady,
            repeatMode = repeatMode, repeatPlays = repeatPlays, playbackId = playbackId, audioEffects = effects.get(), audioInfo = info)
    }

    private fun readFileAudioInfo(uri: Uri): AudioInfo? {
        val reader = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content") reader.setDataSource(appContext, uri)
            else reader.setDataSource(if (uri.scheme == "file") uri.path else uri.toString())
            val name = audioFormatName(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE), uri.toString()) ?: return null
            AudioInfo(name, reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 },
                runCatching { reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE) }.getOrNull()?.toIntOrNull()?.takeIf { it > 0 })
        } catch (_: Exception) { null }
        finally { reader.release() }
    }

    private fun mediaItem(track: PlayableTrack) = MediaItem.Builder().setMediaId(track.entryId).setUri(track.location)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setExtras(Bundle().apply { putString("trackId", track.id); putString("sourceId", track.sourceId) }).build()).build()

    override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)
    override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
        require(startIndex in tracks.indices)
        command {
            val selected = tracks[startIndex]
            detailsJob?.cancel()
            detailsSource = null
            fileAudioInfo = null
            val previous = mutableStatus.value
            playbackId = previous.playbackId.takeIf { previous.entryId == selected.entryId && previous.sourceId != selected.sourceId }
                ?: Uuid.random().toString()
            playbackEntry = selected.entryId
            repeatedEntry = selected.entryId.takeIf { repeatPlays != null }
            repeatStarted = false
            setMediaItems(tracks.map(::mediaItem), startIndex, positionMs.coerceAtLeast(0))
            prepare()
            playWhenReady = play
        }
    }
    override fun updateQueue(tracks: List<PlayableTrack>) = command {
        val current = currentMediaItem
        val destination = tracks.indexOfFirst { it.entryId == current?.mediaId }
        val updated = tracks.map(::mediaItem)
        if (current == null || destination < 0) {
            replaceMediaItems(0, mediaItemCount, updated)
        } else {
            val track = tracks[destination]
            val sourceId = if (current.localConfiguration?.uri.toString() != track.location)
                current.mediaMetadata.extras?.getString("sourceId") else track.sourceId
            val metadata = current.mediaMetadata.buildUpon().setTitle(track.title).setArtist(track.artist)
                .setExtras(Bundle().apply { putString("trackId", track.id); putString("sourceId", sourceId) }).build()
            // Keep the current media source and decoder; replace each surrounding range in one operation.
            replaceMediaItem(currentMediaItemIndex, current.buildUpon().setMediaMetadata(metadata).build())
            replaceMediaItems(0, currentMediaItemIndex, updated.take(destination))
            replaceMediaItems(destination + 1, mediaItemCount, updated.drop(destination + 1))
        }
    }
    override fun play() = command { play() }
    override fun pause() = command { pause(); mutableAudioLevel.value = 0f }
    override fun seek(positionMs: Long) = command { seekTo(positionMs.coerceAtLeast(0)) }
    override fun setVolume(volume: Double) {
        require(volume.isFinite())
        command { this.volume = volume.coerceIn(0.0, 1.0).toFloat() }
    }
    override fun setSpeed(speed: Double) {
        require(speed in 0.5..2.0)
        command { playbackParameters = PlaybackParameters(speed.toFloat(), playbackParameters.pitch) }
    }
    override fun setPitch(pitch: Double) {
        require(pitch in 0.5..2.0)
        command { playbackParameters = PlaybackParameters(playbackParameters.speed, pitch.toFloat()) }
    }
    override suspend fun setAudioEffects(effects: AudioEffects) {
        effects.validate()
        this.effects.set(effects)
        mutableStatus.update { it.copy(audioEffects = effects) }
    }
    override fun setReplayGain(replayGain: ReplayGain) {
        replayGain.validate()
        this.replayGain.set(replayGain)
    }
    override fun setRepeat(mode: RepeatMode, playCount: Int?) {
        require(playCount == null || mode == RepeatMode.ONE && playCount > 0)
        command {
            this@AndroidExoPlaybackEngine.repeatMode = mode
            repeatPlays = playCount
            repeatedEntry = currentMediaItem?.mediaId.takeIf { playCount != null }
            repeatStarted = false
            repeatMode = when (mode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ONE -> if (playCount == 1) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            }
        }
    }
    private fun command(operation: ExoPlayer.() -> Unit) = onMain {
        if (!closed) player?.let { operation(it); publish(it) } ?: run { pending += operation }
    }
    private fun onMain(operation: () -> Unit) {
        if (Looper.myLooper() == main.looper) operation() else main.post { operation() }
    }
    override fun close() = onMain {
        if (!closed) {
            closed = true
            detailsScope.cancel()
            main.removeCallbacks(progress)
            pending.clear()
            player?.release()
            player = null
        }
    }
}
