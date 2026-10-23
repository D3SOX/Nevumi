package me.d3sox.nevumi.navidrome

import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.security.CredentialVault
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8

@Serializable
data class ServerProfile(val baseUrl: String, val username: String) {
    val serverId: String get() = "$baseUrl\u0000$username".encodeUtf8().sha256().hex()
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

/** [route] maps the server address to the address requests use, such as a tunnel relay. */
class NavidromeConnection(private val store: MusicStore, private val http: HttpClient,
    private val vault: CredentialVault, private val route: suspend (String) -> String = { it }) {
    private val access = Mutex()
    private var cachedProfile: ServerProfile? = null
    private var cachedPassword: String? = null
    private var cachedRequestUrl: String? = null
    private var cachedClient: NavidromeClient? = null

    suspend fun connect(baseUrl: String, credentials: ServerCredentials) = access.withLock {
        val profile = ServerProfile(normalizeServerAddress(baseUrl), credentials.username)
        val requestUrl = route(profile.baseUrl)
        val client = NavidromeClient(http, profile.baseUrl, credentials, requestUrl)
        client.ping()
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
            cachedClient = NavidromeClient(http, profile.baseUrl, ServerCredentials(profile.username, password), requestUrl)
            cachedProfile = profile
            cachedPassword = password
            cachedRequestUrl = requestUrl
        }
        cachedClient
    }
    /** The queue this device last saved to the server, so it does not offer to continue its own queue. */
    suspend fun uploadedPlayQueue(): ServerPlayQueue? = store.read("server:play-queue")
    suspend fun rememberUploadedPlayQueue(queue: ServerPlayQueue) = store.put("server:play-queue", "play-queue-uploads", queue)
    suspend fun disconnect() = access.withLock {
        vault.delete("navidrome")
        store.database.records().delete("server:navidrome")
        store.database.records().delete("server:play-queue")
        cachedClient = null
        cachedProfile = null
        cachedPassword = null
        cachedRequestUrl = null
    }
}
