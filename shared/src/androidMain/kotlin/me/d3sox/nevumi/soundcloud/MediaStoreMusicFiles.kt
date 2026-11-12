package me.d3sox.nevumi.soundcloud

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink

/** Saves downloads to Music/Nevumi/SoundCloud through MediaStore, which needs no storage permission for the app's own files. */
class MediaStoreSoundCloudFiles(context: Context) : SoundCloudFiles {
    private val resolver = context.contentResolver
    override val folder = "/storage/emulated/0/$RELATIVE_PATH"

    override suspend fun save(fileName: String, write: suspend (okio.BufferedSink) -> Unit): String = withContext(Dispatchers.IO) {
        val name = downloadFileName(fileName)
        // MediaStore renames the file when the folder already has one with this name.
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "$RELATIVE_PATH/")
            put(MediaStore.Audio.Media.MIME_TYPE, when (name.substringAfterLast('.').lowercase()) {
                "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; else -> "audio/flac"
            })
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        })) { "Android could not create the music file" }
        try {
            val sink = requireNotNull(resolver.openOutputStream(uri, "wt")) { "Android could not open the music file" }.sink().buffer()
            try { write(sink) } finally { sink.close() }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) == 1) {
                "The music file could not be published"
            }
            uri.toString()
        } catch (failure: Throwable) {
            withContext(NonCancellable) { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    private companion object { const val RELATIVE_PATH = "Music/Nevumi/SoundCloud" }
}
