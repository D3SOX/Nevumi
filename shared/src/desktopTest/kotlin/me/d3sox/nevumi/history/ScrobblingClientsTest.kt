package me.d3sox.nevumi.history

import me.d3sox.nevumi.library.TrackMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.*

class ScrobblingClientsTest {
    private val listen = Listen("listen", "canonical", "play", 1_700_000_123_456,
        TrackMetadata("Song & night", "Artíst", "Album", durationMs = 180_000, trackNumber = 2,
            albumArtist = "Various", folder = "/private/music", recordingId = "189002e7-3285-4e2e-92a3-7f6c30d407a2"),
        listenedMs = 100_000, playedMs = 100_000, durationMs = 180_000, ended = true)

    @Test
    fun missingLastFmConfirmationsStayUncertainAndExplicitRejectionsAndRateLimitsRemainDistinct() = runBlocking {
        var payload = "{}"
        var status = HttpStatusCode.OK
        val http = HttpClient(MockEngine { respond(payload, status, jsonHeaders) })
        try {
            val client = LastFmClient(http, "fixture-key", "fixture-secret", "fixture-session")
            for (request in listOf<suspend () -> Unit>({ client.requestAuthorization(); Unit },
                { client.finishAuthorization("request-token"); Unit }, { client.submit(listen) }, { client.nowPlaying(listen) })) {
                val failure = assertFailsWith<ScrobbleException> { request() }
                assertEquals(ScrobbleFailure.UNCERTAIN, failure.failure)
                assertNull(failure.cause)
            }
            payload = """{"token":""}"""
            assertEquals(ScrobbleFailure.UNCERTAIN, assertFailsWith<ScrobbleException> { client.requestAuthorization() }.failure)
            payload = """{"session":{"name":"fixture","key":""}}"""
            assertEquals(ScrobbleFailure.UNCERTAIN, assertFailsWith<ScrobbleException> { client.finishAuthorization("token") }.failure)
            payload = """{"scrobbles":{"scrobble":{"ignoredMessage":{}}}}"""
            assertEquals(ScrobbleFailure.UNCERTAIN, assertFailsWith<ScrobbleException> { client.submit(listen) }.failure)
            payload = """{"scrobbles":{"scrobble":{"ignoredMessage":{"code":"5"}}}}"""
            assertEquals(ScrobbleFailure.REJECTED, assertFailsWith<ScrobbleException> { client.submit(listen) }.failure)
            payload = """{"error":29,"message":"private-secret"}"""
            val limited = assertFailsWith<ScrobbleException> { client.submit(listen) }
            assertEquals(ScrobbleFailure.RETRYABLE, limited.failure)
            assertFalse(limited.stackTraceToString().contains("private-secret"))
            status = HttpStatusCode.TooManyRequests
            assertEquals(ScrobbleFailure.RETRYABLE, assertFailsWith<ScrobbleException> { ListenBrainzClient(http, "fixture-token").submit(listen) }.failure)
        } finally { http.close() }
    }

    @Test
    fun invalidOptionalRecordingIdentifiersAreOmittedWithoutDroppingTheListen() = runBlocking {
        var metadata: JsonObject? = null
        var form: Parameters? = null
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("submit-listens")) {
                metadata = Json.parseToJsonElement(body(request.body)).jsonObject.getValue("payload").jsonArray.single()
                    .jsonObject.getValue("track_metadata").jsonObject
                respond("""{"status":"ok"}""", headers = jsonHeaders)
            } else {
                form = parseQueryString(body(request.body))
                respond("""{"scrobbles":{"scrobble":{"ignoredMessage":{"code":"0"}}}}""", headers = jsonHeaders)
            }
        })
        try {
            val invalid = listen.copy(metadata = listen.metadata.copy(recordingId = "not-an-mbid"))
            ListenBrainzClient(http, "fixture-token").submit(invalid)
            LastFmClient(http, "fixture-key", "fixture-secret", "fixture-session").submit(invalid)
            assertFalse(requireNotNull(metadata).getValue("additional_info").jsonObject.containsKey("recording_mbid"))
            assertEquals(listen.metadata.title, requireNotNull(metadata).getValue("track_name").jsonPrimitive.content)
            assertNull(requireNotNull(form)["mbid"])
            assertEquals(listen.metadata.title, requireNotNull(form)["track"])
        } finally { http.close() }
    }

    @Test
    fun listenBrainzAuthenticatesInTheHeaderAndReceivesMetadataWithoutPrivatePaths() = runBlocking {
        val submissions = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine { request ->
            assertEquals("Token fixture-token", request.headers[HttpHeaders.Authorization])
            assertTrue(request.url.parameters.isEmpty())
            when (request.url.encodedPath) {
                "/1/validate-token" -> respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
                "/1/submit-listens" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    submissions += Json.parseToJsonElement(body(request.body)).jsonObject
                    respond("""{"status":"ok"}""", headers = jsonHeaders)
                }
                else -> error("Unexpected endpoint")
            }
        })
        try {
            val client = ListenBrainzClient(http, "fixture-token", "https://listens.example.test")
            assertEquals("fixture", client.validateToken())
            client.submit(listen)
            client.nowPlaying(listen)
            assertEquals(listOf("single", "playing_now"), submissions.map { it.getValue("listen_type").jsonPrimitive.content })
            val saved = submissions.first().getValue("payload").jsonArray.single().jsonObject
            assertEquals(1_700_000_123L, saved.getValue("listened_at").jsonPrimitive.long)
            val metadata = saved.getValue("track_metadata").jsonObject
            assertEquals("Artíst", metadata.getValue("artist_name").jsonPrimitive.content)
            assertEquals("Song & night", metadata.getValue("track_name").jsonPrimitive.content)
            assertEquals("Album", metadata.getValue("release_name").jsonPrimitive.content)
            assertEquals(180_000, metadata.getValue("additional_info").jsonObject.getValue("duration_ms").jsonPrimitive.int)
            assertEquals(listen.metadata.recordingId, metadata.getValue("additional_info").jsonObject.getValue("recording_mbid").jsonPrimitive.content)
            assertFalse(submissions.last().getValue("payload").jsonArray.single().jsonObject.containsKey("listened_at"))
            assertFalse(submissions.toString().contains("/private"))
            assertFalse(submissions.toString().contains("canonical"))
        } finally { http.close() }
    }

    @Test
    fun lastFmAuthorizationAndSubmissionsUseSignedUtf8FormsAndKeepSecretsOutOfUrls() = runBlocking {
        val forms = mutableListOf<Parameters>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertTrue(request.url.parameters.isEmpty())
            val form = parseQueryString(body(request.body))
            val signed = form.entries().filter { it.key !in setOf("api_sig", "format", "callback") }
                .sortedBy { it.key }.joinToString("") { it.key + it.value.single() } + "fixture-secret"
            val expected = MessageDigest.getInstance("MD5").digest(signed.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, form["api_sig"])
            forms += form
            respond(when (form["method"]) {
                "auth.getToken" -> """{"token":"request-token"}"""
                "auth.getSession" -> """{"session":{"name":"fixture","key":"fixture-session"}}"""
                "track.scrobble" -> """{"scrobbles":{"@attr":{"accepted":"1","ignored":"0"},"scrobble":{"ignoredMessage":{"code":"0","#text":""}}}}"""
                "track.updateNowPlaying" -> """{"nowplaying":{"ignoredMessage":{"code":"0","#text":""}}}"""
                else -> error("Unexpected method")
            }, headers = jsonHeaders)
        })
        try {
            val auth = LastFmClient(http, "fixture-key", "fixture-secret")
            val authorization = auth.requestAuthorization()
            val address = Url(authorization.url)
            assertEquals("www.last.fm", address.host)
            assertEquals("fixture-key", address.parameters["api_key"])
            assertEquals("request-token", address.parameters["token"])
            assertFalse(authorization.toString().contains("request-token"))
            val session = auth.finishAuthorization(authorization.token)
            assertEquals("fixture", session.username)
            assertFalse(session.toString().contains("fixture-session"))
            val client = LastFmClient(http, "fixture-key", "fixture-secret", session.key)
            client.submit(listen)
            client.nowPlaying(listen)
            val scrobble = forms.single { it["method"] == "track.scrobble" }
            assertEquals("Artíst", scrobble["artist"])
            assertEquals("Song & night", scrobble["track"])
            assertEquals("1700000123", scrobble["timestamp"])
            assertEquals("180", scrobble["duration"])
            assertEquals("2", scrobble["trackNumber"])
            assertEquals("fixture-session", scrobble["sk"])
            assertFalse(forms.toString().contains("/private"))
            assertFalse(forms.last().contains("timestamp"))
        } finally { http.close() }
    }

    @Test
    fun authenticationFailuresAndAmbiguousTransportFailuresAreDistinctAndRedacted() = runBlocking {
        val denied = HttpClient(MockEngine { respond("""{"error":9,"message":"Invalid session key"}""", headers = jsonHeaders) })
        val failed = HttpClient(MockEngine { throw IOException("Failure with secret=fixture-secret and token=fixture-token") })
        try {
            assertEquals(ScrobbleFailure.AUTHORIZATION, assertFailsWith<ScrobbleException> {
                LastFmClient(denied, "fixture-key", "fixture-secret", "invalid").submit(listen)
            }.failure)
            val error = assertFailsWith<ScrobbleException> { ListenBrainzClient(failed, "fixture-token").submit(listen) }
            assertEquals(ScrobbleFailure.UNCERTAIN, error.failure)
            assertFalse(error.toString().contains("fixture-secret"))
            assertFalse(error.toString().contains("fixture-token"))
            assertNull(error.cause)
        } finally { denied.close(); failed.close() }
    }

    @Test
    fun lastFmDoesNotSubmitShortSongsOrSongsThatHaveNotBeenHeardEnough() = runBlocking {
        var requests = 0
        val http = HttpClient(MockEngine { requests++; respond("{}", headers = jsonHeaders) })
        try {
            val client = LastFmClient(http, "fixture-key", "fixture-secret", "fixture-session")
            assertFailsWith<IllegalArgumentException> { client.submit(listen.copy(durationMs = 30_000)) }
            assertFailsWith<IllegalArgumentException> { client.submit(listen.copy(playedMs = 1_000)) }
            assertEquals(0, requests)
        } finally { http.close() }
    }

    private fun body(content: OutgoingContent) = (content as OutgoingContent.ByteArrayContent).bytes().decodeToString()
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun requestsThatNeverReachTheServiceAreRetriedInsteadOfLeftUncertain() = runBlocking {
        val unreachable = HttpClient(MockEngine { throw java.net.ConnectException("Connection refused") })
        val refused = assertFailsWith<ScrobbleException> { ListenBrainzClient(unreachable, "token", "https://listenbrainz.example").validateToken() }
        assertEquals(ScrobbleFailure.RETRYABLE, refused.failure)
        val unknownHost = HttpClient(MockEngine { throw java.net.UnknownHostException("listenbrainz.example") })
        assertEquals(ScrobbleFailure.RETRYABLE,
            assertFailsWith<ScrobbleException> { ListenBrainzClient(unknownHost, "token", "https://listenbrainz.example").validateToken() }.failure)
        val timedOut = HttpClient(MockEngine { throw java.net.SocketTimeoutException("Read timed out") })
        assertEquals(ScrobbleFailure.UNCERTAIN,
            assertFailsWith<ScrobbleException> { ListenBrainzClient(timedOut, "token", "https://listenbrainz.example").validateToken() }.failure)
        unreachable.close(); unknownHost.close(); timedOut.close()
    }
}
