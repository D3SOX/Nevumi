package me.d3sox.nevumi.library

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import me.d3sox.nevumi.artwork.ArtworkLibrary
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

class AndroidLocalSources(private val context: Context, private val library: MusicLibrary,
    private val artwork: ArtworkLibrary? = null) : LocalSources {
    override suspend fun roots(): List<String> = library.localRoots()

    private suspend fun deviceStamp(): String = buildString {
        append(library.localFolderStamp())
        // A volume's generation also moves for photos and downloads; only its audio rows decide whether to rescan.
        // Generations count per volume, so each volume keeps its own.
        MediaStore.getExternalVolumeNames(context).sorted().forEach { volume ->
            // A volume that cannot be queried, for example while it is being unmounted, gets a fixed stamp instead of failing
            // the check; it differs from the volume's real stamp, so a scan follows once the volume answers again.
            val audio = try {
                var count = 0L
                var generation = 0L
                context.contentResolver.query(MediaStore.Audio.Media.getContentUri(volume), arrayOf(MediaStore.Audio.Media.GENERATION_MODIFIED),
                    "${MediaStore.Audio.Media.IS_TRASHED} = 0 AND ${MediaStore.Audio.Media.IS_PENDING} = 0", null, null)?.use { cursor ->
                    while (cursor.moveToNext()) { count++; generation = maxOf(generation, cursor.getLong(0)) }
                }
                "$count:$generation"
            } catch (_: Exception) { "unavailable" }
            append('|').append(volume).append(':').append(MediaStore.getVersion(context, volume)).append(":audio:").append(audio)
        }
    }

    override suspend fun pendingRefreshLocations(): List<String> = withContext(Dispatchers.IO) {
        val changedFiles = LocalFiles(library, artwork).pendingRefreshLocations()
        if ("device" in roots() && library.localIndexStamp("device") != deviceStamp()) changedFiles + "device"
        else changedFiles
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val changes: Flow<Unit> = library.observeLocalRoots().flatMapLatest { roots ->
        if ("device" !in roots) emptyFlow() else callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }
    }


    override suspend fun scan(location: String, force: Boolean, onProgress: suspend (LocalScanProgress) -> Unit): List<Track> = withContext(Dispatchers.IO) {
        TagOptionSingleton.getInstance().isAndroid = true
        if (location != "device" && !location.startsWith("content:"))
            return@withContext LocalFiles(library, artwork).scan(location, force, onProgress)
        val wholeDevice = location == "device"
        val stamp = if (wholeDevice) deviceStamp() else null
        require(wholeDevice || location.startsWith("content://media/")) { "Choose device music" }
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val queryUri = if (wholeDevice) collection else Uri.parse(location)
        val result = mutableListOf<Track>()
        val seen = mutableSetOf<String>()
        val folders = library.localFolderRules()
        val previous = library.tracks().flatMap { track -> track.sources.map { it.id to track } }.toMap()
        requireNotNull(resolver.query(queryUri, arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.SIZE),
            "${MediaStore.Audio.Media.IS_TRASHED} = 0 AND ${MediaStore.Audio.Media.IS_PENDING} = 0", null, null)) {
            "Device music is unavailable"
        }.use { cursor ->
            onProgress(LocalScanProgress(0, cursor.count))
            while (cursor.moveToNext()) {
                try {
                val name = cursor.getString(1)
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in setOf("mp3", "wav", "flac")) continue
                if (!folders.allows(cursor.getString(2).orEmpty(), cursor.getString(3)?.let { File(it).parent })) continue
                val uri = if (wholeDevice) ContentUris.withAppendedId(collection, cursor.getLong(0)) else queryUri
                val direct = cursor.getString(3)?.let(::File)?.takeIf { it.isFile && it.canRead() }
                val modifiedAt = direct?.lastModified()?.takeIf { it > 0 } ?: (cursor.getLong(4) * 1_000)
                val size = direct?.length() ?: cursor.getLong(5)
                val sourceId = "local:$uri"
                val cached = previous[sourceId]
                val source = cached?.sources?.firstOrNull { it.id == sourceId }
                if (!force && source?.available == true && source.modifiedAt == modifiedAt && source.indexedSize > 0 &&
                    source.indexedSize == size && (!source.hasArtwork || artwork == null || artwork.path(cached.id) != null)) {
                    seen += sourceId
                    result += cached
                    continue
                }
                val temporary = File.createTempFile("music-index-", ".$extension", context.cacheDir)
                try {
                    val audio = try {
                        if (direct != null) AudioFileIO.read(direct) else {
                            requireNotNull(resolver.openInputStream(uri)).use { input ->
                                temporary.outputStream().use { input.copyTo(it) }
                            }
                            AudioFileIO.read(temporary)
                        }
                    } catch (_: Exception) { continue }
                    val metadata = audio.trackMetadata(name.substringBeforeLast('.'), cursor.getString(2).orEmpty().trimEnd('/'))
                    val cover = audio.fieldTag?.firstArtwork?.binaryData
                    val track = library.importTrack(metadata, TrackSource(sourceId, SourceKind.LOCAL, uri.toString(),
                        filePath = cursor.getString(3), modifiedAt = modifiedAt, indexedSize = size, hasArtwork = cover != null))
                    seen += sourceId
                    if (cover != null) {
                try { artwork?.save(track.id, cover) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* A bad cover cannot make its audio unplayable. */ }
            }
                    else if (track.sources.all { it.kind == SourceKind.LOCAL }) artwork?.remove(track.id)
                    result += track
                } finally { temporary.delete() }
                } finally { onProgress(LocalScanProgress(cursor.position + 1, cursor.count)) }
            }
        }
        val missing = library.tracks().flatMap { it.sources }.filter {
            it.kind == SourceKind.LOCAL && (if (wholeDevice) it.location.startsWith("content://media/") else it.location == location) && it.id !in seen
        }.map { it.id }.toSet()
        library.setSourceAvailability(missing, false)
        if (wholeDevice) {
            library.rememberLocalRoot(location)
            library.saveLocalIndexStamp(location, requireNotNull(stamp))
        }
        result
    }
}
