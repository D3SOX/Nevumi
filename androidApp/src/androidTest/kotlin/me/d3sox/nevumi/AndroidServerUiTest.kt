package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.security.AndroidCredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.content.Context
import android.view.inputmethod.InputMethodManager

@RunWith(AndroidJUnit4::class)
class AndroidServerUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun settingsConnectToNavidromeAndShowItsCatalogueOnAndroid() {
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                lateinit var controller: me.d3sox.nevumi.app.MusicController
                it.onActivity { activity -> controller = (activity.application as MusicApplication).controller }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Music server").performScrollTo().performClick()
                compose.onNodeWithText("Server address").performTextClearance()
                compose.onNodeWithText("Server address").performTextInput("http://127.0.0.1:14533")
                compose.onNodeWithText("Username").performTextClearance()
                compose.onNodeWithText("Username").performTextInput("fixture")
                compose.onNodeWithText("Password").performTextInput("fixture-password")
                compose.onNodeWithText("Connect").performClick()
                compose.waitUntil(5_000) {
                    controller.state.value.server != null && !controller.state.value.loading
                }
                compose.onNodeWithContentDescription("Back").performClick()
                val track = controller.state.value.tracks.first { song -> song.metadata.title == "Night Walk" }
                compose.onNodeWithContentDescription("Search").performClick()
                compose.onNodeWithContentDescription("Search your library").performTextInput("Night Walk")
                it.onActivity { activity ->
                    (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                compose.onNodeWithTag("track-row:${track.id}", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            }
        } finally {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = openMusicStore(context)
            val http = HttpClient(OkHttp)
            try { runBlocking { NavidromeConnection(store, http, AndroidCredentialVault(context)).disconnect() } }
            finally { store.close(); http.close() }
        }
    }
}
