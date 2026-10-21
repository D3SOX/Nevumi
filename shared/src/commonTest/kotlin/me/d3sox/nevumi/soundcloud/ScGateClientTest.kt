package me.d3sox.nevumi.soundcloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScGateClientTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun searchSendsTheQueryThroughTheRequestAddressAndReadsEachResult() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("relay.test", request.url.host)
            assertEquals("/prefix/api/search", request.url.encodedPath)
            assertEquals("night drive", request.url.parameters["q"])
            assertEquals("20", request.url.parameters["offset"])
            respond("""{"results":[
                {"id":7,"url":"https://soundcloud.com/a/night","title":"Night Drive","artist":"Artist A","uploader":"a","artworkUrl":null,
                 "durationMs":201000,"genre":"House","source":"gate","extra":true},
                {"id":8,"url":"https://soundcloud.com/b/night","title":"Night","artist":"B","uploader":"b","artworkUrl":"https://i1.sndcdn.com/x.jpg",
                 "durationMs":0,"genre":"","source":"download"},
                {"id":9,"url":"https://soundcloud.com/c/night","title":"Night (Edit)","artist":"C","uploader":"c","durationMs":5,"genre":null,"source":"none"}
            ],"nextOffset":40}""", headers = json)
        })
        try {
            val page = ScGateClient(http, "http://homelan:3000/", "http://relay.test/prefix").search(" night drive ", offset = 20)
            assertEquals(40, page.nextOffset)
            assertEquals(listOf(SoundCloudSource.GATE, SoundCloudSource.DOWNLOAD, SoundCloudSource.NONE), page.results.map { it.source })
            assertEquals(SoundCloudTrack(7, "https://soundcloud.com/a/night", "Night Drive", "Artist A", "a", null, 201_000, "House", SoundCloudSource.GATE),
                page.results.first())
            assertEquals(null, page.results[1].genre)
        } finally { http.close() }
    }

    @Test
    fun artworkComesThroughTheServer() = runBlocking {
        val cover = byteArrayOf(1, 2, 3)
        val http = HttpClient(MockEngine { request ->
            assertEquals("relay.test", request.url.host)
            assertEquals("/api/artwork", request.url.encodedPath)
            assertEquals("https://i1.sndcdn.com/a-t300x300.jpg", request.url.parameters["url"])
            respond(cover, headers = headersOf(HttpHeaders.ContentType, "image/jpeg"))
        })
        try {
            assertContentEquals(cover, ScGateClient(http, "http://homelan:3000", "http://relay.test").artwork("https://i1.sndcdn.com/a-t300x300.jpg"))
        } finally { http.close() }
    }

    @Test
    fun creatingAJobSendsTheFormatAndDestinationAndReportsAMissingGate() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/job", request.url.encodedPath)
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertEquals("https://soundcloud.com/a/night", body.getValue("soundcloudUrl").jsonPrimitive.content)
            assertEquals("flac", body.getValue("outputFormat").jsonPrimitive.content)
            assertEquals("library", body.getValue("destination").jsonPrimitive.content)
            respond("""{"jobId":"job-1","track":null,"hypedditUrl":null,"needsHypedditUrl":true,"started":false,
                "soundcloudDownloadWarning":null,"destination":"library"}""", headers = json)
        })
        try {
            val created = ScGateClient(http, "http://homelan:3000").createJob("https://soundcloud.com/a/night", ScGateFormat.FLAC, ScGateDestination.LIBRARY)
            assertEquals(ScGateJobStart("job-1", started = false, needsGateLink = true, warning = null), created)
        } finally { http.close() }
    }

    @Test
    fun jobStatusIncludesBandcampChoicesAndTheSavedFileName() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/job/waiting" -> respond("""{"id":"waiting","progress":{"stage":"waiting_bandcamp_track","message":"Pick","percent":40,
                    "bandcampAlbumTracks":[{"title":"One","url":"https://x.bandcamp.com/track/one","score":0.2}]},
                    "defaultMetadata":{"title":"One","artist":"X"},"libraryFilename":null,"error":null}""", headers = json)
                "/api/job/ready" -> respond("""{"id":"ready","progress":{"stage":"ready","message":"Saved","percent":100,"requiresAttention":false},
                    "libraryFilename":"X - One.mp3","outputFilename":null,"error":null}""", headers = json)
                else -> respond("""{"error":"Job not found"}""", HttpStatusCode.NotFound, json)
            }
        })
        try {
            val client = ScGateClient(http, "http://homelan:3000")
            val waiting = client.job("waiting")
            assertEquals("waiting_bandcamp_track", waiting.stage)
            assertEquals(listOf(BandcampChoice("One", "https://x.bandcamp.com/track/one")), waiting.bandcampTracks)
            assertEquals("X", waiting.artist)
            assertEquals("X - One.mp3", client.job("ready").libraryFilename)
            val missing = assertFailsWith<ScGateException> { client.job("forgotten") }
            assertEquals(404, missing.status)
            assertEquals("Job not found", missing.message)
        } finally { http.close() }
    }

    @Test
    fun anOlderServerWithoutSearchIsReportedAndUnreachableServersExplainTheProblem() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            if (request.url.host == "down.test") error("Connection refused")
            respond("""{"browserModes":["headless"],"browserViewUrl":null}""", headers = json)
        })
        try {
            assertEquals(ScGateCapabilities(search = false, libraryDestination = false), ScGateClient(http, "http://old.test").capabilities())
            val failure = assertFailsWith<ScGateException> { ScGateClient(http, "http://down.test").capabilities() }
            assertEquals(null, failure.status)
            assertTrue(failure.message.orEmpty().contains("Could not reach sc-gate-dl"))
        } finally { http.close() }
    }

    @Test
    fun downloadsStreamTheFinishedFile() = runBlocking {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/job/job-1/file", request.url.encodedPath)
            respond(bytes, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
        })
        try {
            val received = okio.Buffer()
            assertEquals(bytes.size.toLong(), ScGateClient(http, "http://homelan:3000").download("job-1") { received.write(it) })
            assertContentEquals(bytes, received.readByteArray())
        } finally { http.close() }
    }
}
