package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.ServerProfile
import me.d3sox.nevumi.navidrome.ServerRefreshProgress
import me.d3sox.nevumi.navidrome.ServerRefreshStage
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ServerRefreshUiTest {
    @Test
    fun aStalledRefreshShowsItsStageAndCountAndCanBeCancelled() {
        val directory = Files.createTempDirectory("nevumi-refresh-feedback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val waiting = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("search3.view")) {
                if (request.url.parameters["songOffset"] == "0")
                    respond("""{"subsonic-response":{"status":"ok","searchResult3":{"song":[{"id":"one","title":"One song"}]}}}""")
                else { waiting.complete(Unit); awaitCancellation() }
            } else respond("""{"subsonic-response":{"status":"ok"}}""")
        })
        val vault = object : CredentialVault {
            private var password: String? = null
            override suspend fun save(key: String, secret: String) { password = secret }
            override suspend fun read(key: String) = password
            override suspend fun delete(key: String) { password = null }
        }
        val connection = NavidromeConnection(store, http, vault)
        runBlocking { connection.connect("https://music.example.test", ServerCredentials("fixture", "test")) }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), connection)
        try {
            runBlocking { withTimeout(3_000) { controller.state.first { it.libraryReady } } }
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Music server").performScrollTo().performClick()
                onNodeWithText("Refresh library").performClick()
                waitUntil(timeoutMillis = 3_000) { waiting.isCompleted }
                onNodeWithText("Loading songs").assertExists()
                onNodeWithText("1 song received").assertExists()
                captureUiSnapshot("server-refresh-loading")
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Loading songs").assertExists()
                onNodeWithText("1 song received").assertExists()
                onNodeWithText("Cancel refresh").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithText("Refresh cancelled").fetchSemanticsNodes().isNotEmpty()
                }
                assertFalse(controller.state.value.loading)
                onNodeWithText("Dismiss").performClick()
                onNodeWithText("Refresh cancelled").assertDoesNotExist()
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun refreshFeedbackFitsSmallScreensAndLandscapeWithNormalAndLargeTextAndReducedMotion() {
        val directory = Files.createTempDirectory("nevumi-refresh-layout")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            for ((width, height, theme) in listOf(Triple(375, 812, ThemeMode.LIGHT), Triple(375, 812, ThemeMode.DARK), Triple(667, 375, ThemeMode.DARK), Triple(1024, 768, ThemeMode.DARK))) {
                for (fontScale in listOf(1f, 2f)) {
                    runSkikoComposeUiTest(size = Size(width * 2f, height * 2f),
                        effectContext = object : MotionDurationScale { override val scaleFactor = 0f }) {
                        val settings = MusicSettings(themeMode = theme)
                        setContent {
                            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                                MusicTheme(settings, null) {
                                    Box(Modifier.size(width.dp, height.dp)) {
                                        ServerSettings(AppState(loading = true, server = ServerProfile("https://music.example.test", "fixture"),
                                            serverRefresh = ServerRefreshProgress(ServerRefreshStage.ARTWORK, 3210, 4000)), controller, {})
                                    }
                                }
                            }
                        }
                        for (text in listOf("Updating artwork", "3210 of 4000 covers checked"))
                            assertFullyReadable(text, "$width x $height with font scale $fontScale")
                        onNodeWithText("Cancel refresh").performScrollTo().assertIsDisplayed()
                        assertTrue(onNodeWithText("Cancel refresh").fetchSemanticsNode().boundsInRoot.height >= 48f)
                        if (fontScale == 1f) captureUiSnapshot("server-refresh-$width-$theme")
                    }
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aRefreshThatSkippedItemsCompletesAndSaysWhatTheNextRefreshTriesAgain() {
        val directory = Files.createTempDirectory("nevumi-refresh-skipped")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        val progress = ServerRefreshProgress(ServerRefreshStage.COMPLETE, 10_816, skippedPlaylists = 1, skippedCovers = 12)
        try {
            for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (fontScale in listOf(1f, 2f)) {
                runSkikoComposeUiTest(size = Size(375 * 2f, 812 * 2f), effectContext = object : MotionDurationScale { override val scaleFactor = 0f }) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                            MusicTheme(MusicSettings(themeMode = theme), null) {
                                Box(Modifier.size(375.dp, 812.dp)) {
                                    ServerSettings(AppState(loading = false, server = ServerProfile("https://music.example.test", "fixture"), serverRefresh = progress), controller, {})
                                }
                            }
                        }
                    }
                    for (text in listOf("Library refreshed", "10816 songs updated",
                        "Couldn’t download 1 playlist and 12 covers. The next refresh tries again."))
                        assertFullyReadable(text, "$theme with font scale $fontScale")
                    onNodeWithText("Dismiss").performScrollTo().assertIsDisplayed()
                    if (fontScale == 1f) captureUiSnapshot("server-refresh-skipped-$theme")
                }
            }
            runSkikoComposeUiTest(size = Size(375 * 2f, 812 * 2f)) {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(2f)) {
                        ServerRefreshStatus(progress.copy(skippedPlaylists = 0, skippedCovers = 1), {}, {})
                    }
                }
                onNodeWithText("Couldn’t download 1 cover. The next refresh tries again.").assertExists()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    private fun ComposeUiTest.assertFullyReadable(text: String, context: String) {
        onNodeWithText(text).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty() && layouts.all { layout ->
            !layout.didOverflowHeight && layout.getLineEnd(layout.lineCount - 1) == text.length &&
                (0 until layout.lineCount).all { line -> !layout.isLineEllipsized(line) &&
                    layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f }
        }, "$text must remain fully readable at $context")
    }
}
