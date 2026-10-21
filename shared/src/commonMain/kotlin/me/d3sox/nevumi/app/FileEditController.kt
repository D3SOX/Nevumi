package me.d3sox.nevumi.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.library.MusicFileEdits
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TagChanges
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.sftp.ServerFiles

/** Edits music files on disk or on the SFTP server: tags, covers, names and deletion, with the undo journal behind them. */
class FileEditController internal constructor(private val context: ControllerContext, private val loader: AppStateLoader,
    private val fileEdits: MusicFileEdits?, private val serverFiles: ServerFiles?, private val preferences: MusicPreferences?) {
    val fileEditingAvailable: Boolean get() = fileEdits != null || serverFiles != null
    val localFileEditingAvailable: Boolean get() = fileEdits != null
    fun canEditServerTrack(track: Track): Boolean {
        val configuration = context.state.value.sftp ?: return false
        return serverFiles != null && configuration.serverId == context.state.value.server?.serverId && track.sources.any {
            it.kind == SourceKind.SERVER && it.available && it.id.startsWith("server:${configuration.serverId}:") &&
                it.fileLibraryId == configuration.libraryId && it.filePath != null
        }
    }
    /** Reads the server tags while the user picks a file action for [track], so the server tag editor opens sooner. */
    fun prepareServerFileEdit(track: Track) {
        val files = serverFiles ?: return
        if (canEditServerTrack(track)) context.scope.launch { files.prepareEdit(track.id) }
    }
    private fun fileEditor(target: FileEditTarget): MusicFileEdits = requireNotNull(
        if (target == FileEditTarget.SERVER) serverFiles else fileEdits) { "File editing is unavailable" }
    private fun journalEditor(editId: String): MusicFileEdits {
        val edit = requireNotNull(context.state.value.fileEdits.firstOrNull { it.id == editId }) { "File edit is missing" }
        return fileEditor(if (edit.providerId.startsWith("sftp:")) FileEditTarget.SERVER else FileEditTarget.LOCAL)
    }
    suspend fun musicFileName(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL) = fileEditor(target).fileName(trackId)
    suspend fun fileTags(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL): TrackMetadata = fileEditor(target).readTags(trackId)
    fun editTags(trackId: String, metadata: TrackMetadata, target: FileEditTarget = FileEditTarget.LOCAL) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try {
            fileEditor(target).editTags(trackId, metadata)
            trimFileHistory()
        } finally { context.operations.withLock { loader.refresh() } }
    }
    fun undoFileEdit(editId: String) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try {
            journalEditor(editId).undo(editId)
            trimFileHistory()
        } finally { context.operations.withLock { loader.refresh() } }
    }
    fun editTags(trackIds: List<String>, changes: TagChanges, target: FileEditTarget = FileEditTarget.LOCAL) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try {
            fileEditor(target).editTags(trackIds, changes)
            trimFileHistory()
        }
        finally { context.operations.withLock { loader.refresh() } }
    }
    fun replaceArtwork(trackId: String, bytes: ByteArray, target: FileEditTarget = FileEditTarget.LOCAL) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try {
            fileEditor(target).replaceArtwork(trackId, bytes)
            trimFileHistory()
        } finally { context.operations.withLock { loader.refresh() } }
    }
    fun renameFile(trackId: String, name: String, target: FileEditTarget = FileEditTarget.LOCAL) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try {
            fileEditor(target).rename(trackId, name)
            trimFileHistory()
        }
        finally { context.operations.withLock { loader.refresh() } }
    }
    fun deleteFile(trackId: String, target: FileEditTarget = FileEditTarget.LOCAL) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try {
            fileEditor(target).delete(trackId)
            trimFileHistory()
        }
        finally { context.operations.withLock { loader.refresh() } }
    }
    fun restoreOriginalFile(editId: String) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try {
            journalEditor(editId).restoreOriginal(editId)
            trimFileHistory()
        } finally { context.operations.withLock { loader.refresh() } }
    }
    fun applyFileEdit(editId: String) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        try {
            journalEditor(editId).apply(editId)
            trimFileHistory()
        } finally { context.operations.withLock { loader.refresh() } }
    }
    fun discardFileEdit(editId: String) = context.action(serialized = false) {
        context.update { it.copy(error = null) }
        journalEditor(editId).discard(editId)
        context.operations.withLock { loader.refresh() }
    }
    /** Finishes the file changes of [target] that a crash interrupted. */
    internal suspend fun recover(target: FileEditTarget) { (if (target == FileEditTarget.SERVER) serverFiles else fileEdits)?.recover() }
    internal suspend fun trimFileHistory() {
        val settings = preferences?.load() ?: MusicSettings()
        fileEdits?.trimHistory(settings.undoRetentionDays * 86_400_000L, settings.undoBackupBytes)
        serverFiles?.trimHistory(settings.undoRetentionDays * 86_400_000L, settings.undoBackupBytes)
    }
}
