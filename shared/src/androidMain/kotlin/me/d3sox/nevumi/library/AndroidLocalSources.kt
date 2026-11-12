package me.d3sox.nevumi.library

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.TagOptionSingleton
import java.io.File
import java.security.MessageDigest
import me.d3sox.nevumi.artwork.ArtworkLibrary
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.open_needs_permission
import me.d3sox.nevumi.resources.open_not_on_device
import org.jetbrains.compose.resources.getString

class AndroidLocalSources(private val context: Context, private val library: MusicLibrary,
    private val artwork: ArtworkLibrary? = null) : LocalSources {
    override suspend fun roots(): List<String> = library.localRoots()

    // Songs the user opened stay in the library, also when a later scan of the device follows the folder rules.
    override suspend fun scanSong(location: String): List<Track> {
        if (location.startsWith("content://media/")) library.rememberChosenSong(location)
        return scan(location)
    }

    // The library indexes device songs under their media store entry, so a link another app hands over is matched to one.
    // Reading that entry, then and when the song plays later, needs the audio permission; a link's own grant ends with the activity.
    override suspend fun openedSong(uri: String): String = withContext(Dispatchers.IO) {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) error(getString(Res.string.open_needs_permission))
        val opened = Uri.parse(uri)
        val media = when {
            opened.authority == MediaStore.AUTHORITY -> opened
            DocumentsContract.isDocumentUri(context, opened) -> runCatching { MediaStore.getMediaUri(context, opened) }.getOrNull()
            else -> null
        } ?: sameFile(opened)
        // Media items, files and downloads on every external volume share their IDs with the collection whole-device scans index;
        // albums, artists and the internal volume's system sounds have IDs of their own.
        val id = media?.takeIf { it.pathSegments.firstOrNull() != MediaStore.VOLUME_INTERNAL &&
            it.pathSegments.dropLast(1).lastOrNull() in setOf("media", "file", "downloads") }
            ?.lastPathSegment?.toLongOrNull() ?: error(getString(Res.string.open_not_on_device))
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
    }

    /**
     * Finds the device song with the name, size and contents [uri] has, for links that file managers serve themselves. Different
     * recordings can share a name and a size, like WAV files of the same length.
     */
    private fun sameFile(uri: Uri): Uri? = runCatching {
        val resolver = context.contentResolver
        val (name, size) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0) && !it.isNull(1)) it.getString(0) to it.getLong(1) else null
        } ?: return null
        val candidates = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.SIZE} = ? AND " +
                "${MediaStore.Audio.Media.IS_TRASHED} = 0 AND ${MediaStore.Audio.Media.IS_PENDING} = 0",
            arrayOf(name, size.toString()), null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))) }
        }.orEmpty()
        if (candidates.isEmpty()) return null
        val opened = digest(uri) ?: return null
        candidates.firstOrNull { digest(it)?.contentEquals(opened) == true }
    }.getOrNull()

    private fun digest(uri: Uri): ByteArray? = context.contentResolver.openInputStream(uri)?.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest()
    }

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

    // The media store reports a burst of changes for one edit, and names no folder, so a pause leads to one scan of every root.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    override val changes: Flow<List<String>> = library.observeLocalRoots().flatMapLatest { roots ->
        if ("device" !in roots) emptyFlow() else callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            awaitClose { context.contentResolver.unregisterContentObserver(observer) }
        }
    }.debounce(300).map { roots() }


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
        val chosen = library.chosenSongs()
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
                val uri = if (wholeDevice) ContentUris.withAppendedId(collection, cursor.getLong(0)) else queryUri
                if (uri.toString() !in chosen && !folders.allows(cursor.getString(2).orEmpty(), cursor.getString(3)?.let { File(it).parent })) continue
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
                // Only a file the app cannot read directly is copied first; creating the copy for every song cost a file per scan.
                var temporary: File? = null
                try {
                    val audio = try {
                        if (direct != null) AudioFileIO.read(direct) else {
                            val copy = File.createTempFile("music-index-", ".$extension", context.cacheDir).also { temporary = it }
                            requireNotNull(resolver.openInputStream(uri)).use { input ->
                                copy.outputStream().use { input.copyTo(it) }
                            }
                            AudioFileIO.read(copy)
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
                } finally { temporary?.delete() }
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
