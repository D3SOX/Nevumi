package me.d3sox.nevumi.library

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.security.MessageDigest

interface FileEditAccess {
    val providerId: String get() = "local"
    val atomicWrites: Boolean get() = false
    fun sourceLocation(source: TrackSource): String? = source.location.takeIf { source.kind == SourceKind.LOCAL && source.available }
    suspend fun reserveMove(library: MusicLibrary, trackId: String, location: String) = library.reserveLocalMove(trackId, location)
    suspend fun releaseMove(library: MusicLibrary, trackId: String, location: String) = library.releaseLocalMove(location, trackId)
    suspend fun relocate(library: MusicLibrary, trackId: String, sourceId: String, from: String, to: String) = library.relocateLocalSource(trackId, sourceId, to)
    fun movedSourceId(originalId: String, location: String) = "local:$location"
    fun normalize(location: String): String
    fun name(location: String): String
    fun folder(location: String): String = FileSystemEditAccess.folder(location)
    fun exists(location: String): Boolean
    fun open(location: String): InputStream
    fun copy(location: String, destination: File)
    /** Copies what a tag edit needs, which is the whole file unless [holdsOnlyTags] says otherwise for its hash. */
    fun image(location: String, destination: File) = copy(location, destination)
    /** Hashes the file as its [image] is compared, or every byte with [whole]. */
    fun contentHash(location: String, whole: Boolean = false): String = open(location).use(::contentHash)
    fun contentHash(file: File, location: String, whole: Boolean = false): String = file.inputStream().use(::contentHash)
    /** Whether a copy with [hash] holds only the tags, so [replaceTags] has to take the audio from the file it replaces. */
    fun holdsOnlyTags(hash: String) = false
    /** The storage a copy with [hash] takes up, which for a sparse copy of only the tags is far less than its length. */
    fun storedSize(copy: File, hash: String): Long = copy.length()
    fun stagingDirectory(location: String): File
    suspend fun replace(location: String, staged: File, originalHash: String? = null)
    /** Replaces the file with a tag edit of an [image]. */
    suspend fun replaceTags(location: String, staged: File, originalHash: String) = replace(location, staged, originalHash)
    fun renamedLocation(location: String, name: String): String
    suspend fun rename(location: String, name: String)
    fun scanLocation(location: String): String
    fun deleted(location: String): Boolean = !exists(location)
    suspend fun delete(location: String) { throw UnsupportedOperationException("Deleting this file is unavailable") }
    suspend fun restoreDeleted(location: String, original: File, name: String, folder: String,
        reserve: suspend (String) -> Unit): String { throw UnsupportedOperationException("Restoring this file is unavailable") }
    suspend fun completeRestoration(location: String) {}
}

fun contentHash(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        digest.update(buffer, 0, size)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

object FileSystemEditAccess : FileEditAccess {
    private fun file(location: String) = (if (location.startsWith("file:")) File(URI(location)) else File(location)).canonicalFile
    override fun normalize(location: String) = file(location).path
    override fun name(location: String) = file(location).name
    override fun folder(location: String) = file(location).parent.orEmpty()
    override fun exists(location: String) = file(location).isFile
    override fun open(location: String) = file(location).inputStream()
    override fun copy(location: String, destination: File) {
        Files.copy(file(location).toPath(), destination.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
    }
    override fun stagingDirectory(location: String) = file(location).parentFile.resolve(".nevumi-edits")
    override suspend fun replace(location: String, staged: File, originalHash: String?) {
        val target = file(location).toPath()
        keepOwnership(target, staged.toPath())
        Files.move(staged.toPath(), target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    // The replacement is a new file owned by whoever runs Nevumi, so it takes the replaced file's owner, group and permissions.
    private fun keepOwnership(original: Path, replacement: Path) {
        val attributes = try { Files.readAttributes(original, PosixFileAttributes::class.java) }
            catch (_: UnsupportedOperationException) { return } catch (_: IOException) { return }
        val view = Files.getFileAttributeView(replacement, PosixFileAttributeView::class.java) ?: return
        view.setPermissions(attributes.permissions())
        // Only root may give a file away, and other users only to their own groups; otherwise the replacement stays theirs.
        try { view.setGroup(attributes.group()) } catch (_: IOException) {}
        try { view.setOwner(attributes.owner()) } catch (_: IOException) {}
    }
    override fun scanLocation(location: String) = normalize(location)
    override fun renamedLocation(location: String, name: String) = requireNotNull(file(location).parentFile).resolve(name).path
    override suspend fun rename(location: String, name: String) {
        Files.move(file(location).toPath(), File(renamedLocation(location, name)).toPath())
    }
    override suspend fun delete(location: String) { Files.delete(file(location).toPath()) }
    override suspend fun restoreDeleted(location: String, original: File, name: String, folder: String,
        reserve: suspend (String) -> Unit): String {
        val directory = Files.createDirectories(stagingDirectory(location).toPath())
        val staged = Files.createTempFile(directory, "restore-deleted-", ".part")
        try {
            Files.copy(original.toPath(), staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            reserve(location)
            Files.move(staged, file(location).toPath())
        } finally { Files.deleteIfExists(staged) }
        return location
    }
}
