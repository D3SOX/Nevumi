package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.backup.MusicBackup
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackupSettingsUiTest {
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
                onNodeWithText("Export backup").performScrollTo().assertIsDisplayed()
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
