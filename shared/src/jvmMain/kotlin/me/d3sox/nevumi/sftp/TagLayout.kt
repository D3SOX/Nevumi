package me.d3sox.nevumi.sftp

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest

// Bytes kept after an MP3's ID3 tag so the tag reader can find the first audio frame.
private const val FRAME_SEARCH = 64 * 1024

/**
 * Where a music file keeps its tags. A tag edit rewrites only [tags] and moves [audio] unchanged,
 * so a server file is edited by sending its tags while the server copies the audio in place.
 * [tags] may overlap [audio] where the tag reader needs a little audio, as with MP3 frames.
 */
internal class TagLayout(val size: Long, val tags: List<LongRange>, val audio: LongRange) {
    /** Identifies the file by its size and tag bytes, which is what tag edits change; the audio bytes kept for the tag reader are left out. */
    fun fingerprint(read: (Long, Int) -> ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putLong(size).array())
        for (range in tags.flatMap { listOf(it.first until minOf(it.last + 1, audio.first), maxOf(it.first, audio.last + 1)..it.last) }.filterNot { it.isEmpty() }) {
            digest.update(ByteBuffer.allocate(16).putLong(range.first).putLong(range.last).array())
            chunks(range) { offset, length -> digest.update(read(offset, length)) }
        }
        return FINGERPRINT + digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Writes a file of the same size holding only the tag bytes; the audio stays as unallocated zeros. */
    fun writeImage(destination: File, read: (Long, Int) -> ByteArray) = RandomAccessFile(destination, "rw").use { file ->
        file.setLength(0)
        file.setLength(size)
        for (range in tags) chunks(range) { offset, length -> file.seek(offset); file.write(read(offset, length)) }
    }

    companion object {
        const val FINGERPRINT = "tags:"

        fun of(name: String, size: Long, read: (Long, Int) -> ByteArray): TagLayout {
            fun bytes(offset: Long, length: Int) = read(offset, length).also {
                if (it.size < length) throw UnreadableTags("The music file ends inside its tags")
            }
            fun id3End(): Long {
                val header = read(0, 10)
                if (header.size < 10 || header.decodeToString(0, 3) != "ID3") return 0
                val tag = (6..9).fold(0L) { total, index -> (total shl 7) or (header[index].toLong() and 0x7f) }
                return 10 + tag + if (header[5].toInt() and 0x10 != 0) 10 else 0
            }
            val layout = when (name.substringAfterLast('.').lowercase()) {
                "flac" -> {
                    var position = id3End()
                    if (bytes(position, 4).decodeToString() != "fLaC") throw UnreadableTags("This is not a FLAC file")
                    position += 4
                    do {
                        val header = bytes(position, 4)
                        position += 4 + ((header[1].toLong() and 0xff) shl 16 or ((header[2].toLong() and 0xff) shl 8) or (header[3].toLong() and 0xff))
                    } while (header[0] >= 0 && position < size)
                    TagLayout(size, listOf(0 until position), position until size)
                }
                "mp3" -> {
                    val start = id3End()
                    val end = size - if (size >= 128 && read(size - 128, 3).decodeToString() == "TAG") 128 else 0
                    TagLayout(size, listOf(0 until minOf(start + FRAME_SEARCH, end), end until size), start until end)
                }
                "wav" -> {
                    val header = bytes(0, 12)
                    if (header.decodeToString(0, 4) != "RIFF" || header.decodeToString(8, 12) != "WAVE") throw UnreadableTags("This is not a WAV file")
                    var position = 12L
                    var audio: LongRange? = null
                    while (position + 8 <= size) {
                        val chunk = bytes(position, 8)
                        val length = (4..7).fold(0L) { total, index -> total or ((chunk[index].toLong() and 0xff) shl (8 * (index - 4))) }
                        val data = position + 8 until minOf(position + 8 + length, size)
                        if (chunk.decodeToString(0, 4) == "data" && audio == null) audio = data
                        position = data.last + 1 + (length and 1)
                    }
                    val sound = audio ?: throw UnreadableTags("The WAV file has no audio")
                    TagLayout(size, listOf(0 until sound.first, sound.last + 1 until size), sound)
                }
                else -> throw UnreadableTags("Unsupported music format")
            }
            if (layout.audio.first > layout.size || layout.tags.any { it.last >= layout.size }) throw UnreadableTags("The music file ends inside its tags")
            return layout
        }

        fun of(file: File, name: String = file.name): Pair<TagLayout, (Long, Int) -> ByteArray> {
            val read = { offset: Long, length: Int ->
                RandomAccessFile(file, "r").use { input ->
                    val buffer = ByteArray(maxOf(0, minOf(length.toLong(), input.length() - offset)).toInt())
                    input.seek(offset)
                    input.readFully(buffer)
                    buffer
                }
            }
            return of(name, file.length(), read) to read
        }

        private inline fun chunks(range: LongRange, action: (Long, Int) -> Unit) {
            var offset = range.first
            while (offset <= range.last) {
                val length = minOf(1L shl 20, range.last + 1 - offset).toInt()
                action(offset, length)
                offset += length
            }
        }
    }
}

/** Hashes a local copy of the server file [name] the way the server file is compared, by size and tag bytes. */
internal fun tagFingerprint(file: File, name: String): String = TagLayout.of(file, name).let { (layout, read) -> layout.fingerprint(read) }

/** The file is not a well-formed music file of its type, so it has no tags to read. */
internal class UnreadableTags(message: String) : IOException(message)

internal val LongRange.length: Long get() = if (isEmpty()) 0 else last - first + 1
