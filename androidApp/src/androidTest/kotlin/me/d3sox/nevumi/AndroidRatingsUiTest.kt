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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRatingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test
    fun ratingAndClearingAServerSongFromThePhoneUpdatesNavidrome() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14533", ServerCredentials("fixture", "fixture-password"))
        val original = runBlocking { client.songs().first { it.metadata.title == "Northern Light" } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                compose.waitUntil(5_000) { !controller.state.value.loading }
                runBlocking { controller.connectServer("http://127.0.0.1:14533", "fixture", "fixture-password").join() }
                compose.onNodeWithContentDescription("Track options: Northern Light").performClick()
                compose.onNodeWithText("Rate song").performClick()
                compose.onNodeWithContentDescription("5 stars").performClick()
                compose.onNodeWithText("Confirm").performClick()
                compose.waitUntil(5_000) {
                    controller.state.value.tracks.single { it.metadata.title == "Northern Light" }.rating == 5 &&
                        runBlocking { client.songs().first { it.id == original.id }.rating == 5 }
                }
                compose.onNodeWithContentDescription("Rating: 5 stars").assertIsDisplayed()
                compose.onNodeWithContentDescription("Track options: Northern Light").performClick()
                compose.onNodeWithText("Rate song").performClick()
                compose.onNodeWithText("Clear rating").performClick()
                compose.onNodeWithText("Confirm").performClick()
                compose.waitUntil(5_000) {
                    controller.state.value.tracks.single { it.metadata.title == "Northern Light" }.rating == 0 &&
                        runBlocking { client.songs().first { it.id == original.id }.rating == 0 }
                }
                compose.onNodeWithContentDescription("Rating: 5 stars").assertDoesNotExist()
            }
        } finally {
            runBlocking {
                app.controller.closeAndJoin()
                client.setRating(original.id, original.rating)
                NavidromeConnection(app.store, http, AndroidCredentialVault(app)).disconnect()
            }
            http.close()
        }
    }
}
