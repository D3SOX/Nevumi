package me.d3sox.nevumi.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Bundle
import me.d3sox.nevumi.storage.MusicStore
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import me.d3sox.nevumi.artwork.ArtworkLibrary

class AndroidFileEdits(context: Context, store: MusicStore, library: MusicLibrary, backupDirectory: String,
    requestWriteAccess: suspend (Uri) -> Boolean = { false }, artwork: ArtworkLibrary? = null) : MusicFileEdits by
    LocalFileEdits(store, library, backupDirectory, files = AndroidFileEditAccess(context, File(backupDirectory, ".staging"), requestWriteAccess),
        localSources = AndroidLocalSources(context, library, artwork))

private class AndroidFileEditAccess(private val context: Context, private val staging: File,
    private val requestWriteAccess: suspend (Uri) -> Boolean) : FileEditAccess {
    private val resolver get() = context.contentResolver
    private fun content(location: String) = location.startsWith("content:")
    override fun normalize(location: String) = if (content(location)) location else FileSystemEditAccess.normalize(location)
    override fun name(location: String): String {
        if (!content(location)) return FileSystemEditAccess.name(location)
        return requireNotNull(resolver.query(Uri.parse(location), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use { cursor ->
            check(cursor.moveToFirst()) { "The music file is missing" }
            cursor.getString(0)
        }
    }
    override fun folder(location: String): String {
        if (!content(location)) return FileSystemEditAccess.folder(location)
        return requireNotNull(resolver.query(Uri.parse(location), arrayOf(MediaStore.Audio.Media.RELATIVE_PATH), null, null, null)).use {
            check(it.moveToFirst()) { "The music file is missing" }; it.getString(0)
        }
    }
    override fun exists(location: String): Boolean = if (!content(location)) FileSystemEditAccess.exists(location) else
        try { resolver.openInputStream(Uri.parse(location))?.use { true } ?: false } catch (_: FileNotFoundException) { false }
    override fun open(location: String) = if (!content(location)) FileSystemEditAccess.open(location) else
        requireNotNull(resolver.openInputStream(Uri.parse(location))) { "The music file is missing" }
    override fun copy(location: String, destination: File) {
        if (!content(location)) FileSystemEditAccess.copy(location, destination)
        else open(location).use { input -> destination.outputStream().use { input.copyTo(it) } }
    }
    override fun stagingDirectory(location: String) = if (content(location)) staging else FileSystemEditAccess.stagingDirectory(location)
    override suspend fun replace(location: String, staged: File, originalHash: String?) {
        if (!content(location)) { FileSystemEditAccess.replace(location, staged); return }
        val uri = Uri.parse(location)
        val descriptor = try { resolver.openFileDescriptor(uri, "rw") } catch (_: SecurityException) {
            check(requestWriteAccess(uri)) { "Android did not grant permission to edit this file." }
            resolver.openFileDescriptor(uri, "rw")
        }
        requireNotNull(descriptor) { "The music file cannot be edited" }
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            if (originalHash != null) {
                val digest = MessageDigest.getInstance("SHA-256")
                open(location).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                check(digest.digest().joinToString("") { "%02x".format(it) } == originalHash) {
                    "The music file changed while Android edit permission was pending. Review the file before applying this edit."
                }
            }
            output.channel.truncate(0)
            output.channel.position(0)
            staged.inputStream().use { it.copyTo(output) }
            output.fd.sync()
        }
        resolver.notifyChange(uri, null)
    }
    override fun scanLocation(location: String) = normalize(location)
    override fun renamedLocation(location: String, name: String) = if (content(location)) location else FileSystemEditAccess.renamedLocation(location, name)
    override suspend fun rename(location: String, name: String) {
        if (!content(location)) { FileSystemEditAccess.rename(location, name); return }
        val uri = Uri.parse(location)
        val relative = requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.RELATIVE_PATH), null, null, null)).use {
            check(it.moveToFirst()) { "The music file is missing" }; it.getString(0)
        }
        requireNotNull(resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(relative, name), null)).use { check(!it.moveToFirst()) { "A file with that name already exists" } }
        val values = ContentValues().apply { put(MediaStore.Audio.Media.DISPLAY_NAME, name) }
        val updated = try { resolver.update(uri, values, null, null) } catch (_: SecurityException) {
            check(requestWriteAccess(uri)) { "Android did not grant permission to rename this file." }
            resolver.update(uri, values, null, null)
        }
        check(updated == 1 && this.name(location) == name) { "The music file could not be renamed" }
    }
    private fun includeTrashed() = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }
    override fun deleted(location: String): Boolean {
        if (!content(location)) return FileSystemEditAccess.deleted(location)
        return requireNotNull(resolver.query(Uri.parse(location), arrayOf(MediaStore.Audio.Media.IS_TRASHED), includeTrashed(), null)).use {
            !it.moveToFirst() || it.getInt(0) == 1
        }
    }
    override suspend fun delete(location: String) {
        if (!content(location)) { FileSystemEditAccess.delete(location); return }
        setTrashed(location, true)
    }
    override suspend fun restoreDeleted(location: String, original: File, name: String, folder: String,
        reserve: suspend (String) -> Unit): String {
        if (!content(location)) return FileSystemEditAccess.restoreDeleted(location, original, name, folder, reserve)
        if (exists(location)) {
            reserve(location)
            setTrashed(location, false)
            return location
        }
        requireNotNull(resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
            arrayOf(folder, name), null)).use { check(!it.moveToFirst()) { "A file with the original name already exists" } }
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.MIME_TYPE, when (name.substringAfterLast('.').lowercase()) {
                "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "m4a" -> "audio/mp4"; else -> "audio/flac"
            })
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        })) { "The original music file cannot be restored" }
        reserve(uri.toString())
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rwt"))
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
            original.inputStream().use { it.copyTo(output) }; output.fd.sync()
        }
        completeRestoration(uri.toString())
        return uri.toString()
    }
    override suspend fun completeRestoration(location: String) {
        if (!content(location)) return
        val uri = Uri.parse(location)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
            put(MediaStore.Audio.Media.IS_TRASHED, 0)
        }
        val updated = try { resolver.update(uri, values, includeTrashed()) } catch (_: SecurityException) {
            check(requestWriteAccess(uri)) { "Android did not grant permission to restore this file." }
            resolver.update(uri, values, includeTrashed())
        }
        check(updated == 1) { "The restored music file could not be published" }
    }
    private suspend fun setTrashed(location: String, value: Boolean) {
        val uri = Uri.parse(location)
        val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_TRASHED, if (value) 1 else 0) }
        val updated = try { resolver.update(uri, values, includeTrashed()) } catch (_: SecurityException) {
            check(requestWriteAccess(uri)) { "Android did not grant permission to change this file." }
            resolver.update(uri, values, includeTrashed())
        }
        check(updated == 1 && deleted(location) == value) { "The music file could not be ${if (value) "deleted" else "restored"}" }
        resolver.notifyChange(uri, null)
    }
}
