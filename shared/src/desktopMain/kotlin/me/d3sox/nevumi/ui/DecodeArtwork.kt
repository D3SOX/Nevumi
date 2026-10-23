package me.d3sox.nevumi.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/** Decodes with Skia and scales in one pass; re-encoding a smaller copy through ImageIO cost two more codec passes. */
internal actual fun decodeArtwork(bytes: ByteArray, maxDimension: Int): ImageBitmap? {
    val image = try { Image.makeFromEncoded(bytes) } catch (_: Exception) { return null }
    if (image.width <= 0 || image.height <= 0) return null
    // Skia decodes the whole image to scale it, so a huge cover is subsampled while it is read and then scaled exactly.
    if (image.width.toLong() * image.height > MAX_DECODED_PIXELS) return subsampled(bytes, maxDimension)?.let { smaller ->
        Image.makeFromEncoded(smaller).takeIf { it.width.toLong() * it.height <= MAX_DECODED_PIXELS }?.let { decodeArtwork(smaller, maxDimension) }
    }
    val largest = maxOf(image.width, image.height)
    if (largest <= maxDimension) return image.toComposeImageBitmap()
    val scale = maxDimension.toFloat() / largest
    val width = (image.width * scale).roundToInt().coerceAtLeast(1)
    val height = (image.height * scale).roundToInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()), SamplingMode.LINEAR, null, true)
    return surface.makeImageSnapshot().toComposeImageBitmap()
}

private const val MAX_DECODED_PIXELS = 16_000_000L

/** Reads every n-th pixel, keeping at least [maxDimension] on the longer side, or null when ImageIO cannot read the format. */
private fun subsampled(bytes: ByteArray, maxDimension: Int): ByteArray? =
    javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
        val readers = javax.imageio.ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.input = input
            val largest = maxOf(reader.getWidth(0), reader.getHeight(0))
            val sample = (largest / maxDimension).coerceAtLeast(1)
            val picture = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
            java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(picture, "png", it) }.toByteArray()
        } catch (_: Exception) { null } finally { reader.dispose() }
    }
