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
    if (image.width <= 0 || image.height <= 0 || image.width.toLong() * image.height > 16_000_000) return null
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
