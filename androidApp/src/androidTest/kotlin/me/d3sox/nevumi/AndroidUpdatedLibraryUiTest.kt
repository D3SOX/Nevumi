package me.d3sox.nevumi

import android.content.Intent
import android.content.pm.PackageManager
import android.service.media.MediaBrowserService
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AndroidUpdatedLibraryUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun panoCanDiscoverNevumiUsingThePublicMediaBrowserServiceIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val services = context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(context.packageName), 0)
        assertTrue(services.any { it.serviceInfo.name == PlaybackService::class.java.name && it.serviceInfo.exported })
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("Artwork animation must not request microphone access", "android.permission.RECORD_AUDIO" in permissions)
    }

    @Test fun settingsSlidersAndPlaylistControlsWorkOnAPhoneLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        // Enabling playlist downloads asks for notification access, whose dialog would cover the activity.
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(
            app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        val audio = File(app.cacheDir, "updated-library.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            runBlocking {
                MusicPreferences(app.store).update { it.copy(themeMode = ThemeMode.DARK, amoledBlack = true) }
                val tracks = listOf("Bravo", "Alpha", "Charlie").map { title ->
                    app.controller.library.importTrack(TrackMetadata(title, artist = "Fixture artist", durationMs = 3_000),
                        TrackSource(title, SourceKind.LOCAL, audio.toURI().toString()))
                }
                Playlists(app.store).create("Evening", tracks.map { it.id })
                app.controller.reload().join()
            }
            fun capture(name: String) {
                val file = File(app.filesDir, "native-test-output/nevumi-updates/$name.png").apply { parentFile!!.mkdirs() }
                file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithContentDescription("Settings").performClick()
                capture("settings-cards")
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Playback speed").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
                compose.onNodeWithContentDescription("Playback pitch").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
                compose.waitUntil(3_000) { app.controller.state.value.preferences.speed == 1.5 }
                capture("playback-sliders")
                runBlocking { app.controller.setSpeed(1.0).join(); app.controller.setPitch(1.0).join() }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("More").performClick()
                compose.onNodeWithText("Playlists").performClick()
                compose.onNodeWithText("Evening").performClick()
                compose.onNodeWithContentDescription("Playlist options").performClick()
                compose.onNodeWithText("Edit playlist").performClick()
                compose.onNodeWithContentDescription("Playlist title").performTextReplacement("After hours")
                compose.onNodeWithContentDescription("Playlist description").performTextInput("Music for late evenings")
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(3_000) { app.controller.state.value.playlists.single().name == "After hours" }
                compose.onNodeWithText("Music for late evenings").assertIsDisplayed()
                compose.onNodeWithContentDescription("Playlist options").performClick()
                compose.onNodeWithText("Play next", substring = false).assertIsDisplayed()
                compose.onNodeWithText("Play after", substring = false).assertIsDisplayed()
                compose.onNodeWithText("Add to queue", substring = false).assertIsDisplayed()
                capture("playlist-queue-actions")
                compose.onNodeWithText("Play next", substring = false).performClick()
                compose.waitUntil(3_000) { app.controller.state.value.queue.entries.size == 3 }
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).onLast()
                    .performSemanticsAction(SemanticsActions.Dismiss) { it() }
                compose.onNodeWithContentDescription("Automatically download playlist").performScrollTo().performClick()
                compose.waitUntil(3_000) { app.controller.state.value.playlists.single().autoDownload }
                compose.onNodeWithContentDescription("Automatically download playlist").assertIsOn()
                capture("playlist-downloads-enabled")
                compose.onNodeWithContentDescription("Playlist song order").performScrollTo().performClick()
                compose.onNode(hasText("Title") and hasAnyAncestor(isPopup())).performClick()
                compose.onNodeWithContentDescription("Shuffle playlist").performClick().assertIsSelected()
                compose.onNodeWithContentDescription("Play playlist").performClick()
                compose.waitUntil(5_000) { app.controller.state.value.queue.unshuffledEntryIds != null }
                assertEquals(3, app.controller.state.value.queue.entries.size)
                runBlocking { app.controller.pause().join() }
                capture("playlist-details")
            }
        } finally { runBlocking { app.controller.pause().join() }; audio.delete() }
    }
}
