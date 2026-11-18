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
                waitForStartup(controller)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Backup & restore").performScrollTo().performClick()
                onNodeWithText("No backup exported on this device yet").assertIsDisplayed()
                onNodeWithText("Export backup").performClick()
                onNodeWithText("Choose what to export").assertIsDisplayed()
                captureUiSnapshot("backup-export-selection", onNode(isDialog()))
                val choices = listOf("Settings", "Playlists", "Saved queues", "Favorites", "Ratings", "Listening history", "Service profiles", "Equalizer profiles", "Downloads")
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
    fun restoringOffersTheBackupsSectionsAndThenSummarizesWhatWasAdded() {
        val directory = Files.createTempDirectory("nevumi-backup-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists,
            preferences = MusicPreferences(store), backup = MusicBackup(store, library))
        val files = object : BackupFiles {
            var opened = 0
            // The second backup only names a song this library lacks, in a playlist that is already here.
            override suspend fun open(): String = if (++opened <= 2)
                """{"format":"nevumi-backup","version":1,"createdAt":0,"settings":{"speed":1.5},"playlists":[{"name":"Road"},{"name":"Gone","trackKeys":["missing"]}]}"""
            else """{"format":"nevumi-backup","version":1,"createdAt":0,"playlists":[{"name":"Road","trackKeys":["missing"]}]}"""
            override suspend fun save(name: String, text: String) = false
        }
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, backupFiles = files) }
                waitForStartup(controller)
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
                val speed = controller.state.value.preferences.speed
                // Cancelling the choice restores nothing.
                onNodeWithText("Restore backup").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Choose what to restore").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("Cancel") and hasAnyAncestor(isDialog())).performClick()
                waitForIdle()
                assertEquals(1, files.opened)
                assertTrue(runBlocking { playlists.all().isEmpty() })
                // Only the sections the file holds are offered; leaving out Settings keeps them.
                onNodeWithText("Restore backup").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Choose what to restore").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("Favorites") and hasAnyAncestor(isDialog())).assertDoesNotExist()
                onNode(hasText("Settings") and hasAnyAncestor(isDialog())).assertIsOn().performClick()
                onNode(hasText("Playlists") and hasAnyAncestor(isDialog())).performClick()
                onNode(hasText("Restore backup") and hasAnyAncestor(isDialog())).assertIsNotEnabled()
                onNodeWithText("Choose at least one item to restore").assertIsDisplayed()
                onNode(hasText("Playlists") and hasAnyAncestor(isDialog())).performClick()
                captureUiSnapshot("backup-restore-selection", onNode(isDialog()))
                onNode(hasText("Restore backup") and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Backup restored").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(speed, controller.state.value.preferences.speed)
                onNodeWithText("New playlists").assertIsDisplayed()
                onNodeWithText("Playlists not restored").assertIsDisplayed()
                onNodeWithText("Everything in this backup is already here.").assertDoesNotExist()
                captureUiSnapshot("backup-restore-summary", onNode(isDialog()))
                onNodeWithText("Done").performClick()
                waitForIdle()
                onNodeWithText("New playlists").assertDoesNotExist()
                assertEquals(listOf("Road"), runBlocking { playlists.all().map { it.name } })
                onNodeWithText("Restore backup").performScrollTo().performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Choose what to restore").fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("Restore backup") and hasAnyAncestor(isDialog())).performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Songs not found in this library").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Everything in this backup is already here.").assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
