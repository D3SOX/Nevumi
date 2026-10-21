package me.d3sox.nevumi.soundcloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun aMalformedResponseIsReportedAsAnScGateError() = runBlocking {
        val http = HttpClient(MockEngine { respond("""{"results":["not an object"],"nextOffset":null}""", headers = json) })
        try {
            val error = assertFailsWith<ScGateException> { ScGateClient(http, "http://homelan:3000").search("night") }
            assertTrue("could not read" in error.message.orEmpty())
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
            // Without a choice sc-gate-dl uses its own browser mode and finishes the job unattended.
            assertFalse("browserMode" in body || "reviewTags" in body)
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
            assertEquals(ScGateTags("One", "X"), waiting.tags)
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
            assertEquals(ScGateCapabilities(search = false, libraryDestination = false, listOf(ScGateBrowser.HEADLESS)),
                ScGateClient(http, "http://old.test").capabilities())
            val failure = assertFailsWith<ScGateException> { ScGateClient(http, "http://down.test").capabilities() }
            assertEquals(null, failure.status)
            assertTrue(failure.message.orEmpty().contains("Could not reach sc-gate-dl"))
        } finally { http.close() }
    }

    @Test
    fun capabilitiesListTheBrowserModesAndTheBrowserViewer() = runBlocking {
        val http = HttpClient(MockEngine {
            respond("""{"browserModes":["headless","xvfb","headed","future"],"browserViewUrl":"http://{host}:6080/vnc.html?autoconnect=true",
                "search":true,"libraryDestination":true}""", headers = json)
        })
        try {
            assertEquals(ScGateCapabilities(search = true, libraryDestination = true, ScGateBrowser.entries, "http://{host}:6080/vnc.html?autoconnect=true"),
                ScGateClient(http, "http://homelan:3000").capabilities())
        } finally { http.close() }
    }

    @Test
    fun aJobCanRunInAVisibleBrowserAndWaitForItsTags() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            assertEquals("headed", body.getValue("browserMode").jsonPrimitive.content)
            assertTrue(body.getValue("reviewTags").jsonPrimitive.boolean)
            respond("""{"jobId":"job-1","started":true,"needsHypedditUrl":false}""", headers = json)
        })
        try {
            val created = ScGateClient(http, "http://homelan:3000").createJob("https://soundcloud.com/a/night", ScGateFormat.MP3_320,
                ScGateDestination.LIBRARY, ScGateBrowser.HEADED, reviewTags = true)
            assertTrue(created.started)
        } finally { http.close() }
    }

    @Test
    fun downloadProgressIsRoundedAndAWaitingJobOffersItsTags() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/job/downloading" -> respond("""{"progress":{"stage":"downloading","message":"Downloading","percent":42.6,
                    "downloadBytes":426,"totalBytes":1000,"browserActive":true},"browserMode":"headed"}""", headers = json)
                else -> respond("""{"progress":{"stage":"waiting_metadata","message":"Check the tags","percent":100},
                    "defaultMetadata":{"title":"Night [FREE DL]","artist":"A","album":"","genre":"House"},
                    "existingMetadata":{"title":"Night","artist":"Artist A","album":"Night EP"},"hasArtwork":true,"hasExistingArtwork":true,
                    "sourceIsLossless":false,"downloadFilename":"Night [123].m4a","outputFormat":"flac","browserMode":"headless"}""", headers = json)
            }
        })
        try {
            val client = ScGateClient(http, "http://homelan:3000")
            val downloading = client.job("downloading")
            assertEquals(43, downloading.percent)
            assertTrue(downloading.browserActive)
            assertEquals(ScGateBrowser.HEADED, downloading.browserMode)
            val waiting = client.job("waiting")
            assertEquals(ScGateTags("Night [FREE DL]", "A", "", "House"), waiting.tags)
            assertEquals(ScGateTags("Night", "Artist A", "Night EP"), waiting.fileTags)
            assertTrue(waiting.hasCover && waiting.hasFileCover && waiting.lossySource)
            assertEquals("Night [123].m4a", waiting.downloadFilename)
        } finally { http.close() }
    }

    @Test
    fun checkedTagsAreSentAsJsonOrWithAChosenCoverAsAForm() = runBlocking {
        val bodies = mutableListOf<Pair<ContentType?, String>>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/job/job-1/metadata", request.url.encodedPath)
            bodies += request.body.contentType to request.body.toByteArray().decodeToString()
            respond("""{"success":true}""", headers = json)
        })
        try {
            val client = ScGateClient(http, "http://homelan:3000")
            val tags = ScGateTags("Night", "Artist A", "Night EP", "House")
            client.finishTags("job-1", ScGateTagChoice(tags, keepFileTags = true, artistTitleName = false, cover = byteArrayOf(1)))
            client.finishTags("job-1", ScGateTagChoice(tags, cover = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
                13, 10, 26, 10, 0)))
            val (jsonType, jsonBody) = bodies[0]
            assertTrue(jsonType?.match(ContentType.Application.Json) == true)
            val sent = Json.parseToJsonElement(jsonBody).jsonObject
            assertEquals("Night EP", sent.getValue("album").jsonPrimitive.content)
            assertTrue(sent.getValue("preserveMetadata").jsonPrimitive.boolean)
            assertFalse(sent.getValue("nameAsArtistTitle").jsonPrimitive.boolean)
            val (formType, form) = bodies[1]
            assertTrue(formType?.match(ContentType.MultiPart.FormData) == true)
            assertTrue("name=\"title\"" in form && "Artist A" in form && "filename=\"cover.png\"" in form && "image/png" in form, form)
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
