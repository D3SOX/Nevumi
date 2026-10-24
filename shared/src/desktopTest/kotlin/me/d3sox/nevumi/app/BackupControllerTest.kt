package me.d3sox.nevumi.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.d3sox.nevumi.backup.MusicBackup
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.backup_notice_exported
import me.d3sox.nevumi.resources.backup_notice_restored
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.storage.openMusicStore
import me.d3sox.nevumi.ui.BackupFiles
import java.nio.file.Files
import kotlin.test.*

class BackupControllerTest {
    private class MemoryFiles(var text: String? = null) : BackupFiles {
        var savedName: String? = null
        var saveResult = true
        override suspend fun open() = text
        override suspend fun save(name: String, text: String): Boolean { savedName = name; this.text = text; return saveResult }
    }

    @Test fun lastBackupSurvivesRestartAndDoesNotChangeAfterCancelledOrFailedSaves() = runBlocking {
        val directory = Files.createTempDirectory("backup-last-export")
        val path = directory.resolve("library.db").toString()
        val store = openMusicStore(path)
        val library = MusicLibrary(store)
        var now = 1_000L
        val backup = MusicBackup(store, library, clock = { now })
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), backup = backup)
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            assertNull(player.backupController.lastExportedAt.first())
            val files = MemoryFiles()
            player.backupController.export(files).join()
            assertEquals(1_000L, player.backupController.lastExportedAt.first())
            now = 2_000L
            files.saveResult = false
            player.backupController.export(files).join()
            assertEquals(1_000L, player.backupController.lastExportedAt.first())
            val failing = object : BackupFiles {
                override suspend fun open(): String? = null
                override suspend fun save(name: String, text: String): Boolean = throw java.io.IOException("Disk full")
            }
            player.backupController.export(failing).join()
            assertEquals(1_000L, player.backupController.lastExportedAt.first())
            assertTrue(player.state.value.error.orEmpty().contains("Disk full"))
        } finally { player.closeAndJoin(); store.close() }
        try {
            val reopened = openMusicStore(path)
            try { assertEquals(1_000L, MusicBackup(reopened).lastExportedAt.first()) }
            finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun exportingAndRestoringThroughThePickerUpdatesTheAppStateAndShowsASummary() = runBlocking {
        val directory = Files.createTempDirectory("backup-controller")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val song = library.importTrack(TrackMetadata("Song", "Artist"), TrackSource("local:1", SourceKind.LOCAL, "/music/song.flac"))
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists,
            preferences = MusicPreferences(store), backup = MusicBackup(store, library))
        suspend fun notice(action: () -> Job): ActionNotice = coroutineScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { player.notices.first() }
            action().join()
            withTimeout(3_000) { pending.await() }
        }
        try {
            withTimeout(3_000) { player.state.first { it.libraryReady } }
            playlists.create("Road", listOf(song.id))
            MusicPreferences(store).update { it.copy(themeMode = ThemeMode.DARK) }
            val files = MemoryFiles()
            assertEquals(Res.string.backup_notice_exported, notice { player.backupController.export(files, "1.0") }.message)
            assertTrue(files.savedName!!.matches(Regex("nevumi-backup-\\d{4}-\\d{2}-\\d{2}\\.json")))

            playlists.delete(playlists.all().single().id)
            MusicPreferences(store).update { it.copy(themeMode = ThemeMode.LIGHT) }
            assertEquals(Res.string.backup_notice_restored, notice { player.backupController.restore(files) }.message)
            val summary = assertNotNull(player.backupController.status.value.summary)
            assertEquals(1 to true, summary.playlistsAdded to summary.settings)
            assertEquals(ThemeMode.DARK, player.state.value.preferences.themeMode)
            assertEquals(listOf("Road"), player.state.value.playlists.map { it.name })
            assertFalse(player.backupController.status.value.working)
            player.backupController.dismissSummary()
            assertNull(player.backupController.status.value.summary)

            player.backupController.restore(MemoryFiles("""{"format":"nevumi-backup","version":9,"createdAt":0}""")).join()
            assertEquals("This backup uses format 9, which this version of Nevumi cannot read. Update Nevumi and try again.", player.state.value.error)
            assertNull(player.backupController.status.value.summary)
        } finally { player.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
