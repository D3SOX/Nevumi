package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.SecretServiceVault
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ServerSetupUiTest {
    @Test
    fun refreshingTheServerLibraryUsesSavedCredentialsAndImportsChangesMadeElsewhere() {
        val directory = Files.createTempDirectory("server-refresh-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val connection = NavidromeConnection(store, http, vault)
        val library = MusicLibrary(store)
        val changes = NavidromeChanges(store, library, client = connection::client)
        val address = System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533"
        val client = NavidromeClient(http, address, ServerCredentials("fixture", "fixture-password"))
        val original = runBlocking { client.songs().first { it.metadata.title == "Northern Light" } }
        runBlocking { client.setFavorite(original.id, false) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            connection, serverChanges = changes)
        try {
            runBlocking { controller.connectServer(address, "fixture", "fixture-password").join() }
            assertEquals(false, controller.state.value.tracks.first { it.metadata.title == "Northern Light" }.favorite)
            runBlocking { client.setFavorite(original.id, true) }
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Music server").performScrollTo().performClick()
                onNodeWithText("Connected").assertExists()
                onNodeWithText("Password").assertDoesNotExist()
                onNodeWithText("Refresh library").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    controller.state.value.tracks.first { it.metadata.title == "Northern Light" }.favorite
                }
            }
        } finally {
            runBlocking { controller.closeAndJoin(); client.setFavorite(original.id, original.favorite); vault.delete("navidrome") }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun disconnectingTheServerKeepsItsSongsButMarksThemUnavailable() {
        val directory = Files.createTempDirectory("server-disconnect-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"),
            NavidromeConnection(store, http, vault))
        try {
            runBlocking { controller.connectServer(System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                "fixture", "fixture-password").join() }
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Music server").performScrollTo().performClick()
                onNodeWithText("Disconnect").performClick()
                onNodeWithText("Disconnect music server?").assertExists()
                onAllNodesWithText("Disconnect").onLast().performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.server == null && !controller.state.value.loading }
                onNodeWithText("Password").assertExists()
                assertEquals(3, controller.state.value.tracks.size)
                assertEquals(true, controller.state.value.tracks.all { track -> track.sources.none { it.available } })
            }
        } finally {
            runBlocking { controller.closeAndJoin(); vault.delete("navidrome") }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun favoritingAServerTrackFromItsMenuUpdatesNavidrome() {
        val directory = Files.createTempDirectory("server-favorite-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val connection = NavidromeConnection(store, http, vault)
        val library = MusicLibrary(store)
        val changes = NavidromeChanges(store, library, client = connection::client)
        val client = NavidromeClient(http,
            System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
            ServerCredentials("fixture", "fixture-password"))
        val original = runBlocking { client.songs().first { it.metadata.title == "Northern Light" } }
        runBlocking { client.setFavorite(original.id, false) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            connection, serverChanges = changes, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            controller.connectServer(System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533",
                "fixture", "fixture-password")
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.tracks.size == 3 }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNode(hasText("Favorite") and hasClickAction()).performClick()
                waitUntil(timeoutMillis = 5_000) {
                    controller.state.value.tracks.first { it.metadata.title == "Northern Light" }.favorite &&
                        runBlocking { client.songs().first { it.id == original.id }.favorite }
                }
                assertEquals(0, runBlocking { changes.pendingCount() })
            }
        } finally {
            runBlocking { controller.closeAndJoin(); client.setFavorite(original.id, original.favorite); vault.delete("navidrome") }
            store.close(); http.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aServerSongPlaysDirectlyFromTheUnifiedLibrary() {
        val directory = Files.createTempDirectory("server-playback-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"),
            NavidromeConnection(store, http, vault))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Music server").performScrollTo().performClick()
                onNodeWithText("Server address").performTextInput(
                    System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533")
                onNodeWithText("Username").performTextInput("fixture")
                onNodeWithText("Password").performTextInput("fixture-password")
                onNodeWithText("Connect").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.server != null && !controller.state.value.loading }
                onNodeWithContentDescription("Back").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Northern Light").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithText("Northern Light").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.engine.status.value.playing }
                onNodeWithContentDescription("Pause").assertExists()
            }
        } finally {
            runBlocking { controller.closeAndJoin(); vault.delete("navidrome") }
            store.close(); http.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun connectingToNavidromeFromSettingsAddsItsSongsToTheLibrary() {
        val directory = Files.createTempDirectory("server-setup-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val vault = SecretServiceVault("me.d3sox.nevumi.tests.${Uuid.random()}")
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"),
            NavidromeConnection(store, http, vault))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Music server").performScrollTo().performClick()
                onNodeWithText("Server address").performTextInput(
                    System.getenv("MUSICPLAYER_TEST_NAVIDROME_URL") ?: "http://127.0.0.1:14533")
                onNodeWithText("Username").performTextInput("fixture")
                onNodeWithText("Password").performTextInput("fixture-password")
                onNodeWithText("Connect").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.server != null && !controller.state.value.loading }
                onNodeWithContentDescription("Back").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    onAllNodesWithText("Northern Light").fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription("Songs, 3 songs").assertExists()
            }
        } finally {
            runBlocking { controller.closeAndJoin(); vault.delete("navidrome") }
            store.close(); http.close()
            directory.toFile().deleteRecursively()
        }
    }
}
