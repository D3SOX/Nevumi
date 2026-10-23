package me.d3sox.nevumi.navidrome

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.d3sox.nevumi.storage.MusicStore
import kotlin.time.Clock

/** An artist the server knows; [id] is null when the server only knows the name, such as an artist outside its library. */
@Serializable
data class ServerArtistRef(val id: String? = null, val name: String)

@Serializable
data class ServerArtistInfo(val biography: String = "", val musicBrainzId: String = "", val lastFmUrl: String = "",
    val imageUrls: List<String> = emptyList(), val similarArtists: List<ServerArtistRef> = emptyList())

/** What discovery remembers about one artist on one server: its server ID, its information and its top songs' server IDs. */
@Serializable
data class ServerArtistDetails(val artistId: String? = null, val info: ServerArtistInfo? = null,
    val topSongIds: List<String> = emptyList(), val fetchedAt: Long = 0)

internal const val ARTIST_INFO_KIND = "artist-info"
internal const val ARTIST_INFO_TTL_MS = 7 * 24 * 60 * 60 * 1_000L

internal fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()

internal fun parseArtistInfo(info: JsonObject) = ServerArtistInfo(
    biography = plainBiography(info.text("biography")), musicBrainzId = info.text("musicBrainzId"), lastFmUrl = info.text("lastFmUrl"),
    imageUrls = listOf("largeImageUrl", "mediumImageUrl", "smallImageUrl").map(info::text).filter { it.isNotBlank() },
    similarArtists = (info["similarArtist"] as? JsonArray).orEmpty().map { it.jsonObject }
        .filter { it.text("name").isNotBlank() }.map { ServerArtistRef(it.text("id").ifBlank { null }, it.text("name")) })

/** Last.fm biographies end in an HTML "Read more" link; the link is shown separately, so only the text remains. */
internal fun plainBiography(html: String) = html.replace(Regex("<a\\b[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL), "")
    .replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    .replace("&lt;", "<").replace("&gt;", ">").trim()

/**
 * Asks the connected server for similar artists, top songs and similar songs. Every call returns null instead of throwing
 * when no server is connected, the server lacks the endpoint or the request fails, so discovery never breaks a screen.
 * Artist details are kept for a week per server, and stale details are reused while the server is unreachable.
 */
class ServerDiscovery(private val store: MusicStore, private val client: suspend () -> NavidromeClient?,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {

    suspend fun serverId(): String? = attempt { it.serverId }

    /** [songIds] are server IDs of the artist's songs, used to find the artist when searching by name fails. */
    suspend fun artist(name: String, songIds: List<String> = emptyList()): ServerArtistDetails? {
        if (name.isBlank()) return null
        val client = connected() ?: return null
        val key = "$ARTIST_INFO_KIND:${client.serverId}:${name.trim().lowercase()}"
        val cached = try { store.read<ServerArtistDetails>(key) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        if (cached != null && now() - cached.fetchedAt in 0 until ARTIST_INFO_TTL_MS) return cached
        return try {
            val id = client.artistId(name, songIds.firstOrNull())
            ServerArtistDetails(id, id?.let { client.artistInfo(it) }, client.topSongs(name)?.map { it.id }.orEmpty(), now())
                .also { store.put(key, ARTIST_INFO_KIND, it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { cached }
    }

    suspend fun similarToSong(songId: String, count: Int = 50): List<ServerSong>? = attempt { it.similarSongs(songId, count) }

    suspend fun similarToArtist(name: String, songIds: List<String> = emptyList(), count: Int = 50): List<ServerSong>? {
        val id = artist(name, songIds)?.artistId ?: return null
        return attempt { it.similarSongsByArtist(id, count) }
    }

    private suspend fun connected(): NavidromeClient? = attempt { it }

    private suspend fun <T> attempt(block: suspend (NavidromeClient) -> T?): T? = try { client()?.let { block(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
}
