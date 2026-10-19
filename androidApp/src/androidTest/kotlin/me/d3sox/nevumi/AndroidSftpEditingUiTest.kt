package me.d3sox.nevumi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.sftp.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties
import java.util.UUID
import android.content.ContentValues
import android.provider.MediaStore
import me.d3sox.nevumi.library.SourceKind

@RunWith(AndroidJUnit4::class)
class AndroidSftpEditingUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test
    fun theProductionAppCanUploadAnOriginalMediaStoreSongAndKeepItsCanonicalIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val properties = Properties().apply { File("/data/local/tmp/nevumi-sftp-fixture.properties").inputStream().use { load(it) } }
        val profile = SftpProfile("127.0.0.1", 14534, properties.getProperty("username"), properties.getProperty("editableMusicDirectory"),
            properties.getProperty("fingerprint"))
        val authentication = SftpAuthentication(privateKey = File("/data/local/tmp/nevumi-sftp-client").readText())
        val remote = SftpFiles(profile, authentication)
        val id = UUID.randomUUID().toString()
        val relative = "Android uploads/$id/song.mp3"
        val bytes = instrumentation.context.assets.open("fixture.mp3").use { it.readBytes() }
        val resolver = app.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "upload-$id.mp3")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-sftp-upload-$id")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val received = File(app.cacheDir, "uploaded-$id.mp3")
        try {
            app.controller.connectServer("http://127.0.0.1:14536", "fixture", "fixture-password").join()
            assertNull(app.controller.state.value.error)
            app.controller.connectServerFiles(profile, 1, authentication)
            app.controller.importSource(uri.toString()).join()
            assertNull(app.controller.state.value.error)
            val track = app.controller.state.value.tracks.single { it.sources.any { source -> source.location == uri.toString() } }
            app.controller.setFavorite(track.id, true).join()
            app.controller.uploadTrack(track.id, relative).join()
            assertNull(app.controller.state.value.error)
            val uploaded = app.controller.state.value.tracks.single { it.id == track.id }
            assertTrue(uploaded.favorite)
            // The fixture file is already on the server, so the song can also link that identical server copy.
            assertEquals(1, uploaded.sources.count { it.kind == SourceKind.SERVER && it.filePath == relative })
            assertEquals(uri.toString(), uploaded.sources.single { it.kind == SourceKind.LOCAL }.location)
            remote.download(relative, received)
            assertArrayEquals(bytes, received.readBytes())
            Unit
        } finally {
            if (remote.exists(relative)) remote.delete(relative)
            received.delete()
            resolver.delete(uri, null, null)
            app.controller.disconnectServerFiles()
            app.controller.refreshServer().join()
        }
    }

    @Test
    fun theProductionTrackMenuEditsAndRestoresTheActualNavidromeFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val properties = Properties().apply { File("/data/local/tmp/nevumi-sftp-fixture.properties").inputStream().use { load(it) } }
        val profile = SftpProfile("127.0.0.1", 14534, properties.getProperty("username"), properties.getProperty("editableMusicDirectory"),
            properties.getProperty("fingerprint"))
        val authentication = SftpAuthentication(privateKey = File("/data/local/tmp/nevumi-sftp-client").readText())
        val remote = SftpFiles(profile, authentication)
        val relative = "Fixture Artist/Fixture Album/01 Northern Light.flac"
        val original = File(app.cacheDir, "sftp-original-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> original.outputStream().use { input.copyTo(it) } }
        var appliedId: String? = null
        try {
            runBlocking {
                app.controller.connectServer("http://127.0.0.1:14536", "fixture", "fixture-password").join()
                assertNull(app.controller.state.value.error)
                app.controller.connectServerFiles(profile, 1, authentication)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { !app.controller.state.value.loading }
                val track = app.controller.state.value.tracks.single { it.metadata.title == "Northern Light" }
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-pager")))
                    .performScrollToNode(hasTestTag("track-row:${track.id}"))
                compose.onNodeWithContentDescription("Track options: Northern Light").performClick()
                compose.onNodeWithText("File actions").performClick()
                compose.onNodeWithText("Edit server tags").performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Northern Light") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Edited by Android Nevumi")
                compose.onNodeWithText("Save changes").performClick()
                compose.waitUntil(15_000) { app.controller.state.value.fileEdits.any { edit -> edit.trackId == track.id && edit.applied } }
                appliedId = app.controller.state.value.fileEdits.single { edit -> edit.trackId == track.id }.id
                assertNull(app.controller.state.value.error)
                assertEquals("Edited by Android Nevumi", app.controller.state.value.tracks.single { song -> song.id == track.id }.metadata.title)
                compose.onNodeWithContentDescription("Track options: Edited by Android Nevumi").performClick()
                compose.onNodeWithText("File actions").performClick()
                compose.onNodeWithText("Undo file edit").performClick()
                compose.waitUntil(15_000) { app.controller.state.value.fileEdits.single { edit -> edit.id == appliedId }.undone }
                val received = File(app.cacheDir, "sftp-restored-${UUID.randomUUID()}.flac")
                try { remote.download(relative, received); assertArrayEquals(original.readBytes(), received.readBytes()) }
                finally { received.delete() }
            }
        } finally {
            runBlocking {
                appliedId?.let { id ->
                    if (app.controller.state.value.fileEdits.any { it.id == id && !it.undone }) app.controller.undoFileEdit(id).join()
                }
                app.controller.disconnectServerFiles()
            }
            // Fixture-only fallback also restores bytes when an assertion interrupts an edit.
            remote.replace(relative, original, remote.hash(relative))
            original.delete()
            runBlocking { app.controller.refreshServer().join() }
        }
    }
}
