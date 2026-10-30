package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class AndroidSongsToMatchUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun sharedSongsHaveTheirOwnPageAndCanBeMatchedFromLocalSongCards() {
        val controller = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MusicApplication).controller
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { controller.state.value.libraryReady }
            val devices = requireNotNull(controller.pairedDevices)
            val local = runBlocking { controller.library.importTrack(TrackMetadata("My copy", "Local artist", "Local album", folder = "/music"),
                TrackSource("local:matching-fixture", SourceKind.LOCAL, "/music/copy.flac")) }
            runBlocking {
                controller.reload().join()
                devices.library.merge(SyncSnapshot(deviceId = "matching-fixture-peer", tracks = listOf(SyncTrack("remote-song", TrackMetadata("Shared song", "Remote artist", "Album"))),
                    favorites = listOf(SyncFavorite("remote-song", true, SyncRevision(9, "matching-fixture-peer")))))
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Paired devices").performScrollTo().performClick()
            compose.onNodeWithText("Songs to match").performScrollTo().performClick()
            compose.onNodeWithText("Pair another device").assertDoesNotExist()
            compose.onNodeWithContentDescription("Match shared song: Shared song").performClick()
            compose.onNodeWithText("Choose your copy").assertIsDisplayed()
            compose.onNodeWithContentDescription("Choose this copy: My copy").performClick()
            compose.waitUntil(5_000) { runBlocking { devices.library.unmatched().isEmpty() } }
            // The page reads the songs through a database flow, which can update after the next idle frame.
            compose.waitUntil(5_000) { compose.onAllNodesWithText("All songs matched").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("All songs matched").assertIsDisplayed()
            // The mapping is saved before the shared favorite is merged, so the favorite can land a moment later.
            compose.waitUntil(5_000) { runBlocking { controller.library.findTrack(local.id) }?.favorite == true }
        }
    }
}
