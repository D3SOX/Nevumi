package me.d3sox.nevumi.artwork

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

private fun jpeg(image: BufferedImage): ByteArray {
    val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) image
        else BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    return try {
        ByteArrayOutputStream().also { output ->
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val parameters = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 0.9f }
                writer.write(null, IIOImage(rgb, null, null), parameters)
            }
        }.toByteArray()
    } finally { writer.dispose() }
}

internal actual fun artworkPreview(bytes: ByteArray): ByteArray {
    require(bytes.isNotEmpty() && bytes.size <= 64 * 1024 * 1024) { "Choose a valid artwork image" }
    return ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "Choose a valid artwork image" }
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0); val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000) { "Choose a valid artwork image" }
            if (maxOf(width, height) <= 1024 && bytes.size <= 8 * 1024 * 1024) bytes
            else {
                val sample = ((maxOf(width, height) + 1023) / 1024).coerceAtLeast(1)
                val image = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) })
                // A lossless 1024 px cover is often over a megabyte; one without transparency loses nothing visible as a JPEG.
                if (image.colorModel.hasAlpha()) ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
                else jpeg(image)
            }
        } finally { reader.dispose() }
    }
}
