package me.d3sox.nevumi.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** One public media session and one audio-focus owner for all overlapping decoders. */
@UnstableApi
class AndroidSessionPlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    val engine = CrossfadePlaybackEngine { AndroidExoPlaybackEngine(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    private var items = emptyList<MediaItem>()
    private var artworkPaths = emptyMap<String, String>()
    private var artworkKey: Pair<String?, String?>? = null
    private var artworkData: ByteArray? = null
    private var artworkJob: Job? = null
    private var playback = PlaybackStatus()
    private var prepared = false
    private var wantedPlaying = false
    private var focusRequested = false
    private var suppressed = false
    private var ducked = false
    private var masterVolume = 1f
    private var revision = 0L
    private var discontinuity: Int? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    suppressed = false
                    ducked = false
                    engine.setVolume(masterVolume.toDouble())
                    if (wantedPlaying) engine.play()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    engine.setVolume(masterVolume * 0.2)
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { suppressed = true; engine.pause() }
                AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
            }
            invalidateState()
        }, Handler(Looper.getMainLooper())).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) { pausePlayback(); invalidateState() }
        }
    }

    init {
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(noisy, filter)
        scope.launch {
            engine.status.collect { current ->
                discontinuity = Player.DISCONTINUITY_REASON_AUTO_TRANSITION.takeIf {
                    playback.entryId != null && current.entryId == playback.entryId &&
                        playback.playbackId != null && current.playbackId != null && current.playbackId != playback.playbackId
                }
                playback = current
                refreshArtwork()
                if (current.ended && current.repeatMode == RepeatMode.OFF && items.lastOrNull()?.mediaId == current.entryId) {
                    wantedPlaying = false
                    abandonFocus()
                }
                invalidateState()
            }
        }
    }

    fun privateStatus(): PlaybackStatus = playback.copy(volume = masterVolume.toDouble(), playWhenReady = wantedPlaying,
        playing = playback.playing && wantedPlaying && !suppressed)

    fun updateArtworkPaths(paths: Map<String, String>) {
        artworkPaths = paths.toMap()
        refreshArtwork()
    }

    private fun refreshArtwork() {
        val key = playback.trackId to artworkPaths[playback.trackId]
        if (key == artworkKey) return
        artworkKey = key
        artworkData = null
        artworkJob?.cancel()
        invalidateState()
        val path = key.second ?: return
        artworkJob = scope.launch {
            val data = withContext(Dispatchers.IO) {
                try {
                    val file = java.io.File(path)
                    if (file.length() > 8 * 1024 * 1024) return@withContext null
                    val bytes = file.readBytes()
                    val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                    val scale = minOf(1f, 512f / maxOf(decoded.width, decoded.height))
                    val preview = android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1), true)
                    try {
                        java.io.ByteArrayOutputStream().also { preview.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                            .toByteArray().takeIf { it.size < 256 * 1024 }
                    } finally { if (preview !== decoded) preview.recycle(); decoded.recycle() }
                } catch (_: Exception) { null }
            }
            if (artworkKey == key) { artworkData = data; invalidateState() }
        }
    }

    private fun publicItem(item: MediaItem) = MediaItem.Builder().setMediaId(item.mediaId)
        .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(null).setArtworkUri(null)
            .setArtworkData(artworkData.takeIf { item.mediaMetadata.extras?.getString("trackId") == artworkKey?.first },
                MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build()

    override fun getPlaceholderMediaItemData(mediaItem: MediaItem): MediaItemData =
        super.getPlaceholderMediaItemData(publicItem(mediaItem))

    override fun getState(): State {
        val index = items.indexOfFirst { it.mediaId == playback.entryId }.coerceAtLeast(0)
        val timeline = items.map { item ->
            // Public controllers need metadata and an opaque queue identifier, never an authenticated URI or source ID.
            MediaItemData.Builder(item.mediaId).setMediaItem(publicItem(item)).setIsSeekable(true)
                .setDurationUs(if (item.mediaId == playback.entryId && playback.durationMs > 0) playback.durationMs * 1_000 else C.TIME_UNSET).build()
        }
        val state = when {
            !prepared || items.isEmpty() || playback.error != null -> Player.STATE_IDLE
            playback.ended -> Player.STATE_ENDED
            playback.durationMs > 0 || playback.playing -> Player.STATE_READY
            else -> Player.STATE_BUFFERING
        }
        val commands = Player.Commands.Builder().addAllReadOnlyCommands().addAll(Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_VOLUME).build()
        val builder = State.Builder().setAvailableCommands(commands).setPlaylist(timeline)
            .setCurrentMediaItemIndex(index).setPlaybackState(state)
            .setPlayWhenReady(wantedPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackSuppressionReason(if (suppressed) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE)
            .setAudioAttributes(audioAttributes).setVolume(masterVolume)
            .setPlaybackParameters(PlaybackParameters(playback.speed.toFloat(), playback.pitch.toFloat()))
            .setRepeatMode(when (playback.repeatMode) { RepeatMode.ONE -> Player.REPEAT_MODE_ONE; RepeatMode.ALL -> Player.REPEAT_MODE_ALL; else -> Player.REPEAT_MODE_OFF })
            .setContentPositionMs(PositionSupplier.getConstant(playback.positionMs))
            .setIsLoading(state == Player.STATE_BUFFERING)
        playback.error?.let { builder.setPlayerError(PlaybackException(it, null, PlaybackException.ERROR_CODE_UNSPECIFIED)) }
        discontinuity?.let { builder.setPositionDiscontinuity(it, playback.positionMs) }
        return builder.build()
    }

    private fun requestPlayback() {
        wantedPlaying = true
        val granted = focusRequested && !suppressed || audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequested = granted
        suppressed = !granted
        if (granted) engine.play() else engine.pause()
    }
    private fun pausePlayback() {
        wantedPlaying = false
        suppressed = false
        ducked = false
        engine.pause()
        engine.setVolume(masterVolume.toDouble())
        abandonFocus()
    }
    private fun abandonFocus() {
        if (focusRequested) audio.abandonAudioFocusRequest(focus)
        focusRequested = false
    }
    private fun future(operation: suspend () -> Unit): ListenableFuture<Void> {
        val result = SettableFuture.create<Void>()
        val job = scope.launch {
            try { operation(); result.set(null) }
            catch (failure: Exception) { result.setException(failure) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, { it.run() })
        return result
    }
    private fun tracks() = items.map { item ->
        PlayableTrack(requireNotNull(item.mediaMetadata.extras?.getString("trackId") ?: item.mediaId),
            requireNotNull(item.localConfiguration).uri.toString(), item.mediaMetadata.title?.toString().orEmpty(),
            item.mediaMetadata.artist?.toString().orEmpty(), item.mediaId, item.mediaMetadata.extras?.getString("sourceId"))
    }
    private suspend fun awaitSelection(entry: String, previousId: String?, request: Long,
        previousSource: String? = null, selectedSource: String? = null) = withTimeout(10_000) {
        engine.status.first { revision != request || it.entryId == entry && it.playbackId != null &&
            (it.playbackId != previousId || selectedSource != previousSource && it.sourceId == selectedSource) }
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = future {
        if (playWhenReady) requestPlayback() else pausePlayback()
        withTimeout(10_000) { engine.status.first { it.playWhenReady == (playWhenReady && !suppressed) } }
    }
    override fun handlePrepare(): ListenableFuture<*> = future { prepared = true }
    override fun handleStop(): ListenableFuture<*> = future { pausePlayback(); prepared = false }
    override fun handleSetVolume(volume: Float): ListenableFuture<*> = future {
        masterVolume = volume.coerceIn(0f, 1f)
        engine.setVolume(masterVolume * if (ducked) 0.2 else 1.0)
    }
    override fun handleSetPlaybackParameters(parameters: PlaybackParameters): ListenableFuture<*> = future {
        engine.setSpeed(parameters.speed.toDouble()); engine.setPitch(parameters.pitch.toDouble())
    }
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = future {
        engine.setRepeat(when (repeatMode) { Player.REPEAT_MODE_ONE -> RepeatMode.ONE; Player.REPEAT_MODE_ALL -> RepeatMode.ALL; else -> RepeatMode.OFF })
    }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> = future {
        items = mediaItems.toList()
        revision++
        if (items.isEmpty()) { engine.pause(); engine.updateQueue(emptyList()); prepared = false }
        else {
            val index = startIndex.takeUnless { it == C.INDEX_UNSET }?.coerceIn(items.indices) ?: 0
            val previousId = engine.status.value.playbackId
            val previousSource = engine.status.value.sourceId
            val selectedSource = items[index].mediaMetadata.extras?.getString("sourceId")
            engine.loadQueue(tracks(), index, startPositionMs.takeUnless { it == C.TIME_UNSET } ?: 0, wantedPlaying && !suppressed)
            awaitSelection(items[index].mediaId, previousId, revision, previousSource, selectedSource)
        }
    }
    private suspend fun updateQueueAfterInsertion() {
        val current = engine.status.value
        val index = items.indexOfFirst { it.mediaId == current.entryId }
        val resumesEndedQueue = current.ended && current.playWhenReady && index >= 0 && index < items.lastIndex
        if (resumesEndedQueue) {
            revision++
            val selected = items[index + 1]
            // Queue exhaustion releases focus and clears the session's play intent.
            requestPlayback()
            engine.loadQueue(tracks(), index + 1, play = wantedPlaying && !suppressed)
            awaitSelection(selected.mediaId, current.playbackId, revision, current.sourceId,
                selected.mediaMetadata.extras?.getString("sourceId"))
        } else engine.updateQueue(tracks())
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = future {
        items = items.toMutableList().apply { addAll(index, mediaItems) }
        updateQueueAfterInsertion()
    }
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = future {
        items = items.toMutableList().apply { val moving = subList(fromIndex, toIndex).toList(); subList(fromIndex, toIndex).clear(); addAll(newIndex, moving) }
        engine.updateQueue(tracks())
    }
    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = future {
        items = items.toMutableList().apply { subList(fromIndex, toIndex).clear(); addAll(fromIndex, mediaItems) }
        updateQueueAfterInsertion()
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = future {
        items = items.toMutableList().apply { subList(fromIndex, toIndex).clear() }
        engine.updateQueue(tracks())
        if (items.isEmpty()) { pausePlayback(); prepared = false }
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> = future {
        val index = mediaItemIndex.coerceIn(items.indices)
        val position = positionMs.takeUnless { it == C.TIME_UNSET } ?: 0
        if (items[index].mediaId == engine.status.value.entryId) engine.seek(position)
        else {
            revision++
            val previousId = engine.status.value.playbackId
            engine.loadQueue(tracks(), index, position, wantedPlaying && !suppressed)
            awaitSelection(items[index].mediaId, previousId, revision)
        }
    }
    override fun handleRelease(): ListenableFuture<*> {
        abandonFocus()
        context.unregisterReceiver(noisy)
        engine.close()
        scope.cancel()
        return com.google.common.util.concurrent.Futures.immediateVoidFuture()
    }
}
