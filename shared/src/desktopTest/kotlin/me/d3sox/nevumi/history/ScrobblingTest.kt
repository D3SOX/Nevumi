package me.d3sox.nevumi.history

import me.d3sox.nevumi.downloads.DownloadNetwork
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.security.SecretServiceVault
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*
import kotlin.uuid.Uuid
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerCredentials
import kotlinx.serialization.json.*

class ScrobblingTest {
    @Test
    fun nowPlayingIsReportedOncePerStartOrResumeAndNeverReplaysAStaleOfflineTrack() = runBlocking {
        val directory = Files.createTempDirectory("now-playing-scrobbling")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val network = MutableStateFlow(DownloadNetwork(connected = true))
        var profileId: String? = null
        var worker: Scrobbling? = null
        val reports = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else {
                reports += Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                respond("""{"status":"ok"}""", headers = jsonHeaders)
            }
        })
        try {
            val library = MusicLibrary(store)
            val song = library.importTrack(TrackMetadata("Song", "Artist", durationMs = 180_000),
                TrackSource("local", SourceKind.LOCAL, "/private/song.flac"))
            val service = Scrobbling(store, library, http, vault, network = network, now = { 1_000 }); worker = service
            profileId = service.connectListenBrainz("fixture-token").id
            service.start()
            val playing = PlaybackStatus(song.id, playing = true, durationMs = 180_000, playbackId = "one")
            service.onPlayback(playing)
            withTimeout(3_000) { while (reports.size != 1) delay(10) }
            repeat(10) { service.onPlayback(playing.copy(positionMs = it * 100L)) }
            service.onPlayback(playing.copy(playing = false, playWhenReady = true)) // Buffering.
            service.onPlayback(playing)
            delay(100)
            assertEquals(1, reports.size)
            service.onPlayback(playing.copy(playing = false, playWhenReady = false))
            delay(100)
            service.onPlayback(playing)
            withTimeout(3_000) { while (reports.size != 2) delay(10) }
            network.value = DownloadNetwork(connected = false)
            service.onPlayback(playing.copy(playbackId = "offline-play"))
            service.onPlayback(playing.copy(playbackId = "latest-play"))
            delay(100)
            assertEquals(2, reports.size)
            network.value = DownloadNetwork(connected = true)
            withTimeout(3_000) { while (reports.size != 3) delay(10) }
            network.value = DownloadNetwork(connected = false)
            delay(100)
            network.value = DownloadNetwork(connected = true)
            withTimeout(3_000) { while (reports.size != 4) delay(10) }
            assertTrue(reports.all { it["listen_type"]?.jsonPrimitive?.content == "playing_now" })
            assertTrue(reports.all { !it.getValue("payload").jsonArray.single().jsonObject.containsKey("listened_at") })
            assertFalse(reports.toString().contains("/private"))
            assertEquals(emptyList(), service.submissions())
        } finally {
            worker?.closeAndJoin(); profileId?.let { vault.delete("scrobbling:$it") }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun disconnectingRemovesCredentialsAndPendingMetadataWhileKeepingLocalHistoryAndDeduplicationReceipts() = runBlocking {
        val directory = Files.createTempDirectory("disconnected-scrobbling")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var profileId: String? = null
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else { requests++; respond("""{"status":"ok"}""", headers = jsonHeaders) }
        })
        try {
            val library = MusicLibrary(store)
            val service = Scrobbling(store, library, http, vault, now = { 1_000 })
            val profile = service.connectListenBrainz("fixture-token"); profileId = profile.id
            recordQualifiedListen(store, library)
            service.enqueueEligible()
            service.disconnect(profile.id)
            assertEquals(emptyList(), service.profiles())
            assertNull(vault.read("scrobbling:${profile.id}"))
            assertEquals(ScrobbleState.DISCARDED, service.submissions().single().state)
            assertNull(service.submissions().single().listen)
            assertEquals(1, ListeningHistory(store, library).all().size)
            service.connectListenBrainz("fixture-token")
            service.enqueueEligible(); service.runPending()
            assertEquals(0, requests)
            service.closeAndJoin()
        } finally { profileId?.let { vault.delete("scrobbling:$it") }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun theBackgroundWorkerQueuesNewHistoryAndResumesWhenTheNetworkReturnsWithoutDoubleStarting() = runBlocking {
        val directory = Files.createTempDirectory("background-scrobbling")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val network = MutableStateFlow(DownloadNetwork(connected = false))
        var profileId: String? = null
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else { requests++; respond("""{"status":"ok"}""", headers = jsonHeaders) }
        })
        var scrobbling: Scrobbling? = null
        try {
            val library = MusicLibrary(store)
            val worker = Scrobbling(store, library, http, vault, network = network, now = { 1_000 }); scrobbling = worker
            profileId = worker.connectListenBrainz("fixture-token").id
            worker.start(); worker.start()
            recordQualifiedListen(store, library, "one")
            withTimeout(3_000) { while (worker.submissions().isEmpty()) delay(10) }
            assertEquals(0, requests)
            network.value = DownloadNetwork(connected = true)
            withTimeout(3_000) { while (worker.submissions().single().state != ScrobbleState.CONFIRMED) delay(10) }
            assertEquals(1, requests)
            worker.closeAndJoin()
            recordQualifiedListen(store, library, "after-shutdown")
            delay(100)
            assertEquals(1, requests)
            assertEquals(1, worker.submissions().size)
        } finally {
            scrobbling?.closeAndJoin(); profileId?.let { vault.delete("scrobbling:$it") }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnavailableNavidromeConnectionDoesNotHoldBackOtherServices() = runBlocking {
        val directory = Files.createTempDirectory("scrobbling-without-navidrome")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var profileId: String? = null
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token")) respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else respond("""{"status":"ok"}""", headers = jsonHeaders)
        })
        var scrobbling: Scrobbling? = null
        try {
            val library = MusicLibrary(store)
            val worker = Scrobbling(store, library, http, vault, network = MutableStateFlow(DownloadNetwork(connected = true)),
                navidrome = { throw IOException("Sign in to Tailscale in Tunnel settings") }, now = { 1_000 }); scrobbling = worker
            profileId = worker.connectListenBrainz("fixture-token").id
            worker.start()
            recordQualifiedListen(store, library, "one")
            withTimeout(3_000) { while (worker.submissions().singleOrNull()?.state != ScrobbleState.CONFIRMED) delay(10) }
            assertNull(worker.error.value)
        } finally {
            scrobbling?.closeAndJoin(); profileId?.let { vault.delete("scrobbling:$it") }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun disablingAServiceStopsDeliveryAndDiscardingPendingHistoryLeavesOnlyReceiptsThatPreventDuplicates() = runBlocking {
        val directory = Files.createTempDirectory("scrobbling-controls")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var profileId: String? = null
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else { requests++; respond("""{"status":"ok"}""", headers = jsonHeaders) }
        })
        try {
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, now = { 1_000 })
            val profile = scrobbling.connectListenBrainz("fixture-token"); profileId = profile.id
            recordQualifiedListen(store, library, "one")
            scrobbling.enqueueEligible()
            scrobbling.setEnabled(profile.id, false)
            scrobbling.runPending()
            assertEquals(0, requests)
            assertEquals(ScrobbleState.PENDING, scrobbling.submissions().single().state)
            scrobbling.discardSubmission(scrobbling.submissions().single().id)
            scrobbling.setEnabled(profile.id, true)
            scrobbling.enqueueEligible(); scrobbling.runPending()
            assertEquals(0, requests)
            recordQualifiedListen(store, library, "two")
            scrobbling.enqueueEligible()
            assertEquals(1, scrobbling.submissions().count { it.state == ScrobbleState.PENDING })
            scrobbling.discardPending()
            assertTrue(scrobbling.submissions().all { it.state == ScrobbleState.DISCARDED && it.listen == null && it.songId == null })
            scrobbling.enqueueEligible(); scrobbling.runPending()
            assertEquals(0, requests)
            recordQualifiedListen(store, library, "three")
            scrobbling.enqueueEligible(); scrobbling.runPending()
            assertEquals(1, requests)
            scrobbling.closeAndJoin()
        } finally { profileId?.let { vault.delete("scrobbling:$it") }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLocalListenUsesItsLinkedNavidromeCopyAndNeverSendsAnOldAccountsSongToAnotherServer() = runBlocking {
        val directory = Files.createTempDirectory("mixed-source-scrobbling")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val received = mutableListOf<Pair<String, String>>()
        var now = 1_000L
        val http = HttpClient(MockEngine { request ->
            assertEquals("/rest/scrobble.view", request.url.encodedPath)
            assertEquals("true", request.url.parameters["submission"])
            received += request.url.host to requireNotNull(request.url.parameters["id"])
            respond("""{"subsonic-response":{"status":"ok"}}""", headers = jsonHeaders)
        })
        try {
            val a = NavidromeClient(http, "https://server-a.example.test", ServerCredentials("fixture", "fixture-password"))
            val b = NavidromeClient(http, "https://server-b.example.test", ServerCredentials("fixture", "fixture-password"))
            var connected: NavidromeClient? = a
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, navidrome = { connected }, now = { now })
            val profileA = requireNotNull(scrobbling.configureNavidrome())
            recordQualifiedListen(store, library, "old-play", 2_000, artist = "")
            val local = library.tracks().single()
            library.importTrack(local.metadata, TrackSource("server:${a.serverId}:song-a", SourceKind.SERVER, "song-a"), linkTo = local.id)
            scrobbling.enqueueEligible()
            assertEquals("song-a", scrobbling.submissions().single().songId)
            connected = null
            scrobbling.runPending()
            assertEquals(emptyList(), received)
            connected = b
            scrobbling.runPending()
            assertEquals(emptyList(), received)
            now = 10_000
            scrobbling.configureNavidrome()
            library.importTrack(local.metadata, TrackSource("server:${b.serverId}:song-b", SourceKind.SERVER, "song-b"), linkTo = local.id)
            recordQualifiedListen(store, library, "new-play", 20_000)
            scrobbling.enqueueEligible(); scrobbling.runPending()
            assertEquals(listOf("server-b.example.test" to "song-b"), received)
            assertEquals(ScrobbleState.PENDING, scrobbling.submissions().single { it.profileId == profileA.id }.state)
            now = 30_000; connected = a
            scrobbling.configureNavidrome(); scrobbling.enqueueEligible(); scrobbling.runPending()
            assertEquals(listOf("server-b.example.test" to "song-b", "server-a.example.test" to "song-a"), received)
            scrobbling.closeAndJoin()
        } finally { http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun lastFmSignInSurvivesRestartAndOnlyEligibleNewListensAreQueuedWithoutPlaintextCredentials() = runBlocking {
        val directory = Files.createTempDirectory("lastfm-sign-in")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var profileId: String? = null
        val methods = mutableListOf<String>()
        var granted = false
        val http = HttpClient(MockEngine { request ->
            val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            val method = requireNotNull(form["method"]); methods += method
            respond(when (method) {
                "auth.getToken" -> """{"token":"fixture-authorization"}"""
                "auth.getSession" -> if (granted) """{"session":{"name":"fixture","key":"fixture-session"}}"""
                    else """{"error":14,"message":"Token has not been authorized"}"""
                "track.scrobble" -> """{"scrobbles":{"scrobble":{"ignoredMessage":{"code":"0"}}}}"""
                else -> error("Unexpected Last.fm method")
            }, headers = jsonHeaders)
        })
        try {
            val scrobbling = Scrobbling(store, MusicLibrary(store), http, vault, now = { 1_000 })
            val authorization = scrobbling.startLastFmAuthorization("fixture-api-key", "fixture-shared-secret")
            assertEquals("fixture-authorization", Url(authorization.url).parameters["token"])
            assertEquals(ScrobbleFailure.AUTHORIZATION, assertFailsWith<ScrobbleException> { scrobbling.completeLastFmAuthorization() }.failure)
            scrobbling.closeAndJoin(); store.close(); store = openMusicStore(database)
            val library = MusicLibrary(store)
            val restored = Scrobbling(store, library, http, vault, now = { 2_000 })
            assertEquals(authorization.url, restored.pendingLastFmAuthorization()?.url)
            granted = true
            val profile = restored.completeLastFmAuthorization(); profileId = profile.id
            assertEquals(ScrobblingService.LASTFM, profile.service)
            assertNull(restored.pendingLastFmAuthorization())
            recordQualifiedListen(store, library, "old-play", 1_000)
            recordQualifiedListen(store, library, "eligible", 3_000)
            val short = library.importTrack(TrackMetadata("Short song", "Artist", durationMs = 20_000),
                TrackSource("short", SourceKind.LOCAL, "/music/short.flac"))
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { 4_000 }, elapsedClock = { elapsed })
            val playing = PlaybackStatus(short.id, playing = true, durationMs = 20_000, playbackId = "short")
            history.record(playing); elapsed = 10_000; history.record(playing.copy(playing = false))
            restored.enqueueEligible()
            assertEquals(listOf("eligible"), restored.submissions().map { it.playbackId })
            restored.runPending()
            assertEquals(1, methods.count { it == "track.scrobble" })
            val plaintext = (store.database.records().list("scrobbling-profiles") + store.database.records().list("scrobble-submissions")).joinToString()
            for (secret in listOf("fixture-api-key", "fixture-shared-secret", "fixture-session", "fixture-authorization")) assertFalse(plaintext.contains(secret))
            restored.closeAndJoin()
        } finally {
            vault.delete("lastfm:authorization"); profileId?.let { vault.delete("scrobbling:$it") }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellationDuringASubmissionLeavesAnUncertainReceiptAfterRestartRatherThanResendingIt() = runBlocking {
        val directory = Files.createTempDirectory("interrupted-scrobbling")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var profileId: String? = null
        val accepted = CompletableDeferred<Unit>()
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else { requests++; accepted.complete(Unit); awaitCancellation() }
        })
        try {
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, now = { 1_000 })
            profileId = scrobbling.connectListenBrainz("fixture-token").id
            recordQualifiedListen(store, library)
            scrobbling.enqueueEligible()
            val sending = launch { scrobbling.runPending() }
            withTimeout(3_000) { accepted.await() }
            sending.cancelAndJoin()
            assertEquals(ScrobbleState.IN_FLIGHT, scrobbling.submissions().single().state)
            scrobbling.closeAndJoin(); store.close(); store = openMusicStore(database)
            val restored = Scrobbling(store, MusicLibrary(store), http, vault, now = { 200_000 })
            restored.runPending()
            assertEquals(ScrobbleState.UNCERTAIN, restored.submissions().single().state)
            restored.enqueueEligible(); restored.runPending()
            assertEquals(1, requests)
            restored.closeAndJoin()
        } finally { profileId?.let { vault.delete("scrobbling:$it") }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anExpiredTokenStopsFurtherSubmissionsUntilReconnectingAndThenResumesBothPendingListens() = runBlocking {
        val directory = Files.createTempDirectory("scrobbling-reauthentication")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var profileId: String? = null
        val received = mutableListOf<String>()
        var worker: Scrobbling? = null
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else {
                val token = requireNotNull(request.headers[HttpHeaders.Authorization])
                received += token
                if (token == "Token expired-token") respond("{}", status = HttpStatusCode.Unauthorized, headers = jsonHeaders)
                else respond("""{"status":"ok"}""", headers = jsonHeaders)
            }
        })
        try {
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, now = { 1_000 })
            worker = scrobbling
            profileId = scrobbling.connectListenBrainz("expired-token").id
            scrobbling.start()
            recordQualifiedListen(store, library, "one", 10_000)
            withTimeout(3_000) { while (scrobbling.submissions().singleOrNull()?.state != ScrobbleState.AUTHORIZATION) delay(10) }
            assertEquals(ScrobbleState.AUTHORIZATION, scrobbling.submissions().single().state)
            recordQualifiedListen(store, library, "two", 20_000)
            withTimeout(3_000) { while (scrobbling.submissions().size != 2) delay(10) }
            delay(100)
            assertEquals(listOf("Token expired-token"), received)
            scrobbling.connectListenBrainz("new-token")
            withTimeout(3_000) { while (scrobbling.submissions().any { it.state != ScrobbleState.CONFIRMED }) delay(10) }
            assertEquals(listOf("Token expired-token", "Token new-token", "Token new-token"), received)
            assertTrue(scrobbling.submissions().all { it.state == ScrobbleState.CONFIRMED })
            scrobbling.closeAndJoin()
        } finally { worker?.closeAndJoin(); profileId?.let { vault.delete("scrobbling:$it") }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun switchingAccountsLeavesOldPendingListensBoundToTheirOriginalAccountWithoutBackfillingTheNewOne() = runBlocking {
        val directory = Files.createTempDirectory("scrobbling-accounts")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val network = MutableStateFlow(DownloadNetwork(connected = true))
        val received = mutableListOf<String>()
        val ids = mutableListOf<String>()
        var now = 1_000L
        val http = HttpClient(MockEngine { request ->
            val token = requireNotNull(request.headers[HttpHeaders.Authorization])
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"${if (token == "Token token-a") "account-a" else "account-b"}"}""", headers = jsonHeaders)
            else { received += token; respond("""{"status":"ok"}""", headers = jsonHeaders) }
        })
        try {
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, network = network, now = { now })
            val a = scrobbling.connectListenBrainz("token-a"); ids += a.id
            recordQualifiedListen(store, library, "old-play", 2_000)
            network.value = DownloadNetwork(connected = false)
            scrobbling.enqueueEligible()
            now = 10_000
            val b = scrobbling.connectListenBrainz("token-b"); ids += b.id
            network.value = DownloadNetwork(connected = true)
            scrobbling.enqueueEligible(); scrobbling.runPending()
            assertEquals(emptyList(), received)
            assertEquals(false, scrobbling.profiles().single { it.id == a.id }.enabled)
            assertEquals(true, scrobbling.profiles().single { it.id == b.id }.enabled)
            assertEquals(a.id, scrobbling.submissions().single().profileId)
            recordQualifiedListen(store, library, "new-play", 20_000)
            scrobbling.enqueueEligible(); scrobbling.runPending()
            assertEquals(listOf("Token token-b"), received)
            now = 30_000
            scrobbling.connectListenBrainz("token-a")
            scrobbling.enqueueEligible(); scrobbling.runPending()
            assertEquals(listOf("Token token-b", "Token token-a"), received)
            assertEquals(2, scrobbling.submissions().size)
            scrobbling.closeAndJoin()
        } finally { ids.forEach { vault.delete("scrobbling:$it") }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLostConfirmationIsHeldForReviewAcrossRestartAndOnlyAnExplicitRetrySendsItAgain() = runBlocking {
        val directory = Files.createTempDirectory("uncertain-scrobbling")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var profileId: String? = null
        var submissions = 0
        var loseResponse = true
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
            else {
                submissions++
                if (loseResponse) throw IOException("The server accepted the listen but its response was lost; token=fixture-token")
                respond("""{"status":"ok"}""", headers = jsonHeaders)
            }
        })
        try {
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, now = { 1_000 })
            profileId = scrobbling.connectListenBrainz("fixture-token").id
            recordQualifiedListen(store, library)
            scrobbling.enqueueEligible(); scrobbling.runPending()
            val uncertain = scrobbling.submissions().single()
            assertEquals(ScrobbleState.UNCERTAIN, uncertain.state)
            assertFalse(uncertain.error.orEmpty().contains("fixture-token"))
            scrobbling.closeAndJoin(); store.close(); store = openMusicStore(database)
            val restored = Scrobbling(store, MusicLibrary(store), http, vault, now = { 200_000 })
            loseResponse = false
            restored.enqueueEligible(); restored.runPending()
            assertEquals(1, submissions)
            assertEquals(ScrobbleState.UNCERTAIN, restored.submissions().single().state)
            restored.retrySubmission(uncertain.id)
            restored.runPending()
            assertEquals(2, submissions)
            assertEquals(ScrobbleState.CONFIRMED, restored.submissions().single().state)
            restored.closeAndJoin()
        } finally { profileId?.let { vault.delete("scrobbling:$it") }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anOfflineListenSurvivesRestartAndAConfirmedPlayIsNotSubmittedAgain() = runBlocking {
        val directory = Files.createTempDirectory("offline-scrobbling")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val network = MutableStateFlow(DownloadNetwork(connected = true))
        var submissions = 0
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/1/validate-token" -> respond("""{"valid":true,"user_name":"fixture"}""", headers = jsonHeaders)
                "/1/submit-listens" -> { submissions++; respond("""{"status":"ok"}""", headers = jsonHeaders) }
                else -> error("Unexpected scrobbling request")
            }
        })
        var profileId: String? = null
        try {
            val library = MusicLibrary(store)
            val scrobbling = Scrobbling(store, library, http, vault, network = network, now = { 1_000 })
            val profile = scrobbling.connectListenBrainz("fixture-token")
            profileId = profile.id
            val track = library.importTrack(TrackMetadata("Song", "Artist", durationMs = 180_000),
                TrackSource("local", SourceKind.LOCAL, "/private/song.flac"))
            var elapsed = 0L
            val history = ListeningHistory(store, library, wallClock = { 10_000 }, elapsedClock = { elapsed })
            val playing = PlaybackStatus(track.id, playing = true, durationMs = 180_000, playbackId = "same-play")
            history.record(playing); elapsed = 100_000; history.record(playing.copy(playing = false))
            network.value = DownloadNetwork(connected = false)
            scrobbling.enqueueEligible()
            scrobbling.runPending()
            assertEquals(0, submissions)
            assertEquals(ScrobbleState.PENDING, scrobbling.submissions().single().state)
            assertEquals(profile.id, scrobbling.submissions().single().profileId)
            scrobbling.closeAndJoin()
            store.close(); store = openMusicStore(database)
            val restored = Scrobbling(store, MusicLibrary(store), http, vault, network = network, now = { 200_000 })
            assertEquals(profile.id, restored.profiles().single().id)
            restored.runPending()
            assertEquals(0, submissions)
            network.value = DownloadNetwork(connected = true)
            restored.runPending()
            assertEquals(1, submissions)
            assertEquals(ScrobbleState.CONFIRMED, restored.submissions().single().state)
            assertNull(restored.submissions().single().listen)
            restored.enqueueEligible(); restored.runPending()
            assertEquals(1, submissions)
            // Clearing local history and restarting its active play must not duplicate an external submission.
            val resumed = ListeningHistory(store, MusicLibrary(store), wallClock = { 300_000 }, elapsedClock = { elapsed })
            resumed.clear(); resumed.record(playing); elapsed += 100_000; resumed.record(playing.copy(playing = false))
            restored.enqueueEligible(); restored.runPending()
            assertEquals(1, submissions)
            restored.closeAndJoin()
        } finally {
            profileId?.let { vault.delete("scrobbling:$it") }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private suspend fun recordQualifiedListen(store: me.d3sox.nevumi.storage.MusicStore, library: MusicLibrary,
        playbackId: String = "play", startedAt: Long = 10_000, artist: String = "Artist") {
        val song = library.importTrack(TrackMetadata("Song", artist, durationMs = 180_000),
            TrackSource("local", SourceKind.LOCAL, "/private/song.flac"))
        var elapsed = 0L
        val history = ListeningHistory(store, library, wallClock = { startedAt }, elapsedClock = { elapsed })
        val playing = PlaybackStatus(song.id, playing = true, durationMs = 180_000, playbackId = playbackId)
        history.record(playing); elapsed = 100_000; history.record(playing.copy(playing = false))
    }
}
