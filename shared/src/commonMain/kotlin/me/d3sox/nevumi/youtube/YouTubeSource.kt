package me.d3sox.nevumi.youtube

import kotlinx.serialization.Serializable

/** A search result or an entry of a YouTube Music page: a video, or a page such as an artist that it opens. */
sealed interface YouTubeItem { val key: String }

/** A YouTube search result or video; [song] marks a YouTube Music song from an artist's Topic channel rather than an uploaded video. */
@Serializable
data class YouTubeTrack(val videoId: String, val title: String, val artist: String, val durationMs: Long = 0,
    val artworkUrl: String? = null, val song: Boolean = false) : YouTubeItem {
    override val key: String get() = videoId
    val url: String get() = if (song) "https://music.youtube.com/watch?v=$videoId" else "https://www.youtube.com/watch?v=$videoId"
}

/** Where a YouTube Music page is found; [params] picks a part of it, such as an artist's singles. */
data class YouTubeLink(val browseId: String, val params: String? = null) {
    val url: String get() = when {
        browseId.startsWith("VL") -> "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}"
        browseId.startsWith("UC") -> "https://music.youtube.com/channel/$browseId"
        else -> "https://music.youtube.com/browse/$browseId"
    }
}

/** What a YouTube Music page shows; [LIST] is a list of other pages, such as all albums of an artist. */
enum class YouTubeCollectionKind { ARTIST, ALBUM, PLAYLIST, LIST }

/** An artist, album, playlist or list on YouTube Music that opens as a page. */
data class YouTubeCollection(val link: YouTubeLink, val kind: YouTubeCollectionKind, val title: String, val subtitle: String = "",
    val artworkUrl: String? = null) : YouTubeItem {
    override val key: String get() = link.browseId + link.params.orEmpty()
}

/** How a shelf shows its entries: as list rows, such as an artist's top songs, as a row of cards, or as a grid of cards. */
enum class YouTubeShelfLayout { ROWS, CAROUSEL, GRID }

/** A titled section of a YouTube Music page; [more] opens all of it, such as every album of an artist. */
class YouTubeShelf(val title: String, val items: List<YouTubeItem>, val layout: YouTubeShelfLayout, val more: YouTubeCollection? = null)

/**
 * A YouTube Music artist, album or playlist page with the shelves YouTube Music shows on it. An artist's [artworkUrl] is a wide banner
 * when [banner] is set. [next] continues the last shelf, such as a long playlist.
 */
class YouTubeBrowsePage(val title: String, val subtitle: String = "", val description: String = "", val artworkUrl: String? = null,
    val banner: Boolean = false, val artist: YouTubeCollection? = null, val shelves: List<YouTubeShelf> = emptyList(),
    val next: YouTubeContinuation? = null)

/** What a search looks for: songs, artists or albums on YouTube Music, or any video on YouTube. */
enum class YouTubeSearchKind { SONGS, ARTISTS, ALBUMS, VIDEOS }

/** Where a search or page continues; only the source that returned it reads it. */
class YouTubeContinuation(internal val value: Any)

class YouTubePage(val results: List<YouTubeItem>, val next: YouTubeContinuation?)

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
    /** The YouTube Music page at [link], such as an artist with their songs, albums, videos and similar artists. */
    suspend fun browse(link: YouTubeLink): YouTubeBrowsePage
    /** More entries of the last shelf of a page, which [YouTubeBrowsePage.next] or an earlier [YouTubePage.next] of this call points to. */
    suspend fun browseMore(continuation: YouTubeContinuation): YouTubePage
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
