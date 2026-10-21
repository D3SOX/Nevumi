package me.d3sox.nevumi.soundcloud

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.downloads.OfflineMusic
import me.d3sox.nevumi.library.LocalSources
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerSong
import me.d3sox.nevumi.navidrome.normalizeServerAddress
import me.d3sox.nevumi.storage.MusicStore
import okio.BufferedSink
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What tapping a SoundCloud search result does. */
@Serializable
enum class SoundCloudDestination { ASK, SERVER, DEVICE }

@Serializable
data class SoundCloudProfile(val baseUrl: String, val format: ScGateFormat = ScGateFormat.MP3_320,
    val destination: SoundCloudDestination = SoundCloudDestination.ASK,
    /** sc-gate-dl can save into the music server's library folder. */
    val libraryDestination: Boolean = false)

@Serializable
enum class SoundCloudJobState { STARTING, WORKING, NEEDS_LINK, NEEDS_TRACK, TRANSFERRING, IMPORTING, COMPLETE, FAILED, LOST, CANCELLED }

@Serializable
data class SoundCloudJob(
    val id: String,
    val track: SoundCloudTrack,
    val destination: ScGateDestination,
    val format: ScGateFormat,
    /** The sc-gate-dl server that knows [remoteId], which stays the same when Settings switch to another server. */
    val baseUrl: String,
    /** The music server whose library a [ScGateDestination.LIBRARY] download goes into. */
    val musicServerId: String? = null,
    val state: SoundCloudJobState,
    val keepOffline: Boolean = false,
    val remoteId: String? = null,
    val message: String = "",
    val percent: Int = 0,
    /** The gate browser waits for someone to solve a captcha or sign in. */
    val attention: Boolean = false,
    val bandcampTracks: List<BandcampChoice> = emptyList(),
    /** The finished file's name on the server. */
    val fileName: String? = null,
    /** Where the file was saved on this device. */
    val location: String? = null,
    val trackId: String? = null,
    /** The device folder the song was saved to while the local folder rules leave it out of the library. */
    val excludedFolder: String? = null,
    val createdAt: Long = 0,
) {
    val active: Boolean get() = state in setOf(SoundCloudJobState.STARTING, SoundCloudJobState.WORKING,
        SoundCloudJobState.TRANSFERRING, SoundCloudJobState.IMPORTING)
    val finished: Boolean get() = state in setOf(SoundCloudJobState.COMPLETE, SoundCloudJobState.FAILED,
        SoundCloudJobState.LOST, SoundCloudJobState.CANCELLED)
}

/** Saves finished downloads on this device. */
interface SoundCloudFiles {
    /** The folder downloads go to, written like a folder picked in Local music. */
    val folder: String
    /** Writes a new file named like [fileName] through [write] and returns its location for the local scanner. */
    suspend fun save(fileName: String, write: suspend (BufferedSink) -> Unit): String
}

internal fun downloadFileName(name: String): String {
    val file = name.substringAfterLast('/').substringAfterLast('\\').trim()
    require(file.isNotEmpty() && file != "." && file != ".." && !file.startsWith(".")) { "sc-gate-dl returned an invalid file name" }
    return file
}

/** Saves downloads into a folder, without replacing a file that already has the same name. */
class DirectorySoundCloudFiles(override val folder: String) : SoundCloudFiles {
    override suspend fun save(fileName: String, write: suspend (BufferedSink) -> Unit): String = withContext(Dispatchers.IO) {
        val fs = FileSystem.SYSTEM
        val root = folder.toPath()
        fs.createDirectories(root)
        val name = downloadFileName(fileName)
        val partial = root / ".$name.${Uuid.random()}.part"
        try {
            val sink = fs.sink(partial).buffer()
            try { write(sink) } finally { sink.close() }
            val base = name.substringBeforeLast('.')
            val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
            val target = generateSequence(1) { it + 1 }.map { if (it == 1) root / name else root / "$base ($it)$extension" }
                .first { !fs.exists(it) }
            fs.atomicMove(partial, target)
            target.toString()
        } finally { fs.delete(partial, mustExist = false) }
    }
}

/**
 * Searches SoundCloud and tracks downloads through an sc-gate-dl server. Jobs survive restarts: their state is stored,
 * active ones are polled, and finished files are indexed on the music server or saved to this device.
 */
class SoundCloudDownloads(private val store: MusicStore, private val http: HttpClient,
    private val route: suspend (String) -> String = { it },
    private val files: SoundCloudFiles? = null,
    private val localSources: LocalSources? = null,
    private val server: suspend () -> NavidromeClient? = { null },
    private val serverChanges: NavidromeChanges? = null,
    private val artwork: ArtworkLibrary? = null,
    private val offline: OfflineMusic? = null,
    private val scheduleWork: ((userInitiated: Boolean) -> Unit)? = null,
    private val pollMillis: Long = 2_000) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val runs = Mutex()
    private val edits = Mutex()
    // Profile writes, so a late settings change cannot bring back a profile that Disconnect removed.
    private val profiles = Mutex()
    // Jobs whose creation request is running in this process.
    private val creating = MutableStateFlow<Set<String>>(emptySet())
    // Consecutive failed polls per job, so a short outage does not end a download.
    private val failures = MutableStateFlow<Map<String, Int>>(emptyMap())
    @kotlin.concurrent.Volatile private var capabilitiesChecked = false
    private val mutableProfile = MutableStateFlow<SoundCloudProfile?>(null)
    val profile = mutableProfile.asStateFlow()
    private val mutableJobs = MutableStateFlow<List<SoundCloudJob>?>(null)
    /** Newest first, or null until the stored jobs are loaded. */
    val jobs = mutableJobs.asStateFlow()
    private val mutableCompleted = MutableSharedFlow<SoundCloudJob>(extraBufferCapacity = 16)
    /** Jobs that just finished adding their song. */
    val completed = mutableCompleted.asSharedFlow()
    val deviceDownloads: Boolean get() = files != null && localSources != null

    init {
        scope.launch {
            store.database.records().observe("soundcloud-settings").distinctUntilChanged().collect {
                mutableProfile.value = store.read("soundcloud:profile")
            }
        }
        scope.launch {
            store.database.records().observe("soundcloud-jobs").distinctUntilChanged().collect {
                mutableJobs.value = store.list<SoundCloudJob>("soundcloud-jobs").sortedByDescending { it.createdAt }
            }
        }
        scope.launch {
            // The app stopped while sc-gate-dl was creating these jobs, so their server IDs are unknown.
            edits.withLock { store.transaction {
                store.list<SoundCloudJob>("soundcloud-jobs").filter { it.state == SoundCloudJobState.STARTING && it.id !in creating.value }.forEach {
                    save(it.copy(state = SoundCloudJobState.FAILED, message = "Interrupted. Retry to start again."))
                }
            } }
            wake.trySend(Unit)
            while (isActive) {
                withTimeoutOrNull(30_000) { wake.receive() }
                try {
                    if (scheduleWork == null) runPending()
                    else if (store.list<SoundCloudJob>("soundcloud-jobs").any { it.active }) scheduleWork.invoke(false)
                } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
            }
        }
    }

    override fun close() { supervisor.cancel() }
    suspend fun closeAndJoin() { close(); supervisor.join() }

    private suspend fun save(job: SoundCloudJob) = store.put("soundcloud-job:${job.id}", "soundcloud-jobs", job)
    private suspend fun job(id: String): SoundCloudJob = requireNotNull(store.read("soundcloud-job:$id")) { "The download is no longer listed" }

    /** Saves [next] only when nobody changed the job since [expected] was read, such as a cancellation during a request. */
    private suspend fun replace(expected: SoundCloudJob, next: SoundCloudJob): Boolean = edits.withLock {
        if (store.read<SoundCloudJob>("soundcloud-job:${expected.id}") != expected) false else { save(next); true }
    }

    private fun schedule() {
        if (scheduleWork == null) wake.trySend(Unit) else scheduleWork.invoke(true)
    }

    private suspend fun client(): ScGateClient {
        val profile = requireNotNull(store.read<SoundCloudProfile>("soundcloud:profile")) { "Set up SoundCloud downloads in Settings first" }
        return ScGateClient(http, profile.baseUrl, route(profile.baseUrl))
    }

    private suspend fun client(job: SoundCloudJob) = ScGateClient(http, job.baseUrl, route(job.baseUrl))

    suspend fun capabilities(baseUrl: String): ScGateCapabilities {
        val address = normalizeServerAddress(baseUrl)
        val capabilities = ScGateClient(http, address, route(address)).capabilities()
        if (!capabilities.search) throw ScGateException("Update sc-gate-dl to search SoundCloud from Nevumi")
        return capabilities
    }

    suspend fun connect(baseUrl: String, format: ScGateFormat, destination: SoundCloudDestination): SoundCloudProfile {
        val address = normalizeServerAddress(baseUrl)
        val profile = SoundCloudProfile(address, format, destination, capabilities(address).libraryDestination)
        profiles.withLock {
            store.put("soundcloud:profile", "soundcloud-settings", profile)
            capabilitiesChecked = true
            mutableProfile.value = profile
        }
        return profile
    }

    /** Does nothing after Disconnect, which may finish first when both are chosen quickly. */
    suspend fun update(format: ScGateFormat, destination: SoundCloudDestination) = profiles.withLock {
        val profile = store.read<SoundCloudProfile>("soundcloud:profile") ?: return@withLock
        store.put("soundcloud:profile", "soundcloud-settings", profile.copy(format = format, destination = destination).also { mutableProfile.value = it })
    }

    suspend fun disconnect() = profiles.withLock {
        store.database.records().delete("soundcloud:profile")
        mutableProfile.value = null
    }

    suspend fun search(query: String, offset: Int = 0): SoundCloudPage {
        val client = client()
        // The server may have gained a library folder since it was set up.
        if (!capabilitiesChecked) {
            val capabilities = client.capabilities()
            if (!capabilities.search) throw ScGateException("Update sc-gate-dl to search SoundCloud from Nevumi")
            profiles.withLock {
                store.read<SoundCloudProfile>("soundcloud:profile")?.takeIf { it.libraryDestination != capabilities.libraryDestination }?.let {
                    store.put("soundcloud:profile", "soundcloud-settings", it.copy(libraryDestination = capabilities.libraryDestination)
                        .also { updated -> mutableProfile.value = updated })
                }
            }
            capabilitiesChecked = true
        }
        return client.search(query, offset)
    }

    suspend fun artwork(url: String): ByteArray = client().artwork(url)

    /** Starts a download, or returns the one already running for the same song and destination. */
    suspend fun download(track: SoundCloudTrack, destination: ScGateDestination, keepOffline: Boolean = false): SoundCloudJob {
        val profile = requireNotNull(store.read<SoundCloudProfile>("soundcloud:profile")) { "Set up SoundCloud downloads in Settings first" }
        val musicServerId = musicServerFor(profile, destination)
        var started = false
        val job = edits.withLock {
            store.list<SoundCloudJob>("soundcloud-jobs").firstOrNull {
                it.track.url == track.url && it.destination == destination && it.baseUrl == profile.baseUrl && it.musicServerId == musicServerId && !it.finished
            }
                // Asking again to keep the song offline adds that to the running download.
                ?.let { running -> if (keepOffline && !running.keepOffline) running.copy(keepOffline = true).also { save(it) } else running }
                ?: SoundCloudJob(Uuid.random().toString(), track, destination, profile.format, profile.baseUrl, musicServerId, SoundCloudJobState.STARTING, keepOffline,
                    message = "Looking for a download", createdAt = Clock.System.now().toEpochMilliseconds()).also {
                    creating.update { ids -> ids + it.id }
                    save(it)
                    started = true
                }
        }
        return if (started) create(job) else job
    }

    /** Checks that [destination] is available and returns the music server a library download goes into. */
    private suspend fun musicServerFor(profile: SoundCloudProfile, destination: ScGateDestination): String? =
        if (destination == ScGateDestination.CLIENT) { check(deviceDownloads) { "Downloads to this device are unavailable" }; null }
        else {
            check(profile.libraryDestination) { "Your sc-gate-dl server has no music library folder" }
            requireNotNull(server()) { "Connect to your music server first" }.serverId
        }

    /** Restarts [job] as a new server job when nobody changed it meanwhile. */
    private suspend fun restart(job: SoundCloudJob) {
        // A new server job goes to the server configured now, which may differ from the one that lost or canceled it.
        val profile = requireNotNull(store.read<SoundCloudProfile>("soundcloud:profile")) { "Set up SoundCloud downloads in Settings first" }
        val restarted = job.copy(state = SoundCloudJobState.STARTING, message = "Looking for a download", remoteId = null, baseUrl = profile.baseUrl,
            musicServerId = musicServerFor(profile, job.destination))
        creating.update { it + job.id }
        if (replace(job, restarted)) create(restarted) else creating.update { it - job.id }
    }

    /** Asks sc-gate-dl to create the job saved as [job]; the caller has marked it as [creating]. */
    private suspend fun create(job: SoundCloudJob): SoundCloudJob {
        // Keeps a keep-offline request made while sc-gate-dl was creating the job.
        suspend fun finish(change: (SoundCloudJob) -> SoundCloudJob) = edits.withLock {
            change(job.copy(keepOffline = store.read<SoundCloudJob>("soundcloud-job:${job.id}")?.keepOffline ?: job.keepOffline)).also { save(it) }
        }
        try {
            val created = client(job).createJob(job.track.url, job.format, job.destination)
            val next = finish {
                it.copy(remoteId = created.jobId, state = if (created.needsGateLink) SoundCloudJobState.NEEDS_LINK else SoundCloudJobState.WORKING,
                    message = if (created.needsGateLink) "No download link found" else created.warning ?: "Starting", percent = 0,
                    attention = false, fileName = null, location = null, trackId = null, excludedFolder = null)
            }
            if (next.active) schedule()
            return next
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { finish { it.copy(state = SoundCloudJobState.FAILED, message = "Interrupted. Retry to start again.") } }
            throw cancelled
        } catch (error: Exception) {
            return finish { it.copy(state = SoundCloudJobState.FAILED, message = error.message ?: "sc-gate-dl could not start the download") }
        } finally { creating.update { it - job.id } }
    }

    /** Continues a job that needs a gate link; the SoundCloud track itself downloads its stream. */
    suspend fun submitLink(id: String, link: String) {
        val job = job(id)
        require(job.state == SoundCloudJobState.NEEDS_LINK) { "This download does not need a link" }
        require(link.isNotBlank()) { "Paste a download link" }
        val client = client(job)
        val remoteId = requireNotNull(job.remoteId)
        client.setGateLink(remoteId, link.trim())
        client.start(remoteId)
        replace(job, job.copy(state = SoundCloudJobState.WORKING, message = "Starting"))
        schedule()
    }

    suspend fun useStream(id: String) = submitLink(id, job(id).track.url)

    suspend fun chooseBandcampTrack(id: String, url: String) {
        val job = job(id)
        require(job.state == SoundCloudJobState.NEEDS_TRACK && job.bandcampTracks.any { it.url == url }) { "Choose one of the album's songs" }
        client(job).chooseBandcampTrack(requireNotNull(job.remoteId), url)
        replace(job, job.copy(state = SoundCloudJobState.WORKING, message = "Downloading", bandcampTracks = emptyList()))
        schedule()
    }

    private val cancellable = setOf(SoundCloudJobState.WORKING, SoundCloudJobState.NEEDS_LINK, SoundCloudJobState.NEEDS_TRACK)

    suspend fun cancel(id: String) {
        val job = job(id)
        require(job.state in cancellable) { "This download can no longer be canceled" }
        try { client(job).cancel(requireNotNull(job.remoteId)) }
        catch (missing: ScGateException) { if (missing.status != 404) throw missing }
        // The job may have finished downloading while the request ran; leave that progress alone.
        edits.withLock {
            job(id).takeIf { it.state in cancellable }?.let { save(it.copy(state = SoundCloudJobState.CANCELLED, message = "Canceled", attention = false)) }
        }
    }

    /** Resumes where the job stopped: indexing or saving a finished file, restarting the server's job, or starting a new one. */
    suspend fun retry(id: String) {
        val job = job(id)
        require(job.state in setOf(SoundCloudJobState.FAILED, SoundCloudJobState.LOST, SoundCloudJobState.CANCELLED)) { "This download is not stopped" }
        failures.update { it - id }
        val remoteId = job.remoteId
        if (job.fileName != null && job.state == SoundCloudJobState.FAILED) {
            val next = if (job.destination == ScGateDestination.LIBRARY || job.location != null) SoundCloudJobState.IMPORTING else SoundCloudJobState.TRANSFERRING
            replace(job, job.copy(state = next, message = "Retrying"))
        // A canceled job may have stopped before it had a download link, which sc-gate-dl cannot resume.
        } else if (remoteId == null || job.state == SoundCloudJobState.LOST || job.state == SoundCloudJobState.CANCELLED) {
            restart(job)
            return
        } else {
            try { client(job).start(remoteId) }
            catch (error: ScGateException) {
                if (error.status == 404) { restart(job); return }
                // A job that is already running or waits for a link reports its real stage on the next poll.
                if (error.status != 400) throw error
            }
            replace(job, job.copy(state = SoundCloudJobState.WORKING, message = "Retrying", attention = false))
        }
        schedule()
    }

    suspend fun dismiss(id: String) = edits.withLock {
        check(store.read<SoundCloudJob>("soundcloud-job:$id")?.active != true) { "Wait for the download to finish" }
        store.database.records().delete("soundcloud-job:$id")
    }

    /** Polls the active jobs until none needs this device anymore. */
    suspend fun runPending() = runs.withLock {
        while (true) {
            val active = store.list<SoundCloudJob>("soundcloud-jobs").filter { it.active && it.state != SoundCloudJobState.STARTING }
            if (active.isEmpty()) return@withLock
            for (job in active) advance(job)
            if (store.list<SoundCloudJob>("soundcloud-jobs").any { it.state == SoundCloudJobState.WORKING }) delay(pollMillis)
        }
    }

    private suspend fun advance(job: SoundCloudJob) {
        try {
            when (job.state) {
                SoundCloudJobState.WORKING -> poll(job)
                SoundCloudJobState.TRANSFERRING -> transfer(job)
                SoundCloudJobState.IMPORTING -> import(job)
                else -> Unit
            }
            failures.update { it - job.id }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: ScGateException) {
            val current = store.read<SoundCloudJob>("soundcloud-job:${job.id}") ?: return
            when {
                error.status == 404 && current.state != SoundCloudJobState.IMPORTING -> replace(current, current.copy(state = SoundCloudJobState.LOST,
                    message = "sc-gate-dl no longer has this download. Retry to start it again.", attention = false))
                // A brief network or server outage should not end a download that keeps running on the server.
                error.status == null && current.state == SoundCloudJobState.WORKING && failures.value.getOrElse(job.id) { 0 } < 30 -> {
                    failures.update { it + (job.id to it.getOrElse(job.id) { 0 } + 1) }
                    replace(current, current.copy(message = "Reconnecting to sc-gate-dl"))
                }
                else -> replace(current, current.copy(state = SoundCloudJobState.FAILED, message = error.message ?: "sc-gate-dl request failed"))
            }
        } catch (error: Exception) {
            val current = store.read<SoundCloudJob>("soundcloud-job:${job.id}") ?: return
            replace(current, current.copy(state = SoundCloudJobState.FAILED, message = error.message ?: "The download could not finish"))
        }
    }

    private suspend fun poll(job: SoundCloudJob) {
        val status = client(job).job(requireNotNull(job.remoteId))
        val next = when (status.stage) {
            "waiting_hypeddit" -> job.copy(state = SoundCloudJobState.NEEDS_LINK, message = "No download link found", attention = false)
            "waiting_bandcamp_track" -> job.copy(state = SoundCloudJobState.NEEDS_TRACK, message = "Choose the song on the Bandcamp album",
                bandcampTracks = status.bandcampTracks, attention = false)
            "error" -> job.copy(state = SoundCloudJobState.FAILED, message = status.error ?: status.message.ifBlank { "sc-gate-dl could not download this song" },
                attention = false)
            "cancelled" -> job.copy(state = SoundCloudJobState.CANCELLED, message = "Canceled on sc-gate-dl", attention = false)
            "ready" -> if (job.destination == ScGateDestination.LIBRARY)
                job.copy(state = SoundCloudJobState.IMPORTING, message = "Adding to your music server", percent = 100, attention = false,
                    fileName = downloadFileName(requireNotNull(status.libraryFilename) { "sc-gate-dl did not report where it saved the song" }))
            else job.copy(state = SoundCloudJobState.TRANSFERRING, message = "Saving to this device", percent = 100, attention = false,
                fileName = downloadFileName(requireNotNull(status.outputFilename) { "sc-gate-dl did not report the finished file" }))
            else -> job.copy(message = status.message.ifBlank { "Downloading" }, percent = status.percent.coerceIn(0, 100),
                attention = status.requiresAttention)
        }
        if (replace(job, next) && next.state != SoundCloudJobState.WORKING) advance(next)
    }

    private suspend fun transfer(job: SoundCloudJob) {
        val destination = requireNotNull(files) { "Downloads to this device are unavailable" }
        val location = destination.save(requireNotNull(job.fileName)) { sink ->
            client(job).download(requireNotNull(job.remoteId)) { sink.write(it) }
        }
        val next = job.copy(state = SoundCloudJobState.IMPORTING, message = "Adding to your library", location = location)
        if (replace(job, next)) advance(next)
    }

    private suspend fun import(job: SoundCloudJob) {
        val next = if (job.destination == ScGateDestination.LIBRARY) {
            val client = requireNotNull(server()) { "Connect to your music server to add this song" }
            check(client.serverId == job.musicServerId) { "Connect to the music server this song was saved to, then retry" }
            val changes = requireNotNull(serverChanges) { "Music server changes are unavailable" }
            val name = requireNotNull(job.fileName)
            // Only Navidrome's own API lists real file paths. Other Subsonic servers are matched by the tags below.
            val navidrome = client.isNavidrome()
            // The library already knows these server songs, so a match outside them is the new file, even when an older
            // file elsewhere has the same name or tags. sc-gate-dl only reports the file name inside its library folder.
            val owners = "source-owner:server:${client.serverId}:"
            val known = store.database.records().list("source-owners").mapNotNullTo(HashSet()) { it.key.takeIf { key -> key.startsWith(owners) } }
            fun List<Pair<Track, ServerSong>>.newest() =
                firstOrNull { (_, song) -> "$owners${song.id}" !in known } ?: firstOrNull()
            client.rescan()
            val catalogue = changes.refreshLibrary(client, includeFilePaths = navidrome)
            // sc-gate-dl tags the file with the same title and artist the search showed.
            val (track, song) = catalogue.filter { (_, song) -> song.filePath?.let { it == name || it.endsWith("/$name") } == true }.newest()
                ?: catalogue.filter { (_, song) -> song.metadata.title.equals(job.track.title, ignoreCase = true) &&
                    song.metadata.artist.equals(job.track.artist, ignoreCase = true) }.newest()
                ?: throw IllegalStateException("The music server has not indexed the song yet. Retry in a moment.")
            song.coverArt?.let { cover ->
                try { artwork?.fetch(track.id, client, cover) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The next library refresh downloads the cover. */ }
            }
            if (job.keepOffline) offline?.request(listOf(track.id))
            job.copy(state = SoundCloudJobState.COMPLETE, message = "Added to your music server", trackId = track.id)
        } else importLocal(job)
        if (replace(job, next)) mutableCompleted.tryEmit(next)
    }

    private suspend fun importLocal(job: SoundCloudJob): SoundCloudJob {
        val track = requireNotNull(localSources) { "Local music access is unavailable" }.scan(requireNotNull(job.location)).firstOrNull()
        return if (track == null) job.copy(state = SoundCloudJobState.COMPLETE, message = "Saved to ${files?.folder}", excludedFolder = files?.folder)
        else job.copy(state = SoundCloudJobState.COMPLETE, message = "Added to your library", trackId = track.id, excludedFolder = null)
    }

    /** Indexes a saved download again after its folder joined the library. */
    suspend fun importSaved(id: String) {
        val job = job(id)
        require(job.state == SoundCloudJobState.COMPLETE && job.location != null) { "This download was not saved to this device" }
        val next = importLocal(job)
        check(next.trackId != null) { "Your local folder rules still leave out ${job.excludedFolder}" }
        if (replace(job, next)) mutableCompleted.tryEmit(next)
    }
}
