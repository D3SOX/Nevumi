package me.d3sox.nevumi.history

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import okio.ByteString.Companion.encodeUtf8
import kotlin.uuid.Uuid

enum class ScrobbleFailure { AUTHORIZATION, RETRYABLE, REJECTED, UNCERTAIN }

class ScrobbleException(val failure: ScrobbleFailure, message: String) : Exception(message)

private suspend fun <T> scrobbleRequest(block: suspend () -> T): T = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: ScrobbleException) { throw failure }
catch (_: Exception) { throw ScrobbleException(ScrobbleFailure.UNCERTAIN, "The scrobbling service did not confirm the request") }

private fun checkStatus(response: HttpResponse) {
    val code = response.status.value
    if (code in 200..299) return
    val failure = when (code) {
        401, 403 -> ScrobbleFailure.AUTHORIZATION
        429 -> ScrobbleFailure.RETRYABLE
        in 400..499 -> ScrobbleFailure.REJECTED
        else -> ScrobbleFailure.UNCERTAIN
    }
    throw ScrobbleException(failure, "Scrobbling request failed: HTTP $code")
}

private fun requireMetadata(listen: Listen) {
    require(listen.metadata.artist.isNotBlank() && listen.metadata.title.isNotBlank()) { "Scrobbling needs an artist and song title" }
}

private fun recordingMbid(value: String): String? = try {
    Uuid.parse(value.trim()).takeUnless { it == Uuid.NIL }?.toString()
} catch (_: IllegalArgumentException) { null }

class ListenBrainzClient(private val http: HttpClient, private val token: String,
    private val baseUrl: String = "https://api.listenbrainz.org") {
    suspend fun validateToken(): String = scrobbleRequest {
        val response = http.get("${baseUrl.trimEnd('/')}/1/validate-token") { header(HttpHeaders.Authorization, "Token $token") }
        checkStatus(response)
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (result["valid"]?.jsonPrimitive?.booleanOrNull != true)
            throw ScrobbleException(ScrobbleFailure.AUTHORIZATION, "The ListenBrainz token is invalid")
        result.getValue("user_name").jsonPrimitive.content
    }

    suspend fun submit(listen: Listen) = send(listen, nowPlaying = false)
    suspend fun nowPlaying(listen: Listen) = send(listen, nowPlaying = true)

    private suspend fun send(listen: Listen, nowPlaying: Boolean) {
        requireMetadata(listen)
        val payload = buildJsonObject {
            put("listen_type", if (nowPlaying) "playing_now" else "single")
            putJsonArray("payload") { add(buildJsonObject {
                if (!nowPlaying) put("listened_at", listen.startedAt / 1000)
                putJsonObject("track_metadata") {
                    put("artist_name", listen.metadata.artist); put("track_name", listen.metadata.title)
                    if (listen.metadata.album.isNotBlank()) put("release_name", listen.metadata.album)
                    putJsonObject("additional_info") {
                        put("submission_client", "Nevumi")
                        if (listen.durationMs > 0) put("duration_ms", listen.durationMs)
                        recordingMbid(listen.metadata.recordingId)?.let { put("recording_mbid", it) }
                    }
                }
            }) }
        }
        scrobbleRequest {
            val response = http.post("${baseUrl.trimEnd('/')}/1/submit-listens") {
                header(HttpHeaders.Authorization, "Token $token")
                contentType(ContentType.Application.Json); setBody(payload.toString())
            }
            checkStatus(response)
            val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            if (result["status"]?.jsonPrimitive?.content != "ok")
                throw ScrobbleException(ScrobbleFailure.UNCERTAIN, "ListenBrainz did not confirm the submission")
        }
    }
}

class LastFmAuthorization(internal val token: String, val url: String) {
    override fun toString() = "LastFmAuthorization([redacted])"
}
class LastFmSession(val username: String, internal val key: String) {
    override fun toString() = "LastFmSession(username=$username, key=[redacted])"
}

class LastFmClient(private val http: HttpClient, private val apiKey: String, private val sharedSecret: String,
    private val sessionKey: String? = null, private val endpoint: String = "https://ws.audioscrobbler.com/2.0/") {
    suspend fun requestAuthorization(): LastFmAuthorization = scrobbleRequest {
        val token = request("auth.getToken").getValue("token").jsonPrimitive.content
        check(token.isNotBlank())
        val address = URLBuilder("https://www.last.fm/api/auth/").apply {
            parameters.append("api_key", apiKey); parameters.append("token", token)
        }.buildString()
        LastFmAuthorization(token, address)
    }
    suspend fun finishAuthorization(token: String): LastFmSession = scrobbleRequest {
        val session = request("auth.getSession", mapOf("token" to token)).getValue("session").jsonObject
        val name = session.getValue("name").jsonPrimitive.content
        val key = session.getValue("key").jsonPrimitive.content
        check(name.isNotBlank() && key.isNotBlank())
        LastFmSession(name, key)
    }

    suspend fun submit(listen: Listen) {
        require(listen.durationMs > 30_000) { "Last.fm only scrobbles songs longer than 30 seconds" }
        send(listen, nowPlaying = false)
    }
    suspend fun nowPlaying(listen: Listen) = send(listen, nowPlaying = true)

    private suspend fun send(listen: Listen, nowPlaying: Boolean) {
        requireMetadata(listen)
        val metadata = listen.metadata
        val parameters = buildMap {
            put("sk", requireNotNull(sessionKey) { "Sign in to Last.fm" })
            put("artist", metadata.artist); put("track", metadata.title)
            if (metadata.album.isNotBlank()) put("album", metadata.album)
            if (metadata.albumArtist.isNotBlank()) put("albumArtist", metadata.albumArtist)
            if (metadata.trackNumber > 0) put("trackNumber", metadata.trackNumber.toString())
            recordingMbid(metadata.recordingId)?.let { put("mbid", it) }
            if (listen.durationMs > 0) put("duration", (listen.durationMs / 1000).toString())
            if (!nowPlaying) put("timestamp", (listen.startedAt / 1000).toString())
        }
        scrobbleRequest {
            val response = request(if (nowPlaying) "track.updateNowPlaying" else "track.scrobble", parameters)
            val ignored = if (nowPlaying) response.getValue("nowplaying").jsonObject["ignoredMessage"]?.jsonObject else
                response.getValue("scrobbles").jsonObject.getValue("scrobble").jsonObject["ignoredMessage"]?.jsonObject
            val code = requireNotNull(ignored?.get("code")?.jsonPrimitive?.intOrNull)
            if (code != 0) throw ScrobbleException(ScrobbleFailure.REJECTED, "Last.fm did not accept the song metadata or timestamp")
        }
    }

    private suspend fun request(method: String, values: Map<String, String> = emptyMap()): JsonObject = scrobbleRequest {
        val parameters = values + mapOf("api_key" to apiKey, "method" to method)
        val signature = (parameters.entries.sortedBy { it.key }.joinToString("") { it.key + it.value } + sharedSecret).encodeUtf8().md5().hex()
        val response = http.submitForm(endpoint, Parameters.build {
            parameters.forEach { (key, value) -> append(key, value) }
            append("api_sig", signature); append("format", "json")
        })
        checkStatus(response)
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        result["error"]?.jsonPrimitive?.intOrNull?.let { code ->
            val failure = when (code) {
                4, 9, 10, 14, 15, 26 -> ScrobbleFailure.AUTHORIZATION
                11, 16, 29 -> ScrobbleFailure.RETRYABLE
                else -> ScrobbleFailure.REJECTED
            }
            throw ScrobbleException(failure, when (code) {
                14 -> "Authorize Nevumi in Last.fm, then complete sign-in."
                15 -> "Last.fm authorization expired. Start signing in again."
                9 -> "Reconnect your Last.fm account."
                10, 26 -> "Last.fm rejected the application API key."
                else -> "Last.fm request failed: code $code"
            })
        }
        result
    }
}
