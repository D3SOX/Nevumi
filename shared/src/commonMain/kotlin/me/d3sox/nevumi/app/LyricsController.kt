package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.lyrics.Lyrics
import me.d3sox.nevumi.lyrics.LyricsLibrary
import me.d3sox.nevumi.lyrics.LyricsSource
import me.d3sox.nevumi.settings.MusicSettings

sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Available(val lyrics: Lyrics) : LyricsState
}

/** Finds the lyrics of the playing song while something shows them, without ever holding the operation lock. */
class LyricsController internal constructor(private val context: ControllerContext, private val library: LyricsLibrary?) {
    private data class Request(val track: Track?, val online: Set<LyricsSource>, val refresh: Int)
    private val refreshes = MutableStateFlow(0)
    private var last: Pair<Request, LyricsState>? = null
    val available: Boolean get() = library != null

    @OptIn(ExperimentalCoroutinesApi::class)
    val lyrics: StateFlow<LyricsState> = context.state
        // Position updates arrive often, so the track is only looked up again when the playing song or the library changes.
        .distinctUntilChanged { old, new -> old.playback.trackId == new.playback.trackId && old.tracks === new.tracks &&
            old.preferences.onlineLyrics == new.preferences.onlineLyrics }
        .map { state -> state.playback.trackId?.let { id -> state.tracks.firstOrNull { it.id == id } } to state.preferences.onlineLyrics }
        .distinctUntilChanged { (oldTrack, oldOnline), (newTrack, newOnline) -> oldOnline == newOnline && oldTrack?.id == newTrack?.id &&
            oldTrack?.metadata == newTrack?.metadata && oldTrack?.sources == newTrack?.sources }
        .combine(refreshes) { (track, online), refresh -> Request(track, online, refresh) }
        .transformLatest { request ->
            last?.takeIf { it.first == request }?.let { emit(it.second); return@transformLatest }
            val track = request.track
            if (track == null || library == null) { emit(LyricsState.None); return@transformLatest }
            emit(LyricsState.Loading)
            val found = try { library.lyrics(track, request.online) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val result = found?.let(LyricsState::Available) ?: LyricsState.None
            last = request to result
            emit(result)
        }
        .stateIn(context.scope, SharingStarted.WhileSubscribed(), LyricsState.Loading)

    /** Looks for the playing song's lyrics again, for example after they were added to its file. */
    fun refresh() = refreshes.update { it + 1 }

    private val MusicSettings.onlineLyrics: Set<LyricsSource> get() = when {
        !fetchLyricsOnline -> emptySet()
        fetchLyricsFromNetEase -> setOf(LyricsSource.LRCLIB, LyricsSource.NETEASE)
        else -> setOf(LyricsSource.LRCLIB)
    }
}
