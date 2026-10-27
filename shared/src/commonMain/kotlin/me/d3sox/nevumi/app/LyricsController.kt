package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import me.d3sox.nevumi.lyrics.LyricsQuery
import me.d3sox.nevumi.lyrics.LyricsSource
import me.d3sox.nevumi.settings.MusicSettings

sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Available(val lyrics: Lyrics) : LyricsState
}

/** Finds the lyrics of the playing song while something shows them, without ever holding the operation lock. */
class LyricsController internal constructor(private val context: ControllerContext, private val library: LyricsLibrary?) {
    private data class Request(val track: Track?, val online: Set<LyricsSource>, val refresh: Int, val query: LyricsQuery? = null)
    private val refreshes = MutableStateFlow(0)
    // The last search the listener wrote, which only applies while its song is playing and the online services may be asked.
    private val searches = MutableStateFlow<Pair<String, LyricsQuery>?>(null)
    init {
        // A search ends with its song, even while nothing shows the lyrics, so they come from the cache like any others when it plays again.
        context.scope.launch {
            context.state.map { it.playback.trackId }.distinctUntilChanged().collect { id -> searches.update { search -> search?.takeIf { it.first == id } } }
        }
    }
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
        .combine(searches) { request, search ->
            request.copy(query = search?.takeIf { it.first == request.track?.id && request.online.isNotEmpty() }?.second)
        }
        .distinctUntilChanged()
        .transformLatest { request ->
            last?.takeIf { it.first == request }?.let { emit(it.second); return@transformLatest }
            val track = request.track
            if (track == null || library == null) { emit(LyricsState.None); return@transformLatest }
            emit(LyricsState.Loading)
            val found = try { library.lyrics(track, request.online, request.query) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val result = found?.let(LyricsState::Available) ?: LyricsState.None
            last = request to result
            emit(result)
        }
        .stateIn(context.scope, SharingStarted.WhileSubscribed(), LyricsState.Loading)

    /** Looks for the playing song's lyrics again, for example after they were added to its file. */
    fun refresh() = refreshes.update { it + 1 }

    /** What the online services are asked for [track]: the listener's last search for it, otherwise its tags. */
    fun query(track: Track): LyricsQuery = searches.value?.takeIf { it.first == track.id }?.second ?: LyricsQuery(track.metadata)

    /** Asks the online services for [query] in place of the playing [track]'s tags, keeping what they find as its lyrics. */
    fun search(track: Track, query: LyricsQuery) {
        val search = track.id to query
        // Searching for the same text again looks again, as the services may have had no answer before.
        if (searches.value == search) refresh() else searches.value = search
    }

    private val MusicSettings.onlineLyrics: Set<LyricsSource> get() = when {
        !fetchLyricsOnline -> emptySet()
        fetchLyricsFromNetEase -> setOf(LyricsSource.LRCLIB, LyricsSource.NETEASE)
        else -> setOf(LyricsSource.LRCLIB)
    }
}
