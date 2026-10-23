package me.d3sox.nevumi.ui

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DecodeArtworkTest {
    private fun png(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun aCoverLargerThanTheDecodeLimitStillShowsAtTheRequestedSize() {
        val bitmap = assertNotNull(decodeArtwork(png(5_000, 4_000), 256), "A 20-megapixel cover must still decode")
        assertEquals(256, maxOf(bitmap.width, bitmap.height))
    }

    @Test
    fun aRegularCoverIsScaledToTheRequestedSize() {
        val bitmap = assertNotNull(decodeArtwork(png(1_200, 600), 300))
        assertEquals(300, bitmap.width)
        assertEquals(150, bitmap.height)
    }
}
