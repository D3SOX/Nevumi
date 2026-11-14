package me.d3sox.nevumi.youtube

import me.d3sox.nevumi.artwork.MAX_ARTWORK_BYTES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

internal const val YOUTUBE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

/** Sends NewPipe Extractor's requests; they go straight to YouTube, without an account. */
private object UrlConnectionDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val connection = URI(request.url()).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.requestMethod = request.httpMethod()
            connection.setRequestProperty("User-Agent", YOUTUBE_USER_AGENT)
            // The first value replaces any set before, such as the User-Agent, so a header is never sent twice.
            request.headers().forEach { (name, values) ->
                values.forEachIndexed { index, value -> if (index == 0) connection.setRequestProperty(name, value) else connection.addRequestProperty(name, value) }
            }
            request.dataToSend()?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code == 429) throw ReCaptchaException("YouTube asked to confirm that this is not a bot", request.url())
            val body = (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            return Response(code, connection.responseMessage, connection.headerFields.filterKeys { it != null }, body, connection.url.toString())
        } finally { connection.disconnect() }
    }
}

/**
 * Searches YouTube and YouTube Music with NewPipe Extractor and streams videos' audio through a [YouTubeStreamProxy].
 * Everything runs on this device; YouTube sees its address but no account.
 */
class NewPipeYouTube : YouTubeSource, AutoCloseable {
    private val service = ServiceList.YouTube
    private val proxy = YouTubeStreamProxy { id, fresh -> audio(id, fresh) }
    private class Found(val audio: YouTubeAudio, val expiresAt: Long)
    private val found = ConcurrentHashMap<String, Found>()
    private val lookups = ConcurrentHashMap<String, Mutex>()

    init { initialize() }

    override fun close() = proxy.close()

    override fun playbackLocation(videoId: String): String = proxy.location(videoId)

    override suspend fun artwork(url: String): ByteArray = runInterruptible(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Artwork needs a secure address" }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", YOUTUBE_USER_AGENT)
            if (connection.responseCode !in 200..299) throw IOException("YouTube sent no artwork (HTTP ${connection.responseCode})")
            connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= MAX_ARTWORK_BYTES) { "YouTube artwork is too large" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    override suspend fun search(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation?): YouTubePage = extract {
        if (continuation != null) {
            val previous = continuation.value as? Continued ?: return@extract YouTubeMusicPages.more(continuation)
            return@extract page(previous.extractor, previous.extractor.getPage(previous.page))
        }
        val text = query.trim()
        require(text.isNotEmpty()) { "Enter something to search for" }
        if (accepts(text)) return@extract YouTubePage(listOf(track(StreamInfo.getInfo(service, text))), null)
        // NewPipe Extractor finds artists and albums but cannot open their YouTube Music pages, so they come from those pages' own search.
        if (kind == YouTubeSearchKind.ARTISTS || kind == YouTubeSearchKind.ALBUMS) return@extract YouTubeMusicPages.search(text, kind)
        val filter = if (kind == YouTubeSearchKind.SONGS) YoutubeSearchQueryHandlerFactory.MUSIC_SONGS else YoutubeSearchQueryHandlerFactory.VIDEOS
        val extractor = service.getSearchExtractor(service.searchQHFactory.fromQuery(text, listOf(filter), ""))
        extractor.fetchPage()
        page(extractor, extractor.initialPage)
    }

    override suspend fun browse(link: YouTubeLink): YouTubeBrowsePage = extract { YouTubeMusicPages.browse(link) }

    override suspend fun browseMore(continuation: YouTubeContinuation): YouTubePage = extract { YouTubeMusicPages.more(continuation) }

    override suspend fun audio(videoId: String, fresh: Boolean): YouTubeAudio {
        require(videoId.matches(VIDEO_ID)) { "This is not a YouTube video" }
        // The player may open a stream twice at once, such as for its length and its first bytes; one lookup answers both.
        return lookups.computeIfAbsent(videoId) { Mutex() }.withLock {
            found[videoId]?.takeIf { !fresh && it.expiresAt > System.currentTimeMillis() }?.audio ?: extract {
                val info = StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
                val stream = bestAudio(info.audioStreams) ?: throw YouTubeException("YouTube offers no audio for this video", retryable = false)
                val length = stream.itagItem?.contentLength?.takeIf { it > 0 } ?: 0
                YouTubeAudio(stream.content, if (stream.format == MediaFormat.M4A) "audio/mp4" else stream.format?.mimeType ?: "audio/webm",
                    length, stream.averageBitrate, details(plainText(info.description?.content().orEmpty())))
            }.also { audio ->
                // Stream addresses name their expiry; refresh them a while before it so a long song does not stop halfway.
                val expires = audio.url.substringAfter("expire=", "").substringBefore('&').toLongOrNull()?.times(1_000)
                    ?: (System.currentTimeMillis() + 3_600_000)
                found[videoId] = Found(audio, expires - 20 * 60_000)
            }
        }
    }

    private class Continued(val extractor: SearchExtractor, val page: org.schabi.newpipe.extractor.Page)

    private fun page(extractor: SearchExtractor, page: ListExtractor.InfoItemsPage<InfoItem>): YouTubePage {
        val results = page.items.filterIsInstance<StreamInfoItem>().mapNotNull { item ->
            val id = runCatching { service.streamLHFactory.getId(item.url) }.getOrNull() ?: return@mapNotNull null
            YouTubeTrack(id, item.name.orEmpty(), channelArtist(item.uploaderName.orEmpty()), item.duration.coerceAtLeast(0) * 1_000,
                largest(item.thumbnails), song = item.url.startsWith("https://music.youtube.com/"))
        }
        return YouTubePage(results, page.nextPage?.takeIf { page.hasNextPage() }?.let { YouTubeContinuation(Continued(extractor, it)) })
    }

    private fun track(info: StreamInfo) = YouTubeTrack(info.id, info.name.orEmpty(), channelArtist(info.uploaderName.orEmpty()),
        info.duration.coerceAtLeast(0) * 1_000, largest(info.thumbnails), song = info.uploaderName.orEmpty().endsWith(" - Topic"))

    private fun accepts(text: String) = (text.startsWith("http://") || text.startsWith("https://") || "youtu" in text) &&
        runCatching { service.streamLHFactory.acceptUrl(text) }.getOrDefault(false)

    private suspend fun <T> extract(block: () -> T): T = try { runInterruptible(Dispatchers.IO) { block() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw youTubeError(failure) }

    internal companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        private val YEAR = Regex("(?:Released on: |℗ )(\\d{4})")
        private var initialized = false

        @Synchronized private fun initialize() {
            if (!initialized) { NewPipe.init(UrlConnectionDownloader); initialized = true }
        }

        /** The original-language audio track, preferring AAC in MP4 because it can be tagged without converting it. */
        fun bestAudio(streams: List<AudioStream>): AudioStream? = streams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.audioTrackType != AudioTrackType.DESCRIPTIVE &&
                it.audioTrackType != AudioTrackType.DUBBED }
            .maxWithOrNull(compareBy<AudioStream>({ it.format == MediaFormat.M4A }, { it.averageBitrate }))

        private fun largest(images: List<Image>): String? = images.maxByOrNull { it.width.toLong() * it.height }?.url

        /**
         * The album and year from the description YouTube Music writes for its songs:
         * `Provided to YouTube by Label`, `Title · Artist`, `Album`, `℗ 2001 Label` and `Released on: 2001-03-07`, separated by blank lines.
         */
        fun details(description: String): YouTubeDetails {
            val blocks = description.replace("\r", "").split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (blocks.firstOrNull()?.startsWith("Provided to YouTube by") != true) return YouTubeDetails()
            val album = blocks.getOrNull(2)?.takeUnless { it.startsWith("℗") || it.startsWith("Released on") || '\n' in it }.orEmpty()
            val year = YEAR.find(description)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return YouTubeDetails(album, year)
        }

        /** YouTube writes descriptions as HTML with `<br>` line breaks. */
        fun plainText(html: String): String = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

        fun youTubeError(failure: Exception): Exception = when (failure) {
            is YouTubeException, is IllegalArgumentException -> failure
            is AgeRestrictedContentException -> YouTubeException("YouTube only plays this video to signed-in adults", retryable = false)
            is GeographicRestrictionException -> YouTubeException("This video is unavailable in your country", retryable = false)
            is PrivateContentException -> YouTubeException("This video is private", retryable = false)
            is PaidContentException, is YoutubeMusicPremiumContentException -> YouTubeException("This video needs a paid YouTube subscription", retryable = false)
            is SignInConfirmNotBotException, is ReCaptchaException -> YouTubeException("YouTube asked to confirm that this is not a bot. Try again later or from another network.")
            is ContentNotAvailableException -> YouTubeException(failure.message?.takeIf { it.isNotBlank() } ?: "This video is unavailable", retryable = false)
            is IOException -> YouTubeException("Could not reach YouTube. Check your connection.")
            else -> YouTubeException("YouTube sent a response Nevumi could not read. An update of Nevumi may fix this.")
        }
    }
}
