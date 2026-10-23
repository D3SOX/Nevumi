package me.d3sox.nevumi.library

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    /** Whether [image] copies only a file's tags; [replaceTags] then takes the audio from the file it replaces. */
    val partialCopies: Boolean get() = false
    /** Copies what a tag edit needs, which is the whole file unless [partialCopies]. */
    fun image(location: String, destination: File) = copy(location, destination)
    fun contentHash(location: String): String = open(location).use(::contentHash)
    fun contentHash(file: File, location: String): String = file.inputStream().use(::contentHash)
    /** Whether [hash] came from this access's [contentHash], since older versions hashed differently. */
    fun recognizes(hash: String) = true
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
        Files.move(staged.toPath(), file(location).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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
