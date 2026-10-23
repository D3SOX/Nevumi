package me.d3sox.nevumi.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.wav.WavTag
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File
import java.io.InputStream
import java.net.URI

/**
 * Reads lyrics from a song's tags and from a `.lrc` or `.txt` file beside it. Files without a readable path, like Android
 * content URIs, are copied through [openContent] to read their tags, and have no sidecar.
 */
class FileLyrics(private val openContent: ((String) -> InputStream?)? = null) : LyricsFiles {
    override suspend fun embedded(source: TrackSource): Lyrics? = withContext(Dispatchers.IO) {
        file(source)?.let { return@withContext embedded(it) }
        val open = openContent ?: return@withContext null
        val input = open(source.location) ?: return@withContext null
        input.buffered().use { stream ->
            // jaudiotagger picks its reader by extension. The file path tells it when there is one; scoped storage can leave
            // only a content URI, so the start of the audio tells it then.
            stream.mark(HEADER_BYTES)
            // InputStream.readNBytes(int) needs Android 13; minSdk is 31.
            val header = ByteArray(HEADER_BYTES).let { buffer ->
                var read = 0
                while (read < buffer.size) { val count = stream.read(buffer, read, buffer.size - read); if (count < 0) break; read += count }
                buffer.copyOf(read)
            }
            stream.reset()
            val extension = source.filePath?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() } ?: audioExtension(header)
                ?: return@withContext null
            val copy = File.createTempFile("nevumi-lyrics-", ".$extension")
            try {
                copy.outputStream().use { stream.copyTo(it) }
                embedded(copy)
            } finally { copy.delete() }
        }
    }

    override suspend fun sidecar(source: TrackSource): Lyrics? = withContext(Dispatchers.IO) {
        val audio = file(source) ?: return@withContext null
        listOf("lrc", "txt").firstNotNullOfOrNull { extension ->
            File(audio.parentFile, "${audio.nameWithoutExtension}.$extension").takeIf { it.isFile && it.canRead() }
                ?.let { LrcParser.parse(it.readText(), LyricsSource.SIDECAR) }
        }
    }

    private fun file(source: TrackSource): File? {
        if (source.kind != SourceKind.LOCAL && source.kind != SourceKind.DOWNLOAD) return null
        return listOfNotNull(source.filePath, source.location).firstNotNullOfOrNull { path ->
            try { if (path.startsWith("file:")) File(URI(path)) else if ("://" in path) null else File(path) }
            catch (_: IllegalArgumentException) { null }
                ?.takeIf { it.isFile && it.canRead() }
        }
    }

    private fun embedded(file: File): Lyrics? {
        val container = try { AudioFileIO.read(file).tag } catch (_: Exception) { return null } ?: return null
        val tag: Tag = if (container is WavTag && container.isExistingId3Tag) container.getID3Tag() else container
        fun read(value: () -> String?): String = try { value().orEmpty().trimEnd('\u0000') } catch (_: Exception) { "" }
        val vorbis = (tag as? FlacTag)?.vorbisCommentTag ?: tag as? VorbisCommentTag
        val text = read { tag.getFirst(FieldKey.LYRICS) }.ifBlank { read { vorbis?.getFirst("UNSYNCEDLYRICS") } }
        return LrcParser.parse(text, LyricsSource.EMBEDDED)
    }
}

private const val HEADER_BYTES = 12

/** The extension jaudiotagger reads [header]'s audio by, from the signatures of the formats it supports. */
internal fun audioExtension(header: ByteArray): String? {
    fun starts(text: String, at: Int = 0) = header.size >= at + text.length && text.indices.all { header[at + it] == text[it].code.toByte() }
    return when {
        starts("fLaC") -> "flac"
        starts("ID3") || header.size >= 2 && header[0] == 0xFF.toByte() && header[1].toInt() and 0xE0 == 0xE0 -> "mp3"
        starts("OggS") -> "ogg"
        starts("RIFF") && starts("WAVE", 8) -> "wav"
        starts("ftyp", 4) -> "m4a"
        starts("FORM") -> "aif"
        else -> null
    }
}
