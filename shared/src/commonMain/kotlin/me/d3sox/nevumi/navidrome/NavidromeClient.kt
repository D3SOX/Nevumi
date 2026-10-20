package me.d3sox.nevumi.navidrome

import me.d3sox.nevumi.library.TrackMetadata
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlin.uuid.Uuid
import okio.ByteString.Companion.encodeUtf8
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class ServerPlaylist(val id: String, val name: String, val songIds: List<String>, val changed: String,
    val owner: String, val description: String = "", val coverArt: String? = null, val created: String = "")

class ServerCredentials(val username: String, internal val password: String) {
    override fun toString(): String = "ServerCredentials(username=$username, password=[redacted])"
}

data class ServerSong(
    val id: String,
    val metadata: TrackMetadata,
    val format: String = "",
    val sizeBytes: Long = 0,
    val coverArt: String? = null,
    val favorite: Boolean = false,
    val rating: Int = 0,
    val filePath: String? = null,
    val fileLibraryId: Long? = null,
)

data class NavidromeMusicFile(val songId: String, val path: String, val libraryId: Long, val libraryName: String)
data class NavidromeMusicDirectory(val id: Long, val name: String)

class NavidromeException(val code: Int) : Exception(when (code) {
    40 -> "The music server rejected the username or password"
    50 -> "This account does not have permission for that action"
    41 -> "This account cannot use token authentication. Use a local music-server account"
    60 -> "The music server requires an active Subsonic license"
    70 -> "The requested music server item was not found"
    20, 30 -> "The music server does not support this API version"
    else -> "The music server rejected that request"
})

class NavidromeRequestException(message: String = "Could not complete the music server request. Check your connection and server address.", val httpStatus: Int? = null,
    /** The request never reached the server, so repeating it later is safe. */
    val unreachable: Boolean = false,
    /** The connection itself failed (unreachable host, timeout, TLS or socket error), so every request would fail the same way. */
    val connectionFailure: Boolean = unreachable) : Exception(message)

/** Resolution and connection failures happen before any request is sent. */
internal fun Throwable.isUnreachableHost(): Boolean = this::class.simpleName in setOf("UnknownHostException", "ConnectException",
    "NoRouteToHostException", "ConnectTimeoutException", "UnresolvedAddressException")

/** Failures of the connection rather than of one request: nothing else will succeed against this server right now. */
internal fun Throwable.isConnectionFailure(): Boolean = isUnreachableHost() || this::class.simpleName in setOf("SocketTimeoutException",
    "HttpRequestTimeoutException", "SocketException", "SSLException", "SSLHandshakeException", "SSLPeerUnverifiedException",
    "EOFException", "IOException", "StreamResetException", "ConnectionShutdownException")

private class DownloadCallbackFailure(val failure: Exception) : Exception()

private suspend fun <T> serverRequest(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (local: DownloadCallbackFailure) { throw local.failure }
    catch (rejected: NavidromeException) { throw rejected }
    catch (safe: NavidromeRequestException) { throw safe }
    catch (error: Exception) { throw NavidromeRequestException(unreachable = error.isUnreachableHost(), connectionFailure = error.isConnectionFailure()) }

private suspend fun downloadCallback(block: suspend () -> Unit) {
    try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { throw DownloadCallbackFailure(error) }
}

internal const val CONCURRENT_REQUESTS = 6

/** Requests go to [requestUrl], such as a tunnel relay, while [baseUrl] identifies the server. */
class NavidromeClient(
    private val http: HttpClient,
    baseUrl: String,
    private val credentials: ServerCredentials,
    requestUrl: String = baseUrl,
) {
    private val baseUrl = normalizeServerAddress(requestUrl)
    val serverId: String = ServerProfile(normalizeServerAddress(baseUrl), credentials.username).serverId
    val username: String get() = credentials.username
    private val nativeAccess = Mutex()
    private var nativeToken: String? = null

    suspend fun musicDirectories(): List<NavidromeMusicDirectory> = serverRequest {
        request("getMusicFolders")["musicFolders"]?.jsonObject?.get("musicFolder")?.jsonArray.orEmpty().map { item ->
            val directory = item.jsonObject
            NavidromeMusicDirectory(requireNotNull(directory["id"]?.jsonPrimitive?.longOrNull), directory.getValue("name").jsonPrimitive.content)
        }
    }

    /** Checks the address and credentials with a single request. */
    suspend fun ping() { request("ping") }

    /** Asks the server to index new or changed files; a full scan re-reads every file and is rarely needed. */
    suspend fun rescan(fullScan: Boolean = false) {
        request("startScan", if (fullScan) listOf("fullScan" to "true") else emptyList())
        val finished = kotlinx.coroutines.withTimeoutOrNull(120_000) {
            while (request("getScanStatus")["scanStatus"]?.jsonObject?.get("scanning")?.jsonPrimitive?.booleanOrNull == true)
                kotlinx.coroutines.delay(250)
        }
        if (finished == null) throw NavidromeRequestException("The music server scan did not finish in time. Refresh the library later.")
    }

    /** Looks up one song without downloading the catalogue; pass the same [releases] map to reuse album lookups across songs. */
    suspend fun song(id: String, releases: MutableMap<String, String> = mutableMapOf()): ServerSong = serverRequest {
        parseSong(request("getSong", listOf("id" to id)).getValue("song").jsonObject, releases)
    }

    private suspend fun playlistSummaries(): List<JsonObject> = request("getPlaylists")["playlists"]
        ?.jsonObject?.get("playlist")?.jsonArray.orEmpty().map { it.jsonObject }

    suspend fun playlists(): List<ServerPlaylist> = serverRequest {
        val limit = Semaphore(CONCURRENT_REQUESTS)
        coroutineScope { playlistSummaries().map { summary -> async { limit.withPermit { playlist(summary.getValue("id").jsonPrimitive.content) } } }.awaitAll() }
    }

    /** Maps each playlist ID to its last change time with a single request. */
    suspend fun playlistVersions(): Map<String, String> = serverRequest {
        playlistSummaries().associate { it.getValue("id").jsonPrimitive.content to it["changed"]?.jsonPrimitive?.content.orEmpty() }
    }

    suspend fun playlist(id: String): ServerPlaylist = serverRequest {
        parsePlaylist(request("getPlaylist", listOf("id" to id)).getValue("playlist").jsonObject)
    }

    suspend fun savePlaylist(name: String, songIds: List<String>, id: String? = null, description: String? = null): ServerPlaylist {
        require(name.isNotBlank()) { "Name the playlist" }
        return serverRequest {
            val previousIds = if (id == null) playlistSummaries().map { it.getValue("id").jsonPrimitive.content }.toSet() else emptySet()
            val parameters = if (id == null) listOf("name" to name.trim()) else listOf("playlistId" to id)
            val response = request("createPlaylist", parameters + songIds.map { "songId" to it })
            val createdId = response["playlist"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: id
                ?: playlistSummaries().filter {
                    it.getValue("id").jsonPrimitive.content !in previousIds &&
                        it["name"]?.jsonPrimitive?.content == name.trim() &&
                        it["owner"]?.jsonPrimitive?.content.orEmpty() in setOf("", username)
                }.singleOrNull()?.get("id")?.jsonPrimitive?.content
                ?: throw NavidromeRequestException("The server created the playlist but did not identify it. Refresh playlists before trying again.")
            if (id != null || description != null) request("updatePlaylist", buildList {
                add("playlistId" to createdId); add("name" to name.trim())
                if (description != null) add("comment" to description)
            })
            playlist(createdId)
        }
    }

    suspend fun deletePlaylist(id: String) { request("deletePlaylist", listOf("id" to id)) }

    suspend fun setRating(songId: String, rating: Int) {
        require(rating in 0..5) { "Choose a rating from one to five stars, or zero to clear it" }
        request("setRating", listOf("id" to songId, "rating" to rating.toString()))
    }

    suspend fun scrobble(songId: String, startedAt: Long, submission: Boolean) {
        require(startedAt >= 0) { "The listening timestamp cannot be negative" }
        request("scrobble", listOf("id" to songId, "time" to startedAt.toString(), "submission" to submission.toString()))
    }

    private fun parsePlaylist(playlist: JsonObject): ServerPlaylist = ServerPlaylist(
        playlist.getValue("id").jsonPrimitive.content, playlist.getValue("name").jsonPrimitive.content,
        playlist["entry"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("id").jsonPrimitive.content },
        playlist["changed"]?.jsonPrimitive?.content.orEmpty(), playlist["owner"]?.jsonPrimitive?.content.orEmpty(), playlist["comment"]?.jsonPrimitive?.content.orEmpty(),
        playlist["coverArt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }, playlist["created"]?.jsonPrimitive?.content.orEmpty())

    private suspend fun request(endpoint: String, parameters: List<Pair<String, String>> = emptyList()): JsonObject = serverRequest {
        val salt = Uuid.random().toString()
        val response = http.get("${baseUrl.trimEnd('/')}/rest/$endpoint.view") {
            parameter("u", credentials.username)
            parameter("t", passwordToken(credentials.password, salt))
            parameter("s", salt)
            parameter("v", "1.13.0")
            parameter("c", "Nevumi")
            parameter("f", "json")
            parameters.forEach { (key, value) -> url.parameters.append(key, value) }
        }
        if (response.status.value !in 200..299) throw NavidromeRequestException("Music server request failed: HTTP ${response.status.value}", response.status.value)
        val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("subsonic-response").jsonObject
        if (envelope["status"]?.jsonPrimitive?.content != "ok") {
            val error = envelope["error"]?.jsonObject
            throw NavidromeException(error?.get("code")?.jsonPrimitive?.intOrNull ?: 0)
        }
        envelope
    }

    suspend fun setFavorite(songId: String, favorite: Boolean) {
        request(if (favorite) "star" else "unstar", listOf("id" to songId))
    }

    /** Downloads a cover scaled to [size] pixels, or the original image when [size] is null. */
    suspend fun coverArt(coverId: String, size: Int? = 1024): ByteArray = serverRequest {
        val salt = Uuid.random().toString()
        http.prepareGet("${baseUrl.trimEnd('/')}/rest/getCoverArt.view") {
            parameter("u", credentials.username)
            parameter("t", passwordToken(credentials.password, salt))
            parameter("s", salt)
            parameter("v", "1.13.0")
            parameter("c", "Nevumi")
            parameter("f", "json")
            parameter("id", coverId)
            size?.let { parameter("size", it.toString()) }
        }.execute { response ->
            check(response.status.value in 200..299 && response.headers[HttpHeaders.ContentType]?.startsWith("image/") == true) {
                "Server returned no artwork"
            }
            val bytes = okio.Buffer()
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                check(bytes.size + count <= 10 * 1024 * 1024) { "Server artwork is too large" }
                if (count > 0) bytes.write(buffer, 0, count)
            }
            bytes.readByteArray()
        }
    }

    fun streamUrl(songId: String): String {
        val salt = Uuid.random().toString()
        return URLBuilder("${baseUrl.trimEnd('/')}/rest/stream.view").apply {
            parameters.append("u", credentials.username)
            parameters.append("t", passwordToken(credentials.password, salt))
            parameters.append("s", salt)
            parameters.append("v", "1.13.0")
            parameters.append("c", "Nevumi")
            parameters.append("f", "json")
            parameters.append("id", songId)
            parameters.append("format", "raw")
            parameters.append("maxBitRate", "0")
        }.buildString()
    }
    private suspend fun nativeSession(nativeHttp: HttpClient): String {
        nativeToken?.let { return it }
        val login = nativeHttp.post("$baseUrl/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("username", credentials.username); put("password", credentials.password) }.toString())
        }
        if (login.status.value == 401) throw NavidromeException(40)
        if (login.status.value !in 200..299) throw NavidromeRequestException("Navidrome file access failed: HTTP ${login.status.value}")
        return Json.parseToJsonElement(login.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content.also { nativeToken = it }
    }

    private suspend fun nativeRequest(send: suspend HttpClient.(token: String) -> HttpResponse): HttpResponse = nativeAccess.withLock {
        http.config { followRedirects = false }.use { nativeHttp ->
            var response = nativeHttp.send(nativeSession(nativeHttp))
            if (response.status.value == 401) {
                response.bodyAsText()
                nativeToken = null
                response = nativeHttp.send(nativeSession(nativeHttp))
            }
            when (response.status.value) {
                in 200..299 -> response
                403 -> throw NavidromeException(50)
                404, 405 -> throw NavidromeRequestException("This music server can't change playlist covers. Navidrome 0.61 or newer is required.")
                else -> throw NavidromeRequestException("Navidrome rejected the playlist cover: HTTP ${response.status.value}")
            }
        }
    }

    suspend fun playlistImageUploaded(id: String): Boolean = serverRequest {
        val playlist = Json.parseToJsonElement(nativeRequest { token ->
            get("$baseUrl/api/playlist/$id") { header("x-nd-authorization", "Bearer $token") }
        }.bodyAsText()).jsonObject
        playlist["uploadedImage"]?.jsonPrimitive?.content.orEmpty().isNotBlank()
    }

    suspend fun setPlaylistImage(id: String, image: ByteArray) = serverRequest {
        nativeRequest { token ->
            post("$baseUrl/api/playlist/$id/image") {
                header("x-nd-authorization", "Bearer $token")
                setBody(MultiPartFormDataContent(formData {
                    append("image", image, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"cover\"") })
                }))
            }
        }.bodyAsText()
        Unit
    }

    suspend fun removePlaylistImage(id: String) = serverRequest {
        nativeRequest { token -> delete("$baseUrl/api/playlist/$id/image") { header("x-nd-authorization", "Bearer $token") } }.bodyAsText()
        Unit
    }

    suspend fun isNavidrome(): Boolean = request("ping")["type"]?.jsonPrimitive?.content == "navidrome"

    suspend fun musicFiles(): List<NavidromeMusicFile> = serverRequest {
        if (!isNavidrome())
            throw NavidromeRequestException("Remote file editing requires Navidrome's file catalogue. Other Subsonic servers support streaming and library actions.")
        nativeAccess.withLock {
            http.config { followRedirects = false }.use { nativeHttp ->
                val files = mutableListOf<NavidromeMusicFile>()
                val seen = mutableSetOf<String>()
                var offset = 0
                while (true) {
                    suspend fun page(): io.ktor.client.statement.HttpResponse {
                        val token = nativeSession(nativeHttp)
                        return nativeHttp.get("$baseUrl/api/song") {
                            header("x-nd-authorization", "Bearer $token")
                            parameter("_start", offset); parameter("_end", offset + 500)
                            parameter("_sort", "id"); parameter("_order", "ASC")
                        }
                    }
                    var response = page()
                    if (response.status.value == 401) {
                        response.bodyAsText()
                        nativeToken = null
                        response = page()
                    }
                    if (response.status.value !in 200..299) throw NavidromeRequestException("Navidrome file access failed: HTTP ${response.status.value}")
                    val entries = Json.parseToJsonElement(response.bodyAsText()).jsonArray.map { item ->
                        val song = item.jsonObject
                        NavidromeMusicFile(song.getValue("id").jsonPrimitive.content, song.getValue("path").jsonPrimitive.content,
                            requireNotNull(song["libraryId"]?.jsonPrimitive?.longOrNull), song["libraryName"]?.jsonPrimitive?.content.orEmpty())
                    }
                    if (entries.isEmpty()) break
                    val fresh = entries.filter { seen.add(it.songId) }
                    check(fresh.isNotEmpty()) { "Navidrome returned a repeated file catalogue page" }
                    files += fresh
                    offset += entries.size
                }
                files
            }
        }
    }

    suspend fun songs(includeFilePaths: Boolean = false): List<ServerSong> = serverRequest {
        val songs = mutableListOf<ServerSong>()
        val seen = mutableSetOf<String>()
        val releases = mutableMapOf<String, String>()
        var offset = 0
        while (true) {
        val envelope = optionalCatalogueRequest("search3", listOf("query" to "", "artistCount" to "0", "albumCount" to "0",
            "songCount" to "500", "songOffset" to offset.toString()))
        val result = envelope?.get("searchResult3")?.jsonObject.orEmpty()
        val entries = result["song"]?.jsonArray.orEmpty().map { it.jsonObject }
        loadReleases(entries, releases)
        val page = entries.map { parseSong(it, releases) }
        if (page.isEmpty()) {
            if (songs.isEmpty()) songs += catalogueFallback(releases)
            break
        }
        val fresh = page.filter { seen.add(it.id) }
        check(fresh.isNotEmpty()) { "Server returned a repeated catalogue page" }
        songs += fresh
        offset += page.size
        }
        if (!includeFilePaths) songs else {
            val files = musicFiles().associateBy { it.songId }
            songs.map { song ->
                val file = requireNotNull(files[song.id]) { "Navidrome did not provide this song's music file" }
                song.copy(filePath = file.path, fileLibraryId = file.libraryId)
            }
        }
    }


    private fun recordingId(song: JsonObject) = song["musicBrainzId"]?.jsonPrimitive?.content.orEmpty().takeIf {
        song["mediaType"]?.jsonPrimitive?.content == "song"
    }.orEmpty()

    private suspend fun albumRelease(albumId: String) =
        request("getAlbum", listOf("id" to albumId)).getValue("album").jsonObject["musicBrainzId"]?.jsonPrimitive?.content.orEmpty()

    /** Fetches the release ids of a page's albums a few at a time instead of one album per song in sequence. */
    private suspend fun loadReleases(songs: List<JsonObject>, releases: MutableMap<String, String>) {
        val albumIds = songs.filter { recordingId(it).isNotBlank() }
            .mapNotNull { it["albumId"]?.jsonPrimitive?.content?.takeIf { id -> id.isNotBlank() && id !in releases } }.distinct()
        if (albumIds.isEmpty()) return
        val limit = Semaphore(CONCURRENT_REQUESTS)
        coroutineScope { albumIds.map { id -> async { limit.withPermit { id to albumRelease(id) } } }.awaitAll() }
            .forEach { (id, release) -> releases[id] = release }
    }

    private suspend fun parseSong(song: JsonObject, releases: MutableMap<String, String>): ServerSong {
            val recordingId = recordingId(song)
            val albumId = song["albumId"]?.jsonPrimitive?.content.orEmpty()
            val releaseId = if (recordingId.isNotBlank() && albumId.isNotBlank()) releases.getOrPut(albumId) { albumRelease(albumId) } else ""
            val genres = song["genres"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            }.distinct().ifEmpty { song["genre"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty() }
            return ServerSong(song.getValue("id").jsonPrimitive.content,
                TrackMetadata(song["title"]?.jsonPrimitive?.content ?: song["name"]?.jsonPrimitive?.content.orEmpty(),
                    song["artist"]?.jsonPrimitive?.content.orEmpty(),
                    song["album"]?.jsonPrimitive?.content.orEmpty(),
                    durationMs = (song["duration"]?.jsonPrimitive?.longOrNull ?: 0) * 1_000,
                    genres = genres,
                    albumArtist = song["albumArtists"]?.jsonArray.orEmpty().mapNotNull {
                        it.jsonObject["name"]?.jsonPrimitive?.content
                    }.joinToString(", ").ifBlank { song["albumArtist"]?.jsonPrimitive?.content.orEmpty() }
                        .ifBlank { song["artist"]?.jsonPrimitive?.content.orEmpty() },
                    trackNumber = song["track"]?.jsonPrimitive?.intOrNull ?: 0,
                    discNumber = (song["discNumber"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1),
                    year = song["year"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..9999 } ?: 0,
                    recordingId = recordingId,
                    releaseId = releaseId,
                    folder = song["path"]?.jsonPrimitive?.content.orEmpty().replace('\\', '/')
                        .substringBeforeLast('/', "")),
                format = song["suffix"]?.jsonPrimitive?.content.orEmpty(),
                sizeBytes = song["size"]?.jsonPrimitive?.longOrNull ?: 0,
                coverArt = song["coverArt"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                favorite = song["starred"]?.jsonPrimitive?.content?.let { it.isNotBlank() && it != "null" } == true,
                rating = song["userRating"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..5 } ?: 0)
    }

    // Empty search is an OpenSubsonic extension. Classic servers need album or directory browsing.
    private suspend fun optionalCatalogueRequest(endpoint: String, parameters: List<Pair<String, String>> = emptyList()): JsonObject? =
        try { request(endpoint, parameters) }
        catch (error: NavidromeException) { if (error.code in setOf(0, 10, 70)) null else throw error }
        catch (error: NavidromeRequestException) { if (error.httpStatus in setOf(404, 501)) null else throw error }

    private suspend fun catalogueFallback(releases: MutableMap<String, String>): List<ServerSong> {
        val songs = linkedMapOf<String, ServerSong>()
        val albums = mutableSetOf<String>()
        var offset = 0
        while (true) {
            val page = optionalCatalogueRequest("getAlbumList2", listOf("type" to "alphabeticalByName",
                "size" to "500", "offset" to offset.toString()))
                ?.get("albumList2")?.jsonObject?.get("album")?.jsonArray.orEmpty()
            if (page.isEmpty()) break
            val fresh = page.map { it.jsonObject.getValue("id").jsonPrimitive.content }.filter { albums.add(it) }
            check(fresh.isNotEmpty()) { "Server returned a repeated album catalogue page" }
            for (id in fresh) {
                val album = request("getAlbum", listOf("id" to id)).getValue("album").jsonObject
                releases[id] = album["musicBrainzId"]?.jsonPrimitive?.content.orEmpty()
                for (entry in album["song"]?.jsonArray.orEmpty()) {
                    val song = parseSong(entry.jsonObject, releases)
                    songs[song.id] = song
                }
            }
            offset += page.size
        }
        if (songs.isNotEmpty()) return songs.values.toList()
        val indexes = request("getIndexes")["indexes"]?.jsonObject ?: return emptyList()
        val directories = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        fun addDirectory(entry: JsonObject) { entry["id"]?.jsonPrimitive?.content?.let { if (visited.add(it)) directories.addLast(it) } }
        suspend fun addFiles(entries: List<JsonObject>) {
            for (entry in entries) {
                if (entry["isDir"]?.jsonPrimitive?.booleanOrNull == true) addDirectory(entry)
                else if (entry["isVideo"]?.jsonPrimitive?.booleanOrNull != true && entry["type"]?.jsonPrimitive?.content != "video") {
                    val song = parseSong(entry, releases)
                    songs[song.id] = song
                }
            }
        }
        for (index in indexes["index"]?.jsonArray.orEmpty())
            index.jsonObject["artist"]?.jsonArray.orEmpty().forEach { addDirectory(it.jsonObject) }
        indexes["shortcut"]?.jsonArray.orEmpty().forEach { addDirectory(it.jsonObject) }
        addFiles(indexes["child"]?.jsonArray.orEmpty().map { it.jsonObject })
        while (directories.isNotEmpty()) {
            val directory = request("getMusicDirectory", listOf("id" to directories.removeFirst())).getValue("directory").jsonObject
            addFiles(directory["child"]?.jsonArray.orEmpty().map { it.jsonObject })
        }
        return songs.values.toList()
    }

    suspend fun downloadSong(songId: String, offset: Long = 0, ifRange: String? = null,
        onValidator: suspend (String?) -> Unit = {},
        onStart: suspend (Long) -> Unit = {}, onChunk: suspend (ByteArray) -> Unit): Long = serverRequest {
        val salt = Uuid.random().toString()
        http.prepareGet("${baseUrl.trimEnd('/')}/rest/download.view") {
            parameter("u", credentials.username)
            parameter("t", passwordToken(credentials.password, salt))
            parameter("s", salt)
            parameter("v", "1.13.0")
            parameter("c", "Nevumi")
            parameter("f", "json")
            parameter("id", songId)
            if (offset > 0) {
                header(HttpHeaders.Range, "bytes=$offset-")
                if (ifRange != null) header(HttpHeaders.IfRange, ifRange)
            }
        }.execute { response ->
            if (response.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true) {
                val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    .getValue("subsonic-response").jsonObject
                val error = envelope["error"]?.jsonObject
                throw NavidromeException(error?.get("code")?.jsonPrimitive?.intOrNull ?: 0)
            }
            check(response.status.value in 200..299) { "Audio request failed: HTTP ${response.status.value}" }
            val start = if (response.status.value == 206) {
                val range = response.headers[HttpHeaders.ContentRange].orEmpty()
                Regex("bytes (\\d+)-\\d+/\\d+").matchEntire(range)?.groupValues?.get(1)?.toLong()
                    ?: error("Invalid audio resume response")
            } else 0L
            check(start == 0L || start == offset) { "Server resumed at the wrong byte" }
            val etag = response.headers[HttpHeaders.ETag]
            val validator = if (etag != null) etag.takeIf { it.startsWith('"') && it.endsWith('"') }
            else response.headers[HttpHeaders.LastModified]?.takeIf { modified ->
                runCatching {
                    val date = requireNotNull(response.headers[HttpHeaders.Date])
                    date.fromHttpToGmtDate().timestamp - modified.fromHttpToGmtDate().timestamp >= 1_000
                }.getOrDefault(false)
            }
            if (start > 0 && ifRange != null && validator != null)
                check(validator == ifRange) { "Server returned a different audio version while resuming" }
            downloadCallback { onValidator(validator) }
            downloadCallback { onStart(start) }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(32 * 1024)
            var received = 0L
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count == -1) break
                if (count > 0) {
                    downloadCallback { onChunk(buffer.copyOf(count)) }
                    received += count
                }
            }
            received
        }
    }
}

internal expect fun passwordToken(password: String, salt: String): String
