package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.LocalFolderRules
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.library.youTubeVideoId
import me.d3sox.nevumi.library.inLibrary
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_soundcloud_added
import me.d3sox.nevumi.resources.notice_soundcloud_check_tags
import me.d3sox.nevumi.resources.notice_soundcloud_downloading
import me.d3sox.nevumi.resources.notice_soundcloud_failed
import me.d3sox.nevumi.resources.notice_soundcloud_saved_outside
import me.d3sox.nevumi.resources.notice_youtube_downloading_songs
import me.d3sox.nevumi.soundcloud.ScGateTagChoice
import me.d3sox.nevumi.youtube.*

/** Searches and streams YouTube on this device, and follows its downloads until the songs join the library. */
class YouTubeController internal constructor(private val context: ControllerContext, private val library: MusicLibrary,
    private val libraryController: LibraryController, private val queueController: QueueController, private val artwork: ArtworkLibrary?,
    private val source: YouTubeSource?, private val downloads: YouTubeDownloads?) {
    val youTubeAvailable: Boolean get() = source != null
    val youTubeDeviceDownloads: Boolean get() = downloads?.deviceDownloads == true
    val youTubeServerUploads: Boolean get() = downloads?.serverUploads == true

    init {
        downloads?.let { service -> context.scope.launch {
            var previous: Map<String, YouTubeJobState>? = null
            service.jobs.collect { jobs ->
                if (jobs == null) return@collect
                context.update { it.copy(youTubeJobs = jobs) }
                // Jobs stored before this launch only announce later changes.
                val before = previous
                previous = jobs.associate { it.id to it.state }
                if (before != null) jobs.filter { before[it.id] != it.state }.forEach { job ->
                    val message = when (job.state) {
                        YouTubeJobState.NEEDS_TAGS -> Res.string.notice_soundcloud_check_tags
                        YouTubeJobState.FAILED -> Res.string.notice_soundcloud_failed
                        else -> null
                    }
                    if (message != null) context.mutableNotices.tryEmit(ActionNotice(message, ActionDestination.YOUTUBE_DOWNLOADS, arguments = listOf(job.tags.title)))
                }
            }
        } }
        downloads?.let { service -> context.scope.launch {
            service.completed.collect { job ->
                val trackId = job.trackId
                // A video played before its download streams as a separate song, which the queue and Now Playing still hold.
                // Linking it to the download moves those references to the saved song and its server copy.
                if (trackId != null) library.trackForSource("youtube:${job.track.videoId}")?.takeIf { it.id != trackId && !it.inLibrary }
                    ?.let { libraryController.linkTracks(trackId, it.id).join() }
                context.mutableNotices.tryEmit(if (trackId != null) ActionNotice(Res.string.notice_soundcloud_added, arguments = listOf(job.tags.title),
                    play = { queueController.playTrack(trackId, listOf(trackId), 0, "YouTube") })
                    else ActionNotice(Res.string.notice_soundcloud_saved_outside, ActionDestination.YOUTUBE_DOWNLOADS, arguments = listOf(job.tags.title)))
            }
        } }
    }


    /** Deletes the YouTube songs that nothing refers to any more, such as videos of a list that was queued once, with their covers. */
    internal suspend fun removeUnusedTracks() = library.removeUnusedYouTubeTracks().forEach { artwork?.remove(it) }

    private fun source() = requireNotNull(source) { "YouTube is unavailable" }
    private fun downloads() = requireNotNull(downloads) { "YouTube downloads are unavailable" }
    private fun requireEnabled() = check(context.state.value.preferences.youTube) { "Turn on YouTube in Settings first" }

    suspend fun searchYouTube(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation? = null): YouTubePage {
        requireEnabled()
        return source().search(query, kind, continuation)
    }

    suspend fun youTubeArtwork(url: String): ByteArray = source().artwork(url)

    /** The YouTube Music page of [videoId]'s artist or album, by [kind], or null after showing why it could not be found. */
    suspend fun youTubePage(videoId: String, kind: YouTubeCollectionKind): YouTubeCollection? {
        val name = if (kind == YouTubeCollectionKind.ALBUM) "album" else "artist"
        return try {
            requireEnabled()
            source().page(videoId, kind) ?: throw YouTubeException("YouTube names no $name for this video", retryable = false)
        } catch (error: Exception) {
            rethrowCancellation(error)
            context.update { it.copy(error = error.message ?: "YouTube could not find the $name") }
            null
        }
    }

    suspend fun browseYouTube(link: YouTubeLink): YouTubeBrowsePage {
        requireEnabled()
        return source().browse(link)
    }

    suspend fun browseYouTubeMore(continuation: YouTubeContinuation): YouTubePage {
        requireEnabled()
        return source().browseMore(continuation)
    }

    /** Plays [track] on its own, or with [action] adds it to the queue. Its audio is looked up first, so an unavailable video says why. */
    fun playYouTube(track: YouTubeTrack, action: QueueAction? = null): kotlinx.coroutines.Job {
        // Numbered when tapped, so the order of the taps decides, not the order the lookups finish in.
        val request = if (action == null) ++context.playRequests else 0
        return context.action(serialized = false) {
            requireEnabled()
            source().audio(track.videoId)
            val trackId = libraryTracks(listOf(track)).single()
            saveArtwork(listOf(track), listOf(trackId))
            // Looking a video up takes a moment. The queue checks the request when it plays, so anything chosen before then wins.
            if (action == null) queueController.playTrack(trackId, listOf(trackId), 0, "YouTube", request = request).join()
            else queueController.enqueue(trackId, action).join()
        }
    }

    /** Numbers a play choice when it is made, for a play that starts only after its songs load; any later choice wins over it. */
    fun reservePlayRequest(): Long = ++context.playRequests

    /**
     * Plays [tracks], such as an album, starting at [index], or shuffled from any of them. Only the first song's audio is looked up
     * before it plays; the others are looked up when the player reaches them.
     */
    fun playYouTube(tracks: List<YouTubeTrack>, index: Int, name: String, shuffle: Boolean = false,
        request: Long = reservePlayRequest()): kotlinx.coroutines.Job {
        return context.action(serialized = false) {
            requireEnabled()
            require(tracks.isNotEmpty()) { "There is nothing to play" }
            val ids = libraryTracks(tracks)
            val start = if (shuffle) queueController.shuffleStart(ids) else index.coerceIn(tracks.indices)
            source().audio(tracks[start].videoId)
            saveArtwork(listOf(tracks[start]), listOf(ids[start]))
            queueController.playTrack(ids[start], ids, start, name, shuffleContext = shuffle, request = request).join()
            // The other covers follow while the first song plays.
            saveArtwork(tracks, ids)
        }
    }

    /** Adds [tracks], such as an album, to the queue with [action]; their audio is looked up when the player reaches them. */
    fun enqueueYouTube(tracks: List<YouTubeTrack>, action: QueueAction) = context.action(serialized = false) {
        requireEnabled()
        require(tracks.isNotEmpty()) { "There is nothing to add" }
        val ids = libraryTracks(tracks)
        queueController.enqueue(ids, action).join()
        saveArtwork(tracks, ids)
    }

    /**
     * The songs of the page at [link] that its play button plays, and the page's name. A list that continues, such as a long playlist,
     * gives up to 1,000 songs.
     */
    private suspend fun collectionTracks(collection: YouTubeCollection): Pair<String, List<YouTubeTrack>> {
        val page = source().browse(collection.link)
        val index = page.shelves.indexOfFirst { shelf -> shelf.layout == YouTubeShelfLayout.ROWS && shelf.items.any { it is YouTubeTrack } }
        require(index >= 0) { "There is nothing to play" }
        val items = page.shelves[index].items.toMutableList()
        // Only the last list or grid continues.
        if (index == page.shelves.indexOfLast { it.layout != YouTubeShelfLayout.CAROUSEL }) {
            var next = page.next
            while (next != null && items.size < 1_000) {
                val more = source().browseMore(next)
                val shown = items.map { it.key }.toSet()
                items += more.results.filter { it.key !in shown }
                next = more.next
            }
        }
        val name = if (collection.kind == YouTubeCollectionKind.LIST) collection.title else page.title.ifEmpty { collection.title }
        return name to items.filterIsInstance<YouTubeTrack>().take(1_000)
    }

    /** Plays the songs of [collection], such as an album shown as a card, loading its page first. */
    fun playYouTube(collection: YouTubeCollection, shuffle: Boolean = false): kotlinx.coroutines.Job {
        val request = reservePlayRequest()
        return context.action(serialized = false) {
            requireEnabled()
            val (name, tracks) = collectionTracks(collection)
            playYouTube(tracks, 0, name, shuffle, request).join()
        }
    }

    fun enqueueYouTube(collection: YouTubeCollection, action: QueueAction) = context.action(serialized = false) {
        requireEnabled()
        enqueueYouTube(collectionTracks(collection).second, action).join()
    }

    fun downloadFromYouTube(collection: YouTubeCollection, destination: YouTubeDestination, reviewTags: Boolean) = context.action(serialized = false) {
        requireEnabled()
        val (name, tracks) = collectionTracks(collection)
        downloadFromYouTube(tracks, name, destination, reviewTags).join()
    }

    /** The library songs that stream [tracks], kept out of the library views; the same video always maps to the same song. */
    private suspend fun libraryTracks(tracks: List<YouTubeTrack>): List<String> {
        val ids = tracks.map { track ->
            val (title, artist) = track.suggestedTags()
            val sourceId = "youtube:${track.videoId}"
            // A video played before keeps its song, whose tags may be richer, such as after linking it to a local copy.
            (library.trackForSource(sourceId) ?: library.importTrack(TrackMetadata(title, artist, durationMs = track.durationMs),
                TrackSource(sourceId, SourceKind.YOUTUBE, track.videoId))).id
        }
        // The queue looks songs up in the app state, which the library's own update reaches only a moment later.
        context.operations.withLock {
            val all = library.tracks()
            context.update { it.copy(tracks = all) }
        }
        return ids
    }

    /** Saves the covers of songs that have none yet; songs of one album share a cover, which is fetched once. */
    private suspend fun saveArtwork(tracks: List<YouTubeTrack>, ids: List<String>) {
        if (artwork == null) return
        val fetched = mutableMapOf<String, ByteArray?>()
        tracks.zip(ids).forEach { (track, id) ->
            val url = track.artworkUrl ?: return@forEach
            if (context.state.value.artwork[id] != null) return@forEach
            try {
                val bytes = fetched.getOrPut(url) {
                    try { source().artwork(largeArtwork(url)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                } ?: return@forEach
                artwork.save(id, bytes)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The song plays without a cover. */ }
        }
    }

    fun downloadFromYouTube(track: YouTubeTrack, destination: YouTubeDestination, reviewTags: Boolean) =
        downloadFromYouTube(listOf(track), null, destination, reviewTags)
    /** Downloads each of [tracks], such as an album called [name] or songs picked from a list, with one notice for all of them. */
    fun downloadFromYouTube(tracks: List<YouTubeTrack>, name: String?, destination: YouTubeDestination, reviewTags: Boolean) =
        context.action(serialized = false) {
            requireEnabled()
            require(tracks.isNotEmpty()) { "There is nothing to download" }
            val title = name ?: tracks.singleOrNull()?.suggestedTags()?.first
            if (title != null) context.confirm(Res.string.notice_soundcloud_downloading, ActionDestination.YOUTUBE_DOWNLOADS, arguments = listOf(title))
            else context.confirm(Res.string.notice_youtube_downloading_songs, ActionDestination.YOUTUBE_DOWNLOADS, arguments = listOf(tracks.size))
            tracks.forEach { downloads().download(it, destination, reviewTags) }
        }
    /** Saves a song streamed from YouTube, such as the one playing, with the tags and cover it plays with. */
    fun downloadFromYouTube(trackId: String, destination: YouTubeDestination, reviewTags: Boolean) = context.action(serialized = false) {
        requireEnabled()
        val track = requireNotNull(context.state.value.tracks.firstOrNull { it.id == trackId }) { "The song is no longer in the library" }
        val videoId = requireNotNull(track.youTubeVideoId) { "This song does not stream from YouTube" }
        val cover = artwork?.path(trackId)?.let { path ->
            withContext(Dispatchers.IO) { runCatching { FileSystem.SYSTEM.read(path.toPath()) { readByteArray() } }.getOrNull() }
        }
        // The song already carries the suggested tags, or the user's own edits, so they are kept as they are.
        val video = YouTubeTrack(videoId, track.metadata.title, track.metadata.artist, track.metadata.durationMs, song = true)
        context.confirm(Res.string.notice_soundcloud_downloading, ActionDestination.YOUTUBE_DOWNLOADS, arguments = listOf(video.title))
        downloads().download(video, destination, reviewTags, cover)
    }
    suspend fun youTubeCover(id: String): ByteArray? = downloads().cover(id)
    suspend fun finishYouTubeTags(id: String, choice: ScGateTagChoice) = downloads().finishTags(id, choice)
    fun cancelYouTubeDownload(id: String) = context.action(serialized = false) { downloads().cancel(id) }
    fun retryYouTubeDownload(id: String) = context.action(serialized = false) { downloads().retry(id) }
    fun dismissYouTubeDownload(id: String) = context.action(serialized = false) { downloads().dismiss(id) }
    /** Adds the folder a finished download was saved to, so the song joins the library. */
    fun addYouTubeFolder(id: String) = context.action(serialized = false) {
        val folder = requireNotNull(context.state.value.youTubeJobs.firstOrNull { it.id == id }?.excludedFolder) { "The download is no longer listed" }
        val rules = LocalFolderRules(context.state.value.preferences.includeFolders, context.state.value.preferences.excludeFolders)
        rules.excludedParent(folder)?.let { parent ->
            error("Your excluded folder $parent also hides $folder. Remove that exclusion in Settings › Local music to add your downloads.")
        }
        val allowed = rules.allowing(folder)
        libraryController.updateLocalFolders(allowed.included, allowed.excluded).join()
        downloads().importSaved(id)
    }
}
