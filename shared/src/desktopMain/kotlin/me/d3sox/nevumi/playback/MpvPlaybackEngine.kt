package me.d3sox.nevumi.playback

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.Structure
import java.io.File
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid
import kotlin.math.pow

internal interface LibCLocale : Library {
    fun setlocale(category: Int, locale: String): String?
}

private val numericLocaleLock = Any()
private val nativeLocale by lazy { Native.load("c", LibCLocale::class.java) }

internal interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(context: Pointer): Int
    fun mpv_set_option_string(context: Pointer, name: String, value: String): Int
    fun mpv_set_property_string(context: Pointer, name: String, value: String): Int
    fun mpv_get_property(context: Pointer, name: String, format: Int, value: Pointer): Int
    fun mpv_command(context: Pointer, arguments: Pointer): Int
    fun mpv_command_string(context: Pointer, arguments: String): Int
    fun mpv_wait_event(context: Pointer, timeout: Double): Pointer
    fun mpv_terminate_destroy(context: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_get_property_string(context: Pointer, name: String): Pointer?
    fun mpv_free(value: Pointer)
}

@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal class MpvEvent(pointer: Pointer) : Structure(pointer) {
    @JvmField var eventId: Int = 0
    @JvmField var error: Int = 0
    @JvmField var replyUserdata: Long = 0
    @JvmField var data: Pointer? = null
    init { read() }
}

@Structure.FieldOrder("playlistEntryId")
internal class MpvStartFile(pointer: Pointer) : Structure(pointer) {
    @JvmField var playlistEntryId: Long = 0
    init { read() }
}

@Structure.FieldOrder("reason", "error", "playlistEntryId", "playlistInsertId", "playlistInsertEntries")
internal class MpvEndFile(pointer: Pointer) : Structure(pointer) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlistEntryId: Long = 0
    @JvmField var playlistInsertId: Long = 0
    @JvmField var playlistInsertEntries: Int = 0
    init { read() }
}

class MpvPlaybackEngine(audioOutput: String = "auto", pcmOutputFile: String? = null, audioDevice: String? = null) : PlaybackEngine {
    private val native = Native.load("mpv", LibMpv::class.java)
    private val handle = synchronized(numericLocaleLock) {
        // Linux LC_NUMERIC is 1. libmpv requires C numeric parsing, independently of the UI locale.
        requireNotNull(nativeLocale.setlocale(1, "C")) { "Could not configure the audio player's numeric locale" }
        requireNotNull(native.mpv_create()) { "Could not create audio player" }
    }
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(PlaybackStatus())
    override val status = mutableStatus.asStateFlow()
    private val mutableAudioLevel = MutableStateFlow(0f)
    override val audioLevel = mutableAudioLevel.asStateFlow()
    private var audioLevelEnabled = false
    private var closed = false
    private var tracks = emptyList<PlayableTrack>()
    private var pausedSeekPosition: Long? = null
    private data class PendingLoad(val nativeEntryId: Long, val positionMs: Long, val play: Boolean, val entryId: String)
    private data class FailedLoad(val track: PlayableTrack, val error: String, val positionMs: Long, val durationMs: Long)
    private var pendingLoad: PendingLoad? = null
    private var failedLoad: FailedLoad? = null
    private var startedNativeEntryId: Long? = null
    private var playWhenReady = false
    private var playbackId: String? = null
    private var bufferedRepeatId: String? = null
    private var explicitSeek = false
    private var repeatMode = RepeatMode.OFF
    private var repeatPlays: Int? = null
    private var repeatedEntryId: String? = null
    private var audioEffects = AudioEffects()

    init {
        // An unset ao lets mpv choose PipeWire/PulseAudio/ALSA. "auto" is not a driver name.
        if (audioOutput != "auto") checked(native.mpv_set_option_string(handle, "ao", audioOutput))
        mapOf("vid" to "no", "terminal" to "no", "config" to "no",
            "idle" to "yes", "keep-open" to "yes", "hr-seek" to "yes").forEach { (name, value) ->
            checked(native.mpv_set_option_string(handle, name, value))
        }
        if (pcmOutputFile != null) checked(native.mpv_set_option_string(handle, "ao-pcm-file", pcmOutputFile))
        if (audioDevice != null) checked(native.mpv_set_option_string(handle, "audio-device", audioDevice))
        checked(native.mpv_initialize(handle))
        scope.launch {
            while (isActive) {
                var busy = true
                synchronized(lock) {
                    if (!closed) {
                        drainEvents()
                        if (pendingLoad != null) return@synchronized
                        val duration = doubleProperty("duration")
                        val idle = flagProperty("idle-active")
                        val paused = flagProperty("pause")
                        val audioPts = doublePropertyOrNull("audio-pts")
                        // Gapless looping can decode the next pass while the previous pass is still audible.
                        // Its negative audio timestamp describes the old pass's remaining buffered samples.
                        val bufferedRepeat = repeatMode == RepeatMode.ONE && duration > 0 && audioPts != null && audioPts < -0.002
                        if (bufferedRepeat && bufferedRepeatId == null) bufferedRepeatId = Uuid.random().toString()
                        val completedRepeat = !bufferedRepeat && !paused && bufferedRepeatId != null
                        if (completedRepeat) bufferedRepeatId?.let {
                            playbackId = it
                            bufferedRepeatId = null
                            repeatPlays = repeatPlays?.let { remaining -> (remaining - 1).coerceAtLeast(1) }
                        }
                        val position = if (bufferedRepeat) duration + audioPts else doubleProperty("time-pos")
                        val atEnd = flagProperty("eof-reached") && paused &&
                            (playWhenReady || mutableStatus.value.ended) &&
                            (duration <= 0 || pausedSeekPosition == null || pausedSeekPosition!! / 1_000.0 >= duration - 0.002)
                        if (!paused) pausedSeekPosition = null
                        busy = !paused || playWhenReady || System.nanoTime() - commandedAtNanos < 500_000_000L
                        val failure = failedLoad
                        val currentTrack = failure?.track ?: tracks.getOrNull(longProperty("playlist-pos").toInt())
                        if (repeatPlays != null && currentTrack != null) {
                            if (repeatedEntryId == null) repeatedEntryId = currentTrack.entryId
                            if (currentTrack.entryId != repeatedEntryId || atEnd) {
                                setRepeat(RepeatMode.OFF)
                            } else if (completedRepeat && repeatPlays == 1)
                                checked(native.mpv_set_property_string(handle, "loop-file", "no"))
                        }
                        mutableAudioLevel.value = if (audioLevelEnabled && failure == null && !idle && !paused && !atEnd) {
                            // libmpv 0.37 crashes when querying a nested filter metadata key.
                            val decibels = stringProperty("af-metadata/nevumi-level")?.let { metadata ->
                                Json.parseToJsonElement(metadata).jsonObject["lavfi.astats.Overall.RMS_level"]
                                    ?.jsonPrimitive?.content?.toDoubleOrNull()
                            }
                            if (decibels?.isFinite() == true) 10.0.pow(decibels / 20).toFloat().coerceIn(0f, 1f) else 0f
                        } else 0f
                        mutableStatus.value = PlaybackStatus(currentTrack?.id,
                            playing = failure == null && !idle && !paused && !atEnd,
                            positionMs = failure?.positionMs ?: pausedSeekPosition ?: (position * 1_000).toLong().coerceAtLeast(0),
                            durationMs = failure?.durationMs ?: (duration * 1_000).toLong(),
                            ended = failure == null && atEnd, entryId = currentTrack?.entryId, error = failure?.error,
                            volume = doubleProperty("volume") / 100, speed = doubleProperty("speed"),
                            sourceId = currentTrack?.sourceId, playWhenReady = playWhenReady,
                            repeatMode = repeatMode, repeatPlays = repeatPlays, playbackId = playbackId.takeIf { currentTrack != null },
                            pitch = doubleProperty("pitch"), audioEffects = audioEffects,
                            audioInfo = if (failure == null && currentTrack != null) audioFormatName(stringProperty("audio-codec-name"), currentTrack.location)?.let {
                                AudioInfo(it, longProperty("current-tracks/audio/demux-bitrate").takeIf { bits -> bits > 0 }
                                    ?: doubleProperty("audio-bitrate").toLong().takeIf { bits -> bits > 0 },
                                    longProperty("audio-params/samplerate").toInt().takeIf { rate -> rate > 0 })
                            } else null)
                    }
                }
                // A paused player's properties only change through commands, which are applied at once; poll it slowly.
                delay(if (busy) 50 else 250)
            }
        }
    }

    @Volatile private var commandedAtNanos = Long.MIN_VALUE / 2
    private fun commanded() { commandedAtNanos = System.nanoTime() }

    override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)

    override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) = synchronized(lock) {
        check(!closed)
        commanded()
        require(startIndex in tracks.indices)
        val selected = tracks[startIndex]
        val previous = mutableStatus.value
        playbackId = previous.playbackId.takeIf { previous.entryId == selected.entryId && previous.sourceId != selected.sourceId }
            ?: Uuid.random().toString()
        this.tracks = tracks
        playWhenReady = play
        failedLoad = null
        bufferedRepeatId = null
        pausedSeekPosition = null
        checked(native.mpv_set_property_string(handle, "pause", "yes"))
        checked(native.mpv_set_property_string(handle, "playlist-start", startIndex.toString()))
        val playlist = tracks.joinToString("\n", prefix = "memory://#EXTM3U\n", postfix = "\n") { track ->
            val location = when {
                track.location.startsWith("file:") -> "file://${URI(track.location).rawPath}"
                "://" in track.location -> track.location
                else -> "file://${File(track.location).toURI().rawPath}"
            }
            require('\n' !in location && '\r' !in location) { "Invalid audio location" }
            location
        }
        command("loadlist", playlist, "replace")
        pendingLoad = PendingLoad(longProperty("playlist/$startIndex/id"), positionMs.coerceAtLeast(0), play, tracks[startIndex].entryId)
        if (repeatPlays != null) repeatedEntryId = selected.entryId
        mutableStatus.value = PlaybackStatus(selected.id, positionMs = positionMs.coerceAtLeast(0), entryId = selected.entryId,
            sourceId = selected.sourceId, playWhenReady = play, volume = doubleProperty("volume") / 100, speed = doubleProperty("speed"),
            repeatMode = repeatMode, repeatPlays = repeatPlays, playbackId = playbackId, pitch = doubleProperty("pitch"), audioEffects = audioEffects)
    }

    override fun play() = synchronized(lock) {
        check(!closed)
        commanded()
        playWhenReady = true
        pendingLoad = pendingLoad?.copy(play = true)
        if (pendingLoad == null) {
            pausedSeekPosition?.let(::resumeAt) ?: checked(native.mpv_set_property_string(handle, "pause", "no"))
        }
        mutableStatus.update { it.copy(playWhenReady = true) }
    }
    override fun updateQueue(tracks: List<PlayableTrack>) = synchronized(lock) {
        check(!closed)
        val working = this.tracks.toMutableList()
        val currentEntry = working.getOrNull(longProperty("playlist-pos").toInt())?.entryId
        val replacements = tracks.associateBy { it.entryId }
        for (index in working.lastIndex downTo 0) {
            val replacement = replacements[working[index].entryId]
            if (replacement == null || (working[index].entryId != currentEntry && replacement.location != working[index].location)) {
                command("playlist-remove", index.toString())
                working.removeAt(index)
            }
        }
        tracks.forEachIndexed { destination, track ->
            // Entries before the destination are already in their final order.
            var origin = (destination until working.size).firstOrNull { working[it].entryId == track.entryId } ?: -1
            if (origin < 0) {
                command("loadfile", track.location, "append")
                working += track
                origin = working.lastIndex
            }
            if (origin != destination) {
                command("playlist-move", origin.toString(), (if (origin < destination) destination + 1 else destination).toString())
                working.add(destination, working.removeAt(origin))
            }
        }
        this.tracks = tracks.map { updated ->
            val active = working.firstOrNull { it.entryId == currentEntry && it.entryId == updated.entryId }
            if (active != null) updated.copy(location = active.location, sourceId = active.sourceId) else updated
        }
    }
    override fun pause() = synchronized(lock) {
        check(!closed)
        commanded()
        playWhenReady = false
        mutableAudioLevel.value = 0f
        pendingLoad = pendingLoad?.copy(play = false)
        if (pendingLoad == null && !flagProperty("pause") && !mutableStatus.value.ended &&
            (flagProperty("eof-reached") || bufferedRepeatId != null)) {
            val audioPts = doublePropertyOrNull("audio-pts")
            val position = if (bufferedRepeatId != null && audioPts != null && audioPts < 0)
                doubleProperty("duration") + audioPts else audioPts ?: doubleProperty("time-pos")
            pausedSeekPosition = (position * 1_000).toLong().coerceAtLeast(0)
            bufferedRepeatId = null
        }
        checked(native.mpv_set_property_string(handle, "pause", "yes"))
        mutableStatus.update { it.copy(playing = false, playWhenReady = false) }
    }
    override fun seek(positionMs: Long) = synchronized(lock) {
        commanded()
        check(!closed)
        bufferedRepeatId = null
        if (pendingLoad != null) {
            pendingLoad = pendingLoad?.copy(positionMs = positionMs.coerceAtLeast(0))
            return@synchronized
        }
        if (flagProperty("pause")) {
            pausedSeekPosition = positionMs.coerceAtLeast(0)
            if (mutableStatus.value.ended) playWhenReady = false
            mutableStatus.update { it.copy(positionMs = positionMs.coerceAtLeast(0), ended = false, playWhenReady = playWhenReady) }
        } else {
            explicitSeek = true
            command("seek", (positionMs.coerceAtLeast(0) / 1_000.0).toString(), "absolute+exact")
        }
    }
    override fun setVolume(volume: Double) = synchronized(lock) {
        check(!closed)
        require(volume.isFinite()) { "Volume must be finite" }
        checked(native.mpv_set_property_string(handle, "volume", (volume.coerceIn(0.0, 1.0) * 100).toString()))
    }
    override fun setSpeed(speed: Double) = synchronized(lock) {
        check(!closed)
        require(speed in 0.5..2.0) { "Playback speed must be between 0.5 and 2" }
        checked(native.mpv_set_property_string(handle, "speed", speed.toString()))
    }
    override fun setPitch(pitch: Double) = synchronized(lock) {
        check(!closed)
        require(pitch in 0.5..2.0) { "Playback pitch must be between 0.5 and 2" }
        checked(native.mpv_set_property_string(handle, "pitch", pitch.toString()))
    }
    override fun setAudioLevelEnabled(enabled: Boolean) = synchronized(lock) {
        if (!closed && audioLevelEnabled != enabled) {
            audioLevelEnabled = enabled
            mutableAudioLevel.value = 0f
            applyAudioFilters()
        }
    }
    override suspend fun setAudioEffects(effects: AudioEffects) = synchronized(lock) {
        check(!closed)
        effects.validate()
        audioEffects = effects
        applyAudioFilters()
        mutableStatus.update { it.copy(audioEffects = effects) }
    }
    override fun setReplayGain(replayGain: ReplayGain) = synchronized(lock) {
        check(!closed)
        replayGain.validate()
        // mpv reads the tags itself, including from streams, and lowers gains that would clip the stored peak.
        checked(native.mpv_set_property_string(handle, "replaygain", replayGain.mode.name.lowercase().replace("off", "no")))
        checked(native.mpv_set_property_string(handle, "replaygain-preamp", replayGain.preampDb.toString()))
    }
    private fun applyAudioFilters() {
        val effects = audioEffects
        val filters = buildList {
            if (effects.mono) add("pan=stereo|c0<c0+c1+c2+c3+c4+c5+c6+c7|c1<c0+c1+c2+c3+c4+c5+c6+c7")
            if (effects.enabled) {
                effects.bands.filter { it.gainDb != 0.0 }.forEach { band ->
                    val filter = when (band.kind) {
                        EqualizerKind.PEAK -> "equalizer"
                        EqualizerKind.LOW_SHELF -> "bass"
                        EqualizerKind.HIGH_SHELF -> "treble"
                    }
                    add("$filter=f=${band.frequencyHz}:t=q:w=${band.q}:g=${band.gainDb}")
                }
                if (effects.effectivePreampDb != 0.0) add("volume=${effects.effectivePreampDb}dB")
            }
        }
        val chain = buildList {
            if (filters.isNotEmpty()) add("lavfi=[${filters.joinToString(",")}]")
            if (audioLevelEnabled) add("@nevumi-level:astats=metadata=1:reset=1:measure_perchannel=none:measure_overall=RMS_level")
        }.joinToString(",")
        checked(native.mpv_set_property_string(handle, "af", chain))
    }
    override fun setRepeat(mode: RepeatMode, playCount: Int?) = synchronized(lock) {
        check(!closed)
        require(playCount == null || (mode == RepeatMode.ONE && playCount > 0)) { "A repeat count requires song repeat and at least one play" }
        repeatMode = mode
        repeatPlays = playCount
        repeatedEntryId = if (playCount != null) mutableStatus.value.entryId else null
        checked(native.mpv_set_property_string(handle, "loop-file", if (mode == RepeatMode.ONE)
            playCount?.let { if (it == 1) "no" else (it - 1).toString() } ?: "inf" else "no"))
        checked(native.mpv_set_property_string(handle, "loop-playlist", if (mode == RepeatMode.ALL) "inf" else "no"))
        mutableStatus.update { it.copy(repeatMode = mode, repeatPlays = playCount) }
    }

    private fun command(vararg arguments: String) = checked(native.mpv_command(handle, StringArray(arguments)))
    private fun resumeAt(positionMs: Long) {
        explicitSeek = true
        if (repeatMode == RepeatMode.ONE && repeatPlays != null)
            checked(native.mpv_set_property_string(handle, "loop-file", if (repeatPlays == 1) "no" else (repeatPlays!! - 1).toString()))
        // Run both numeric commands together so EOF handling cannot discard a short buffered tail between them.
        checked(native.mpv_command_string(handle, "no-osd set pause no; no-osd seek ${positionMs / 1_000.0} absolute+exact"))
    }
    private fun drainEvents() {
        while (true) {
            val event = MpvEvent(native.mpv_wait_event(handle, 0.0))
            when (event.eventId) {
                0 -> return
                6 -> {
                    startedNativeEntryId = event.data?.let { MpvStartFile(it).playlistEntryId }
                    if (pendingLoad == null) playbackId = Uuid.random().toString()
                }
                7 -> event.data?.let(::MpvEndFile)?.let { end ->
                    if (end.reason == 4) {
                        val pending = pendingLoad
                        val track = if (pending?.nativeEntryId == end.playlistEntryId)
                            tracks.firstOrNull { it.entryId == pending.entryId }
                        else tracks.indices.firstOrNull { longProperty("playlist/$it/id") == end.playlistEntryId }?.let(tracks::get)
                        if (track != null) {
                            if (pending?.nativeEntryId == end.playlistEntryId) pendingLoad = null
                            val previous = mutableStatus.value.takeIf { it.entryId == track.entryId }
                            failedLoad = FailedLoad(track, "Could not play audio: ${native.mpv_error_string(end.error)}",
                                previous?.positionMs ?: pending?.positionMs ?: 0, previous?.durationMs ?: 0)
                            checked(native.mpv_set_property_string(handle, "pause", "yes"))
                        }
                    }
                }
                8 -> pendingLoad?.let { pending ->
                    if (pending.nativeEntryId == startedNativeEntryId) {
                        if (pending.play) {
                            if (pending.positionMs > 0) resumeAt(pending.positionMs)
                            else checked(native.mpv_set_property_string(handle, "pause", "no"))
                        } else {
                            pausedSeekPosition = pending.positionMs
                            checked(native.mpv_set_property_string(handle, "pause", "yes"))
                        }
                        pendingLoad = null
                    }
                }
                21 -> if (!flagProperty("seeking")) explicitSeek = false
            }
        }
    }
    private fun checked(result: Int) { check(result >= 0) { "Audio backend failed ($result)" } }
    private fun stringProperty(name: String): String? {
        val value = native.mpv_get_property_string(handle, name) ?: return null
        return try { value.getString(0) } finally { native.mpv_free(value) }
    }
    private fun doubleProperty(name: String): Double = Memory(8).use { value ->
        if (native.mpv_get_property(handle, name, 5, value) >= 0) value.getDouble(0) else 0.0
    }
    private fun doublePropertyOrNull(name: String): Double? = Memory(8).use { value ->
        if (native.mpv_get_property(handle, name, 5, value) >= 0) value.getDouble(0) else null
    }
    private fun longProperty(name: String): Long = Memory(8).use { value ->
        if (native.mpv_get_property(handle, name, 4, value) >= 0) value.getLong(0) else -1
    }
    private fun flagProperty(name: String): Boolean = Memory(4).use { value ->
        native.mpv_get_property(handle, name, 3, value) >= 0 && value.getInt(0) != 0
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            scope.cancel()
            native.mpv_terminate_destroy(handle)
        }
    }
}
