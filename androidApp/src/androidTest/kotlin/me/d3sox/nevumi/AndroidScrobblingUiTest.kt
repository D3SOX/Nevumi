package me.d3sox.nevumi

import android.content.ContentValues
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.history.ScrobbleState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidScrobblingUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun startupUsesPlatformEmojiWithoutAutomaticallyStartingADownloadableFontProvider() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val provider = context.packageManager.getProviderInfo(ComponentName(context.packageName, "androidx.startup.InitializationProvider"),
            PackageManager.GET_META_DATA)
        assertFalse(provider.metaData?.containsKey("androidx.emoji2.text.EmojiCompatInitializer") == true)
    }

    @Test
    fun settingsConnectToListenBrainzAndScrobbleARealLocalSongOnThePhone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val resolver = app.contentResolver
        val audio = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "scrobbling-${UUID.randomUUID()}.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-scrobbling-${UUID.randomUUID()}")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val http = HttpClient(OkHttp)
        try {
            runBlocking { http.delete("http://127.0.0.1:14535/test/listens") }
            instrumentation.context.assets.open("fixture.flac").use { input -> requireNotNull(resolver.openOutputStream(audio)).use { input.copyTo(it) } }
            resolver.update(audio, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                compose.waitUntil(5_000) { !controller.state.value.loading }
                runBlocking { controller.importSource(audio.toString()).join() }
                val song = controller.state.value.tracks.single { it.sources.any { source -> source.location == audio.toString() } }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                compose.onNodeWithText("Connect ListenBrainz").performClick()
                compose.onNodeWithText("User token").performScrollTo().performTextInput("fixture-token")
                compose.onNodeWithText("Service address").performScrollTo().performTextReplacement("http://127.0.0.1:14535")
                compose.onNodeWithText("Connect").performClick()
                compose.waitUntil(5_000) { controller.state.value.scrobbling.profiles.size == 1 }
                compose.onNodeWithText("Connected as fixture").performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText(song.metadata.title).performClick()
                compose.waitUntil(10_000) { controller.state.value.playback.ended && controller.state.value.scrobbling.submissions.singleOrNull()?.state == ScrobbleState.CONFIRMED }
                val delivered = JSONObject(runBlocking { http.get("http://127.0.0.1:14535/test/listens").bodyAsText() }).getJSONArray("listens")
                assertEquals(1, (0 until delivered.length()).count { delivered.getJSONObject(it).getString("listen_type") == "single" })
                assertEquals(1, (0 until delivered.length()).count { delivered.getJSONObject(it).getString("listen_type") == "playing_now" })
                assertFalse(delivered.toString().contains(audio.toString()))
                assertFalse(delivered.toString().contains("fixture-token"))
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Listening & scrobbling").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Send listens to ListenBrainz").performScrollTo().performClick()
                compose.onNode(hasText("Off") and hasAnyAncestor(isPopup())).performClick()
                compose.waitUntil(3_000) { !controller.state.value.scrobbling.profiles.single().enabled }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                runBlocking { controller.playTrack(song.id).join() }
                compose.waitUntil(10_000) { controller.state.value.playback.ended }
                assertEquals(1, controller.state.value.scrobbling.submissions.size)
            }
        } finally { http.close(); resolver.delete(audio, null, null) }
    }
}
