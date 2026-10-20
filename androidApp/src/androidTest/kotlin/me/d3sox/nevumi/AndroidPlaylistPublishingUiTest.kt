package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.security.AndroidCredentialVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AndroidPlaylistPublishingUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun publishingAPlaylistFromThePhonePreservesRepeatedSongsOnNavidrome() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val name = "Phone publish ${Uuid.random()}"
        var playlistId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                compose.waitUntil(5_000) { !controller.state.value.loading }
                runBlocking {
                    controller.connectServer("http://127.0.0.1:14533", "fixture", "fixture-password").join()
                    val tracks = controller.state.value.tracks
                    controller.createPlaylist(name, listOf(tracks[0].id, tracks[1].id, tracks[0].id)).join()
                }
                playlistId = controller.state.value.playlists.single { it.name == name }.id
                compose.onNodeWithText("Library refreshed").assertIsDisplayed()
                compose.onNodeWithText("Dismiss").performClick()
                compose.onNodeWithText("Library refreshed").assertDoesNotExist()
                compose.openLibraryPage("Playlists")
                compose.onNodeWithText(name).performClick()
                compose.onNodeWithContentDescription("Playlist options").performClick()
                compose.onNodeWithText("Publish to your music server").performClick()
                compose.onNodeWithText("Publish").performClick()
                compose.waitUntil(10_000) {
                    controller.state.value.playlists.any { it.id == playlistId && it.server?.id != null && !it.pendingSync }
                }
                val saved = controller.state.value.playlists.single { it.id == playlistId }
                val http = HttpClient(OkHttp)
                try {
                    val remote = runBlocking { NavidromeClient(http, "http://127.0.0.1:14533",
                        ServerCredentials("fixture", "fixture-password")).playlist(saved.server!!.id!!) }
                    assertEquals(name, remote.name)
                    assertEquals(3, remote.songIds.size)
                    assertEquals(remote.songIds[0], remote.songIds[2])
                } finally { http.close() }
                compose.onNodeWithText("Server playlist").performScrollTo().assertIsDisplayed()
            }
        } finally {
            val http = HttpClient(OkHttp)
            try {
                runBlocking {
                    val client = NavidromeClient(http, "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
                    client.playlists().filter { it.name == name || it.name == "Nevumi pending $playlistId" }.forEach { client.deletePlaylist(it.id) }
                    NavidromeConnection(app.store, http, AndroidCredentialVault(app)).disconnect()
                }
            } finally { http.close() }
        }
    }
}
