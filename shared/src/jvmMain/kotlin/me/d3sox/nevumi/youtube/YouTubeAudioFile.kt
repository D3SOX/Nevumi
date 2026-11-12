package me.d3sox.nevumi.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.artwork.artworkPreview
import me.d3sox.nevumi.library.artworkSize
import me.d3sox.nevumi.soundcloud.ScGateTags
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import org.jaudiotagger.tag.id3.valuepair.ImageFormats
import java.io.File

internal actual suspend fun finishYouTubeAudio(source: String, target: String, tags: ScGateTags, year: Int, cover: ByteArray?) = withContext(Dispatchers.IO) {
    val output = File(target)
    try {
        Mp4Defragmenter.defragment(File(source), output)
        val audio = AudioFileIO.read(output)
        val tag = audio.tagOrCreateAndSetDefault
        fun set(key: FieldKey, value: String) = if (value.isBlank()) tag.deleteField(key) else tag.setField(key, value.trim())
        set(FieldKey.TITLE, tags.title)
        set(FieldKey.ARTIST, tags.artist)
        set(FieldKey.ALBUM, tags.album)
        set(FieldKey.GENRE, tags.genre)
        set(FieldKey.YEAR, year.takeIf { it in 1..9999 }?.toString().orEmpty())
        coverArtwork(cover)?.let { tag.deleteArtworkField(); tag.setField(it) }
        audio.commit()
    } catch (failure: Exception) {
        output.delete()
        throw failure
    }
}

/** A PNG or JPEG cover of a sensible size, or null for any other image, which the song then goes without. */
private fun coverArtwork(original: ByteArray?): AndroidArtwork? {
    if (original == null) return null
    val bytes = try { artworkPreview(original) } catch (_: Exception) { return null }
    val mime = ImageFormats.getMimeTypeForBinarySignature(bytes)?.takeIf { it == "image/png" || it == "image/jpeg" } ?: return null
    val (width, height) = try { artworkSize(bytes) } catch (_: Exception) { return null }
    if (width <= 0 || height <= 0) return null
    // AndroidArtwork only stores the bytes; the desktop variant decodes them through AWT, which Android lacks.
    return AndroidArtwork().apply {
        binaryData = bytes; mimeType = mime; pictureType = PictureTypes.DEFAULT_ID
        this.width = width; this.height = height
    }
}
