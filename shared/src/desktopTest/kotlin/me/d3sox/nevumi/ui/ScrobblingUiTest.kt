package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.history.*
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.SecretServiceVault
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.uuid.Uuid
import java.io.IOException
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.ktor.http.content.OutgoingContent
import kotlinx.serialization.json.*
import io.ktor.client.request.HttpRequestData

@OptIn(ExperimentalTestApi::class)
class ScrobblingUiTest {
    @Test
    fun thresholdControlsRemainReadableWithLargeTextOnSmallScreensAndInLandscape() {
        val directory = Files.createTempDirectory("scrobbling-layout-ui")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val player = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            for ((width, height, theme) in listOf(Triple(375, 812, ThemeMode.LIGHT), Triple(667, 375, ThemeMode.DARK))) {
                runComposeUiTest {
                    val settings = MusicSettings(themeMode = theme)
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                            MusicTheme(settings, null) {
                                Box(Modifier.size(width.dp, height.dp)) {
                                    SettingsScreen({}, { androidx.compose.material3.Text("Listening & scrobbling") }, {
                                        Column(Modifier.verticalScroll(rememberScrollState())) {
                                            ScrobblingSettingsContent(AppState(preferences = settings), player) {}
                                        }
                                    })
                                }
                            }
                        }
                    }
                    for (label in listOf("Scrobble after", "Or after")) {
                        onNodeWithContentDescription(label).performScrollTo().assertIsDisplayed()
                        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                        assertTrue(layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
                    }
                    captureUiSnapshot("scrobbling-$width-$theme")
                }
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun scrobblingThresholdsCanBeChangedAndRestoreAfterRestart() {
        val directory = Files.createTempDirectory("scrobbling-threshold-ui")
        val database = directory.resolve("music.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(MockEngine { error("No account is connected") })
        fun controller(): MusicController {
            val library = MusicLibrary(store)
            return MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store),
                scrobbling = Scrobbling(store, library, http, SecretServiceVault("me.d3sox.nevumi.tests.threshold-ui")))
        }
        var player = controller()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                captureUiSnapshot("scrobbling-settings")
                onNodeWithContentDescription("Scrobble after").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(75f) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.preferences.scrobblePercent == 75 }
                onNodeWithContentDescription("Or after").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
                waitUntil(timeoutMillis = 3_000) { player.state.value.preferences.scrobbleMaxSeconds == 0 }
            }
            runBlocking { player.closeAndJoin() }; store.close(); store = openMusicStore(database)
            player = controller()
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                assertEquals(75, player.state.value.preferences.scrobblePercent)
                assertEquals(0, player.state.value.preferences.scrobbleMaxSeconds)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextInput("time limit")
                onNodeWithText("Or after").performClick()
                onNodeWithText("75%").performScrollTo().assertIsDisplayed()
                onNodeWithContentDescription("Increase Or after").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.preferences.scrobbleMaxSeconds == 30 }
                assertEquals(30, runBlocking { MusicPreferences(store).load() }.scrobbleMaxSeconds)
            }
        } finally {
            runBlocking { player.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun lastFmSignInOpensTheAuthorizationPageAndKeepsThePendingSignInWhenAuthorizationIsNotGrantedYet() {
        val directory = Files.createTempDirectory("lastfm-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val opened = mutableListOf<String>()
        var authorized = false
        val http = HttpClient(MockEngine { request ->
            val form = parseQueryString((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            respond(when (form["method"]) {
                "auth.getToken" -> """{"token":"fixture-authorization"}"""
                "auth.getSession" -> if (authorized) """{"session":{"name":"fixture","key":"fixture-session"}}"""
                    else """{"error":14,"message":"Token not authorized"}"""
                else -> error("Unexpected Last.fm method")
            }, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val service = Scrobbling(store, library, http, vault)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            listeningHistory = ListeningHistory(store, library), preferences = MusicPreferences(store), scrobbling = service)
        try {
            runComposeUiTest {
                setContent { CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) { MusicPlayerApp(controller) } }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Connect Last.fm").performScrollTo().performClick()
                onNodeWithText("Application API key").performTextInput("fixture-api-key")
                onNodeWithText("Application shared secret").performTextInput("fixture-shared-secret")
                onNodeWithText("Start sign-in").performClick()
                waitUntil(timeoutMillis = 3_000) { opened.size == 1 }
                assertEquals("www.last.fm", Url(opened.single()).host)
                assertEquals("fixture-authorization", Url(opened.single()).parameters["token"])
                assertFalse(opened.single().contains("fixture-shared-secret"))
                onNodeWithText("Complete sign-in").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Authorize Nevumi in Last.fm, then complete sign-in.").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(controller.state.value.scrobbling.profiles.isEmpty())
                onNodeWithText("Open Last.fm authorization").assertExists()
                authorized = true
                onNodeWithText("Complete sign-in").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.profiles.size == 1 }
                onNodeWithText("Connected as fixture").performScrollTo().assertIsDisplayed()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Complete sign-in").fetchSemanticsNodes().isEmpty() }
                assertEquals(ScrobblingService.LASTFM, controller.state.value.scrobbling.profiles.single().service)
                onNodeWithText("Connect Last.fm").performScrollTo().performClick()
                onNodeWithText("Application API key").performTextInput("fixture-api-key")
                onNodeWithText("Application shared secret").performTextInput("fixture-shared-secret")
                onNodeWithText("Start sign-in").performClick()
                waitUntil(timeoutMillis = 3_000) { opened.size == 2 }
                onNodeWithText("Cancel sign-in").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Complete sign-in").fetchSemanticsNodes().isEmpty() }
                assertNull(runBlocking { service.pendingLastFmAuthorization() })
                assertEquals(1, controller.state.value.scrobbling.profiles.size)
            }
        } finally {
            runBlocking { controller.closeAndJoin(); vault.delete("lastfm:authorization"); service.profiles().forEach { vault.delete("scrobbling:${it.id}") } }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun anUnconfirmedListenCanBeReviewedAndOnlyRetriedAfterConfirmingThePossibleDuplicate() {
        val directory = Files.createTempDirectory("uncertain-scrobbling-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else {
                if (listenType(request) == "single") {
                    requests++
                    if (requests == 1) throw IOException("Lost confirmation")
                }
                respond("""{"status":"ok"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val service = Scrobbling(store, library, http, vault)
        runBlocking {
            service.connectListenBrainz("fixture-token")
            library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", durationMs = 3_000),
                TrackSource("local", SourceKind.LOCAL, Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            listeningHistory = ListeningHistory(store, library), preferences = MusicPreferences(store), scrobbling = service)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended && controller.state.value.scrobbling.submissions.singleOrNull()?.state == ScrobbleState.UNCERTAIN }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Unconfirmed submission").performScrollTo().assertIsDisplayed()
                onNodeWithText("Retry").performScrollTo().performClick()
                onNodeWithText("Retry unconfirmed listen?").assertIsDisplayed()
                onNodeWithText("Cancel").performClick()
                assertEquals(1, requests)
                onNodeWithText("Retry").performScrollTo().performClick()
                onNodeWithText("Retry listen").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.submissions.single().state == ScrobbleState.CONFIRMED }
                assertEquals(2, requests)
                onNodeWithText("No pending listens").performScrollTo().assertIsDisplayed()
            }
        } finally {
            runBlocking { controller.closeAndJoin(); service.profiles().forEach { vault.delete("scrobbling:${it.id}") } }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun connectingListenBrainzThroughSettingsScrobblesARealSongAndCanBeDisabled() {
        val directory = Files.createTempDirectory("scrobbling-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        var requests = 0
        var nowPlaying = 0
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("validate-token"))
                respond("""{"valid":true,"user_name":"fixture"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else {
                if (listenType(request) == "single") requests++ else nowPlaying++
                respond("""{"status":"ok"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val scrobbling = Scrobbling(store, library, http, vault)
        runBlocking {
            library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", durationMs = 3_000),
                TrackSource("local", SourceKind.LOCAL, Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()))
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            listeningHistory = ListeningHistory(store, library), preferences = MusicPreferences(store), scrobbling = scrobbling)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Connect ListenBrainz").performScrollTo().performClick()
                onNodeWithText("User token").performTextInput("fixture-token")
                onNodeWithText("Connect").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.profiles.size == 1 }
                onNodeWithText("Connected as fixture").performScrollTo().assertIsDisplayed()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended && controller.state.value.scrobbling.submissions.singleOrNull()?.state == ScrobbleState.CONFIRMED }
                assertEquals(1, requests)
                assertEquals(1, nowPlaying)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithContentDescription("Send listens to ListenBrainz").performClick()
                onNode(hasText("Off") and hasAnyAncestor(isPopup())).performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.profiles.single().enabled == false }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                runBlocking { controller.playTrack(controller.state.value.tracks.single().id).join() }
                waitUntil(timeoutMillis = 6_000) { controller.state.value.playback.ended }
                assertEquals(1, requests)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                onNodeWithText("Disconnect ListenBrainz").performScrollTo().performClick()
                onNodeWithText("Cancel").performClick()
                assertEquals(1, controller.state.value.scrobbling.profiles.size)
                onNodeWithText("Disconnect ListenBrainz").performScrollTo().performClick()
                onNodeWithText("Disconnect").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.scrobbling.profiles.isEmpty() }
                assertEquals(2, runBlocking { ListeningHistory(store, library).all() }.size)
            }
        } finally {
            runBlocking { controller.closeAndJoin(); scrobbling.profiles().forEach { vault.delete("scrobbling:${it.id}") } }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }
    private fun listenType(request: HttpRequestData) = Json.parseToJsonElement(
        (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject.getValue("listen_type").jsonPrimitive.content
}
