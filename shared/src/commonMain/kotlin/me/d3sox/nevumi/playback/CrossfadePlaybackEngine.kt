package me.d3sox.nevumi.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeSource

class CrossfadePlaybackEngine(private val factory: () -> PlaybackEngine) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val closing = MutableStateFlow(false)
    private val requestedPlaying = MutableStateFlow(false)
    private val mutableStatus = MutableStateFlow(PlaybackStatus())
    override val status = mutableStatus.asStateFlow()
    private val mutableAudioLevel = MutableStateFlow(0f)
    override val audioLevel = mutableAudioLevel.asStateFlow()
    private var audioLevelEnabled = false
    private var active = factory()
    private var standby: PlaybackEngine? = null
    private var outgoing: PlaybackEngine? = null
    private var tracks = emptyList<PlayableTrack>()
    private var preparedEntry: String? = null
    private var selectedEntry: String? = null
    private data class PendingLoad(val track: PlayableTrack, val positionMs: Long)
    // Set by callers and cleared by the worker once the decoder reports the requested entry.
    @kotlin.concurrent.Volatile private var pendingLoad: PendingLoad? = null
    private var crossfadeMs = 0L
    private var gapless = true
    private var masterVolume = 1.0
    private var speed = 1.0
    private var pitch = 1.0
    private var effects = AudioEffects()
    private var repeatMode = RepeatMode.OFF
    private var repeatPlays: Int? = null
    private var fadeElapsedMs = 0.0
    private var fadeDurationMs = 0.0
    private val clock = TimeSource.Monotonic.markNow()
    private var lastTickMs = 0L
    // Starts before the recent-command window; Long.MIN_VALUE would overflow the subtraction and keep the fast cadence.
    private var commandedAtMs = -COMMAND_CADENCE_MS
    /** Fades and loads need the fast cadence; a paused or idle player only has to notice commands, which wake it at once. */
    private fun tickDelayMs(): Long = if (requestedPlaying.value || outgoing != null || pendingLoad != null || active.status.value.playing ||
        clock.elapsedNow().inWholeMilliseconds - commandedAtMs < COMMAND_CADENCE_MS) 25 else 250
    private val worker = scope.launch {
        try {
            while (isActive) {
                // A select never loses a command that arrives as the wait ends, unlike cancelling a receive.
                @OptIn(ExperimentalCoroutinesApi::class)
                var operation = select<(suspend () -> Unit)?> { commands.onReceive { it }; onTimeout(tickDelayMs()) { null } }
                while (operation != null) {
                    commandedAtMs = clock.elapsedNow().inWholeMilliseconds
                    try { operation() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableStatus.update { it.copy(error = "Could not complete this playback operation") } }
                    operation = commands.tryReceive().getOrNull()
                }
                try { tick() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableStatus.update { it.copy(error = "Could not continue playback") } }
            }
        } finally {
            active.close()
            standby?.close()
            outgoing?.close()
            commands.close()
        }
    }

    private fun command(operation: suspend () -> Unit) {
        check(!closing.value) { "The audio player is closed" }
        check(commands.trySend(operation).isSuccess) { "The audio player is closed" }
    }

    override fun setAudioLevelEnabled(enabled: Boolean) {
        if (closing.value) return
        if (!enabled) mutableAudioLevel.value = 0f
        command { audioLevelEnabled = enabled; engines().forEach { it.setAudioLevelEnabled(enabled) } }
    }

    override fun setCrossfadeDuration(milliseconds: Long) {
        require(milliseconds in 0..12_000) { "Crossfade must be between 0 and 12 seconds" }
        command {
            cancelFade()
            crossfadeMs = milliseconds
            val index = currentIndex()
            if (index >= 0) active.updateQueue(if (nativeQueue()) tracks else listOf(tracks[index]))
            applyRepeat()
        }
    }

    private fun nativeQueue() = crossfadeMs == 0L && gapless

    override fun setGaplessPlayback(enabled: Boolean) = command {
        if (gapless != enabled) {
            gapless = enabled
            if (crossfadeMs > 0) return@command
            cancelFade()
            val index = currentIndex()
            if (index >= 0) {
                selectedEntry = tracks[index].entryId
                active.updateQueue(if (nativeQueue()) tracks else listOf(tracks[index]))
            }
            applyRepeat()
        }
    }

    override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)

    override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
        require(startIndex in tracks.indices)
        requestedPlaying.value = play
        val pending = PendingLoad(tracks[startIndex], positionMs.coerceAtLeast(0))
        pendingLoad = pending
        // Report the requested song immediately so observers never see the previous song selected again.
        mutableStatus.update { pendingStatus(it, pending).copy(playWhenReady = play) }
        command {
            cancelFade()
            this.tracks = tracks.toList()
            selectedEntry = tracks[startIndex].entryId
            preparedEntry = null
            standby?.pause()
            applyRepeat()
            try {
                active.loadQueue(if (nativeQueue()) tracks else listOf(tracks[startIndex]),
                    if (nativeQueue()) startIndex else 0, positionMs, play)
            } catch (failure: Exception) {
                // The decoder never adopts the entry, so stop reporting it as loading and let the failure surface.
                if (pendingLoad === pending) pendingLoad = null
                throw failure
            }
        }
    }

    override fun updateQueue(tracks: List<PlayableTrack>) = command {
        this.tracks = tracks.toList()
        if (outgoing != null && tracks.none { it.entryId == outgoing?.status?.value?.entryId }) cancelFade()
        val index = currentIndex()
        if (index >= 0) active.updateQueue(if (nativeQueue()) tracks else listOf(tracks[index]))
        else active.updateQueue(emptyList())
        if (preparedEntry != nextTrack()?.entryId) { standby?.pause(); preparedEntry = null }
        applyRepeat()
    }

    override fun play() {
        requestedPlaying.value = true
        mutableStatus.update { it.copy(playWhenReady = true) }
        command { active.play(); outgoing?.play() }
    }

    override fun pause() {
        requestedPlaying.value = false
        mutableAudioLevel.value = 0f
        mutableStatus.update { it.copy(playing = false, playWhenReady = false,
            fadingOut = it.fadingOut?.copy(playing = false, playWhenReady = false)) }
        command { active.pause(); outgoing?.pause() }
    }

    override fun seek(positionMs: Long) = command {
        cancelFade()
        standby?.pause()
        preparedEntry = null
        active.seek(positionMs)
    }

    override fun setVolume(volume: Double) {
        require(volume.isFinite()) { "Volume must be finite" }
        command { masterVolume = volume.coerceIn(0.0, 1.0); applyGains() }
    }

    override fun setSpeed(speed: Double) {
        require(speed in 0.5..2.0) { "Playback speed must be between 0.5 and 2" }
        command { this.speed = speed; engines().forEach { it.setSpeed(speed) } }
    }

    override fun setPitch(pitch: Double) {
        require(pitch in 0.5..2.0) { "Playback pitch must be between 0.5 and 2" }
        command { this.pitch = pitch; engines().forEach { it.setPitch(pitch) } }
    }

    override suspend fun setAudioEffects(effects: AudioEffects) {
        effects.validate()
        val completed = CompletableDeferred<Unit>(worker)
        command {
            try {
                engines().forEach { it.setAudioEffects(effects) }
                this.effects = effects
                completed.complete(Unit)
            } catch (failure: Exception) { completed.completeExceptionally(failure) }
        }
        completed.await()
    }

    override fun setRepeat(mode: RepeatMode, playCount: Int?) {
        require(playCount == null || mode == RepeatMode.ONE && playCount > 0) { "A repeat count requires song repeat and at least one play" }
        command {
            if (mode == RepeatMode.ONE) cancelFade()
            repeatMode = mode
            repeatPlays = playCount
            applyRepeat()
        }
    }

    private suspend fun applyRepeat() {
        val nativeMode = if (!nativeQueue() && repeatMode == RepeatMode.ALL) {
            if (tracks.size > 1) RepeatMode.OFF else RepeatMode.ONE
        } else repeatMode
        active.setRepeat(nativeMode, repeatPlays)
        withTimeout(5_000) { active.status.first { it.repeatMode == nativeMode && it.repeatPlays == repeatPlays } }
    }

    private fun currentIndex(): Int = tracks.indexOfFirst { it.entryId == (active.status.value.entryId ?: selectedEntry) }

    private fun nextTrack(): PlayableTrack? {
        if (repeatMode == RepeatMode.ONE) return null
        val index = currentIndex()
        if (index < 0) return null
        return tracks.getOrNull(index + 1) ?: tracks.firstOrNull().takeIf { repeatMode == RepeatMode.ALL && tracks.size > 1 }
    }

    private fun engines() = listOfNotNull(active, standby, outgoing).distinct()

    private suspend fun tick() {
        val now = clock.elapsedNow().inWholeMilliseconds
        val elapsed = (now - lastTickMs).coerceAtLeast(0)
        lastTickMs = now
        val current = active.status.value
        if (repeatPlays != null && current.repeatPlays == null && current.repeatMode == RepeatMode.OFF && current.durationMs > 0) {
            repeatPlays = null
            repeatMode = RepeatMode.OFF
        } else if (repeatPlays != null) repeatPlays = current.repeatPlays
        if (crossfadeMs > 0 && current.error == null) {
            if (outgoing != null) {
                if (requestedPlaying.value && current.playing) fadeElapsedMs += elapsed
                applyGains()
                if (fadeElapsedMs >= fadeDurationMs || outgoing?.status?.value?.ended == true) cancelFade()
            } else {
                val next = nextTrack()
                if (next != null && current.durationMs > 0 && preparedEntry != next.entryId) {
                    val waiting = standby ?: factory().also { standby = it }
                    waiting.setAudioLevelEnabled(audioLevelEnabled)
                    waiting.setVolume(0.0)
                    waiting.setSpeed(speed)
                    waiting.setPitch(pitch)
                    waiting.setAudioEffects(effects)
                    waiting.setRepeat(RepeatMode.OFF)
                    waiting.load(next, play = false)
                    preparedEntry = next.entryId
                }
                val remaining = (current.durationMs - current.positionMs).coerceAtLeast(0) / speed
                val waiting = standby
                val fadeLimit = minOf(crossfadeMs.toDouble(), current.durationMs / speed / 2,
                    (waiting?.status?.value?.durationMs ?: 0) / speed / 2)
                if (next != null && waiting != null && preparedEntry == next.entryId && requestedPlaying.value &&
                    waiting.status.value.entryId == next.entryId && waiting.status.value.durationMs > 0 &&
                    waiting.status.value.error == null && remaining <= fadeLimit &&
                    (current.playing || current.ended)) {
                    outgoing = active
                    active = waiting
                    standby = null
                    selectedEntry = next.entryId
                    preparedEntry = null
                    fadeElapsedMs = 0.0
                    fadeDurationMs = remaining.coerceAtLeast(25.0)
                    active.play()
                    applyGains()
                } else if (current.ended && next != null && requestedPlaying.value) {
                    active.load(next)
                    selectedEntry = next.entryId
                    preparedEntry = null
                }
            }
        }
        if (!gapless && crossfadeMs == 0L && current.ended && current.entryId == selectedEntry && requestedPlaying.value) {
            nextTrack()?.let { next -> selectedEntry = next.entryId; active.load(next) }
        }
        val requested = pendingLoad
        if (requested != null && active.status.value.entryId == requested.track.entryId) pendingLoad = null
        val playback = if (requested != null && active.status.value.entryId != requested.track.entryId) pendingStatus(active.status.value, requested)
            else active.status.value
        val tail = outgoing?.status?.value?.takeIf { !it.ended }
        mutableAudioLevel.value = if (audioLevelEnabled && requestedPlaying.value && playback.playing) {
            val energy = active.audioLevel.value * playback.volume
            val tailEnergy = (outgoing?.audioLevel?.value ?: 0f) * (tail?.volume ?: 0.0)
            sqrt(energy * energy + tailEnergy * tailEnergy).toFloat().coerceIn(0f, 1f)
        } else 0f
        mutableStatus.value = playback.copy(volume = masterVolume, speed = speed, pitch = pitch, audioEffects = effects,
            playWhenReady = requestedPlaying.value, playing = playback.playing && requestedPlaying.value,
            repeatMode = repeatMode, repeatPlays = repeatPlays, fadingOut = tail, crossfadeDurationMs = crossfadeMs)
    }

    private fun pendingStatus(current: PlaybackStatus, pending: PendingLoad) = current.copy(trackId = pending.track.id,
        entryId = pending.track.entryId, sourceId = pending.track.sourceId, positionMs = pending.positionMs, durationMs = 0,
        playing = false, ended = false, error = null, playbackId = null, fadingOut = null, audioInfo = null)

    private fun applyGains() {
        if (outgoing == null) active.setVolume(masterVolume)
        else {
            val progress = (fadeElapsedMs / fadeDurationMs.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            active.setVolume(masterVolume * sin(progress * PI / 2))
            outgoing?.setVolume(masterVolume * cos(progress * PI / 2))
        }
    }

    private fun cancelFade() {
        outgoing?.let { old -> old.pause(); old.setVolume(0.0); standby?.close(); standby = old }
        outgoing = null
        preparedEntry = null
        fadeElapsedMs = 0.0
        active.setVolume(masterVolume)
    }

    override fun close() {
        if (closing.compareAndSet(false, true)) {
            requestedPlaying.value = false
            worker.cancel()
            active.close()
            standby?.close()
            outgoing?.close()
            scope.cancel()
        }
    }
}

/** How long after a command the worker keeps the fast cadence. */
private const val COMMAND_CADENCE_MS = 500L
