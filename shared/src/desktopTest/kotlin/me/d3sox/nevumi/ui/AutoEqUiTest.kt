package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.*
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AutoEqUiTest {
    @Test
    fun anEmptyCatalogRefreshesOnOpeningAndProfilesCanBeSelectedOffline() {
        val directory = Files.createTempDirectory("nevumi-autoeq-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            respond(if (request.url.encodedPath.endsWith("INDEX.md")) {
                "- [Fixture Headphones](./Fixture/over-ear/Fixture%20Headphones) by Fixture measurements"
            } else "Preamp: -3 dB\nFilter 1: ON PK Fc 1000 Hz Gain 3 dB Q 1.0")
        })
        val profiles = AutoEqProfiles(store, http)
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = MusicPreferences(store), equalizerProfiles = profiles)
        val files = EqualizerFiles { EqualizerDocument("Imported headphones", "Preamp: -2 dB\nFilter 1: ON LSC Fc 200 Hz Gain 2 dB Q 0.7") }
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, equalizerFiles = files) }
                waitForStartup(controller)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Playback").performScrollTo().performClick()
                onNodeWithText("Audio effects").performScrollTo().performClick()
                onNodeWithText("Headphone profiles").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Fixture Headphones").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1, requests)
                onNodeWithText("Search headphones").performTextInput("fixture")
                onNodeWithContentDescription("Download profile: Fixture Headphones, Fixture measurements").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.audioEffects.profileName == "Fixture Headphones" }
                assertEquals(2, requests)
                assertEquals(-3.0, controller.state.value.playback.audioEffects.preampDb)
                onNodeWithText("Import profile").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.audioEffects.profileName == "Imported headphones" }
                assertEquals(2, runBlocking { profiles.saved() }.size)
                onNodeWithContentDescription("Use saved profile: Fixture Headphones").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.audioEffects.profileName == "Fixture Headphones" }
                assertEquals(2, requests)
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Equalizer profile").performClick()
                onNodeWithText("Imported headphones").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.audioEffects.profileName == "Imported headphones" }
                onNodeWithText("Save as profile").performScrollTo().performClick()
                onNodeWithText("Profile name").performTextInput("My headphones")
                onNodeWithText("Save profile").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.audioEffects.profileName == "My headphones" }
                val configured = controller.state.value.preferences.audioEffects
                onNodeWithText("Reset equalizer").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.audioEffects.profileName == null }
                onNodeWithContentDescription("Equalizer profile").performScrollTo().performClick()
                onNodeWithText("My headphones").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.audioEffects == configured }
                assertEquals(configured, runBlocking { profiles.saved().first { it.name == "My headphones" }.effects })
                assertEquals(2, requests)
            }
        } finally { runBlocking { controller.closeAndJoin() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
