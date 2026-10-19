package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.d3sox.nevumi.downloads.DownloadStatus
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.security.AndroidCredentialVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import android.content.Context
import android.view.inputmethod.InputMethodManager
import me.d3sox.nevumi.settings.MusicPreferences
import java.io.File
import org.junit.Assert.assertEquals

@RunWith(AndroidJUnit4::class)
class AndroidDownloadUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test
    fun aServerTrackCanBeDownloadedFromItsMenuAndPlayedAsADeviceFile() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val application = compose.activity.application as MusicApplication
        val controller = application.controller
        compose.waitUntil(5_000) { !controller.state.value.loading }
        val originalMetered = runBlocking { MusicPreferences(application.store).load() }.downloadOnMetered
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Music server").performScrollTo().performClick()
        compose.onNodeWithText("Server address").performTextClearance()
        compose.onNodeWithText("Server address").performTextInput("http://127.0.0.1:14533")
        compose.onNodeWithText("Username").performTextClearance()
        compose.onNodeWithText("Username").performTextInput("fixture")
        compose.onNodeWithText("Password").performTextInput("fixture-password")
        compose.onNodeWithText("Connect").performClick()
        compose.waitUntil(10_000) { controller.state.value.server != null && !controller.state.value.loading }
        val http = HttpClient(OkHttp)
        try {
            compose.onNodeWithText("Downloads & cache").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Download network").performScrollTo().performClick()
            compose.onNodeWithText("Any network").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Search").performClick()
            compose.onNodeWithContentDescription("Search your library").performTextInput("Northern Light")
            compose.activityRule.scenario.onActivity { activity ->
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            }
            val track = controller.state.value.tracks.first { it.metadata.title == "Northern Light" && it.sources.any { source -> source.kind == SourceKind.SERVER } }
            val row = hasTestTag("track-row:${track.id}")
            compose.onNode(row, useUnmergedTree = true).performScrollTo()
            compose.onNode(hasContentDescription("Track options: Northern Light") and hasAnyAncestor(row), useUnmergedTree = true).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Download").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Download").performClick()
            compose.waitUntil(10_000) { controller.state.value.downloads[track.id]?.status == DownloadStatus.COMPLETE }
            val downloaded = File(requireNotNull(controller.state.value.downloads[track.id]?.path))
            val header = ByteArray(4)
            downloaded.inputStream().use { java.io.DataInputStream(it).readFully(header) }
            assertEquals("fLaC", header.toString(Charsets.US_ASCII))
            runBlocking { NavidromeConnection(application.store, http, AndroidCredentialVault(application)).disconnect() }
            compose.onNode(row, useUnmergedTree = true).performTouchInput { click(center) }
            try {
                compose.waitUntil(5_000) { controller.state.value.playback.playing && controller.state.value.playback.trackId == track.id }
            } catch (error: Throwable) {
                throw AssertionError("Offline playback failed: ${controller.state.value.playback}; download bytes=${downloaded.length()}", error)
            }
            assertTrue(controller.state.value.tracks.first { it.id == track.id }.sources.any { it.kind == SourceKind.DOWNLOAD })
        } finally {
            runBlocking { controller.updatePreferences { it.copy(downloadOnMetered = originalMetered) }.join() }
            runBlocking { NavidromeConnection(application.store, http, AndroidCredentialVault(application)).disconnect() }
            http.close()
        }
    }
}
