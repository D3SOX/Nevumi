package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.sftp.SftpConnection
import me.d3sox.nevumi.tunnel.Tailscale
import me.d3sox.nevumi.tunnel.TailscaleTunnel
import me.d3sox.nevumi.tunnel.TunnelRoutes
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class TunnelUiTest {
    @Test
    fun tunnelSettingsStartAndCancelASignInAndSaveTheRoutes() {
        val directory = Files.createTempDirectory("nevumi-tailscale-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) {}
            override suspend fun read(key: String): String? = null
            override suspend fun delete(key: String) {}
        }
        val library = MusicLibrary(store)
        val navidrome = NavidromeConnection(store, http, vault)
        val node = directory.resolve("tailscale").toFile()
        val tunnel = TailscaleTunnel(store, Tailscale(node, "nevumi-ui-test"))
        val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString(),
            tailscale = tunnel::serverFiles)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome, serverFiles = files, tunnel = tunnel)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Tunnel").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Sign in to Tailscale").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("Music server").performScrollTo().performClick()
                waitUntil(timeoutMillis = 5_000) { runBlocking { tunnel.routes() } == TunnelRoutes(musicServer = true) }
                onNodeWithText("Sign in to Tailscale to use these connections.").assertExists()
                onNodeWithText("Control server (optional)").performScrollTo().performTextInput("http://127.0.0.1:9")
                onNodeWithText("Sign in to Tailscale").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Cancel sign-in").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Cancel sign-in").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Sign in to Tailscale").fetchSemanticsNodes().isNotEmpty() }
                assertFalse(node.exists())
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }
}
