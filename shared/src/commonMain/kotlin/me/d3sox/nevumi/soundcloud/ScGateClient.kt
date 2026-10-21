package me.d3sox.nevumi.soundcloud

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.d3sox.nevumi.navidrome.normalizeServerAddress

/** Output formats the local scanner can index; sc-gate-dl's original format may be AIFF, M4A or OGG. */
@Serializable
enum class ScGateFormat(val apiName: String, val label: String) { MP3_320("mp3-320", "MP3 320"), FLAC("flac", "FLAC") }

/** Where sc-gate-dl leaves the finished file: in the music server's library or for this device to download. */
@Serializable
enum class ScGateDestination(val apiName: String) { LIBRARY("library"), CLIENT("client") }

/** What the SoundCloud listing offers: the creator's download, a download gate, or neither. */
@Serializable
enum class SoundCloudSource { DOWNLOAD, GATE, NONE }

data class ScGateCapabilities(val search: Boolean, val libraryDestination: Boolean)

@Serializable
data class SoundCloudTrack(val id: Long, val url: String, val title: String, val artist: String, val uploader: String = "",
    val artworkUrl: String? = null, val durationMs: Long = 0, val genre: String? = null, val source: SoundCloudSource = SoundCloudSource.NONE)

data class SoundCloudPage(val results: List<SoundCloudTrack>, val nextOffset: Int?)

data class ScGateJobStart(val jobId: String, val started: Boolean, val needsGateLink: Boolean, val warning: String?)

@Serializable
data class BandcampChoice(val title: String, val url: String)

data class ScGateJobStatus(val stage: String, val message: String, val percent: Int, val downloadedBytes: Long? = null,
    val totalBytes: Long? = null, val requiresAttention: Boolean = false, val bandcampTracks: List<BandcampChoice> = emptyList(),
    val queuePosition: Int? = null, val libraryFilename: String? = null, val outputFilename: String? = null,
    val error: String? = null, val title: String? = null, val artist: String? = null)

/** [status] is the HTTP status; 404 for a job means sc-gate-dl restarted and forgot it. */
class ScGateException(message: String, val status: Int? = null) : Exception(message)

private suspend fun <T> scGateRequest(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (rejected: ScGateException) { throw rejected }
    catch (_: Exception) { throw ScGateException("Could not reach sc-gate-dl. Check its address and your connection.") }

/** Talks to an sc-gate-dl server; requests go to [requestUrl], such as a tunnel relay. */
class ScGateClient(private val http: HttpClient, baseUrl: String, requestUrl: String = baseUrl) {
    val baseUrl: String = normalizeServerAddress(baseUrl)
    private val requestUrl = normalizeServerAddress(requestUrl)

    suspend fun capabilities(): ScGateCapabilities {
        val body = json(HttpMethod.Get, "/api/capabilities")
        return ScGateCapabilities(body.boolean("search"), body.boolean("libraryDestination"))
    }

    suspend fun search(query: String, offset: Int = 0, limit: Int = 20): SoundCloudPage {
        require(query.isNotBlank()) { "Enter something to search for" }
        // The server queries SoundCloud through a rate-limited queue, so a search can wait several seconds.
        val body = json(HttpMethod.Get, "/api/search", timeoutMillis = 60_000) {
            parameter("q", query.trim()); parameter("offset", offset); parameter("limit", limit)
        }
        return SoundCloudPage((body["results"] as? JsonArray).orEmpty().map { item ->
            val result = item.jsonObject
            SoundCloudTrack(requireNotNull(result.long("id")), result.text("url").orEmpty(), result.text("title").orEmpty(),
                result.text("artist").orEmpty(), result.text("uploader").orEmpty(), result.text("artworkUrl"),
                result.long("durationMs") ?: 0, result.text("genre")?.takeIf { it.isNotBlank() },
                when (result.text("source")) { "download" -> SoundCloudSource.DOWNLOAD; "gate" -> SoundCloudSource.GATE; else -> SoundCloudSource.NONE })
        }, body["nextOffset"]?.jsonPrimitive?.intOrNull)
    }

    /** A search result's cover, which sc-gate-dl fetches so this device never contacts SoundCloud. */
    suspend fun artwork(url: String): ByteArray = scGateRequest {
        request(HttpMethod.Get, "/api/artwork", timeoutMillis = 30_000, configure = { parameter("url", url) }) { it.readRawBytes() }
    }

    /** Creating a job resolves the gate and probes the download before answering, which takes up to a minute. */
    suspend fun createJob(url: String, format: ScGateFormat, destination: ScGateDestination): ScGateJobStart {
        val body = json(HttpMethod.Post, "/api/job", timeoutMillis = 180_000, body = buildJsonObject {
            put("soundcloudUrl", url); put("outputFormat", format.apiName); put("destination", destination.apiName)
        })
        return ScGateJobStart(requireNotNull(body.text("jobId")) { "sc-gate-dl did not return a job" }, body.boolean("started"),
            body.boolean("needsHypedditUrl"), body.text("soundcloudDownloadWarning"))
    }

    suspend fun job(id: String): ScGateJobStatus {
        val body = json(HttpMethod.Get, "/api/job/$id")
        val progress = body["progress"]?.jsonObject ?: JsonObject(emptyMap())
        val metadata = body["defaultMetadata"] as? JsonObject
        return ScGateJobStatus(progress.text("stage").orEmpty(), progress.text("message").orEmpty(), progress["percent"]?.jsonPrimitive?.intOrNull ?: 0,
            progress.long("downloadBytes"), progress.long("totalBytes"), progress.boolean("requiresAttention"),
            (progress["bandcampAlbumTracks"] as? JsonArray).orEmpty().map {
                BandcampChoice(it.jsonObject.text("title").orEmpty(), it.jsonObject.text("url").orEmpty())
            }, progress["queuePosition"]?.jsonPrimitive?.intOrNull, body.text("libraryFilename"), body.text("outputFilename"),
            body.text("error"), metadata?.text("title"), metadata?.text("artist"))
    }

    /** A gate link, or the SoundCloud track itself to download its stream. */
    suspend fun setGateLink(id: String, link: String) {
        json(HttpMethod.Post, "/api/job/$id/hypeddit", body = buildJsonObject { put("hypedditUrl", link) })
    }

    suspend fun start(id: String) { json(HttpMethod.Post, "/api/job/$id/start", body = JsonObject(emptyMap())) }

    suspend fun chooseBandcampTrack(id: String, trackUrl: String) {
        json(HttpMethod.Post, "/api/job/$id/bandcamp-track", body = buildJsonObject { put("trackUrl", trackUrl) })
    }

    suspend fun cancel(id: String) { json(HttpMethod.Post, "/api/job/$id/cancel", timeoutMillis = 30_000) }

    /** Streams the finished file; the server may delete it after one complete transfer. */
    suspend fun download(id: String, onChunk: suspend (ByteArray) -> Unit): Long = scGateRequest {
        request(HttpMethod.Get, "/api/job/$id/file", timeoutMillis = null) { response ->
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(64 * 1024)
            var received = 0L
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count == -1) break
                if (count > 0) { onChunk(buffer.copyOf(count)); received += count }
            }
            response.headers["Content-Length"]?.toLongOrNull()?.let {
                if (received != it) throw ScGateException("The download from sc-gate-dl was interrupted")
            }
            received
        }
    }

    private suspend fun json(method: HttpMethod, path: String, timeoutMillis: Long = 30_000, body: JsonObject? = null,
        configure: HttpRequestBuilder.() -> Unit = {}): JsonObject = scGateRequest {
        request(method, path, timeoutMillis, body, configure) { response ->
            val text = response.bodyAsText()
            if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
        }
    }

    // Without HttpTimeout, OkHttp gives up on a response that takes more than ten seconds to start.
    private suspend fun <T> request(method: HttpMethod, path: String, timeoutMillis: Long?, body: JsonObject? = null,
        configure: HttpRequestBuilder.() -> Unit = {}, read: suspend (HttpResponse) -> T): T = http.config { install(HttpTimeout) }.use { client ->
        client.prepareRequest("$requestUrl$path") {
            this.method = method
            timeout {
                socketTimeoutMillis = timeoutMillis ?: 60_000
                requestTimeoutMillis = timeoutMillis ?: HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
            configure()
        }.execute { response ->
            if (response.status.value !in 200..299) {
                val message = try { Json.parseToJsonElement(response.bodyAsText()).jsonObject.text("error") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                throw ScGateException(message ?: "sc-gate-dl request failed: HTTP ${response.status.value}", response.status.value)
            }
            read(response)
        }
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
