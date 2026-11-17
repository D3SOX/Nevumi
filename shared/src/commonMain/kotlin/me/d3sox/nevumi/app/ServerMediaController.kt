package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.NavidromeException
import me.d3sox.nevumi.navidrome.NavidromeRequestException
import me.d3sox.nevumi.navidrome.PodcastChannel
import me.d3sox.nevumi.navidrome.PodcastEpisode
import me.d3sox.nevumi.navidrome.PodcastStatus
import me.d3sox.nevumi.navidrome.RadioStation
import me.d3sox.nevumi.navidrome.ServerRoles
import me.d3sox.nevumi.navidrome.ServerShare
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.queue.QueueOrigin
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_share_link_copied
import me.d3sox.nevumi.resources.podcasts_title
import me.d3sox.nevumi.resources.radio_title
import org.jetbrains.compose.resources.getString

/**
 * What the music server offers besides its catalogue: internet radio, podcasts and share links. Each is null while the server
 * does not offer it, or before it was asked; [roles] say what the account may change.
 */
data class ServerMedia(
    val serverId: String? = null,
    val loading: Boolean = false,
    val stations: List<RadioStation>? = null,
    val podcasts: List<PodcastChannel>? = null,
    val newestEpisodes: List<PodcastEpisode> = emptyList(),
    val shares: List<ServerShare>? = null,
    val roles: ServerRoles = ServerRoles(),
    /** Saved episode positions by stream ID, kept on the server as bookmarks. */
    val bookmarks: Map<String, Long> = emptyMap(),
    val error: String? = null,
) {
    /** A server without stations only offers radio to an account that may add one. */
    val radioAvailable: Boolean get() = stations != null && (stations.isNotEmpty() || roles.admin)
    val podcastsAvailable: Boolean get() = podcasts != null
    val sharingAvailable: Boolean get() = shares != null && roles.share
}

/** Origins of the queues played from the radio and podcast pages on Home, so the player leads back to them. */
internal const val RADIO_ORIGIN = "radio"
internal const val PODCAST_ORIGIN = "podcast:"

/** Loads and changes the server's internet radio stations, podcasts and share links, and plays stations and episodes. */
class ServerMediaController internal constructor(private val context: ControllerContext, private val library: MusicLibrary,
    private val queueController: QueueController, private val playback: PlaybackController, private val artwork: ArtworkLibrary?,
    private val navidrome: NavidromeConnection?) {
    private val mutableMedia = MutableStateFlow(ServerMedia())
    val media: StateFlow<ServerMedia> = mutableMedia.asStateFlow()
    private val loads = Mutex()
    private var podcastPoll: Job? = null

    init {
        if (navidrome != null) {
            context.scope.launch {
                // A new server cancels a load still waiting on the previous one, such as one behind an unreachable address.
                context.state.map { it.server?.serverId }.distinctUntilChanged().collectLatest { serverId ->
                    if (serverId == null) mutableMedia.value = ServerMedia() else load(serverId)
                }
            }
            context.scope.launch { followEpisodePositions() }
        }
    }

    private suspend fun client(): NavidromeClient? = try { navidrome?.client() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private suspend fun requireClient(): NavidromeClient = requireNotNull(client()) { "Connect a music server first" }

    /** Reads an optional list, keeping [previous] when the server could not be reached, which does not mean it lacks the feature. */
    private suspend fun <T> optional(previous: T?, read: suspend () -> T?): Pair<T?, String?> = try { read() to null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { previous to (failure.message ?: "The music server could not be reached") }

    private suspend fun load(serverId: String) = loads.withLock {
        val client = client()?.takeIf { it.serverId == serverId } ?: return@withLock
        val previous = mutableMedia.value.takeIf { it.serverId == serverId } ?: ServerMedia(serverId)
        mutableMedia.value = previous.copy(loading = true)
        val roles = optional(previous.roles) { client.roles() }.first ?: previous.roles
        val (stations, radioError) = optional(previous.stations) { client.radioStations() }
        val (podcasts, podcastError) = optional(previous.podcasts) { client.podcasts() }
        val newest = podcasts?.let { optional(previous.newestEpisodes) { client.newestPodcasts(20, it) }.first }.orEmpty()
        val (shares, shareError) = optional(previous.shares) { client.shares() }
        val bookmarks = if (podcasts != null) optional(previous.bookmarks) { client.bookmarks() }.first.orEmpty() else emptyMap()
        if (context.state.value.server?.serverId != serverId) return@withLock
        mutableMedia.value = ServerMedia(serverId, false, stations, podcasts, newest, shares, roles, bookmarks,
            listOfNotNull(radioError, podcastError, shareError).firstOrNull())
        podcasts?.let { saveChannelCovers(client, it) }
        if (podcasts.orEmpty().any { channel -> channel.episodes.any { it.status == PodcastStatus.DOWNLOADING } }) pollPodcasts()
    }

    /** Asks the server again for its stations, podcasts and share links. */
    fun refresh() = context.action(serialized = false) { context.state.value.server?.serverId?.let { load(it) } }

    /**
     * Runs a change on the server, then reads what it offers again; a failure becomes the app's error, like other actions. A change
     * to an item names [itemServer], the server that listed it, as another server may use the same ID for something else.
     */
    private fun change(itemServer: String? = null, block: suspend (NavidromeClient) -> Unit) = context.action(serialized = false) {
        val client = requireClient()
        // A page or dialog can still show the previous server's items while the connection already moved on.
        require(client.serverId == context.state.value.server?.serverId && (itemServer == null || itemServer == client.serverId)) {
            "The music server changed. Open the page again."
        }
        try { block(client) } finally { load(client.serverId) }
    }

    // Internet radio

    fun addStation(name: String, streamUrl: String, homepageUrl: String) = change { it.createRadioStation(name, streamUrl, homepageUrl) }
    fun updateStation(serverId: String?, id: String, name: String, streamUrl: String, homepageUrl: String) =
        change(requireNotNull(serverId)) { it.updateRadioStation(id, name, streamUrl, homepageUrl) }
    fun deleteStation(serverId: String?, id: String) = change(requireNotNull(serverId)) { it.deleteRadioStation(id) }

    /** Plays [station] with the other stations as the queue, so skipping changes the station. */
    fun playStation(station: RadioStation): Job = context.action(serialized = false) {
        val client = requireClient()
        val stations = mutableMedia.value.stations.orEmpty().ifEmpty { listOf(station) }
        val ids = libraryTracks(stations.map { radioTrack(client.serverId, it) })
        val index = stations.indexOfFirst { it.id == station.id }.coerceAtLeast(0)
        queueController.playTrack(ids[index], ids, index, getString(Res.string.radio_title),
            origin = QueueOrigin(LibraryTab.HOME, RADIO_ORIGIN)).join()
    }

    // The station's site names it under its title, such as in the queue.
    private fun radioTrack(serverId: String, station: RadioStation) = TrackMetadata(station.name,
        station.homepageUrl.ifBlank { station.streamUrl }.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')) to
        TrackSource("radio:$serverId:${station.id}", SourceKind.RADIO, station.streamUrl)

    // Podcasts

    fun subscribe(url: String) = change { client ->
        try { client.subscribePodcast(url) }
        // Servers download the feed while they answer, so a slow feed can outlast the request; the next refresh shows it.
        catch (slow: NavidromeRequestException) { if (!slow.connectionFailure || slow.unreachable) throw slow }
    }
    fun unsubscribe(serverId: String?, channelId: String) = change(requireNotNull(serverId)) { it.deletePodcastChannel(channelId) }
    fun refreshPodcasts() = change { it.refreshPodcasts(); pollPodcasts() }
    fun downloadEpisode(serverId: String?, episodeId: String) = change(requireNotNull(serverId)) { it.downloadPodcastEpisode(episodeId); pollPodcasts() }
    fun deleteEpisode(serverId: String?, episodeId: String) = change(requireNotNull(serverId)) { it.deletePodcastEpisode(episodeId) }

    /** Reads the podcasts again every few seconds while the server downloads episodes or checks feeds, for up to five minutes. */
    private fun pollPodcasts() {
        if (podcastPoll?.isActive == true) return
        podcastPoll = context.scope.launch {
            repeat(60) {
                delay(5_000)
                val serverId = context.state.value.server?.serverId ?: return@launch
                load(serverId)
                if (mutableMedia.value.podcasts.orEmpty().none { channel -> channel.episodes.any { it.status == PodcastStatus.DOWNLOADING } }) return@launch
            }
        }
    }

    /**
     * Plays [episode] with the channel's other downloaded episodes after it, oldest first, from where it was left. An episode the
     * server has not downloaded cannot play yet.
     */
    fun playEpisode(episode: PodcastEpisode): Job = context.action(serialized = false) {
        require(episode.playable) { "Download this episode to the server first" }
        val client = requireClient()
        val channel = mutableMedia.value.podcasts.orEmpty().firstOrNull { it.id == episode.channelId }
        val episodes = (channel?.episodes.orEmpty().filter { it.playable }.ifEmpty { listOf(episode) }).sortedBy { it.publishDate }
        val ids = libraryTracks(episodes.map { episodeTrack(client.serverId, it, channel) })
        val index = episodes.indexOfFirst { it.id == episode.id }.coerceAtLeast(0)
        episode.coverArt?.let { cover -> saveCovers(client, ids, cover) }
        queueController.playTrack(ids[index], ids, index, channel?.title ?: getString(Res.string.podcasts_title),
            origin = QueueOrigin(LibraryTab.HOME, PODCAST_ORIGIN + episode.channelId)).join()
        val resume = mutableMedia.value.bookmarks[episode.streamId] ?: 0
        if (resume > 0 && context.state.value.playback.trackId == ids[index]) playback.seek(resume).join()
    }

    private fun episodeTrack(serverId: String, episode: PodcastEpisode, channel: PodcastChannel?) =
        TrackMetadata(episode.title, episode.artist.ifBlank { channel?.title ?: episode.channelTitle },
            channel?.title ?: episode.channelTitle, durationMs = episode.durationMs) to
            TrackSource("podcast:$serverId:${episode.id}", SourceKind.PODCAST, requireNotNull(episode.streamId))

    /**
     * Keeps each played episode's position as a server bookmark: when it pauses, every half minute while it plays, and when another
     * song replaces it. A finished episode loses its bookmark, so it starts from the beginning next time.
     */
    private suspend fun followEpisodePositions() {
        var previous: PlaybackStatus? = null
        var savedAt = 0L
        val clock = kotlin.time.TimeSource.Monotonic.markNow()
        context.state.map { it.playback }.distinctUntilChanged().collect { current ->
            val last = previous
            previous = current
            if (last == null || last.trackId == null) return@collect
            val changed = current.entryId != last.entryId
            // Reaching an episode through the queue, such as by Next or when the previous one ends, also resumes its bookmark.
            if (changed) resumeBookmark(current)
            val paused = last.playing && !current.playing && !changed
            val now = clock.elapsedNow().inWholeMilliseconds
            val periodic = current.playing && !changed && now - savedAt >= 30_000
            if (!changed && !paused && !periodic && !current.ended) return@collect
            // Ordinary songs reach here every half minute too, so the lookup below runs at most that often for them.
            if (periodic) savedAt = now
            val status = if (changed) last else current
            val track = context.state.value.tracks.firstOrNull { it.id == status.trackId } ?: library.findTrack(status.trackId ?: return@collect)
            val episode = track?.sources?.firstOrNull { it.kind == SourceKind.PODCAST } ?: return@collect
            savedAt = now
            val duration = status.durationMs.takeIf { it > 0 } ?: track.metadata.durationMs
            val finished = status.ended || duration > 0 && status.positionMs >= duration - 15_000
            savePosition(episode, if (finished) null else status.positionMs.takeIf { it >= 5_000 })
        }
    }

    private fun resumeBookmark(status: PlaybackStatus) {
        val entryId = status.entryId ?: return
        val serverId = context.state.value.server?.serverId ?: return
        val track = context.state.value.tracks.firstOrNull { it.id == status.trackId } ?: return
        val episode = track.sources.firstOrNull { it.kind == SourceKind.PODCAST && it.id.startsWith("podcast:$serverId:") } ?: return
        val resume = mutableMedia.value.takeIf { it.serverId == serverId }?.bookmarks?.get(episode.location) ?: return
        if (resume > 0 && status.positionMs < 5_000) playback.seekCurrentEntry(entryId, resume)
    }

    /** Writes only to the server [episode] came from, as another server may use the same stream ID for something else. */
    private suspend fun savePosition(episode: me.d3sox.nevumi.library.TrackSource, positionMs: Long?) {
        val streamId = episode.location
        try {
            val client = client()?.takeIf { episode.id.startsWith("podcast:${it.serverId}:") } ?: return
            if (positionMs == null) client.deleteBookmark(streamId) else client.saveBookmark(streamId, positionMs)
            mutableMedia.update { media ->
                media.copy(bookmarks = if (positionMs == null) media.bookmarks - streamId else media.bookmarks + (streamId to positionMs))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        // A position that could not be saved only means the episode starts earlier next time.
        catch (_: Exception) { }
    }

    // Shares

    /**
     * Creates a link to [ids], songs or a single album or playlist on the server, and hands it to [deliver]; when [deliver] says it
     * copied the link, a notice confirms that.
     */
    fun share(ids: List<String>, description: String, deliver: (ServerShare) -> Boolean) = context.action(serialized = false) {
        val client = requireClient()
        val share = try { client.createShare(ids, description) }
            catch (unsupported: NavidromeRequestException) {
                if (unsupported.httpStatus != 501) throw unsupported
                load(client.serverId)
                throw NavidromeRequestException("This music server doesn't allow sharing. Turn on sharing in its settings to create links.")
            }
            catch (refused: NavidromeException) {
                if (refused.code != 50) throw refused
                throw NavidromeRequestException("This account may not share. Ask the server's administrator to allow sharing.")
            }
        mutableMedia.update { it.copy(shares = listOf(share) + it.shares.orEmpty().filterNot { old -> old.id == share.id }) }
        if (deliver(share)) context.confirm(Res.string.notice_share_link_copied)
    }

    fun deleteShare(serverId: String?, id: String) = change(requireNotNull(serverId)) { it.deleteShare(id) }

    /** Confirms that a link went to the clipboard. */
    fun announceCopied() = context.confirm(Res.string.notice_share_link_copied)

    /** The server's IDs of [tracks] on the connected server, in order; songs from elsewhere cannot be shared. */
    fun serverSongIds(tracks: List<Track>): List<String> {
        val serverId = context.state.value.server?.serverId ?: return emptyList()
        return tracks.mapNotNull { track -> track.sources.firstOrNull { it.kind == SourceKind.SERVER && it.id.startsWith("server:$serverId:") }?.location }
    }

    // Shared helpers

    /** The songs that play [items]; the same station or episode keeps its song, which the library views leave out. */
    private suspend fun libraryTracks(items: List<Pair<TrackMetadata, TrackSource>>): List<String> {
        val ids = items.map { (metadata, source) -> library.importTrack(metadata, source).id }
        // The queue looks songs up in the app state, which the library's own update reaches only a moment later.
        context.operations.withLock {
            val all = library.tracks()
            context.update { it.copy(tracks = all) }
        }
        return ids
    }

    private suspend fun saveCovers(client: NavidromeClient, trackIds: List<String>, coverId: String) {
        if (artwork == null) return
        val missing = trackIds.filter { context.state.value.artwork[it] == null }
        if (missing.isEmpty()) return
        try {
            artwork.fetch(missing, client, coverId)
            val paths = artwork.paths(missing)
            context.update { it.copy(artwork = it.artwork + paths) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The episode shows the podcast icon instead. */ }
    }

    private suspend fun saveChannelCovers(client: NavidromeClient, channels: List<PodcastChannel>) {
        for (channel in channels) {
            val cover = channel.coverArt ?: continue
            saveCovers(client, listOf(channelArtworkKey(client.serverId, channel.id)), cover)
        }
    }
}

/** The artwork key of a podcast channel's cover. */
internal fun channelArtworkKey(serverId: String, channelId: String) = "podcast-channel:$serverId:$channelId"
