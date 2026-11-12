package me.d3sox.nevumi.youtube

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import me.d3sox.nevumi.library.LocalSources
import me.d3sox.nevumi.soundcloud.DeviceMusicFiles
import me.d3sox.nevumi.soundcloud.ScGateTagChoice
import me.d3sox.nevumi.soundcloud.ScGateTags
import me.d3sox.nevumi.storage.MusicStore
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Where a YouTube download goes: this device, or this device and then the music server over SFTP. */
@Serializable
enum class YouTubeDestination { DEVICE, SERVER }

@Serializable
enum class YouTubeJobState { DOWNLOADING, NEEDS_TAGS, SAVING, UPLOADING, COMPLETE, FAILED, CANCELLED }

@Serializable
data class YouTubeJob(
    val id: String,
    val track: YouTubeTrack,
    val destination: YouTubeDestination,
    val state: YouTubeJobState,
    val createdAt: Long = 0,
    /** The download waits until its tags are checked. */
    val reviewTags: Boolean = false,
    val message: String = "",
    val percent: Int = 0,
    /** The tags to write; once [tagsChecked], the ones the user chose. */
    val tags: ScGateTags = ScGateTags(),
    val year: Int = 0,
    val tagsChecked: Boolean = false,
    /** The file is named `Artist - Title` instead of after the title alone. */
    val artistTitleName: Boolean = true,
    /** The user picked a cover while checking the tags, kept next to the download until it is saved. */
    val chosenCover: Boolean = false,
    /** The length of the audio stream being downloaded, so continuing a download can tell whether YouTube still offers the same one. */
    val totalBytes: Long = 0,
    /** The whole audio is in the download folder. */
    val downloaded: Boolean = false,
    /** Where the file was saved on this device. */
    val location: String? = null,
    val trackId: String? = null,
    /** The device folder the song was saved to while the local folder rules leave it out of the library. */
    val excludedFolder: String? = null,
    /** Trying again cannot help, such as for a private video. */
    val permanent: Boolean = false,
) {
    val active: Boolean get() = state in setOf(YouTubeJobState.DOWNLOADING, YouTubeJobState.SAVING, YouTubeJobState.UPLOADING)
    val finished: Boolean get() = state in setOf(YouTubeJobState.COMPLETE, YouTubeJobState.FAILED, YouTubeJobState.CANCELLED)
    val fileName: String get() = youTubeFileName(tags, artistTitleName)
}

private val unsafeFileCharacters = Regex("""[<>:"/\\|?*\u0000-\u001F]""")
private val whitespace = Regex("""\s+""")
private fun fileNamePart(value: String) = whitespace.replace(unsafeFileCharacters.replace(value, ""), " ").trim().trimStart('.')

/** The name a download is saved with. */
internal fun youTubeFileName(tags: ScGateTags, artistTitle: Boolean): String {
    val title = fileNamePart(tags.title).ifEmpty { "Unknown Title" }
    return (if (artistTitle) "${fileNamePart(tags.artist).ifEmpty { "Unknown Artist" }} - $title" else title).take(180) + ".m4a"
}

/** Converts the downloaded [source] into a plain M4A at [target] and writes [tags], [year] and [cover] into it. */
internal expect suspend fun finishYouTubeAudio(source: String, target: String, tags: ScGateTags, year: Int, cover: ByteArray?)

/**
 * Downloads YouTube audio on this device. Jobs survive restarts: their state is stored, an interrupted download continues where
 * it stopped, and finished files are saved to [files], indexed in the library and, for [YouTubeDestination.SERVER], uploaded.
 */
class YouTubeDownloads(private val store: MusicStore, private val http: HttpClient, private val source: YouTubeSource,
    /** Keeps downloads until they are saved; the app's own storage, since the system may clear its cache at any time. */
    private val workDirectory: String,
    private val files: DeviceMusicFiles? = null,
    private val localSources: LocalSources? = null,
    /** Uploads a library song to the music server at a path inside its library folder. */
    private val upload: (suspend (trackId: String, path: String) -> Unit)? = null,
    private val scheduleWork: ((userInitiated: Boolean) -> Unit)? = null,
    private val chunkBytes: Long = 1L shl 20) : AutoCloseable {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val runs = Mutex()
    private val edits = Mutex()
    // Jobs whose audio this process is writing; a cancellation leaves their files to the writer, which deletes them once it stops.
    private val fetching = mutableSetOf<String>()
    private val mutableJobs = MutableStateFlow<List<YouTubeJob>?>(null)
    /** Newest first, or null until the stored jobs are loaded. */
    val jobs = mutableJobs.asStateFlow()
    private val mutableCompleted = MutableSharedFlow<YouTubeJob>(extraBufferCapacity = 16)
    /** Jobs that just finished adding their song. */
    val completed = mutableCompleted.asSharedFlow()
    val deviceDownloads: Boolean get() = files != null && localSources != null
    val serverUploads: Boolean get() = upload != null

    init {
        scope.launch {
            store.database.records().observeRevision(COLLECTION).distinctUntilChanged().collect {
                mutableJobs.value = store.list<YouTubeJob>(COLLECTION).sortedByDescending { it.createdAt }
            }
        }
        scope.launch {
            wake.trySend(Unit)
            while (isActive) {
                withTimeoutOrNull(30_000) { wake.receive() }
                try {
                    if (scheduleWork == null) runPending()
                    else if (store.list<YouTubeJob>(COLLECTION).any { it.active }) scheduleWork.invoke(false)
                } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
            }
        }
    }

    override fun close() { supervisor.cancel() }
    suspend fun closeAndJoin() { close(); supervisor.join() }

    private val directory = workDirectory.toPath()
    private fun partFile(id: String) = directory / "$id.part"
    private fun finishedFile(id: String) = directory / "$id.m4a"
    private fun coverFile(id: String) = directory / "$id.cover"
    private fun deleteFiles(id: String) = listOf(partFile(id), finishedFile(id), coverFile(id)).forEach { FileSystem.SYSTEM.delete(it, mustExist = false) }

    private suspend fun save(job: YouTubeJob) = store.put("youtube-job:${job.id}", COLLECTION, job)
    private suspend fun job(id: String): YouTubeJob = requireNotNull(store.read("youtube-job:$id")) { "The download is no longer listed" }

    /** Saves [next] only when nobody changed the job since [expected] was read, such as a cancellation during a download. */
    private suspend fun replace(expected: YouTubeJob, next: YouTubeJob): Boolean = edits.withLock {
        if (store.read<YouTubeJob>("youtube-job:${expected.id}") != expected) false else { save(next); true }
    }

    private fun schedule() {
        if (scheduleWork == null) wake.trySend(Unit) else scheduleWork.invoke(true)
    }

    /** Starts a download, or returns the one already running for the same video and destination. */
    suspend fun download(track: YouTubeTrack, destination: YouTubeDestination, reviewTags: Boolean): YouTubeJob {
        check(deviceDownloads) { "Downloads to this device are unavailable" }
        if (destination == YouTubeDestination.SERVER) checkNotNull(upload) { "Set up server file access in Settings to upload songs" }
        val job = edits.withLock {
            store.list<YouTubeJob>(COLLECTION).firstOrNull { it.track.videoId == track.videoId && it.destination == destination && !it.finished }
                ?: run {
                    val (title, artist) = track.suggestedTags()
                    YouTubeJob(Uuid.random().toString(), track, destination, YouTubeJobState.DOWNLOADING, Clock.System.now().toEpochMilliseconds(),
                        reviewTags, "Starting", tags = ScGateTags(title, artist)).also { save(it) }
                }
        }
        if (job.active) schedule()
        return job
    }

    /** The cover to tag [id] with: the one chosen while checking its tags, or the video's artwork. */
    suspend fun cover(id: String): ByteArray? {
        val job = job(id)
        if (job.chosenCover) return withContext(Dispatchers.IO) { FileSystem.SYSTEM.read(coverFile(id)) { readByteArray() } }
        return job.track.artworkUrl?.let { source.artwork(largeArtwork(it)) }
    }

    /** Writes the checked tags into the downloaded file and saves it like any other download. */
    suspend fun finishTags(id: String, choice: ScGateTagChoice) {
        val job = job(id)
        require(job.state == YouTubeJobState.NEEDS_TAGS) { "This download is not waiting for its tags" }
        require(choice.tags.title.isNotBlank()) { "Enter a title" }
        choice.cover?.let { bytes -> withContext(Dispatchers.IO) { FileSystem.SYSTEM.write(coverFile(id)) { write(bytes) } } }
        replace(job, job.copy(state = YouTubeJobState.SAVING, message = "Writing tags", tags = choice.tags, tagsChecked = true,
            artistTitleName = choice.artistTitleName, chosenCover = job.chosenCover || choice.cover != null))
        schedule()
    }

    suspend fun cancel(id: String) {
        edits.withLock {
            val job = job(id)
            require(job.state == YouTubeJobState.DOWNLOADING || job.state == YouTubeJobState.NEEDS_TAGS) { "This download can no longer be canceled" }
            save(job.copy(state = YouTubeJobState.CANCELLED, message = "Canceled", downloaded = false, percent = 0))
            // Deleting under the lock keeps a quick retry from starting a new writer before the old files are gone.
            if (id !in fetching) withContext(Dispatchers.IO) { deleteFiles(id) }
        }
    }

    /** Resumes where the job stopped: uploading a saved song, saving a downloaded file, or downloading the rest of the audio. */
    suspend fun retry(id: String) {
        val job = job(id)
        require(job.state == YouTubeJobState.FAILED || job.state == YouTubeJobState.CANCELLED) { "This download is not stopped" }
        val downloaded = job.downloaded && withContext(Dispatchers.IO) { FileSystem.SYSTEM.exists(partFile(id)) }
        val next = when {
            job.location != null && job.trackId != null && job.destination == YouTubeDestination.SERVER -> YouTubeJobState.UPLOADING
            job.location != null || downloaded && (job.tagsChecked || !job.reviewTags) -> YouTubeJobState.SAVING
            downloaded -> YouTubeJobState.NEEDS_TAGS
            else -> YouTubeJobState.DOWNLOADING
        }
        replace(job, job.copy(state = next, message = if (next == YouTubeJobState.NEEDS_TAGS) "Check the tags to finish" else "Retrying",
            downloaded = downloaded, permanent = false))
        if (next != YouTubeJobState.NEEDS_TAGS) schedule()
    }

    suspend fun dismiss(id: String) {
        edits.withLock {
            check(store.read<YouTubeJob>("youtube-job:$id")?.active != true) { "Wait for the download to finish" }
            store.database.records().delete("youtube-job:$id")
            if (id !in fetching) withContext(Dispatchers.IO) { deleteFiles(id) }
        }
    }

    /** Indexes a saved download again after its folder joined the library. */
    suspend fun importSaved(id: String) {
        val job = job(id)
        require(job.state == YouTubeJobState.COMPLETE && job.location != null) { "This download was not saved to this device" }
        val indexed = importLocal(job)
        check(indexed.trackId != null) { "Your local folder rules still leave out ${job.excludedFolder}" }
        if (job.destination == YouTubeDestination.SERVER) {
            if (replace(job, indexed.copy(state = YouTubeJobState.UPLOADING, message = "Uploading to your music server"))) schedule()
        } else if (replace(job, indexed)) mutableCompleted.tryEmit(indexed)
    }

    /** Works through the active jobs until none is left. */
    suspend fun runPending() = runs.withLock {
        while (true) {
            val active = store.list<YouTubeJob>(COLLECTION).filter { it.active }.sortedBy { it.createdAt }
            if (active.isEmpty()) return@withLock
            for (job in active) advance(job)
        }
    }

    private suspend fun advance(job: YouTubeJob) {
        try {
            when (job.state) {
                YouTubeJobState.DOWNLOADING -> fetch(job)
                YouTubeJobState.SAVING -> saveFile(job)
                YouTubeJobState.UPLOADING -> uploadFile(job)
                else -> Unit
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val current = store.read<YouTubeJob>("youtube-job:${job.id}") ?: return
            if (current.active) replace(current, current.copy(state = YouTubeJobState.FAILED,
                message = error.message ?: "The download could not finish", permanent = (error as? YouTubeException)?.retryable == false))
        }
    }

    private suspend fun fetch(found: YouTubeJob) {
        edits.withLock { fetching += found.id }
        try { fetchRanges(found) }
        finally {
            withContext(NonCancellable) { edits.withLock {
                fetching -= found.id
                // A retry right after a cancellation continues this file, so only a stopped download loses it.
                val current = store.read<YouTubeJob>("youtube-job:${found.id}")
                if (current == null || current.state == YouTubeJobState.CANCELLED) withContext(Dispatchers.IO) { deleteFiles(found.id) }
            } }
        }
    }

    private suspend fun fetchRanges(found: YouTubeJob) {
        var job = found
        var audio = source.audio(job.track.videoId)
        // YouTube Music's song descriptions name the album and year, which are good defaults for the tags.
        if (!job.tagsChecked && (job.tags.album.isBlank() && audio.details.album.isNotBlank() || job.year == 0 && audio.details.year > 0)) {
            val next = job.copy(tags = job.tags.copy(album = job.tags.album.ifBlank { audio.details.album }), year = job.year.takeIf { it > 0 } ?: audio.details.year)
            if (!replace(job, next)) return
            job = next
        }
        // Only MP4 audio becomes a taggable M4A without converting it; YouTube offers it for nearly every video.
        if (audio.mimeType != "audio/mp4") throw YouTubeException("YouTube offers no M4A audio for this video to save", retryable = false)
        val total = audio.contentLength.takeIf { it > 0 } ?: throw YouTubeException("YouTube did not say how long this audio is")
        val target = partFile(job.id)
        val fs = FileSystem.SYSTEM
        withContext(Dispatchers.IO) { fs.createDirectories(directory) }
        // A partial file of another stream cannot be continued.
        if (job.totalBytes != total) {
            withContext(Dispatchers.IO) { fs.delete(target, mustExist = false) }
            val next = job.copy(totalBytes = total, percent = 0)
            if (!replace(job, next)) return
            job = next
        }
        var position = withContext(Dispatchers.IO) { fs.metadataOrNull(target)?.size ?: 0 }.coerceAtMost(total)
        var refreshed = false
        while (position < total) {
            val last = minOf(total - 1, position + chunkBytes - 1)
            val status = http.config { install(HttpTimeout) }.use { client ->
                client.prepareGet(audio.url) {
                    header(HttpHeaders.Range, "bytes=$position-$last")
                    timeout { requestTimeoutMillis = 120_000; socketTimeoutMillis = 30_000 }
                }.execute { response ->
                    if (response.status.value == 206) withContext(Dispatchers.IO) {
                        fs.appendingSink(target).buffer().use { sink ->
                            val channel = response.bodyAsChannel()
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = channel.readAvailable(buffer)
                                if (count == -1) break
                                if (count > 0) { sink.write(buffer, 0, count); position += count }
                            }
                        }
                    }
                    response.status.value
                }
            }
            when {
                status == 206 -> refreshed = false
                // The stream's address expired, such as during a long pause; the next one continues from the same byte.
                (status == 403 || status == 410) && !refreshed -> { refreshed = true; audio = source.audio(job.track.videoId, fresh = true) }
                else -> throw YouTubeException("YouTube stopped the download (HTTP $status)")
            }
            val percent = (position * 100 / total).toInt()
            val current = store.read<YouTubeJob>("youtube-job:${job.id}")
            if (current?.state != YouTubeJobState.DOWNLOADING) return
            if (percent != current.percent || current.message != "Downloading") {
                val next = current.copy(percent = percent, message = "Downloading")
                if (!replace(current, next)) return
                job = next
            } else job = current
        }
        val review = job.reviewTags && !job.tagsChecked
        replace(job, job.copy(downloaded = true, percent = 100, state = if (review) YouTubeJobState.NEEDS_TAGS else YouTubeJobState.SAVING,
            message = if (review) "Check the tags to finish" else "Saving to this device"))
    }

    private suspend fun saveFile(found: YouTubeJob) {
        var job = found
        if (job.location == null) {
            val destination = requireNotNull(files) { "Downloads to this device are unavailable" }
            val cover = try { cover(job.id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            finishYouTubeAudio(partFile(job.id).toString(), finishedFile(job.id).toString(), job.tags, job.year, cover)
            val location = destination.save(job.fileName) { sink ->
                withContext(Dispatchers.IO) { FileSystem.SYSTEM.source(finishedFile(job.id)).use { sink.writeAll(it) } }
            }
            val saved = job.copy(location = location, message = "Adding to your library")
            if (!replace(job, saved)) return
            job = saved
            withContext(Dispatchers.IO) { deleteFiles(job.id) }
        }
        val indexed = importLocal(job)
        val next = if (job.destination == YouTubeDestination.SERVER && indexed.trackId != null)
            indexed.copy(state = YouTubeJobState.UPLOADING, message = "Uploading to your music server") else indexed
        if (replace(job, next)) { if (next.state == YouTubeJobState.COMPLETE) mutableCompleted.tryEmit(next) else advance(next) }
    }

    private suspend fun uploadFile(job: YouTubeJob) {
        val send = requireNotNull(upload) { "Set up server file access in Settings to upload songs" }
        val path = listOfNotNull(fileNamePart(job.tags.artist).ifEmpty { null }, fileNamePart(job.tags.album).ifEmpty { null }, job.fileName).joinToString("/")
        send(requireNotNull(job.trackId), path)
        val next = job.copy(state = YouTubeJobState.COMPLETE, message = "Added to your music server")
        if (replace(job, next)) mutableCompleted.tryEmit(next)
    }

    private suspend fun importLocal(job: YouTubeJob): YouTubeJob {
        val sources = requireNotNull(localSources) { "Local music access is unavailable" }
        // A song meant for the music server is indexed wherever it was saved, since the upload needs it in the library.
        val track = (if (job.destination == YouTubeDestination.SERVER) sources.scanSong(requireNotNull(job.location))
            else sources.scan(requireNotNull(job.location))).firstOrNull()
        return if (track == null) job.copy(state = YouTubeJobState.COMPLETE, message = "Saved to ${files?.folder}", excludedFolder = files?.folder)
        else job.copy(state = YouTubeJobState.COMPLETE, message = "Added to your library", trackId = track.id, excludedFolder = null)
    }

    private companion object { const val COLLECTION = "youtube-jobs" }
}

/** A version of YouTube artwork that is large enough for a cover. YouTube Music scales its square artwork to the size in the address. */
internal fun largeArtwork(url: String): String = url.replace(Regex("""=w\d+-h\d+"""), "=w1200-h1200")

/** A version of YouTube artwork that is large enough for a list row. */
fun smallArtwork(track: YouTubeTrack): String? = track.artworkUrl?.let { url ->
    if ("googleusercontent.com" in url) url.replace(Regex("""=w\d+-h\d+"""), "=w226-h226") else "https://i.ytimg.com/vi/${track.videoId}/mqdefault.jpg"
}
