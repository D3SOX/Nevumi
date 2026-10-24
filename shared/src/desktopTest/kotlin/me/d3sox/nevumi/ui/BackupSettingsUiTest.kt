package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.backup.MusicBackup
import me.d3sox.nevumi.backup.BackupDocument
import kotlinx.serialization.json.Json
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BackupSettingsUiTest {
    @Test
    fun exportChoicesRequireASelectionAndOnlySaveTheChosenData() {
        val directory = Files.createTempDirectory("nevumi-backup-export-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        runBlocking { playlists.create("Road", emptyList()) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists,
            preferences = MusicPreferences(store), backup = MusicBackup(store, library))
        val files = object : BackupFiles {
            var saved: String? = null
            override suspend fun open(): String? = null
            override suspend fun save(name: String, text: String): Boolean { saved = text; return true }
        }
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, backupFiles = files) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Backup & restore").performScrollTo().performClick()
                onNodeWithText("No backup exported on this device yet").assertIsDisplayed()
                onNodeWithText("Export backup").performClick()
                onNodeWithText("Choose what to export").assertIsDisplayed()
                captureUiSnapshot("backup-export-selection", onNode(isDialog()))
                val choices = listOf("Settings", "Playlists", "Saved queues", "Favorites", "Ratings", "Listening history", "Service profiles", "Equalizer profiles")
                for (label in choices) onNodeWithText(label).performScrollTo().assertIsOn().performClick()
                onNode(hasText("Export backup") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
                assertNull(files.saved)
                onNodeWithText("Playlists").performScrollTo().performClick()
                onNode(hasText("Export backup") and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithText("No backup exported on this device yet").fetchSemanticsNodes().isEmpty()
                }
                onNodeWithText("Last backup").assertIsDisplayed()
                val document = Json.decodeFromString<BackupDocument>(assertNotNull(files.saved))
                assertEquals(listOf("Road"), document.playlists.map { it.name })
                assertNull(document.settings)
                assertTrue(document.tracks.isEmpty() && document.savedQueues.isEmpty() && document.listens.isEmpty())
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Backup saved").fetchSemanticsNodes().isNotEmpty() }
                onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss)).onFirst()
                    .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss)
                captureUiSnapshot("backup-restore-last-export")
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun restoringAsksFirstAndThenSummarizesWhatWasAdded() {
        val directory = Files.createTempDirectory("nevumi-backup-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists,
            preferences = MusicPreferences(store), backup = MusicBackup(store, library))
        val files = object : BackupFiles {
            var opened = 0
            override suspend fun open(): String { opened++; return """{"format":"nevumi-backup","version":1,"createdAt":0,"playlists":[{"name":"Road"}]}""" }
            override suspend fun save(name: String, text: String) = false
        }
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, backupFiles = files) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Export backup").assertDoesNotExist()
                onNodeWithText("Restore backup").assertDoesNotExist()
                onNodeWithText("About Nevumi").performScrollTo()
                captureUiSnapshot("settings-backup-entry")
                onNodeWithText("Backup & restore").performScrollTo().performClick()
                onNodeWithText("Export backup").performScrollTo().assertIsDisplayed()
                captureUiSnapshot("backup-restore-settings")
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Export backup").assertDoesNotExist()
                onNodeWithContentDescription("Search settings").performClick()
                onNodeWithContentDescription("Search settings query").performTextInput("restore backup")
                onNodeWithText("Restore backup").performClick()
                onNodeWithText("Restore backup").performScrollTo().performClick()
                onNodeWithText("Restore a backup?").assertIsDisplayed()
                onNodeWithText("Cancel").performClick()
                assertEquals(0, files.opened)
                onNodeWithText("Restore backup").performScrollTo().performClick()
                onNodeWithText("Choose file").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Backup restored").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("New playlists").assertIsDisplayed()
                onNodeWithText("Done").performClick()
                waitForIdle()
                onNodeWithText("New playlists").assertDoesNotExist()
                assertEquals(listOf("Road"), runBlocking { playlists.all().map { it.name } })
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
