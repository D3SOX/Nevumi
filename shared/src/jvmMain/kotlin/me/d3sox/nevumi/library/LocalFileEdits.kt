package me.d3sox.nevumi.library

import me.d3sox.nevumi.storage.MusicStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.id3.valuepair.ImageFormats
import org.jaudiotagger.tag.wav.WavTag
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.security.MessageDigest
import java.io.InputStream
import java.io.IOException

class LocalFileEdits(private val store: MusicStore, private val library: MusicLibrary, backupDirectory: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val files: FileEditAccess = FileSystemEditAccess,
    private val localSources: LocalSources = LocalFiles(library)) : MusicFileEdits {
    private val backups = File(backupDirectory)
    private val edits = Mutex()

    private fun editableSource(track: Track): TrackSource = requireNotNull(track.sources.firstOrNull { files.sourceLocation(it) != null }) {
        "An available music file is required"
    }

    private suspend fun readEdit(id: String): LocalFileEdit? = store.read<LocalFileEdit>("file-edit:$id")?.also {
        check(it.providerId == files.providerId) { "Reconnect to the original file location before using this edit" }
    }

    override suspend fun linkTracks(keepId: String, otherId: String): Track = edits.withLock { library.linkTracks(keepId, otherId) }

    override suspend fun delete(trackId: String): LocalFileEdit = edits.withLock { withContext(Dispatchers.IO) {
        val track = requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        val source = editableSource(track)
        val location = files.normalize(requireNotNull(files.sourceLocation(source)))
        check(!files.deleted(location)) { "The music file is missing" }
        Files.createDirectories(backups.toPath())
        val id = UUID.randomUUID().toString()
        val backup = backups.resolve("$id.${files.name(location).substringAfterLast('.')}")
        files.copy(location, backup)
        val hash = fileHash(backup)
        check(sourceHash(location) == hash) { "The music file changed while its backup was made" }
        val edit = LocalFileEdit(id, trackId, location, backup.path, hash, hash, "", now(), writing = true,
            operation = FileEditOperation.DELETE, sourceId = source.id, originalName = files.name(location), originalFolder = files.folder(location), providerId = files.providerId)
        store.put("file-edit:$id", "file-edits", edit)
        files.delete(location)
        library.setSourceAvailability(setOf(source.id), false)
        val completed = edit.copy(applied = true, writing = false)
        store.put("file-edit:$id", "file-edits", completed)
        localSources.scan(files.scanLocation(location), force = true)
        completed
    } }

    override suspend fun fileName(trackId: String): String = withContext(Dispatchers.IO) {
        val track = requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        val source = editableSource(track)
        files.name(files.normalize(requireNotNull(files.sourceLocation(source))))
    }

    override suspend fun readTags(trackId: String): TrackMetadata = edits.withLock { withContext(Dispatchers.IO) {
        val track = requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        val source = editableSource(track)
        val location = files.normalize(requireNotNull(files.sourceLocation(source)))
        val name = files.name(location)
        val directory = Files.createDirectories(backups.resolve(".read-tags").toPath())
        val staged = Files.createTempFile(directory, "read-tags-", ".${name.substringAfterLast('.')}")
        try {
            files.open(location).use { input -> Files.newOutputStream(staged).use { input.copyTo(it) } }
            val audio = AudioFileIO.read(staged.toFile())
            val container = audio.tag
            val tag = if (container is WavTag && container.isExistingId3Tag) container.getID3Tag() else container
            fun field(key: FieldKey): String = try { tag?.getFirst(key).orEmpty().trimEnd('\u0000') }
                catch (_: UnsupportedOperationException) { "" }
            val number = field(FieldKey.TRACK).ifBlank {
                if (container is WavTag) container.infoTag.unrecognisedFields.firstOrNull { it.id == "IPRT" }?.content.orEmpty().trimEnd('\u0000') else ""
            }
            TrackMetadata(field(FieldKey.TITLE).ifBlank { name.substringBeforeLast('.') }, field(FieldKey.ARTIST), field(FieldKey.ALBUM),
                audio.audioHeader.trackLength.toLong() * 1_000,
                tag?.getAll(FieldKey.GENRE).orEmpty().map { it.trimEnd('\u0000') }.filter { it.isNotBlank() },
                field(FieldKey.ALBUM_ARTIST), number.substringBefore('/').toIntOrNull() ?: 0,
                field(FieldKey.DISC_NO).substringBefore('/').toIntOrNull() ?: 1, files.folder(location),
                field(FieldKey.YEAR).take(4).toIntOrNull() ?: 0,
                field(FieldKey.MUSICBRAINZ_TRACK_ID), field(FieldKey.MUSICBRAINZ_RELEASEID))
        } finally { Files.deleteIfExists(staged) }
    } }

    override suspend fun history(): List<LocalFileEdit> = store.list<LocalFileEdit>("file-edits").filter { it.providerId == files.providerId }.sortedBy { it.createdAt }
    override suspend fun trimHistory(maxAgeMs: Long, maxBytes: Long) = edits.withLock { withContext(Dispatchers.IO) {
        require(maxAgeMs >= 0 && maxBytes >= 0) { "Undo retention limits cannot be negative" }
        val records = history()
        val removable = records.filter { !it.writing && !it.needsRecovery && (it.applied || it.undone) }
        val expired = removable.filter { it.createdAt < now() - maxAgeMs }.toMutableSet()
        val newest = removable.lastOrNull { !it.undone && it !in expired }?.id
        fun size(edit: LocalFileEdit) = File(edit.backupPath).length() +
            if (edit.stagedPath.isNotBlank()) File(edit.stagedPath).length() else 0L
        var retainedBytes = records.filterNot { it in expired }.sumOf(::size)
        for (edit in removable.filterNot { it in expired }) {
            if (retainedBytes <= maxBytes) break
            if (maxBytes > 0 && edit.id == newest) continue
            expired += edit
            retainedBytes -= size(edit)
        }
        for (edit in expired) {
            Files.deleteIfExists(File(edit.backupPath).toPath())
            if (edit.stagedPath.isNotBlank()) Files.deleteIfExists(File(edit.stagedPath).toPath())
            store.database.records().delete("file-edit:${edit.id}")
        }
        val referenced = records.map { File(it.backupPath).canonicalPath }.toSet()
        for (file in backups.listFiles().orEmpty()) {
            val ownedName = try { UUID.fromString(file.nameWithoutExtension).toString() == file.nameWithoutExtension }
                catch (_: IllegalArgumentException) { false }
            if (file.isFile && ownedName && file.extension.lowercase() in setOf("mp3", "wav", "flac") && file.canonicalPath !in referenced)
                Files.deleteIfExists(file.toPath())
        }
    } }

    override suspend fun recover() = edits.withLock {
        history().filter { !it.undone && (!it.applied || it.writing) }.forEach { edit ->
            if (edit.operation == FileEditOperation.DELETE) {
                recoverDeletion(edit)
                return@forEach
            }
            if (edit.operation == FileEditOperation.RENAME) {
                recoverRename(edit)
                return@forEach
            }
            val hash = withContext(Dispatchers.IO) {
                if (files.exists(edit.sourcePath)) sourceHash(edit.sourcePath) else null
            }
            if (edit.applied && hash == edit.originalHash) {
                store.put("file-edit:${edit.id}", "file-edits", edit.copy(undone = true, writing = false, needsRecovery = false, recoveryHash = null))
                localSources.scan(files.scanLocation(edit.sourcePath), force = true)
            } else if (!edit.applied && hash == edit.editedHash) applyPrepared(edit.id)
            else if (edit.writing) {
                val interrupted = hash != if (edit.applied) edit.editedHash else edit.originalHash
                store.put("file-edit:${edit.id}", "file-edits", edit.copy(writing = interrupted,
                    needsRecovery = interrupted, recoveryHash = if (interrupted) hash else null))
            }
        }
    }

    private suspend fun recoverDeletion(edit: LocalFileEdit) = withContext(Dispatchers.IO) {
        if (edit.applied && edit.destinationPath.isNotBlank() && files.exists(edit.destinationPath)) {
            val hash = sourceHash(edit.destinationPath)
            if (hash == edit.originalHash) {
                files.completeRestoration(edit.destinationPath)
                files.relocate(library, edit.trackId, edit.sourceId, edit.sourcePath, edit.destinationPath)
                localSources.scan(files.scanLocation(edit.destinationPath), force = true)
                store.put("file-edit:${edit.id}", "file-edits", edit.copy(undone = true, writing = false, needsRecovery = false))
            } else store.put("file-edit:${edit.id}", "file-edits", edit.copy(needsRecovery = true, recoveryHash = hash))
            return@withContext
        }
        if (files.deleted(edit.sourcePath)) {
            if (edit.destinationPath.isNotBlank() && edit.destinationPath != edit.sourcePath)
                files.releaseMove(library, edit.trackId, edit.destinationPath)
            library.setSourceAvailability(setOf(edit.sourceId), false)
            store.put("file-edit:${edit.id}", "file-edits", edit.copy(applied = true, writing = false, needsRecovery = false,
                destinationPath = "", recoveryHash = null))
        } else {
            val hash = sourceHash(edit.sourcePath)
            if (hash == edit.originalHash) {
                localSources.scan(files.scanLocation(edit.sourcePath), force = true)
                store.put("file-edit:${edit.id}", "file-edits", edit.copy(undone = true, writing = false, needsRecovery = false))
            } else store.put("file-edit:${edit.id}", "file-edits", edit.copy(needsRecovery = true, recoveryHash = hash))
        }
    }

    override suspend fun restoreOriginal(editId: String) = edits.withLock {
        withContext(Dispatchers.IO) {
            val edit = requireNotNull(readEdit(editId)) { "Edit is missing" }
            check(edit.needsRecovery) { "This edit does not need recovery" }
            val location = edit.recoveryPath.ifBlank {
                if (edit.operation == FileEditOperation.DELETE && edit.destinationPath.isNotBlank()) edit.destinationPath else edit.sourcePath
            }
            val hash = if (files.exists(location)) sourceHash(location) else null
            check(hash == edit.recoveryHash) { "The music file changed since recovery was reviewed. Refresh before restoring it." }
            if (edit.operation == FileEditOperation.RENAME && location != edit.sourcePath) {
                check(!files.exists(edit.sourcePath)) { "A file with the original name already exists" }
                files.reserveMove(library, edit.trackId, edit.sourcePath)
            }
            val backup = File(edit.backupPath)
            check(backup.isFile && fileHash(backup) == edit.originalHash) { "The original backup is unavailable or damaged" }
            val directory = Files.createDirectories(files.stagingDirectory(location).toPath())
            val staged = Files.createTempFile(directory, "restore-", ".part")
            val previous = directory.resolve("before-restore-${UUID.randomUUID()}.part").toFile()
            try {
                if (hash != null) files.copy(location, previous)
                Files.copy(backup.toPath(), staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                if (hash == null) files.replace(location, staged.toFile())
                else replaceSafely(location, staged.toFile(), previous, hash)
                if (edit.operation == FileEditOperation.DELETE) {
                    files.completeRestoration(location)
                    files.relocate(library, edit.trackId, edit.sourceId, edit.sourcePath, location)
                }
                if (edit.operation == FileEditOperation.RENAME) {
                    if (files.name(location) != edit.originalName) files.rename(location, edit.originalName)
                    finishRename(edit, undone = true)
                    return@withContext
                }
                store.put("file-edit:$editId", "file-edits", edit.copy(undone = true, writing = false, needsRecovery = false, recoveryHash = null))
                localSources.scan(files.scanLocation(location), force = true)
                if (edit.stagedPath.isNotBlank()) Files.deleteIfExists(File(edit.stagedPath).toPath())
                Unit
            } finally { Files.deleteIfExists(staged); Files.deleteIfExists(previous.toPath()) }
        }
    }

    override suspend fun apply(editId: String): LocalFileEdit = edits.withLock { applyPrepared(editId) }
    private suspend fun applyFresh(edit: LocalFileEdit): LocalFileEdit = edits.withLock { applyPrepared(edit.id, fresh = true) }

    override suspend fun discard(editId: String) = edits.withLock { withContext(Dispatchers.IO) {
        val edit = requireNotNull(readEdit(editId)) { "Edit is missing" }
        check(!edit.applied && !edit.writing && !edit.needsRecovery) { "Recover or undo this edit before removing its backup" }
        if (edit.stagedPath.isNotBlank()) Files.deleteIfExists(File(edit.stagedPath).toPath())
        Files.deleteIfExists(File(edit.backupPath).toPath())
        store.database.records().delete("file-edit:$editId")
    } }

    private suspend fun applyPrepared(editId: String, fresh: Boolean = false): LocalFileEdit = withContext(Dispatchers.IO) {
            val edit = requireNotNull(readEdit(editId)) { "Edit is missing" }
            check(!edit.undone) { "This edit was already undone" }
            // A remote file just matched its backup, and atomic replacement compares it again, so reading it once more only costs time.
            val hash = if (fresh && files.atomicWrites) edit.originalHash else sourceHash(edit.sourcePath)
            if (hash != edit.editedHash) {
                check(!edit.applied && hash == edit.originalHash) {
                    "The music file changed outside Nevumi. Applying this edit would overwrite those changes."
                }
                val staged = File(edit.stagedPath)
                check(staged.isFile && fileHash(staged) == edit.editedHash) { "The prepared edit is unavailable or damaged" }
                store.put("file-edit:$editId", "file-edits", edit.copy(writing = true))
                replaceSafely(edit.sourcePath, staged, File(edit.backupPath), edit.originalHash)
            }
            val completed = edit.copy(applied = true, writing = false, needsRecovery = false, recoveryHash = null)
            store.put("file-edit:$editId", "file-edits", completed)
            localSources.scan(files.scanLocation(edit.sourcePath), force = true)
            Files.deleteIfExists(File(edit.stagedPath).toPath())
            completed
    }

    override suspend fun undo(editId: String) = edits.withLock {
        withContext(Dispatchers.IO) {
            val edit = requireNotNull(readEdit(editId)) { "Edit is missing" }
            check(!edit.undone) { "This edit was already undone" }
            check(edit.applied) { "The edit has not been applied" }
            if (edit.operation == FileEditOperation.DELETE) {
                check(files.deleted(edit.sourcePath)) { "A music file already exists there. Undo would overwrite it." }
                check(!files.exists(edit.sourcePath) || sourceHash(edit.sourcePath) == edit.originalHash) { "The trashed music file changed outside Nevumi." }
                val backup = File(edit.backupPath)
                check(backup.isFile && fileHash(backup) == edit.originalHash) { "The original backup is unavailable or damaged" }
                store.put("file-edit:$editId", "file-edits", edit.copy(writing = true))
                val restored = files.restoreDeleted(edit.sourcePath, backup, edit.originalName, edit.originalFolder) { location ->
                    store.put("file-edit:$editId", "file-edits", edit.copy(writing = true, destinationPath = location))
                    files.reserveMove(library, edit.trackId, location)
                }
                files.relocate(library, edit.trackId, edit.sourceId, edit.sourcePath, restored)
                localSources.scan(files.scanLocation(restored), force = true)
                store.put("file-edit:$editId", "file-edits", edit.copy(destinationPath = restored, undone = true, writing = false))
                return@withContext
            }
            if (edit.operation == FileEditOperation.RENAME) {
                undoRename(edit)
                return@withContext
            }
            check(files.exists(edit.sourcePath) && sourceHash(edit.sourcePath) == edit.editedHash) {
                "The music file changed outside Nevumi. Undo would overwrite those changes."
            }
            val backup = File(edit.backupPath)
            check(backup.isFile && fileHash(backup) == edit.originalHash) { "The original backup is unavailable or damaged" }
            val stagingDirectory = Files.createDirectories(files.stagingDirectory(edit.sourcePath).toPath())
            val staged = Files.createTempFile(stagingDirectory, "undo-", ".${files.name(edit.sourcePath).substringAfterLast('.')}")
            val previous = stagingDirectory.resolve("before-undo-${UUID.randomUUID()}.part").toFile()
            try {
                files.copy(edit.sourcePath, previous)
                Files.copy(backup.toPath(), staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                store.put("file-edit:$editId", "file-edits", edit.copy(writing = true))
                replaceSafely(edit.sourcePath, staged.toFile(), previous, edit.editedHash)
                localSources.scan(files.scanLocation(edit.sourcePath), force = true)
                store.put("file-edit:$editId", "file-edits", edit.copy(undone = true, writing = false, needsRecovery = false, recoveryHash = null))
            } finally { Files.deleteIfExists(staged); Files.deleteIfExists(previous.toPath()) }
        }
    }

    override suspend fun editTags(trackId: String, metadata: TrackMetadata): LocalFileEdit = applyFresh(prepareTags(trackId, metadata))

    override suspend fun rename(trackId: String, name: String): LocalFileEdit = edits.withLock { withContext(Dispatchers.IO) {
        require(name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }) { "Choose a file name without folders" }
        val track = requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        val source = editableSource(track)
        val location = files.normalize(requireNotNull(files.sourceLocation(source)))
        val previousName = files.name(location)
        require(name != previousName) { "The file already has that name" }
        val extension = previousName.substringAfterLast('.').lowercase()
        require(name.substringAfterLast('.').lowercase() == extension) { "Keep the .$extension extension" }
        val destination = files.renamedLocation(location, name)
        check(destination == location || !files.exists(destination)) { "A file with that name already exists" }
        Files.createDirectories(backups.toPath())
        val id = UUID.randomUUID().toString()
        val backup = backups.resolve("$id.$extension")
        files.copy(location, backup)
        val hash = fileHash(backup)
        check(sourceHash(location) == hash) { "The music file changed while its backup was made" }
        val edit = LocalFileEdit(id, trackId, location, backup.path, hash, hash, "", now(), writing = true,
            operation = FileEditOperation.RENAME, sourceId = source.id, destinationPath = destination, originalName = previousName, changedName = name, providerId = files.providerId)
        store.put("file-edit:$id", "file-edits", edit)
        files.reserveMove(library, trackId, destination)
        files.rename(location, name)
        finishRename(edit, undone = false)
    } }

    private suspend fun finishRename(edit: LocalFileEdit, undone: Boolean): LocalFileEdit {
        val location = if (undone) edit.sourcePath else edit.destinationPath
        val sourceId = if (undone) files.movedSourceId(edit.sourceId, edit.destinationPath) else edit.sourceId
        files.relocate(library, edit.trackId, sourceId, if (undone) edit.destinationPath else edit.sourcePath, location)
        localSources.scan(files.scanLocation(location), force = true)
        val completed = edit.copy(applied = true, undone = undone, writing = false, needsRecovery = false, recoveryHash = null)
        store.put("file-edit:${edit.id}", "file-edits", completed)
        return completed
    }

    private suspend fun undoRename(edit: LocalFileEdit) {
        check(files.exists(edit.destinationPath) && files.name(edit.destinationPath) == edit.changedName && sourceHash(edit.destinationPath) == edit.editedHash) {
            "The renamed music file changed outside Nevumi. Undo would overwrite those changes."
        }
        check(edit.sourcePath == edit.destinationPath || !files.exists(edit.sourcePath)) { "A file with the original name already exists" }
        store.put("file-edit:${edit.id}", "file-edits", edit.copy(writing = true))
        files.reserveMove(library, edit.trackId, edit.sourcePath)
        files.rename(edit.destinationPath, edit.originalName)
        finishRename(edit, undone = true)
    }

    private suspend fun recoverRename(edit: LocalFileEdit) = withContext(Dispatchers.IO) {
        val atDestination = files.exists(edit.destinationPath) && files.name(edit.destinationPath) == edit.changedName &&
            sourceHash(edit.destinationPath) == edit.editedHash && (edit.sourcePath == edit.destinationPath || !files.exists(edit.sourcePath))
        val atOriginal = files.exists(edit.sourcePath) && files.name(edit.sourcePath) == edit.originalName && sourceHash(edit.sourcePath) == edit.originalHash
        when {
            atDestination -> finishRename(edit, undone = false)
            atOriginal && edit.applied -> finishRename(edit, undone = true)
            atOriginal -> {
                if (edit.sourcePath != edit.destinationPath) files.releaseMove(library, edit.trackId, edit.destinationPath)
                store.put("file-edit:${edit.id}", "file-edits", edit.copy(undone = true, writing = false))
            }
            else -> {
                val location = if (files.exists(edit.destinationPath) && files.name(edit.destinationPath) == edit.changedName)
                    edit.destinationPath else edit.sourcePath
                val hash = if (files.exists(location)) sourceHash(location) else null
                store.put("file-edit:${edit.id}", "file-edits", edit.copy(needsRecovery = true, recoveryHash = hash, recoveryPath = location))
            }
        }
    }

    override suspend fun editTags(trackIds: List<String>, changes: TagChanges): List<LocalFileEdit> {
        val completed = mutableListOf<LocalFileEdit>()
        val failures = mutableListOf<String>()
        for (id in trackIds.distinct()) {
            val track = library.findTrack(id)
            try {
                requireNotNull(track) { "Track is missing" }
                completed += applyFresh(prepare(id) { tag ->
                    changes.title?.let { tag.setField(FieldKey.TITLE, it) }
                    changes.artist?.let { tag.setFieldOrDelete(FieldKey.ARTIST, it) }
                    changes.album?.let { tag.setFieldOrDelete(FieldKey.ALBUM, it) }
                    changes.albumArtist?.let { tag.setFieldOrDelete(FieldKey.ALBUM_ARTIST, it) }
                    changes.genres?.let { genres ->
                        tag.deleteField(FieldKey.GENRE)
                        genres.forEach { tag.addField(FieldKey.GENRE, it) }
                    }
                    changes.trackNumber?.let { tag.setFieldOrDelete(FieldKey.TRACK, it.takeIf { it > 0 }?.toString().orEmpty()) }
                    changes.discNumber?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }
                    changes.year?.let {
                        require(it in 0..9999) { "Year must be between 1 and 9999, or empty" }
                        tag.setFieldOrDelete(FieldKey.YEAR, it.takeIf { value -> value > 0 }?.toString().orEmpty())
                    }
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val name = track?.sources?.firstOrNull { it.kind == SourceKind.LOCAL }?.location
                    ?.let { location -> try { files.name(location) } catch (_: Exception) { null } }
                    ?: track?.metadata?.title?.takeIf { it.isNotBlank() } ?: id
                failures += "$name: ${error.message ?: "Edit failed"}"
            }
        }
        if (failures.isNotEmpty()) throw IOException("${completed.size} songs saved. ${failures.size} edits failed:\n${failures.joinToString("\n")}")
        return completed
    }

    override suspend fun replaceArtwork(trackId: String, original: ByteArray): LocalFileEdit {
        val bytes = me.d3sox.nevumi.artwork.artworkPreview(original)
        val mime = ImageFormats.getMimeTypeForBinarySignature(bytes)
        require(mime in setOf("image/png", "image/jpeg")) { "Choose a PNG or JPEG image" }
        val (width, height) = artworkSize(bytes)
        require(width > 0 && height > 0 && width.toLong() * height <= 16_000_000) { "Choose a valid image of at most 16 megapixels" }
        val artwork = object : AndroidArtwork() {
            // FLAC calls this even after dimensions have been read by the platform decoder.
            override fun setImageFromData() = true
        }.apply {
            binaryData = bytes; mimeType = mime; pictureType = 3
            this.width = width; this.height = height
        }
        return applyFresh(prepare(trackId) { tag -> tag.deleteArtworkField(); tag.setField(artwork) })
    }

    suspend fun prepareTags(trackId: String, metadata: TrackMetadata): LocalFileEdit = prepare(trackId) { tag ->
        tag.setField(FieldKey.TITLE, metadata.title)
        tag.setFieldOrDelete(FieldKey.ARTIST, metadata.artist)
        tag.setFieldOrDelete(FieldKey.ALBUM, metadata.album)
        tag.setFieldOrDelete(FieldKey.ALBUM_ARTIST, metadata.albumArtist)
        tag.deleteField(FieldKey.GENRE)
        metadata.genres.forEach { tag.addField(FieldKey.GENRE, it) }
        tag.setFieldOrDelete(FieldKey.TRACK, metadata.trackNumber.takeIf { it > 0 }?.toString().orEmpty())
        tag.setField(FieldKey.DISC_NO, metadata.discNumber.toString())
        require(metadata.year in 0..9999) { "Year must be between 1 and 9999, or empty" }
        tag.setFieldOrDelete(FieldKey.YEAR, metadata.year.takeIf { it > 0 }?.toString().orEmpty())
    }

    // ID3v2.3 cannot build an empty year frame, so an empty value removes the field instead.
    private fun Tag.setFieldOrDelete(key: FieldKey, value: String) = if (value.isEmpty()) deleteField(key) else setField(key, value)

    private suspend fun prepare(trackId: String, change: (Tag) -> Unit): LocalFileEdit = edits.withLock {
        withContext(Dispatchers.IO) {
            val track = requireNotNull(library.findTrack(trackId)) { "Track is missing" }
            val source = editableSource(track)
            val location = files.normalize(requireNotNull(files.sourceLocation(source)))
            require(files.exists(location)) { "The music file is missing" }
            val extension = files.name(location).substringAfterLast('.', "").lowercase()
            require(extension in setOf("mp3", "wav", "flac")) { "Unsupported music format" }
            Files.createDirectories(backups.toPath())
            val id = UUID.randomUUID().toString()
            val backup = backups.resolve("$id.$extension")
            val stagingDirectory = Files.createDirectories(files.stagingDirectory(location).toPath())
            val staged = Files.createTempFile(stagingDirectory, "edit-", ".$extension")
            var prepared = false
            try {
                files.copy(location, backup)
                Files.copy(backup.toPath(), staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                val audio = AudioFileIO.read(staged.toFile())
                val containerTag = audio.tagOrCreateAndSetDefault
                val tag = if (containerTag is WavTag) {
                    containerTag.syncToId3FromInfoIfEmpty()
                    if (containerTag.getID3Tag() == null) containerTag.setID3Tag(WavTag.createDefaultID3Tag())
                    containerTag.getID3Tag()
                } else containerTag
                change(tag)
                if (containerTag is WavTag) containerTag.syncToInfoFromId3Overwrite()
                audio.commit()
                AudioFileIO.read(staged.toFile())
                val result = LocalFileEdit(id, trackId, location, backup.path, fileHash(backup), fileHash(staged.toFile()), staged.toString(), now(), sourceId = source.id, providerId = files.providerId)
                store.put("file-edit:$id", "file-edits", result)
                prepared = true
                result
            } finally {
                if (!prepared) Files.deleteIfExists(staged)
            }
        }
    }

    private fun sourceHash(location: String) = files.open(location).use(::streamHash)
    private fun fileHash(file: File) = file.inputStream().use(::streamHash)

    private suspend fun replaceSafely(location: String, staged: File, original: File, originalHash: String) {
        check(original.isFile && fileHash(original) == originalHash) { "The original backup is unavailable or damaged" }
        try { files.replace(location, staged, originalHash) } catch (error: IOException) {
            if (files.atomicWrites) throw error
            withContext(NonCancellable) {
                try {
                    if (!files.exists(location) || sourceHash(location) != originalHash) {
                        val rollback = Files.createTempFile(Files.createDirectories(files.stagingDirectory(location).toPath()), "rollback-", ".part")
                        try {
                            Files.copy(original.toPath(), rollback, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                            files.replace(location, rollback.toFile())
                        } finally { Files.deleteIfExists(rollback) }
                    }
                } catch (rollback: Exception) { error.addSuppressed(rollback) }
            }
            throw error
        }
    }

    private fun streamHash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
