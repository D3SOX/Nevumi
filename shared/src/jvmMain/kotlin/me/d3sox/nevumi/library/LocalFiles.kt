package me.d3sox.nevumi.library

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.wav.WavTag
import me.d3sox.nevumi.artwork.ArtworkLibrary

class LocalFiles(private val library: MusicLibrary, private val artwork: ArtworkLibrary? = null) : LocalSources {
    // Earlier versions registered every edited file as a root; ignore those so they stop being rescanned.
    override suspend fun roots(): List<String> = library.localRoots().filterNot { File(it).isFile }

    override suspend fun pendingRefreshLocations(): List<String> = withContext(Dispatchers.IO) {
        val indexed = library.tracks().flatMap { it.sources }.filter { it.kind == SourceKind.LOCAL }.associateBy { it.id }
        val folders = library.localFolderRules()
        roots().filter { location ->
            if (location.startsWith("content:") || location == "device") return@filter false
            val root = File(location).canonicalFile
            val files = root.walkTopDown().onEnter { it.name != ".nevumi-edits" }.filter {
                it.isFile && it.extension.lowercase() in setOf("mp3", "wav", "flac") &&
                    folders.allows(it.canonicalFile.parent.orEmpty(), it.canonicalFile.parentFile?.relativeToOrNull(root)?.path)
            }.associateBy { "local:${it.canonicalPath}" }
            files.any { (id, file) ->
                val source = indexed[id]
                source == null || !source.available || source.modifiedAt != file.lastModified() || source.indexedSize != file.length()
            } || indexed.values.any {
                it.available && !it.location.startsWith("content:") && File(it.location).canonicalFile.toPath().startsWith(root.toPath()) && it.id !in files
            }
        }
    }

    override suspend fun scan(location: String, force: Boolean, onProgress: suspend (LocalScanProgress) -> Unit): List<Track> = withContext(Dispatchers.IO) {
        val root = File(location).canonicalFile
        // File edits rescan a single file; only folders are library roots.
        if (root.isDirectory) library.rememberLocalRoot(root.path)
        val folders = library.localFolderRules()
        val seen = mutableSetOf<String>()
        val previous = library.tracks().flatMap { track -> track.sources.map { it.id to track } }.toMap()
        val files = root.walkTopDown().onEnter { it.name != ".nevumi-edits" }.filter { it.isFile && it.extension.lowercase() in
            setOf("mp3", "wav", "flac") && folders.allows(it.canonicalFile.parent.orEmpty(),
                it.canonicalFile.parentFile?.relativeToOrNull(root)?.path) }.toList()
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
            val containerTag = audio.tag
            val tag = if (containerTag is WavTag && containerTag.isExistingId3Tag) containerTag.getID3Tag() else containerTag
            fun field(key: FieldKey): String {
                val value = try { tag?.getFirst(key).orEmpty().trimEnd('\u0000') }
                    catch (_: UnsupportedOperationException) { "" }
                return if (key == FieldKey.TRACK && value.isBlank() && containerTag is WavTag)
                    containerTag.infoTag.unrecognisedFields.firstOrNull { it.id == "IPRT" }?.content.orEmpty().trimEnd('\u0000')
                else value
            }
            val metadata = TrackMetadata(
                title = tag?.getFirst(FieldKey.TITLE).orEmpty().trimEnd('\u0000').ifBlank { file.nameWithoutExtension },
                artist = tag?.getFirst(FieldKey.ARTIST).orEmpty().trimEnd('\u0000'),
                album = tag?.getFirst(FieldKey.ALBUM).orEmpty().trimEnd('\u0000'),
                durationMs = audio.audioHeader.trackLength.toLong() * 1_000,
                genres = tag?.getAll(FieldKey.GENRE).orEmpty().map { it.trimEnd('\u0000') }.filter { it.isNotBlank() },
                albumArtist = field(FieldKey.ALBUM_ARTIST).ifBlank { field(FieldKey.ARTIST) },
                trackNumber = field(FieldKey.TRACK).substringBefore('/').toIntOrNull() ?: 0,
                discNumber = field(FieldKey.DISC_NO).substringBefore('/').toIntOrNull() ?: 1,
                folder = file.canonicalFile.parent.orEmpty(),
                year = field(FieldKey.YEAR).take(4).toIntOrNull() ?: 0,
                recordingId = field(FieldKey.MUSICBRAINZ_TRACK_ID),
                releaseId = field(FieldKey.MUSICBRAINZ_RELEASEID),
            )
            val cover = tag?.firstArtwork?.binaryData
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
