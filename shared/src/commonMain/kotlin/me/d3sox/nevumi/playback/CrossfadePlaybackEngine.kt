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

/**
 * Mixes two decoders for crossfades. [soundEdges] reads where a song's sound starts and ends from analysis that already ran,
 * or returns null, so a crossfade ends where the outgoing song falls silent and the incoming song starts at its first sound.
 */
class CrossfadePlaybackEngine(private val soundEdges: suspend (PlayableTrack) -> SoundEdges? = { null },
    private val factory: () -> PlaybackEngine) : PlaybackEngine {
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
    private var prepared: PlayableTrack? = null
    /** Where the standby decoder was asked to start [prepared]. */
    private var preparedStartMs = 0L
    private class EdgeLookup(val edges: SoundEdges?, val atMs: Long)
    private val edgeLookups = mutableMapOf<PlayableTrack, EdgeLookup>()
    private var selectedEntry: String? = null
    /** Android's decoder reports a load only once its main thread runs it, so until then the worker uses the loaded entry. */
    private var loadingEntry: String? = null
    private data class PendingLoad(val track: PlayableTrack, val positionMs: Long)
    // Set by callers and cleared by the worker once the decoder reports the requested entry.
    @kotlin.concurrent.Volatile private var pendingLoad: PendingLoad? = null
    private var crossfadeMs = 0L
    /** Bit-perfect output plays one decoder at a time, so it keeps the chosen crossfade for later and plays without it. */
    private val activeCrossfadeMs get() = if (bitPerfect) 0L else crossfadeMs
    private var gapless = true
    private var masterVolume = 1.0
    private var speed = 1.0
    private var pitch = 1.0
    private var effects = AudioEffects()
    private var replayGain = ReplayGain()
    private var bitPerfect = false
    private var playPauseFade = false
    private var skipSilence = false
    /** The play/pause fade's share of the volume: 0 while paused with the fade on, otherwise 1 once a fade-in finishes. */
    private var transportGain = 1.0
    /** A pause with the fade on waits until the fade-out ends before it pauses the decoders. */
    private var pausePending = false
    /** The song a pending pause fades out; a gapless queue that moves on meanwhile pauses at once instead. */
    private var pausingEntry: String? = null
    /** Whether the worker last told the decoders to play; callers change [requestedPlaying] before their command runs. */
    private var decodersPlaying = false
    private var repeatMode = RepeatMode.OFF
    private var repeatPlays: Int? = null
    /** See [setEndAfterCurrent]: the decoder gets no later song and no repeat, while the status keeps the listener's repeat. */
    private var endAfterCurrent = false
    private var fadeElapsedMs = 0.0
    private var fadeDurationMs = 0.0
    private val clock = TimeSource.Monotonic.markNow()
    private var lastTickMs = 0L
    // Starts before the recent-command window; Long.MIN_VALUE would overflow the subtraction and keep the fast cadence.
    private var commandedAtMs = -COMMAND_CADENCE_MS
    /** Fades and loads need the fast cadence; a paused or idle player only has to notice commands, which wake it at once. */
    private fun tickDelayMs(): Long = if (requestedPlaying.value || pausePending || outgoing != null || pendingLoad != null || active.status.value.playing ||
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
            applyCrossfade()
        }
    }

    /** Gives the playing decoder the whole queue when it plays without crossfade, and only the current song when it crossfades. */
    private suspend fun applyCrossfade() {
        val index = currentIndex()
        if (index >= 0) {
            // A native queue may have moved on without the worker, so a single song becomes the selected one again.
            if (!nativeQueue()) selectedEntry = tracks[index].entryId
            active.updateQueue(decoderQueue(index))
        }
        applyRepeat()
    }

    /** The songs the playing decoder holds when the song at [index] plays. */
    private fun decoderQueue(index: Int) = when {
        !nativeQueue() -> listOf(tracks[index])
        endAfterCurrent -> tracks.subList(0, index + 1).toList()
        else -> tracks
    }

    private fun nativeQueue() = activeCrossfadeMs == 0L && gapless

    override fun setGaplessPlayback(enabled: Boolean) = command {
        if (gapless != enabled) {
            gapless = enabled
            if (activeCrossfadeMs > 0) return@command
            cancelFade()
            val index = currentIndex()
            if (index >= 0) {
                selectedEntry = tracks[index].entryId
                active.updateQueue(decoderQueue(index))
            }
            applyRepeat()
        }
    }

    override fun setReplayGain(replayGain: ReplayGain) {
        replayGain.validate()
        command { this.replayGain = replayGain; engines().forEach { it.setReplayGain(replayGain) } }
    }

    override fun setBitPerfectOutput(enabled: Boolean) = command {
        bitPerfect = enabled
        if (enabled) {
            // Android reserves a bit-perfect mixer for the app, not for one decoder, so a waiting decoder must not claim it.
            cancelFade()
            standby?.close()
            standby = null
            prepared = null
            speed = 1.0
            pitch = 1.0
            engines().forEach { it.setSpeed(1.0); it.setPitch(1.0) }
        }
        engines().forEach { it.setBitPerfectOutput(enabled) }
        if (crossfadeMs > 0) applyCrossfade()
    }

    override fun setPlayPauseFade(enabled: Boolean) = command {
        if (playPauseFade == enabled) return@command
        if (pausePending) pauseDecoders()
        playPauseFade = enabled
        // Paused with the fade on, the next play starts silent; with it off, nothing lowers the volume.
        transportGain = if (enabled && !decodersPlaying) 0.0 else 1.0
        applyGains()
    }

    override fun setSkipSilence(enabled: Boolean) = command {
        // A decoder that rejects it keeps the previous state, so the standby player is never prepared with a failing setting.
        active.setSkipSilence(enabled)
        skipSilence = enabled
        engines().filter { it !== active }.forEach { it.setSkipSilence(enabled) }
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
            // Another song replaces one that is fading out; one loaded paused starts silent, ready to fade in.
            pausePending = false
            // A song that starts while nothing is heard, such as after the queue ended, fades in like a resume.
            val audible = active.status.value.playing || outgoing?.status?.value?.playing == true
            if (playPauseFade && (!play || !audible)) transportGain = 0.0
            decodersPlaying = play
            cancelFade()
            this.tracks = tracks.toList()
            selectedEntry = tracks[startIndex].entryId
            loadingEntry = selectedEntry
            prepared = null
            standby?.pause()
            applyRepeat()
            try {
                active.loadQueue(decoderQueue(startIndex), if (nativeQueue()) startIndex else 0, positionMs, play)
            } catch (failure: Exception) {
                // The decoder never adopts the entry, so stop reporting it as loading and let the failure surface.
                if (pendingLoad === pending) pendingLoad = null
                loadingEntry = null
                throw failure
            }
        }
    }

    override fun updateQueue(tracks: List<PlayableTrack>) = command {
        this.tracks = tracks.toList()
        if (outgoing != null && tracks.none { it.entryId == outgoing?.status?.value?.entryId }) cancelFade()
        // A removed song may never be reported, and waiting for it would stop crossfades until the next load. Clearing it first
        // keeps the song the decoder still plays, which also advances to the next song again when it ends.
        if (tracks.none { it.entryId == loadingEntry }) {
            loadingEntry = null
            selectedEntry = active.status.value.entryId ?: selectedEntry
        }
        val index = currentIndex()
        if (index >= 0) active.updateQueue(decoderQueue(index))
        else active.updateQueue(emptyList())
        // A finished download or a new server route changes the next song's source, so the standby player loads it again.
        if (prepared != nextTrack()) { standby?.pause(); prepared = null }
        applyRepeat()
    }

    override fun play() {
        requestedPlaying.value = true
        mutableStatus.update { it.copy(playWhenReady = true) }
        // A play during the fade-out turns it around; the tick fades in from the current volume once audio runs.
        command { pausePending = false; decodersPlaying = true; active.play(); outgoing?.play() }
    }

    override fun pause() = pause(fade = true)

    /** Pauses; without [fade], at once even with the play/pause fade on, as when headphones are unplugged. */
    fun pause(fade: Boolean) {
        requestedPlaying.value = false
        mutableAudioLevel.value = 0f
        mutableStatus.update { it.copy(playing = false, playWhenReady = false,
            fadingOut = it.fadingOut?.copy(playing = false, playWhenReady = false)) }
        command {
            val audible = active.status.value.playing || outgoing?.status?.value?.playing == true
            // A song that would end during the fade pauses at once, so a gapless queue never starts the next one meanwhile.
            val current = active.status.value
            val endsDuringFade = current.durationMs > 0 && (current.durationMs - current.positionMs) / speed < PLAY_PAUSE_FADE_MS + 100
            if (fade && playPauseFade && audible && transportGain > 0 && !endsDuringFade) {
                pausePending = true
                pausingEntry = active.status.value.entryId
            } else pauseDecoders()
        }
    }

    private fun pauseDecoders() {
        pausePending = false
        decodersPlaying = false
        active.pause()
        outgoing?.pause()
        if (playPauseFade) { transportGain = 0.0; applyGains() }
    }

    override fun seek(positionMs: Long) = command {
        // Seeking while a pause fades out finishes the pause first, so the new position is not heard faintly.
        if (pausePending) pauseDecoders()
        cancelFade()
        standby?.pause()
        prepared = null
        active.seek(positionMs)
    }

    override fun resume(entryId: String, positionMs: Long) = command {
        if ((loadingEntry ?: active.status.value.entryId) != entryId) return@command
        if (pausePending) pauseDecoders()
        // During a crossfade the song fading in is the active one, so it moves on while the previous song still fades out.
        if (outgoing == null) { standby?.pause(); prepared = null }
        active.seek(positionMs)
    }

    override fun setVolume(volume: Double) {
        require(volume.isFinite()) { "Volume must be finite" }
        command { masterVolume = volume.coerceIn(0.0, 1.0); applyGains() }
    }

    override fun setSpeed(speed: Double) {
        require(speed in 0.5..2.0) { "Playback speed must be between 0.5 and 2" }
        // A headset or watch reaches the engine without the app's settings, so bit-perfect output keeps the neutral speed here.
        command { if (!bitPerfect) { this.speed = speed; engines().forEach { it.setSpeed(speed) } } }
    }

    override fun setPitch(pitch: Double) {
        require(pitch in 0.5..2.0) { "Playback pitch must be between 0.5 and 2" }
        command { if (!bitPerfect) { this.pitch = pitch; engines().forEach { it.setPitch(pitch) } } }
    }

    override suspend fun setAudioEffects(effects: AudioEffects) {
        effects.validate()
        // Without a parent, so a rejected filter fails only this call; a stopped worker drops the command, so it fails the wait.
        val completed = CompletableDeferred<Unit>()
        val stopped = worker.invokeOnCompletion { completed.completeExceptionally(IllegalStateException("The audio player is closed")) }
        try {
            command {
                try {
                    engines().forEach { it.setAudioEffects(effects) }
                    this.effects = effects
                    completed.complete(Unit)
                } catch (failure: Exception) { completed.completeExceptionally(failure) }
            }
            completed.await()
        } finally { stopped.dispose() }
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
        val nativeMode = when {
            endAfterCurrent -> RepeatMode.OFF
            !nativeQueue() && repeatMode == RepeatMode.ALL -> if (tracks.size > 1) RepeatMode.OFF else RepeatMode.ONE
            else -> repeatMode
        }
        val nativePlays = repeatPlays.takeIf { !endAfterCurrent }
        active.setRepeat(nativeMode, nativePlays)
        // A timeout is a CancellationException, which would stop the worker; a slow decoder only delays the repeat state.
        withTimeoutOrNull(5_000) { active.status.first { it.repeatMode == nativeMode && it.repeatPlays == nativePlays } }
    }

    override fun setEndAfterCurrent(enabled: Boolean) = command {
        if (endAfterCurrent == enabled) return@command
        endAfterCurrent = enabled
        // A crossfade into the next song may already run; the song fading in is then the one to end after.
        val index = currentIndex()
        if (index >= 0) active.updateQueue(decoderQueue(index))
        if (enabled) { standby?.pause(); prepared = null }
        applyRepeat()
    }

    private fun currentIndex(): Int = tracks.indexOfFirst { it.entryId == (loadingEntry ?: active.status.value.entryId ?: selectedEntry) }

    private fun loadNext(next: PlayableTrack) {
        val previous = selectedEntry
        selectedEntry = next.entryId
        loadingEntry = next.entryId
        // The decoder keeps the old song when it cannot load, so a later tick can try again.
        try { active.load(next) } catch (failure: Exception) { selectedEntry = previous; loadingEntry = null; throw failure }
    }

    private fun nextTrack(): PlayableTrack? {
        if (repeatMode == RepeatMode.ONE || endAfterCurrent) return null
        val index = currentIndex()
        if (index < 0) return null
        return tracks.getOrNull(index + 1) ?: tracks.firstOrNull().takeIf { repeatMode == RepeatMode.ALL && tracks.size > 1 }
    }

    private fun engines() = listOfNotNull(active, standby, outgoing).distinct()

    private suspend fun tick() {
        val now = clock.elapsedNow().inWholeMilliseconds
        val elapsed = (now - lastTickMs).coerceAtLeast(0)
        lastTickMs = now
        if (playPauseFade) {
            // A late tick must not skip most of the fade.
            val step = elapsed.coerceAtMost(50).toDouble() / PLAY_PAUSE_FADE_MS
            if (pausePending) {
                transportGain = (transportGain - step).coerceAtLeast(0.0)
                if (transportGain == 0.0 || active.status.value.entryId != pausingEntry) pauseDecoders() else applyGains()
            } else if (requestedPlaying.value && transportGain < 1.0 &&
                (active.status.value.playing || outgoing?.status?.value?.playing == true)) {
                // Only while audio runs: a stream that is still opening would otherwise start at full volume.
                transportGain = (transportGain + step).coerceAtMost(1.0)
                applyGains()
            }
        }
        val current = active.status.value
        if (current.entryId == loadingEntry) loadingEntry = null
        // Ending after the current song, the decoder repeats nothing, so its repeat says nothing about the listener's.
        if (!endAfterCurrent) {
            if (repeatPlays != null && current.repeatPlays == null && current.repeatMode == RepeatMode.OFF && current.durationMs > 0) {
                repeatPlays = null
                repeatMode = RepeatMode.OFF
            } else if (repeatPlays != null) repeatPlays = current.repeatPlays
        }
        if (activeCrossfadeMs > 0 && current.error == null) {
            if (outgoing != null) {
                if (requestedPlaying.value && current.playing) fadeElapsedMs += elapsed
                applyGains()
                // During a pause's fade-out, ending the crossfade would raise the incoming song to the full volume; it ends after the pause.
                if (!pausePending && (fadeElapsedMs >= fadeDurationMs || outgoing?.status?.value?.ended == true)) cancelFade()
            } else if (loadingEntry == null) {
                // The old song's ended state would otherwise advance past the requested song.
                val next = nextTrack()
                val playing = tracks.getOrNull(currentIndex())
                edgeLookups.keys.retainAll { it == playing || it == next }
                val ending = playing?.let { edges(it) }?.takeIf { current.durationMs > 0 && it.startMs < current.durationMs }
                val nextStartMs = next?.let { edges(it) }?.startMs ?: 0
                if (next != null && current.durationMs > 0 && (prepared != next || preparedStartMs != nextStartMs)) {
                    val waiting = standby ?: factory().also { standby = it }
                    waiting.setAudioLevelEnabled(audioLevelEnabled)
                    waiting.setVolume(0.0)
                    waiting.setSpeed(speed)
                    waiting.setPitch(pitch)
                    waiting.setAudioEffects(effects)
                    waiting.setReplayGain(replayGain)
                    waiting.setBitPerfectOutput(bitPerfect)
                    waiting.setSkipSilence(skipSilence)
                    waiting.setRepeat(RepeatMode.OFF)
                    waiting.load(next, nextStartMs, play = false)
                    prepared = next
                    preparedStartMs = nextStartMs
                }
                // The fade ends where the song falls silent, so its trailing silence is never heard.
                val endMs = ending?.endMs?.coerceAtMost(current.durationMs) ?: current.durationMs
                val remaining = (endMs - current.positionMs).coerceAtLeast(0) / speed
                val waiting = standby
                val fadeLimit = minOf(activeCrossfadeMs.toDouble(), current.durationMs / speed / 2,
                    (waiting?.status?.value?.durationMs ?: 0) / speed / 2)
                if (next != null && waiting != null && prepared == next && requestedPlaying.value &&
                    waiting.status.value.entryId == next.entryId && waiting.status.value.durationMs > 0 &&
                    waiting.status.value.error == null && remaining <= fadeLimit &&
                    (current.playing || current.ended)) {
                    outgoing = active
                    active = waiting
                    standby = null
                    selectedEntry = next.entryId
                    prepared = null
                    fadeElapsedMs = 0.0
                    fadeDurationMs = remaining.coerceAtLeast(25.0)
                    active.play()
                    applyGains()
                } else if (current.ended && next != null && requestedPlaying.value) {
                    loadNext(next)
                    prepared = null
                }
            }
        }
        if (!gapless && activeCrossfadeMs == 0L && current.ended && current.entryId == selectedEntry && requestedPlaying.value) {
            nextTrack()?.let(::loadNext)
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
            repeatMode = repeatMode, repeatPlays = repeatPlays, fadingOut = tail, crossfadeDurationMs = activeCrossfadeMs)
    }

    /** Looks a song's edges up once, and again later while its analysis may still be running. */
    private suspend fun edges(track: PlayableTrack): SoundEdges? {
        val now = clock.elapsedNow().inWholeMilliseconds
        edgeLookups[track]?.takeIf { it.edges != null || now - it.atMs < EDGE_RETRY_MS }?.let { return it.edges }
        // A slow lookup only costs this song its edges; the fade timing must not wait for it.
        val found = try { withTimeoutOrNull(EDGE_LOOKUP_TIMEOUT_MS) { soundEdges(track) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        edgeLookups[track] = EdgeLookup(found, now)
        return found
    }

    private fun pendingStatus(current: PlaybackStatus, pending: PendingLoad) = current.copy(trackId = pending.track.id,
        entryId = pending.track.entryId, sourceId = pending.track.sourceId, positionMs = pending.positionMs, durationMs = 0,
        playing = false, ended = false, error = null, playbackId = null, fadingOut = null, audioInfo = null, streamTitle = null)

    private fun applyGains() {
        val volume = masterVolume * transportGain
        if (outgoing == null) active.setVolume(volume)
        else {
            val progress = (fadeElapsedMs / fadeDurationMs.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
            active.setVolume(volume * sin(progress * PI / 2))
            outgoing?.setVolume(volume * cos(progress * PI / 2))
        }
    }

    private fun cancelFade() {
        outgoing?.let { old -> old.pause(); old.setVolume(0.0); standby?.close(); standby = old }
        outgoing = null
        prepared = null
        fadeElapsedMs = 0.0
        active.setVolume(masterVolume * transportGain)
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
/** How often a song without analysis is looked up again, since its waveform may be decoded meanwhile. */
private const val EDGE_RETRY_MS = 10_000L
private const val EDGE_LOOKUP_TIMEOUT_MS = 500L
