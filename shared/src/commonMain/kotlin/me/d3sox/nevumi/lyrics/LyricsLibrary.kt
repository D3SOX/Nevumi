package me.d3sox.nevumi.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.sync.LibrarySync
import kotlin.math.abs

interface LyricsLibrary {
    /** The lyrics of [track], asking the [online] lyrics services as a last resort. */
    suspend fun lyrics(track: Track, online: Set<LyricsSource>): Lyrics?
}

/** A lyrics service on the internet that finds a song by its tags. */
interface OnlineLyrics {
    val source: LyricsSource
    suspend fun lyrics(metadata: TrackMetadata): Lyrics?
}

/** Reads lyrics stored with a music file on this device. */
interface LyricsFiles {
    suspend fun embedded(source: TrackSource): Lyrics?
    suspend fun sidecar(source: TrackSource): Lyrics?
}

/**
 * Looks for lyrics in the file's tags, then a sidecar file, then the music server, then the [online] services in their order, and keeps
 * what the server and the services returned under a key that finds the same song again on another device.
 */
class LyricsSources(private val store: MusicStore?, private val files: LyricsFiles?,
    private val server: suspend () -> NavidromeClient? = { null }, private val online: List<OnlineLyrics> = emptyList(),
    private val onlineTimeoutMillis: Long = 10_000) : LyricsLibrary {
    override suspend fun lyrics(track: Track, online: Set<LyricsSource>): Lyrics? {
        val local = track.sources.filter { it.available && (it.kind == SourceKind.LOCAL || it.kind == SourceKind.DOWNLOAD) }
        if (files != null) {
            local.firstNotNullOfOrNull { attempt { files.embedded(it) } }?.let { return it }
            local.firstNotNullOfOrNull { attempt { files.sidecar(it) } }?.let { return it }
        }
        // The server is asked first, so its lyrics replace an online result kept while it was unreachable.
        val key = cacheKey(track.metadata)?.let { "lyrics:$it" }
        val found = attempt { serverLyrics(track) }
            ?: key?.let { store?.read<Lyrics>(it) }?.let { return it }
            ?: onlineLyrics(track.metadata, online)
        if (found != null && key != null) store?.put(key, KIND, found)
        return found
    }

    /** Asks the chosen services in order until one has synced lyrics, keeping the first plain text in case none has. */
    private suspend fun onlineLyrics(metadata: TrackMetadata, chosen: Set<LyricsSource>): Lyrics? {
        var plain: Lyrics? = null
        for (service in online.filter { it.source in chosen }) {
            // A slow service gives way to the next one instead of keeping the lyrics loading.
            val found = attempt { withTimeoutOrNull(onlineTimeoutMillis) { service.lyrics(metadata) } } ?: continue
            if (found.synced) return found
            plain = plain ?: found
        }
        return plain
    }

    private suspend fun serverLyrics(track: Track): Lyrics? {
        val sources = track.sources.filter { it.kind == SourceKind.SERVER }
        if (sources.isEmpty()) return null
        val client = server() ?: return null
        val source = sources.firstOrNull { it.id.startsWith("server:${client.serverId}:") } ?: return null
        return client.lyrics(source.location, track.metadata.artist, track.metadata.title)
    }

    /** One source failing must not hide the lyrics another one has. */
    private inline fun attempt(block: () -> Lyrics?): Lyrics? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    companion object {
        const val KIND = "lyrics"
        /** MusicBrainz identifiers when the song has them, otherwise its artist, title, album and length in seconds; null for untagged files, whose names collide. */
        fun cacheKey(metadata: TrackMetadata): String? = LibrarySync.identity(metadata)
            ?: if (metadata.artist.isBlank() || metadata.title.isBlank()) null
            // The length tells live, remastered and extended versions with the same tags apart, as it does for LRCLIB.
            // A JSON array keeps a separator inside a tag from making two songs share a key.
            else "text:" + Json.encodeToString(listOf(metadata.artist, metadata.title, metadata.album).map { it.trim().lowercase() } +
                ((metadata.durationMs + 500) / 1_000).toString())
    }
}

/** Fetches lyrics from LRCLIB, a free lyrics database. */
class LrcLibClient(private val http: HttpClient, version: String, private val baseUrl: String = "https://lrclib.net") : OnlineLyrics {
    override val source = LyricsSource.LRCLIB
    private val userAgent = "Nevumi/$version (https://github.com/D3SOX/Nevumi)"

    override suspend fun lyrics(metadata: TrackMetadata): Lyrics? {
        if (metadata.title.isBlank() || metadata.artist.isBlank()) return null
        val response = http.get("${baseUrl.trimEnd('/')}/api/get") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("artist_name", metadata.artist)
            parameter("track_name", metadata.title)
            if (metadata.album.isNotBlank()) parameter("album_name", metadata.album)
            if (metadata.durationMs > 0) parameter("duration", (metadata.durationMs + 500) / 1_000)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "LRCLIB request failed: HTTP ${response.status.value}" }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        fun text(key: String) = body[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return text("syncedLyrics")?.let { LrcParser.parse(it, LyricsSource.LRCLIB) }
            ?: text("plainLyrics")?.let { LrcParser.parse(it, LyricsSource.LRCLIB) }
    }
}

/**
 * Fetches synced lyrics from NetEase Cloud Music through the endpoints its web player uses, which need no account but are not a
 * documented API. A song counts as a match only with the same title and artist, and a length within a few seconds when both are known.
 */
class NetEaseClient(private val http: HttpClient, version: String, private val baseUrl: String = "https://music.163.com") : OnlineLyrics {
    override val source = LyricsSource.NETEASE
    private val userAgent = "Nevumi/$version (https://github.com/D3SOX/Nevumi)"

    override suspend fun lyrics(metadata: TrackMetadata): Lyrics? {
        if (metadata.title.isBlank() || metadata.artist.isBlank()) return null
        val songs = request("cloudsearch/pc") {
            parameter("s", "${metadata.artist} ${metadata.title}")
            parameter("type", 1)
            parameter("limit", 10)
        }["result"]?.jsonObject?.get("songs")?.jsonArray.orEmpty().map { it.jsonObject }
        // The catalogue often holds the same recording more than once, and only some copies have synced lyrics.
        var plain: Lyrics? = null
        for (song in songs.filter { matches(it, metadata) }.take(3)) {
            val id = song["id"]?.jsonPrimitive?.longOrNull ?: continue
            val body = request("song/lyric") { parameter("id", id); parameter("lv", 1) }
            if (body["pureMusic"]?.jsonPrimitive?.booleanOrNull == true) return null
            val found = body["lrc"]?.jsonObject?.get("lyric")?.jsonPrimitive?.contentOrNull
                ?.let { LrcParser.parse(it, LyricsSource.NETEASE) }?.withoutCredits() ?: continue
            if (found.synced) return found
            plain = plain ?: found
        }
        return plain
    }

    private suspend fun request(path: String, parameters: HttpRequestBuilder.() -> Unit): JsonObject {
        val response = http.get("${baseUrl.trimEnd('/')}/api/$path") {
            header(HttpHeaders.UserAgent, userAgent)
            parameters()
        }
        check(response.status.isSuccess()) { "NetEase request failed: HTTP ${response.status.value}" }
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        // Refusals, such as a demand to verify the device, arrive with HTTP 200 and their own code.
        val code = body["code"]?.jsonPrimitive?.intOrNull
        check(code == 200) { "NetEase request failed: code $code" }
        return body
    }

    private fun matches(song: JsonObject, metadata: TrackMetadata): Boolean {
        val title = song["name"]?.jsonPrimitive?.contentOrNull ?: return false
        val artist = words(metadata.artist)
        val artists = song["ar"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.let(::words) }
        val length = song["dt"]?.jsonPrimitive?.longOrNull ?: 0
        return comparable(title) == comparable(metadata.title) &&
            // Tags may credit several artists in one field, and the catalogue may add a translated name, as in "BTS (防弹少年团)".
            artists.any { it.containsWords(artist) || artist.containsWords(it) } &&
            (metadata.durationMs <= 0 || length <= 0 || abs(length - metadata.durationMs) <= 3_000)
    }

    /** Ignores case, spacing and punctuation, which the catalogue often writes differently from the tags. */
    private fun comparable(text: String) = text.lowercase().filter { it.isLetterOrDigit() }
    private fun words(text: String) = text.lowercase().split(nonWord).filter { it.isNotEmpty() }
    private fun List<String>.containsWords(other: List<String>) = other.isNotEmpty() && other in windowed(other.size)

    /** Drops the timed credit lines, like "作词 : Someone", that open most of the catalogue's lyrics. */
    private fun Lyrics.withoutCredits(): Lyrics? {
        if (!synced) return this
        val rest = lines.dropWhile { it.text.isEmpty() || credit.containsMatchIn(it.text) }
        return if (rest.none { it.text.isNotEmpty() }) null else copy(lines = rest)
    }

    private companion object {
        // Only known credit labels count; Japanese, numeric and spoken opening lines can also contain a spaced colon.
        val credit = Regex("""^(?:作词|作曲|编曲|词|曲|制作人|录音师|混音工程师|混音|母带)\s[:：]\s""")
        val nonWord = Regex("""[^\p{L}\p{N}]+""")
    }
}

/** Reads an OpenSubsonic `getLyricsBySongId` response, preferring synced lyrics. */
internal fun structuredLyrics(response: JsonObject): Lyrics? {
    val entries = response["lyricsList"]?.jsonObject?.get("structuredLyrics")?.jsonArray.orEmpty().map { it.jsonObject }
    fun lines(entry: JsonObject) = entry["line"]?.jsonArray.orEmpty().map { it.jsonObject }
    val entry = entries.firstOrNull { it["synced"]?.jsonPrimitive?.booleanOrNull == true && lines(it).isNotEmpty() }
        ?: entries.firstOrNull { lines(it).isNotEmpty() } ?: return null
    fun text(line: JsonObject) = line["value"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    if (entry["synced"]?.jsonPrimitive?.booleanOrNull != true) return LrcParser.parse(lines(entry).joinToString("\n", transform = ::text), LyricsSource.SERVER)
    // The offset follows the LRC tag it usually comes from, so a positive one shows the lyrics sooner.
    val offset = entry["offset"]?.jsonPrimitive?.longOrNull ?: 0
    val synced = lines(entry).mapNotNull { line ->
        val start = line["start"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        LyricLine((start - offset).coerceAtLeast(0), text(line))
    }.sortedBy { it.startMs }
    return if (synced.isEmpty()) null else Lyrics(LyricsSource.SERVER, synced)
}

/** Reads a classic Subsonic `getLyrics` response, whose text may itself be LRC. */
internal fun classicLyrics(response: JsonObject): Lyrics? =
    response["lyrics"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull?.let { LrcParser.parse(it, LyricsSource.SERVER) }
