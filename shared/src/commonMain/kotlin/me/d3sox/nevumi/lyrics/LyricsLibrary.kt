package me.d3sox.nevumi.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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

interface LyricsLibrary {
    /** The lyrics of [track], searching LRCLIB as a last resort only when [online] allows it. */
    suspend fun lyrics(track: Track, online: Boolean): Lyrics?
}

/** Reads lyrics stored with a music file on this device. */
interface LyricsFiles {
    suspend fun embedded(source: TrackSource): Lyrics?
    suspend fun sidecar(source: TrackSource): Lyrics?
}

/**
 * Looks for lyrics in the file's tags, then a sidecar file, then the music server, then LRCLIB, and keeps what the server
 * and LRCLIB returned under a key that finds the same song again on another device.
 */
class LyricsSources(private val store: MusicStore?, private val files: LyricsFiles?,
    private val server: suspend () -> NavidromeClient? = { null }, private val lrcLib: LrcLibClient? = null) : LyricsLibrary {
    override suspend fun lyrics(track: Track, online: Boolean): Lyrics? {
        val local = track.sources.filter { it.available && (it.kind == SourceKind.LOCAL || it.kind == SourceKind.DOWNLOAD) }
        if (files != null) {
            local.firstNotNullOfOrNull { attempt { files.embedded(it) } }?.let { return it }
            local.firstNotNullOfOrNull { attempt { files.sidecar(it) } }?.let { return it }
        }
        // The server is asked first, so its lyrics replace an LRCLIB result kept while it was unreachable.
        val key = cacheKey(track.metadata)?.let { "lyrics:$it" }
        val found = attempt { serverLyrics(track) }
            ?: key?.let { store?.read<Lyrics>(it) }?.let { return it }
            ?: if (online && lrcLib != null) attempt { lrcLib.lyrics(track.metadata) } else null
        if (found != null && key != null) store?.put(key, KIND, found)
        return found
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
class LrcLibClient(private val http: HttpClient, version: String, private val baseUrl: String = "https://lrclib.net") {
    private val userAgent = "Nevumi/$version (https://github.com/D3SOX/Nevumi)"

    suspend fun lyrics(metadata: TrackMetadata): Lyrics? {
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
