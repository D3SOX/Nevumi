package me.d3sox.nevumi.library

import kotlinx.serialization.Serializable

data class TagChanges(val title: String? = null, val artist: String? = null, val album: String? = null,
    val albumArtist: String? = null, val genres: List<String>? = null, val trackNumber: Int? = null, val discNumber: Int? = null,
    val year: Int? = null) {
    internal fun applyTo(metadata: TrackMetadata) = metadata.copy(title = title ?: metadata.title, artist = artist ?: metadata.artist,
        album = album ?: metadata.album, albumArtist = albumArtist ?: metadata.albumArtist, genres = genres ?: metadata.genres,
        trackNumber = trackNumber ?: metadata.trackNumber, discNumber = discNumber ?: metadata.discNumber,
        year = year ?: metadata.year)
}

@Serializable
enum class FileEditOperation { CONTENT, RENAME, DELETE }

@Serializable
data class LocalFileEdit(val id: String, val trackId: String, val sourcePath: String, val backupPath: String,
    val originalHash: String, val editedHash: String, val stagedPath: String,
    val createdAt: Long,
    val applied: Boolean = false, val undone: Boolean = false, val writing: Boolean = false,
    val needsRecovery: Boolean = false, val recoveryHash: String? = null,
    val operation: FileEditOperation = FileEditOperation.CONTENT, val sourceId: String = "",
    val destinationPath: String = "", val originalName: String = "", val changedName: String = "",
    val originalFolder: String = "", val recoveryPath: String = "", val providerId: String = "local")

interface MusicFileEdits {
    suspend fun fileName(trackId: String): String
    suspend fun readTags(trackId: String): TrackMetadata
    suspend fun linkTracks(keepId: String, otherId: String): Track
    /** Links (keep, other) pairs in batches; pairs whose songs are gone or busy with a download or file edit are skipped. */
    suspend fun linkTracks(pairs: List<Pair<String, String>>): List<Track>
    suspend fun history(): List<LocalFileEdit>
    suspend fun trimHistory(maxAgeMs: Long, maxBytes: Long)
    suspend fun recover()
    suspend fun apply(editId: String): LocalFileEdit
    suspend fun discard(editId: String)
    suspend fun editTags(trackId: String, metadata: TrackMetadata): LocalFileEdit
    suspend fun editTags(trackIds: List<String>, changes: TagChanges): List<LocalFileEdit>
    suspend fun replaceArtwork(trackId: String, bytes: ByteArray): LocalFileEdit
    suspend fun rename(trackId: String, name: String): LocalFileEdit
    suspend fun delete(trackId: String): LocalFileEdit
    suspend fun undo(editId: String)
    suspend fun restoreOriginal(editId: String)
}
