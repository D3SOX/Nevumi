package me.d3sox.nevumi.library

import java.io.File
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.wav.WavTag
import me.d3sox.nevumi.artwork.ArtworkLibrary

/** The audio formats a local scan indexes. */
val localMusicExtensions = setOf("mp3", "m4a", "wav", "flac")

/** The tag a file's fields are read from, which in a WAV file with an ID3 tag is that tag rather than the INFO chunk. */
val AudioFile.fieldTag: Tag? get() = tag.let { if (it is WavTag && it.isExistingId3Tag) it.getID3Tag() else it }

/**
 * Maps a parsed file's tags to track metadata; [name] titles a file without a title tag.
 * The library files a song without an album artist under its artist, while the tag editor shows what the file holds.
 */
fun AudioFile.trackMetadata(name: String, folder: String, albumArtistFallback: Boolean = true): TrackMetadata {
    val container = tag
    val tag = fieldTag
    fun field(key: FieldKey): String = try { tag?.getFirst(key).orEmpty().trimEnd('\u0000') }
        catch (_: UnsupportedOperationException) { "" }
    val number = field(FieldKey.TRACK).ifBlank {
        if (container is WavTag) container.infoTag.unrecognisedFields.firstOrNull { it.id == "IPRT" }?.content.orEmpty().trimEnd('\u0000') else ""
    }
    fun values(key: FieldKey): List<String> = try { tag?.getAll(key).orEmpty().map { it.trimEnd('\u0000').trim() }.filter { it.isNotEmpty() } }
        catch (_: UnsupportedOperationException) { emptyList() }
        catch (_: org.jaudiotagger.tag.KeyNotFoundException) { emptyList() }
    val artist = field(FieldKey.ARTIST)
    // Several artist values, or an ARTISTS tag as MusicBrainz Picard writes it. An ARTISTS tag that names someone the artist
    // tag doesn't was left behind by an edit of the artist tag, so it no longer counts.
    val artists = values(FieldKey.ARTIST).takeIf { it.size > 1 }
        ?: values(FieldKey.ARTISTS).takeIf { names -> names.size > 1 && names.all { artist.contains(it, ignoreCase = true) } }.orEmpty()
    return TrackMetadata(
        title = field(FieldKey.TITLE).ifBlank { name },
        artist = artist,
        album = field(FieldKey.ALBUM),
        // Milliseconds rather than whole seconds, so a length is rounded once, wherever it is shown or compared.
        durationMs = (audioHeader.preciseTrackLength * 1_000).roundToLong(),
        genres = tag?.getAll(FieldKey.GENRE).orEmpty().map { it.trimEnd('\u0000') }.filter { it.isNotBlank() },
        albumArtist = field(FieldKey.ALBUM_ARTIST).ifBlank { if (albumArtistFallback) artist else "" },
        trackNumber = number.substringBefore('/').toIntOrNull() ?: 0,
        discNumber = field(FieldKey.DISC_NO).substringBefore('/').toIntOrNull() ?: 1,
        folder = folder,
        year = field(FieldKey.YEAR).take(4).toIntOrNull() ?: 0,
        recordingId = field(FieldKey.MUSICBRAINZ_TRACK_ID),
        releaseId = field(FieldKey.MUSICBRAINZ_RELEASEID),
        artists = artists,
    )
}

class LocalFiles(private val library: MusicLibrary, private val artwork: ArtworkLibrary? = null) : LocalSources {
    // Earlier versions registered every edited file as a root; ignore those so they stop being rescanned.
    override suspend fun roots(): List<String> = library.localRoots().filterNot { File(it).isFile }

    override suspend fun pendingRefreshLocations(): List<String> = withContext(Dispatchers.IO) {
        val indexed = library.tracks().flatMap { it.sources }.filter { it.kind == SourceKind.LOCAL }.associateBy { it.id }
        val folders = library.localFolderRules()
        val chosen = library.chosenSongs()
        // Resolving a path reads every folder in it, so each file and indexed song is resolved once.
        val indexedPaths by lazy {
            indexed.values.filter { it.available && !it.location.startsWith("content:") }.associateWith { File(it.location).canonicalFile.toPath() }
        }
        roots().filter { location ->
            if (location.startsWith("content:") || location == "device") return@filter false
            val root = File(location).canonicalFile
            val files = root.musicFiles().filter { (_, canonical) ->
                canonical.path in chosen || folders.allows(canonical.parent.orEmpty(), canonical.parentFile?.relativeToOrNull(root)?.path)
            }.associate { (file, canonical) -> "local:${canonical.path}" to file }
            files.any { (id, file) ->
                val source = indexed[id]
                source == null || !source.available || source.modifiedAt != file.lastModified() || source.indexedSize != file.length() ||
                    source.indexFormat != LOCAL_INDEX_FORMAT
            } || indexedPaths.any { (source, path) -> path.startsWith(root.toPath()) && source.id !in files }
        }
    }

    override suspend fun scan(location: String, force: Boolean, onProgress: suspend (LocalScanProgress) -> Unit): List<Track> =
        scan(location, force, onProgress, followRules = true)

    override suspend fun scanSong(location: String): List<Track> = scan(location, force = false, onProgress = {}, followRules = false)

    private suspend fun scan(location: String, force: Boolean, onProgress: suspend (LocalScanProgress) -> Unit,
        followRules: Boolean): List<Track> = withContext(Dispatchers.IO) {
        val root = File(location).canonicalFile
        // File edits rescan a single file; only folders are library roots, and rescans already cover folders inside one.
        if (root.isDirectory && library.localRoots().none { root.toPath().startsWith(File(it).canonicalFile.toPath()) })
            library.rememberLocalRoot(root.path)
        // Songs the user chose stay wherever they are, also when a rescan follows the folder rules.
        if (!followRules) library.rememberChosenSong(root.path)
        val folders = library.localFolderRules()
        val chosen = library.chosenSongs()
        // Relative folder rules name folders inside a library folder, also when only a folder inside it is scanned again.
        val base = library.localRoots().map { File(it).canonicalFile }.filter { root.toPath().startsWith(it.toPath()) }
            .minByOrNull { it.path.length } ?: root
        val seen = mutableSetOf<String>()
        val previous = library.tracks().flatMap { track -> track.sources.map { it.id to track } }.toMap()
        val folderCovers = mutableMapOf<File?, File?>()
        val files = root.musicFiles().filter { (_, canonical) -> !followRules || canonical.path in chosen ||
            folders.allows(canonical.parent.orEmpty(), canonical.parentFile?.relativeToOrNull(base)?.path) }.toList()
        onProgress(LocalScanProgress(0, files.size))
        val imported = files.mapIndexedNotNull { index, (file, canonical) ->
            try {
            val sourceId = "local:${canonical.path}"
            val cached = previous[sourceId]
            val source = cached?.sources?.firstOrNull { it.id == sourceId }
            if (!force && source?.available == true && source.modifiedAt == file.lastModified() &&
                source.indexedSize > 0 && source.indexedSize == file.length() && source.indexFormat == LOCAL_INDEX_FORMAT &&
                (!source.hasArtwork || artwork == null || artwork.path(cached.id) != null)) {
                seen += sourceId
                return@mapIndexedNotNull cached
            }
            val audio = try { AudioFileIO.read(file) } catch (_: Exception) { return@mapIndexedNotNull null }
            val metadata = audio.trackMetadata(file.nameWithoutExtension, canonical.parent.orEmpty())
            val cover = audio.fieldTag?.firstArtwork?.binaryData
                ?: folderCovers.getOrPut(canonical.parentFile) { folderCover(canonical.parentFile) }?.let { image ->
                    try { image.readBytes() } catch (_: java.io.IOException) { null }
                }
            val track = library.importTrack(metadata, TrackSource(sourceId,
                SourceKind.LOCAL, canonical.path, modifiedAt = file.lastModified(), indexedSize = file.length(),
                hasArtwork = cover != null, indexFormat = LOCAL_INDEX_FORMAT))
            seen += sourceId
            if (cover != null) {
                try { artwork?.save(track.id, cover) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* A bad cover cannot make its audio unplayable. */ }
            }
            else if (track.sources.all { it.kind == SourceKind.LOCAL }) artwork?.remove(track.id)
            track
            } finally { onProgress(LocalScanProgress(index + 1, files.size)) }
        }
        val missing = library.tracks().flatMap { it.sources }.filter {
            it.kind == SourceKind.LOCAL && it.id !in seen && File(it.location).canonicalFile.toPath().startsWith(root.toPath())
        }.map { it.id }.toSet()
        library.setSourceAvailability(missing, false)
        imported
    }
}

private val folderCoverNames = listOf("cover", "folder", "front", "albumart", "album")

/** The cover image beside songs without embedded artwork, such as cover.jpg or Folder.png, preferring the names in that order. */
internal fun folderCover(folder: File?): File? = folder?.listFiles { file ->
    file.isFile && file.extension.lowercase() in setOf("jpg", "jpeg", "png") && file.nameWithoutExtension.lowercase() in folderCoverNames
}?.minWithOrNull(compareBy<File> { folderCoverNames.indexOf(it.nameWithoutExtension.lowercase()) }.thenBy { it.name })

/** The music files in this folder, each with its resolved path. */
private fun File.musicFiles() = walkMusicFolders().filter { it.isFile && it.extension.lowercase() in localMusicExtensions }
    .map { it to it.canonicalFile }

// A symbolic link back to a parent folder would otherwise be walked again and again.
private fun File.walkMusicFolders(): FileTreeWalk {
    val entered = mutableSetOf<String>()
    return walkTopDown().onEnter { it.name != ".nevumi-edits" && entered.add(it.canonicalPath) }
}
