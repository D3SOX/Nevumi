package me.d3sox.nevumi.youtube

import kotlinx.serialization.Serializable

/** A YouTube search result or video; [song] marks a YouTube Music song from an artist's Topic channel rather than an uploaded video. */
@Serializable
data class YouTubeTrack(val videoId: String, val title: String, val artist: String, val durationMs: Long = 0,
    val artworkUrl: String? = null, val song: Boolean = false) {
    val url: String get() = if (song) "https://music.youtube.com/watch?v=$videoId" else "https://www.youtube.com/watch?v=$videoId"
}

/** What a search looks for: songs on YouTube Music, or any video on YouTube. */
enum class YouTubeSearchKind { SONGS, VIDEOS }

/** Where a search continues; only the source that returned it reads it. */
class YouTubeContinuation(internal val value: Any)

class YouTubePage(val results: List<YouTubeTrack>, val next: YouTubeContinuation?)

/** Tags that YouTube Music's song descriptions name, such as the album; blank when the video names none. */
@Serializable
data class YouTubeDetails(val album: String = "", val year: Int = 0)

/** An audio stream of a video. Its [url] expires after a few hours and only answers requests for limited byte ranges quickly. */
data class YouTubeAudio(val url: String, val mimeType: String, val contentLength: Long, val bitrate: Int,
    val details: YouTubeDetails = YouTubeDetails())

/** [retryable] is false when trying again cannot help, such as for a removed or private video. */
class YouTubeException(message: String, val retryable: Boolean = true) : Exception(message)

/** Searches YouTube and streams its audio on this device. */
interface YouTubeSource {
    /** A video address finds that video; anything else searches for [kind]. */
    suspend fun search(query: String, kind: YouTubeSearchKind = YouTubeSearchKind.SONGS, continuation: YouTubeContinuation? = null): YouTubePage
    /** The best audio stream for downloading and playback; [fresh] skips a stream found earlier, such as one that expired. */
    suspend fun audio(videoId: String, fresh: Boolean = false): YouTubeAudio
    /** An address on this device that the player streams [videoId] from, finding its audio only when the player opens it. */
    fun playbackLocation(videoId: String): String
    /** Artwork of a search result, fetched from YouTube's image servers. */
    suspend fun artwork(url: String): ByteArray
}

/** The artist part of a channel name, such as `Artist` for YouTube Music's `Artist - Topic` channels. */
internal fun channelArtist(channel: String): String = channel.removeSuffix(" - Topic").trim()

/**
 * The title and artist to tag a video with. Songs keep their own; uploaded videos are often titled `Artist - Title`,
 * which names the artist better than the uploading channel does.
 */
fun YouTubeTrack.suggestedTags(): Pair<String, String> {
    if (song) return title to artist
    val separator = listOf(" - ", " – ", " — ").firstOrNull { it in title } ?: return title to artist
    val (named, rest) = title.split(separator, limit = 2).map { it.trim() }
    return if (named.isEmpty() || rest.isEmpty()) title to artist else rest to named
}
