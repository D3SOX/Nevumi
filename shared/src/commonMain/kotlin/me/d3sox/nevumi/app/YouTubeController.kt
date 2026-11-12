package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.LocalFolderRules
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_soundcloud_added
import me.d3sox.nevumi.resources.notice_soundcloud_check_tags
import me.d3sox.nevumi.resources.notice_soundcloud_downloading
import me.d3sox.nevumi.resources.notice_soundcloud_failed
import me.d3sox.nevumi.resources.notice_soundcloud_saved_outside
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
                    if (message != null) context.mutableNotices.tryEmit(ActionNotice(message, ActionDestination.DOWNLOADS, arguments = listOf(job.tags.title)))
                }
            }
        } }
        downloads?.let { service -> context.scope.launch {
            service.completed.collect { job ->
                val trackId = job.trackId
                context.mutableNotices.tryEmit(if (trackId != null) ActionNotice(Res.string.notice_soundcloud_added, arguments = listOf(job.tags.title),
                    play = { queueController.playTrack(trackId, listOf(trackId), 0, "YouTube") })
                    else ActionNotice(Res.string.notice_soundcloud_saved_outside, ActionDestination.DOWNLOADS, arguments = listOf(job.tags.title)))
            }
        } }
    }

    private fun source() = requireNotNull(source) { "YouTube is unavailable" }
    private fun downloads() = requireNotNull(downloads) { "YouTube downloads are unavailable" }
    private fun requireEnabled() = check(context.state.value.preferences.youTube) { "Turn on YouTube in Settings first" }

    suspend fun searchYouTube(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation? = null): YouTubePage {
        requireEnabled()
        return source().search(query, kind, continuation)
    }

    suspend fun youTubeArtwork(url: String): ByteArray = source().artwork(url)

    /** Plays [track] on its own, or with [action] adds it to the queue. Its audio is looked up first, so an unavailable video says why. */
    fun playYouTube(track: YouTubeTrack, action: QueueAction? = null) = context.action(serialized = false) {
        requireEnabled()
        source().audio(track.videoId)
        val trackId = libraryTrack(track)
        if (action == null) queueController.playTrack(trackId, listOf(trackId), 0, "YouTube").join()
        else queueController.enqueue(trackId, action).join()
    }

    /** The library song that streams [track], kept out of the library views; the same video always maps to the same song. */
    private suspend fun libraryTrack(track: YouTubeTrack): String {
        val (title, artist) = track.suggestedTags()
        val sourceId = "youtube:${track.videoId}"
        // A video played before keeps its song, whose tags may be richer, such as after linking it to a local copy.
        val song = library.trackForSource(sourceId) ?: library.importTrack(TrackMetadata(title, artist, durationMs = track.durationMs),
            TrackSource(sourceId, SourceKind.YOUTUBE, track.videoId))
        if (artwork != null && context.state.value.artwork[song.id] == null) track.artworkUrl?.let { url ->
            try { artwork.save(song.id, source().artwork(largeArtwork(url))) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The song plays without a cover. */ }
        }
        // The queue looks songs up in the app state, which the library's own update reaches only a moment later.
        context.operations.withLock {
            val tracks = library.tracks()
            context.update { it.copy(tracks = tracks) }
        }
        return song.id
    }

    fun downloadFromYouTube(track: YouTubeTrack, destination: YouTubeDestination, reviewTags: Boolean) = context.action(serialized = false) {
        requireEnabled()
        context.confirm(Res.string.notice_soundcloud_downloading, ActionDestination.DOWNLOADS, arguments = listOf(track.suggestedTags().first))
        downloads().download(track, destination, reviewTags)
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
