package me.d3sox.nevumi.navidrome

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.security.CredentialVault
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8
import kotlin.math.abs

/**
 * Source IDs, playlist links and the other server data embed [serverId]. A server whose address changed keeps the ID of its
 * first address as [identity], so that data stays attached.
 */
@Serializable
data class ServerProfile(val baseUrl: String, val username: String, val identity: String? = null) {
    val serverId: String get() = identity ?: "$baseUrl\u0000$username".encodeUtf8().sha256().hex()
}

internal fun normalizeServerAddress(baseUrl: String): String {
    val input = baseUrl.trim()
    val absolute = input.startsWith("https://", ignoreCase = true) || input.startsWith("http://", ignoreCase = true)
    val address = try { Url(input) } catch (_: Exception) { null }
    require(absolute && address != null && address.protocol.name in setOf("https", "http") && address.host.isNotBlank()
        && address.host.none(Char::isWhitespace)
        && address.user.isNullOrEmpty() && address.password.isNullOrEmpty() && address.parameters.isEmpty() && address.fragment.isEmpty()) {
        "Enter the server's HTTP or HTTPS address without credentials, a query, or a fragment"
    }
    return address.toString().trimEnd('/')
}

/** Whether [baseUrl] would send the password unencrypted to another machine. Loopback traffic stays on this device. */
internal fun sendsPasswordInClearText(baseUrl: String): Boolean {
    val input = baseUrl.trim()
    if (!input.startsWith("http://", ignoreCase = true)) return false
    val host = (try { Url(input).host } catch (_: Exception) { return false }).removeSurrounding("[", "]").lowercase()
    return host.isNotBlank() && host != "localhost" && host != "::1" &&
        !host.matches(Regex("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"))
}

/** [route] maps the server address to the address requests use, such as a tunnel relay. */
class NavidromeConnection(private val store: MusicStore, private val http: HttpClient,
    private val vault: CredentialVault, private val route: suspend (String) -> String = { it }) {
    private val access = Mutex()
    private var cachedProfile: ServerProfile? = null
    private var cachedPassword: String? = null
    private var cachedRequestUrl: String? = null
    private var cachedClient: NavidromeClient? = null

    suspend fun connect(baseUrl: String, credentials: ServerCredentials) = access.withLock {
        val address = normalizeServerAddress(baseUrl)
        val requestUrl = route(address)
        val probe = NavidromeClient(http, address, credentials, requestUrl)
        probe.ping()
        // A new address of the same server and account keeps its identity; another server or account starts a new one.
        val fresh = ServerProfile(address, credentials.username)
        val previous = profile()?.takeIf { it.username == credentials.username && it.serverId != fresh.serverId }
        val identity = previous?.serverId?.takeIf { previous.baseUrl == address || servesSongsOf(probe, it) }
        val profile = fresh.copy(identity = identity)
        val client = NavidromeClient(http, address, credentials, requestUrl, identity = identity)
        vault.save("navidrome", credentials.password)
        store.put("server:navidrome", "servers", profile)
        cachedProfile = profile
        cachedPassword = credentials.password
        cachedRequestUrl = requestUrl
        cachedClient = client
    }
    suspend fun profile(): ServerProfile? = store.read("server:navidrome")
    suspend fun client(): NavidromeClient? = access.withLock {
        val profile = profile() ?: return@withLock null
        val password = vault.read("navidrome") ?: error("Unlock your credential store or reconnect to your music server")
        val requestUrl = route(profile.baseUrl)
        if (cachedProfile != profile || cachedPassword != password || cachedRequestUrl != requestUrl || cachedClient == null) {
            cachedClient = NavidromeClient(http, profile.baseUrl, ServerCredentials(profile.username, password), requestUrl,
                identity = profile.identity)
            cachedProfile = profile
            cachedPassword = password
            cachedRequestUrl = requestUrl
        }
        cachedClient
    }
    /**
     * Whether [client] serves the songs imported from [serverId]. Subsonic song IDs stay the same when a server moves, and
     * matching titles and lengths rule out another server that happens to number its songs the same way.
     */
    private suspend fun servesSongsOf(client: NavidromeClient, serverId: String): Boolean {
        val library = MusicLibrary(store)
        val prefix = "server:$serverId:"
        val known = mutableListOf<Pair<String, TrackMetadata>>()
        // A sample of songs that were still on the server, without reading the whole library.
        for (key in store.database.records().keys("source-owners").filter { it.startsWith("source-owner:$prefix") }.shuffled().take(64)) {
            val sourceId = key.removePrefix("source-owner:")
            val track = library.trackForSource(sourceId) ?: continue
            if (track.sources.any { it.id == sourceId && it.available }) known += sourceId.removePrefix(prefix) to track.metadata
            if (known.size == 8) break
        }
        val releases = mutableMapOf<String, String>()
        val matches = known.count { (songId, metadata) ->
            val song = try { client.song(songId, releases).metadata }
                catch (_: NavidromeException) { null }
                catch (error: NavidromeRequestException) { if (error.serverUnavailable) throw error else null }
            song != null && song.title.trim().equals(metadata.title.trim(), ignoreCase = true) &&
                abs(song.durationMs - metadata.durationMs) <= 2_000
        }
        // Songs removed from the server meanwhile no longer answer, so most of the sample suffices.
        return matches > 0 && matches * 2 >= known.size
    }
    /** The queue this device last saved to the server, so it does not offer to continue its own queue. */
    suspend fun uploadedPlayQueue(serverId: String): ServerPlayQueue? = store.read("server:play-queue:$serverId")
    suspend fun rememberUploadedPlayQueue(serverId: String, queue: ServerPlayQueue) =
        store.put("server:play-queue:$serverId", "play-queue-uploads", queue)
    suspend fun disconnect() = access.withLock {
        profile()?.serverId?.let { store.database.records().delete("server:play-queue:$it") }
        vault.delete("navidrome")
        store.database.records().delete("server:navidrome")
        cachedClient = null
        cachedProfile = null
        cachedPassword = null
        cachedRequestUrl = null
    }
}
