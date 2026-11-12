package me.d3sox.nevumi.artwork

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable

internal const val MAX_ARTWORK_BYTES = 10 * 1024 * 1024

/** Reads an artwork response, failing once it grows past [MAX_ARTWORK_BYTES] instead of holding all of it in memory. */
internal suspend fun HttpResponse.readArtwork(tooLarge: String = "Artwork is too large"): ByteArray {
    val bytes = okio.Buffer()
    val channel = bodyAsChannel()
    val buffer = ByteArray(32 * 1024)
    while (true) {
        val count = channel.readAvailable(buffer)
        if (count < 0) break
        check(bytes.size + count <= MAX_ARTWORK_BYTES) { tooLarge }
        if (count > 0) bytes.write(buffer, 0, count)
    }
    return bytes.readByteArray()
}
