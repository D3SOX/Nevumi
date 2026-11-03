package me.d3sox.nevumi.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal actual fun artworkPreview(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "Choose a valid artwork image" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000) { "Choose a valid artwork image" }
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    if (largest <= 1024 && bytes.size <= 8 * 1024 * 1024) return bytes
    var sample = 1
    while (largest / (sample * 2) >= 1024) sample *= 2
    val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
    val scale = minOf(1f, 1024f / maxOf(decoded.width, decoded.height))
    val preview = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1), true)
    // A lossless 1024 px cover is often over a megabyte; one without transparency loses nothing visible as a JPEG.
    val format = if (preview.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    return try { ByteArrayOutputStream().also { preview.compress(format, 90, it) }.toByteArray() }
    finally { if (preview !== decoded) preview.recycle(); decoded.recycle() }
}
