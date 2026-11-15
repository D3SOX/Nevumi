package me.d3sox.nevumi.playback

import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.playRequested
import me.d3sox.nevumi.app.rethrowCancellation
import me.d3sox.nevumi.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.freedesktop.dbus.annotations.PropertiesEmitsChangedSignal.EmitChangeSignal
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import org.freedesktop.dbus.messages.DBusSignal

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
@DBusProperty(name = "PlaybackStatus", type = String::class, access = Access.READ)
@DBusProperty(name = "Metadata", type = MprisMetadata::class, access = Access.READ)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "Position", type = Long::class, access = Access.READ, emitChangeSignal = EmitChangeSignal.FALSE)
@DBusProperty(name = "Volume", type = Double::class)
@DBusProperty(name = "Rate", type = Double::class)
@DBusProperty(name = "Shuffle", type = Boolean::class)
@DBusProperty(name = "LoopStatus", type = String::class)
@DBusProperty(name = "MinimumRate", type = Double::class, access = Access.READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = Access.READ)
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun Play()
    fun PlayPause()
    fun Stop()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

interface MprisMetadata : TypeRef<Map<String, Variant<*>>>
interface MprisStrings : TypeRef<List<String>>

@DBusInterfaceName("org.mpris.MediaPlayer2")
@DBusProperty(name = "Identity", type = String::class, access = Access.READ)
@DBusProperty(name = "DesktopEntry", type = String::class, access = Access.READ)
@DBusProperty(name = "CanQuit", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = Access.READ)
@DBusProperty(name = "SupportedUriSchemes", type = MprisStrings::class, access = Access.READ)
@DBusProperty(name = "SupportedMimeTypes", type = MprisStrings::class, access = Access.READ)
interface MprisApplication : DBusInterface {
    fun Raise()
    fun Quit()
}

/** MPRIS media controls; [raise] and [quit], when given, let the desktop show the window and quit Nevumi. */
class LinuxMediaSession(private val controller: MusicController, address: String? = null,
    private val raise: (() -> Unit)? = null, private val quit: (() -> Unit)? = null) :
    MprisPlayer, MprisApplication, Properties, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = MutableStateFlow(false)
    private val connection = (address?.let(DBusConnectionBuilder::forAddress)
        ?: DBusConnectionBuilder.forSessionBus()).withShared(false).build()

    init {
        try {
            connection.exportObject(objectPath, this)
            connection.requestBusName("org.mpris.MediaPlayer2.nevumi")
        } catch (error: Exception) { connection.close(); throw error }
        scope.launch {
            var previous = GetAll("org.mpris.MediaPlayer2.Player").filterKeys { it != "Position" }
            var wasPlaying = controller.state.value.playback.playRequested
            // The library can hold many thousands of songs, so the current one is looked up again only when either changes.
            var lookedUp: Pair<List<Track>, String?>? = null
            var currentTrack: Track? = null
            fun currentTrack(state: AppState): Track? {
                val key = state.tracks to state.playback.trackId
                if (lookedUp?.let { it.first === key.first && it.second == key.second } != true) {
                    lookedUp = key; currentTrack = state.tracks.find { it.id == state.playback.trackId }
                }
                return currentTrack
            }
            // Playback status arrives many times per second; only the published values decide whether anything is sent.
            combine(controller.state, stopped) { state, stopped -> state.playback.let { playback ->
                listOf(playback.trackId, playback.entryId, playback.playRequested, playback.ended, playback.error, playback.durationMs,
                    playback.volume, playback.speed, state.queue.unshuffledEntryIds != null, state.queue.repeatMode,
                    state.queue.currentIndex, state.queue.entries.size, state.artwork[playback.trackId], stopped,
                    // Tag edits change only the track, and Metadata is built from it.
                    currentTrack(state))
            } }.distinctUntilChanged().collect { loggingFailures("MPRIS update") {
                val state = controller.state.value
                if (state.playback.playRequested && !wasPlaying) stopped.value = false
                wasPlaying = state.playback.playRequested
                val current = GetAll("org.mpris.MediaPlayer2.Player")
                val changed = current.filter { (key, value) -> key != "Position" && previous[key] != value }
                previous = current.filterKeys { it != "Position" }
                if (changed.isNotEmpty()) connection.sendMessage(Properties.PropertiesChanged(objectPath,
                    "org.mpris.MediaPlayer2.Player", changed, emptyList()))
            } }
        }
        scope.launch {
            controller.seeks.collect { seek -> loggingFailures("MPRIS seek update") {
                if (controller.state.value.queue.currentEntryId == seek.entryId)
                    connection.sendMessage(MprisPlayer.Seeked(objectPath, seek.positionMs * 1_000))
            } }
        }
        scope.launch {
            // Each repeated pass of a song gets a new playback ID; without the signal, clients count on past the song's end.
            var previous: PlaybackStatus? = null
            controller.state.map { it.playback }.distinctUntilChanged { a, b -> a.entryId == b.entryId && a.playbackId == b.playbackId }
                .collect { playback -> loggingFailures("MPRIS repeat update") {
                    val last = previous
                    previous = playback
                    if (last?.playbackId != null && playback.playbackId != null && last.entryId == playback.entryId)
                        connection.sendMessage(MprisPlayer.Seeked(objectPath, playback.positionMs * 1_000))
                } }
        }
    }

    override fun getObjectPath() = "/org/mpris/MediaPlayer2"
    override fun Next() { controller.skip(1) }
    override fun Previous() {
        // On the first song this still restarts it after three seconds, like the in-app button.
        if (controller.state.value.queue.currentIndex >= 0) controller.skip(-1)
    }
    override fun Pause() { controller.pause() }
    override fun Play() { stopped.value = false; controller.play() }
    override fun PlayPause() { stopped.value = false; controller.togglePlayback() }
    override fun Stop() { stopped.value = true; controller.stop() }
    override fun Seek(offset: Long) {
        val playback = controller.state.value.playback
        playback.entryId?.let { entryId ->
            val destination = (playback.positionMs + offset / 1_000).coerceAtLeast(0)
            if (playback.durationMs > 0 && destination > playback.durationMs) Next()
            else controller.seekCurrentEntry(entryId, destination)
        }
    }
    override fun SetPosition(trackId: DBusPath, position: Long) {
        val entryId = controller.state.value.playback.entryId ?: return
        if (trackId.path == trackPath(entryId) && position >= 0)
            controller.seekCurrentEntry(entryId, position / 1_000)
    }
    override fun Raise() { raise?.invoke() }
    override fun Quit() { quit?.invoke() }

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
        if (interfaceName == "org.mpris.MediaPlayer2") return mapOf(
            "Identity" to Variant("Nevumi"),
            "DesktopEntry" to Variant("me.d3sox.nevumi"),
            "HasTrackList" to Variant(false),
            "CanQuit" to Variant(quit != null),
            "CanRaise" to Variant(raise != null),
            "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
            "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
        )
        if (interfaceName != "org.mpris.MediaPlayer2.Player") throw DBusExecutionException("Unknown interface")
        val state = controller.state.value
        val track = state.tracks.find { it.id == state.playback.trackId }
        val metadata = if (track == null) emptyMap() else mapOf(
            "mpris:trackid" to Variant(DBusPath("/org/mpris/MediaPlayer2/track/" +
                requireNotNull(state.playback.entryId).replace('-', '_'))),
            "xesam:title" to Variant(track.metadata.title),
            "xesam:artist" to Variant(listOf(track.metadata.artist), "as"),
            "xesam:album" to Variant(track.metadata.album),
            "mpris:length" to Variant(state.playback.durationMs * 1_000),
        ) + state.artwork[track.id]?.let { path ->
            mapOf("mpris:artUrl" to Variant(java.nio.file.Path.of(path).toUri().toASCIIString()))
        }.orEmpty()
        return mapOf(
            "Metadata" to Variant(metadata, "a{sv}"),
            "PlaybackStatus" to Variant(if (stopped.value || state.playback.ended || state.playback.error != null) "Stopped" else if (state.playback.playRequested) "Playing" else if (track != null) "Paused" else "Stopped"),
            "Position" to Variant(state.playback.positionMs * 1_000),
            "Volume" to Variant(state.playback.volume),
            "Rate" to Variant(state.playback.speed),
            "Shuffle" to Variant(state.queue.unshuffledEntryIds != null),
            "LoopStatus" to Variant(when (state.queue.repeatMode) {
                RepeatMode.OFF -> "None"
                RepeatMode.ONE -> "Track"
                RepeatMode.ALL -> "Playlist"
            }),
            "MinimumRate" to Variant(0.5),
            "MaximumRate" to Variant(2.0),
            "CanGoNext" to Variant(state.queue.currentIndex >= 0 &&
                (state.queue.currentIndex < state.queue.entries.lastIndex || state.queue.repeatMode == RepeatMode.ALL)),
            // Previous restarts the first song after three seconds, so it always has an effect while a song is current.
            "CanGoPrevious" to Variant(state.queue.currentIndex >= 0),
            "CanPlay" to Variant(track != null),
            "CanPause" to Variant(track != null),
            "CanSeek" to Variant(track != null && state.playback.durationMs > 0),
            "CanControl" to Variant(true),
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A =
        (GetAll(interfaceName)[propertyName] ?: throw DBusExecutionException("Unknown property")) as A

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
        if (interfaceName == "org.mpris.MediaPlayer2.Player" && propertyName == "LoopStatus") {
            val mode = when (value) {
                "None" -> RepeatMode.OFF
                "Track" -> RepeatMode.ONE
                "Playlist" -> RepeatMode.ALL
                else -> throw DBusExecutionException("LoopStatus must be None, Track, or Playlist")
            }
            controller.setQueueRepeat(mode)
            return
        }
        if (interfaceName == "org.mpris.MediaPlayer2.Player" && propertyName == "Shuffle") {
            val enabled = value as? Boolean ?: throw DBusExecutionException("Shuffle must be a boolean")
            controller.setQueueShuffled(enabled)
            return
        }
        if (interfaceName == "org.mpris.MediaPlayer2.Player" && propertyName == "Rate") {
            val speed = value as? Double ?: throw DBusExecutionException("Rate must be a double")
            if (speed == 0.0) Pause()
            else {
                if (speed !in 0.5..2.0) throw DBusExecutionException("Rate must be between 0.5 and 2")
                controller.setSpeed(speed)
            }
            return
        }
        if (interfaceName == "org.mpris.MediaPlayer2.Player" && propertyName == "Volume") {
            val volume = value as? Double ?: throw DBusExecutionException("Volume must be a double")
            if (!volume.isFinite()) throw DBusExecutionException("Volume must be finite")
            controller.setVolume(volume)
            return
        }
        throw DBusExecutionException("Property is read only")
    }

    override fun close() { scope.cancel(); connection.close() }

    private fun trackPath(entryId: String) = "/org/mpris/MediaPlayer2/track/" + entryId.replace('-', '_')
}

/** One failed D-Bus update must not stop the desktop's media controls or tray for the rest of the session. */
internal inline fun loggingFailures(update: String, block: () -> Unit) {
    try { block() } catch (error: Exception) {
        rethrowCancellation(error)
        System.err.println("$update failed: ${error.message}")
    }
}
