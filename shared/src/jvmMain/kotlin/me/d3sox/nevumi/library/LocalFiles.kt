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
val localMusicExtensions = setOf("mp3", "wav", "flac")

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
    val artist = field(FieldKey.ARTIST)
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
    )
}

class LocalFiles(private val library: MusicLibrary, private val artwork: ArtworkLibrary? = null) : LocalSources {
    // Earlier versions registered every edited file as a root; ignore those so they stop being rescanned.
    override suspend fun roots(): List<String> = library.localRoots().filterNot { File(it).isFile }

    override suspend fun pendingRefreshLocations(): List<String> = withContext(Dispatchers.IO) {
        val indexed = library.tracks().flatMap { it.sources }.filter { it.kind == SourceKind.LOCAL }.associateBy { it.id }
        val folders = library.localFolderRules()
        val chosen = library.chosenSongs()
        roots().filter { location ->
            if (location.startsWith("content:") || location == "device") return@filter false
            val root = File(location).canonicalFile
            val files = root.walkTopDown().onEnter { it.name != ".nevumi-edits" }.filter {
                it.isFile && it.extension.lowercase() in localMusicExtensions && (it.canonicalPath in chosen ||
                    folders.allows(it.canonicalFile.parent.orEmpty(), it.canonicalFile.parentFile?.relativeToOrNull(root)?.path))
            }.associateBy { "local:${it.canonicalPath}" }
            files.any { (id, file) ->
                val source = indexed[id]
                source == null || !source.available || source.modifiedAt != file.lastModified() || source.indexedSize != file.length()
            } || indexed.values.any {
                it.available && !it.location.startsWith("content:") && File(it.location).canonicalFile.toPath().startsWith(root.toPath()) && it.id !in files
            }
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
        val seen = mutableSetOf<String>()
        val previous = library.tracks().flatMap { track -> track.sources.map { it.id to track } }.toMap()
        val files = root.walkTopDown().onEnter { it.name != ".nevumi-edits" }.filter { it.isFile && it.extension.lowercase() in
            localMusicExtensions && (!followRules || it.canonicalPath in chosen || folders.allows(it.canonicalFile.parent.orEmpty(),
                it.canonicalFile.parentFile?.relativeToOrNull(root)?.path)) }.toList()
        onProgress(LocalScanProgress(0, files.size))
        val imported = files.mapIndexedNotNull { index, file ->
            try {
            val sourceId = "local:${file.canonicalPath}"
            val cached = previous[sourceId]
            val source = cached?.sources?.firstOrNull { it.id == sourceId }
            if (!force && source?.available == true && source.modifiedAt == file.lastModified() &&
                source.indexedSize > 0 && source.indexedSize == file.length() &&
                (!source.hasArtwork || artwork == null || artwork.path(cached.id) != null)) {
                seen += sourceId
                return@mapIndexedNotNull cached
            }
            val audio = try { AudioFileIO.read(file) } catch (_: Exception) { return@mapIndexedNotNull null }
            val metadata = audio.trackMetadata(file.nameWithoutExtension, file.canonicalFile.parent.orEmpty())
            val cover = audio.fieldTag?.firstArtwork?.binaryData
            val track = library.importTrack(metadata, TrackSource(sourceId,
                SourceKind.LOCAL, file.canonicalPath, modifiedAt = file.lastModified(), indexedSize = file.length(),
                hasArtwork = cover != null))
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
