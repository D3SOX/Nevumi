package me.d3sox.nevumi.app

import kotlinx.coroutines.launch
import me.d3sox.nevumi.library.LocalFolderRules
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_soundcloud_added
import me.d3sox.nevumi.resources.notice_soundcloud_check_tags
import me.d3sox.nevumi.resources.notice_soundcloud_downloading
import me.d3sox.nevumi.resources.notice_soundcloud_failed
import me.d3sox.nevumi.resources.notice_soundcloud_needs_choice
import me.d3sox.nevumi.resources.notice_soundcloud_saved_outside
import me.d3sox.nevumi.soundcloud.*

/** Searches SoundCloud through sc-gate-dl and follows its downloads until the songs join the library. */
class SoundCloudController internal constructor(private val context: ControllerContext, private val server: ServerController,
    private val libraryController: LibraryController, private val queueController: QueueController,
    private val soundCloud: SoundCloudDownloads?) {
    val soundCloudAvailable: Boolean get() = soundCloud != null
    val soundCloudDeviceDownloads: Boolean get() = soundCloud?.deviceDownloads == true

    init {
        soundCloud?.let { service -> context.scope.launch {
            service.profile.collect { profile -> context.update { it.copy(soundCloud = profile) } }
        } }
        soundCloud?.let { service -> context.scope.launch {
            var previous: Map<String, SoundCloudJobState>? = null
            service.jobs.collect { jobs ->
                if (jobs == null) return@collect
                context.update { it.copy(soundCloudJobs = jobs) }
                // Jobs stored before this launch only announce later changes.
                val before = previous
                previous = jobs.associate { it.id to it.state }
                if (before != null) jobs.filter { before[it.id] != it.state }.forEach { job ->
                    val message = when (job.state) {
                        SoundCloudJobState.NEEDS_LINK, SoundCloudJobState.NEEDS_TRACK -> Res.string.notice_soundcloud_needs_choice
                        SoundCloudJobState.NEEDS_TAGS -> Res.string.notice_soundcloud_check_tags
                        SoundCloudJobState.FAILED, SoundCloudJobState.LOST -> Res.string.notice_soundcloud_failed
                        else -> null
                    }
                    if (message != null) context.mutableNotices.tryEmit(ActionNotice(message, ActionDestination.DOWNLOADS, arguments = listOf(job.track.title)))
                }
            }
        } }
        soundCloud?.let { service -> context.scope.launch {
            service.completed.collect { job ->
                val trackId = job.trackId
                context.mutableNotices.tryEmit(if (trackId != null) ActionNotice(Res.string.notice_soundcloud_added, arguments = listOf(job.track.title),
                    play = { queueController.playTrack(trackId, listOf(trackId), 0, "SoundCloud") })
                    else ActionNotice(Res.string.notice_soundcloud_saved_outside, ActionDestination.DOWNLOADS, arguments = listOf(job.track.title)))
            }
        } }
    }

    private fun service() = requireNotNull(soundCloud) { "SoundCloud downloads are unavailable" }
    private suspend fun reachProfile() { context.state.value.soundCloud?.let { server.scGateAccess(it.baseUrl) } }

    suspend fun testSoundCloud(address: String): ScGateCapabilities {
        server.scGateAccess(address)
        return service().capabilities(address)
    }
    suspend fun connectSoundCloud(address: String, format: ScGateFormat, destination: SoundCloudDestination, browser: ScGateBrowser? = null,
        reviewTags: Boolean = false): SoundCloudProfile {
        server.scGateAccess(address)
        return service().connect(address, format, destination, browser, reviewTags)
    }
    fun updateSoundCloud(format: ScGateFormat, destination: SoundCloudDestination, browser: ScGateBrowser?, reviewTags: Boolean) =
        context.action(serialized = false) { service().update(format, destination, browser, reviewTags) }
    suspend fun disconnectSoundCloud() = service().disconnect()
    suspend fun searchSoundCloud(query: String, offset: Int = 0): SoundCloudPage {
        reachProfile()
        return service().search(query, offset)
    }
    suspend fun soundCloudArtwork(url: String): ByteArray = service().artwork(url)
    /** [keepOffline] also downloads a song added to the music server for offline playback; [reviewTags] defaults to the setting. */
    fun downloadFromSoundCloud(track: SoundCloudTrack, destination: ScGateDestination, keepOffline: Boolean = false,
        reviewTags: Boolean? = null) = context.action(serialized = false) {
        val service = service()
        val profile = requireNotNull(context.state.value.soundCloud) { "Set up SoundCloud downloads in Settings first" }
        if (destination == ScGateDestination.LIBRARY) requireNotNull(context.state.value.server) { "Connect to your music server first" }
        server.scGateAccess(profile.baseUrl)
        context.confirm(Res.string.notice_soundcloud_downloading, ActionDestination.DOWNLOADS, arguments = listOf(track.title))
        service.download(track, destination, keepOffline, reviewTags)
    }
    fun submitSoundCloudLink(id: String, link: String) = context.action(serialized = false) {
        reachProfile()
        service().submitLink(id, link)
    }
    fun useSoundCloudStream(id: String) = context.action(serialized = false) { service().useStream(id) }
    fun chooseSoundCloudTrack(id: String, url: String) = context.action(serialized = false) { service().chooseBandcampTrack(id, url) }
    suspend fun soundCloudCover(id: String, fromFile: Boolean): ByteArray = service().cover(id, fromFile)
    suspend fun finishSoundCloudTags(id: String, choice: ScGateTagChoice) {
        reachProfile()
        service().finishTags(id, choice)
    }
    /** The address of sc-gate-dl's browser viewer, which a browser on this device can open. */
    suspend fun soundCloudBrowserView(id: String): String {
        reachProfile()
        return service().browserView(id)
    }
    fun cancelSoundCloudDownload(id: String) = context.action(serialized = false) { service().cancel(id) }
    fun retrySoundCloudDownload(id: String) = context.action(serialized = false) {
        reachProfile()
        service().retry(id)
    }
    fun dismissSoundCloudDownload(id: String) = context.action(serialized = false) { service().dismiss(id) }
    /** Adds the folder a finished download was saved to, so the song joins the library. */
    fun addSoundCloudFolder(id: String) = context.action(serialized = false) {
        val folder = requireNotNull(context.state.value.soundCloudJobs.firstOrNull { it.id == id }?.excludedFolder) { "The download is no longer listed" }
        val rules = LocalFolderRules(context.state.value.preferences.includeFolders, context.state.value.preferences.excludeFolders)
        // Lifting an excluded parent would add everything else in it too, so leave that choice to Local music settings.
        rules.excludedParent(folder)?.let { parent ->
            error("Your excluded folder $parent also hides $folder. Remove that exclusion in Settings › Local music to add your downloads.")
        }
        val allowed = rules.allowing(folder)
        libraryController.updateLocalFolders(allowed.included, allowed.excluded).join()
        service().importSaved(id)
    }
}
