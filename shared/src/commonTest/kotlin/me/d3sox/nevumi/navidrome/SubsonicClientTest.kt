package me.d3sox.nevumi.navidrome

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SubsonicClientTest {
    private fun json(body: String) = """{"subsonic-response":{"status":"ok",$body}}"""

    @Test fun genericServersNeverReceiveNavidromeNativeLoginRequests() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/rest/ping.view", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            respond(json(""""type":"gonic""""), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertFailsWith<NavidromeRequestException> {
                NavidromeClient(http, "https://server.test", ServerCredentials("user", "secret")).musicFiles()
            }
            Unit
        } finally { http.close() }
    }

    @Test fun albumsArePagedWhenEmptySearchIsRejected() = runBlocking {
        val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            calls += endpoint
            val body = when (endpoint) {
                "search3" -> """{"subsonic-response":{"status":"failed","error":{"code":10}}}"""
                "getAlbumList2" -> json(""""albumList2":{"album":${when (request.url.parameters["offset"]) {
                    "0" -> """[{"id":"album-a"}]"""
                    "1" -> """[{"id":"album-b"}]"""
                    else -> "[]"
                }}}""")
                "getAlbum" -> json(""""album":{"song":[{"id":"${request.url.parameters["id"]}","title":"Classic","artist":"Artist","genre":"Rock","duration":123,"path":"Artist/Album/Classic.flac"}]}""")
                else -> error("Unexpected endpoint $endpoint")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val songs = NavidromeClient(http, "https://server.test/music", ServerCredentials("user", "secret")).songs()
            assertEquals(listOf("album-a", "album-b"), songs.map { it.id })
            assertEquals(123_000L, songs.first().metadata.durationMs)
            assertEquals(listOf("Rock"), songs.first().metadata.genres)
            assertEquals("Artist/Album", songs.first().metadata.folder)
            assertFalse(calls.any { it == "login" || it == "song" })
        } finally { http.close() }
    }

    @Test fun anErrorAfterTheFirstPageFailsTheRefreshInsteadOfEndingTheCatalogue() = runBlocking {
        for (pagedEndpoint in listOf("search3", "getAlbumList2")) {
            val http = HttpClient(MockEngine { request ->
                val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
                val failed = """{"subsonic-response":{"status":"failed","error":{"code":0}}}"""
                val offset = request.url.parameters["songOffset"] ?: request.url.parameters["offset"]
                val body = when {
                    endpoint == pagedEndpoint && offset != "0" -> failed
                    endpoint == "search3" && pagedEndpoint == "getAlbumList2" -> """{"subsonic-response":{"status":"failed","error":{"code":10}}}"""
                    endpoint == "search3" -> json(""""searchResult3":{"song":[{"id":"first","title":"First"}]}""")
                    endpoint == "getAlbumList2" -> json(""""albumList2":{"album":[{"id":"album-a"}]}""")
                    endpoint == "getAlbum" -> json(""""album":{"song":[{"id":"first","title":"First"}]}""")
                    else -> error("Unexpected endpoint $endpoint")
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try {
                assertFailsWith<NavidromeException>(pagedEndpoint) {
                    NavidromeClient(http, "https://server.test", ServerCredentials("user", "secret")).songs()
                }
                Unit
            } finally { http.close() }
        }
    }

    @Test fun directoryOnlyServersIncludeLooseFilesAndDoNotRevisitDirectories() = runBlocking {
        val visited = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val endpoint = request.url.encodedPath.substringAfterLast('/').removeSuffix(".view")
            val body = when (endpoint) {
                "search3" -> json(""""searchResult3":{}""")
                "getAlbumList2" -> json(""""albumList2":{}""")
                "getIndexes" -> json(""""indexes":{"index":[{"artist":[{"id":"root"}]}],"shortcut":[{"id":"root"}],"child":[{"id":"loose","title":"Loose","isDir":false}]}""")
                "getMusicDirectory" -> {
                    visited += requireNotNull(request.url.parameters["id"])
                    json(""""directory":{"child":[{"id":"album","isDir":true},{"id":"root","isDir":true},{"id":"track","title":"Music","isDir":false},{"id":"video","title":"Film","isVideo":true}]}""")
                }
                else -> error(endpoint)
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val songs = NavidromeClient(http, "https://server.test", ServerCredentials("user", "secret")).songs()
            assertEquals(setOf("loose", "track"), songs.map { it.id }.toSet())
            assertEquals(listOf("root", "album"), visited)
        } finally { http.close() }
    }

    @Test fun rejectedCredentialsNeverTriggerAnotherCatalogueStrategy() = runBlocking {
        var calls = 0
        val http = HttpClient(MockEngine {
            calls++
            respond("""{"subsonic-response":{"status":"failed","error":{"code":40}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertEquals(40, assertFailsWith<NavidromeException> {
                NavidromeClient(http, "https://server.test", ServerCredentials("user", "secret")).songs()
            }.code)
            assertEquals(1, calls)
        } finally { http.close() }
    }

    @Test fun olderServersCanCreateAndOverwritePlaylistsWithEmptyResponses() = runBlocking {
        var created = false
        var name = "Old"
        val http = HttpClient(MockEngine { request ->
            assertEquals("1.13.0", request.url.parameters["v"])
            assertNull(request.url.parameters["p"])
            val body = when (request.url.encodedPath.substringAfterLast('/')) {
                "getPlaylists.view" -> json(""""playlists":{"playlist":${if (created) """[{"id":"new","name":"New","owner":"user"}]""" else "[]"}}""")
                "createPlaylist.view" -> { created = true; """{"subsonic-response":{"status":"ok"}}""" }
                "updatePlaylist.view" -> { name = request.url.parameters["name"]!!; """{"subsonic-response":{"status":"ok"}}""" }
                "getPlaylist.view" -> json(""""playlist":{"id":"new","name":"$name","owner":"user","entry":[{"id":"a"},{"id":"a"}]}""")
                else -> error(request.url.encodedPath)
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val client = NavidromeClient(http, "https://server.test", ServerCredentials("user", "secret"))
            assertEquals("new", client.savePlaylist("New", listOf("a", "a")).id)
            assertEquals("Renamed", client.savePlaylist("Renamed", listOf("a", "a"), "new").name)
        } finally { http.close() }
    }
}
