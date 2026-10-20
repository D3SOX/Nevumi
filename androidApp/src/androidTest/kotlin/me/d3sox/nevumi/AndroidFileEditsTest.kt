package me.d3sox.nevumi

import android.Manifest
import android.content.ContentValues
import android.media.MediaPlayer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidFileEditsTest {
    @Before
    fun waitForMediaStore() {
        InstrumentationRegistry.getInstrumentation().awaitMediaStoreIdle()
    }

    @Test
    fun aRetainedBackupRestoresMusicEvenAfterAndroidRemovesTheTrashedFile() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val folder = "Music/Nevumi-expired-trash-$id/"
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val database = "expired-trash-$id.db"
        var store = openMusicStore(context, database)
        val backups = File(context.filesDir, "expired-trash-$id")
        var restoredUri: android.net.Uri? = null
        var originalRemoved = false
        try {
            val library = MusicLibrary(store)
            val track = AndroidLocalSources(context, library).scan(uri.toString()).single()
            library.setFavorite(track.id, true)
            val edit = AndroidFileEdits(context, store, library, backups.path).delete(track.id)
            resolver.delete(uri, android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) })
            originalRemoved = true
            store.close(); store = openMusicStore(context, database)
            val reopened = MusicLibrary(store)
            val edits = AndroidFileEdits(context, store, reopened, backups.path)
            edits.undo(edit.id)
            val restored = reopened.findTrack(track.id)!!
            restoredUri = android.net.Uri.parse(restored.sources.single().location)
            assertEquals(listOf(track.id), reopened.tracks().map { it.id })
            assertEquals(true, restored.favorite)
            assertEquals(true, restored.sources.single().available)
            assertTrue(requireNotNull(resolver.openInputStream(restoredUri!!)).use { it.readBytes() }.contentEquals(bytes))
            requireNotNull(resolver.query(restoredUri!!, arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)).use {
                check(it.moveToFirst()); assertEquals(folder, it.getString(0)); assertEquals("fixture.flac", it.getString(1))
            }
        } finally {
            store.close(); context.deleteDatabase(database); backups.deleteRecursively()
            restoredUri?.let { resolver.delete(it, null, null) }
            if (!originalRemoved) resolver.delete(uri, android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) })
        }
    }

    @Test
    fun aMissingDeviceFileIsReportedWhileOtherSelectedSongsAreStillSaved() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val uris = listOf("flac", "mp3", "wav").map { extension ->
            val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.$extension")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-partial-$id")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }))
            instrumentation.context.assets.open("fixture.$extension").use { input -> requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        }
        val database = "partial-edits-$id.db"
        val store = openMusicStore(context, database)
        val backups = File(context.filesDir, "partial-edits-$id")
        var missingRemoved = false
        try {
            val library = MusicLibrary(store)
            val tracks = uris.map { AndroidLocalSources(context, library).scan(it.toString()).single() }
            resolver.delete(uris[1], null, null)
            missingRemoved = true
            val edits = AndroidFileEdits(context, store, library, backups.path)
            val failure = runCatching { edits.editTags(tracks.map { it.id }, TagChanges(album = "Shared album", year = 2026)) }.exceptionOrNull()
            assertTrue("A batch failure must describe all completed edits: $failure", failure is java.io.IOException)
            assertTrue(failure!!.message!!.contains("Long Way Home"))
            assertEquals(2, edits.history().count { it.applied })
            for (track in listOf(tracks[0], tracks[2])) {
                assertEquals("Shared album", library.findTrack(track.id)!!.metadata.album)
                assertEquals(2026, library.findTrack(track.id)!!.metadata.year)
            }
        } finally {
            store.close(); context.deleteDatabase(database); backups.deleteRecursively()
            uris.filter { !missingRemoved || it != uris[1] }.forEach { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun deviceMusicCanBeTrashedAndRestoredAfterRestartWithoutLosingItsFavorite() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-delete-$id")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        fun trashed() = requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.IS_TRASHED),
            android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }, null)).use {
            check(it.moveToFirst()); it.getInt(0)
        }
        val database = "delete-$id.db"
        var store = openMusicStore(context, database)
        val backups = File(context.filesDir, "delete-$id")
        try {
            val library = MusicLibrary(store)
            val track = AndroidLocalSources(context, library).scan(uri.toString()).single()
            library.setFavorite(track.id, true)
            val edit = AndroidFileEdits(context, store, library, backups.path).delete(track.id)
            assertEquals(1, trashed())
            assertEquals(false, library.findTrack(track.id)!!.sources.single().available)
            store.close(); store = openMusicStore(context, database)
            val restoredLibrary = MusicLibrary(store)
            val restoredEdits = AndroidFileEdits(context, store, restoredLibrary, backups.path)
            restoredEdits.recover(); restoredEdits.undo(edit.id)
            assertEquals(0, trashed())
            assertEquals(uri.toString(), restoredLibrary.findTrack(track.id)!!.sources.single().location)
            assertEquals(true, restoredLibrary.findTrack(track.id)!!.favorite)
            assertEquals(true, restoredLibrary.findTrack(track.id)!!.sources.single().available)
            assertTrue(requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }.contentEquals(bytes))
        } finally {
            // Include trashed rows when removing this test's own fixture.
            resolver.delete(uri, android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) })
            store.close(); context.deleteDatabase(database); backups.deleteRecursively()
        }
    }

    @Test
    fun editingAnAppPrivateMusicFileRefreshesItsTagsAndCanBeUndone() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "private-music-$id").apply { mkdirs() }
        val file = File(directory, "song.flac")
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        file.writeBytes(bytes)
        val database = "private-edits-$id.db"
        val store = openMusicStore(context, database)
        try {
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.path).single()
            val edits = AndroidFileEdits(context, store, library, File(directory, "backups").path)
            val metadata = track.metadata.copy(album = "Private edited album")
            val edit = edits.editTags(track.id, metadata)
            assertEquals(metadata, library.findTrack(track.id)!!.metadata)
            edits.undo(edit.id)
            assertEquals(track.metadata, library.findTrack(track.id)!!.metadata)
            assertTrue(file.readBytes().contentEquals(bytes))
        } finally { store.close(); context.deleteDatabase(database); directory.deleteRecursively() }
    }

    @Test
    fun editingOneSongRefreshesThatFileWithoutRescanningTheWholeDevice() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} $permission")).use { it.readBytes() }
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        fun insert(name: String): android.net.Uri {
            val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-targeted-$id")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }))
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        val edited = insert("edited.flac")
        var unrelated: android.net.Uri? = null
        val database = "targeted-edit-$id.db"
        val store = openMusicStore(context, database)
        val backups = File(context.filesDir, "targeted-edit-$id")
        try {
            val library = MusicLibrary(store)
            val track = AndroidLocalSources(context, library).scan("device").single { it.sources.any { source -> source.location == edited.toString() } }
            unrelated = insert("new-unrelated.flac")
            val metadata = track.metadata.copy(album = "Edited album")
            AndroidFileEdits(context, store, library, backups.path).editTags(track.id, metadata)
            assertEquals(metadata, library.findTrack(track.id)!!.metadata)
            assertEquals(false, library.tracks().any { track -> track.sources.any { it.location == unrelated.toString() } })
            assertEquals(listOf("device"), AndroidLocalSources(context, library).roots())
        } finally {
            store.close(); context.deleteDatabase(database); backups.deleteRecursively()
            resolver.delete(edited, null, null); unrelated?.let { resolver.delete(it, null, null) }
        }
    }

    @Test
    fun deviceMusicCanBeRenamedAndRestoredWithoutChangingItsUriOrFavorite() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} $permission")).use { it.readBytes() }
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-rename-$id")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val database = "rename-$id.db"
        var store = openMusicStore(context, database)
        val backups = File(context.filesDir, "rename-$id")
        fun name() = requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)).use {
            check(it.moveToFirst()); it.getString(0)
        }
        try {
            val library = MusicLibrary(store)
            val track = AndroidLocalSources(context, library).scan("device").single { it.sources.any { source -> source.location == uri.toString() } }
            library.setFavorite(track.id, true)
            val edit = AndroidFileEdits(context, store, library, backups.path).rename(track.id, "A renamed song.flac")
            assertEquals("A renamed song.flac", name())
            assertEquals(uri.toString(), library.findTrack(track.id)!!.sources.single().location)
            assertEquals(true, library.findTrack(track.id)!!.favorite)
            store.close(); store = openMusicStore(context, database)
            val restoredLibrary = MusicLibrary(store)
            val restoredEdits = AndroidFileEdits(context, store, restoredLibrary, backups.path)
            restoredEdits.recover(); restoredEdits.undo(edit.id)
            assertEquals("fixture.flac", name())
            assertEquals(true, restoredLibrary.findTrack(track.id)!!.favorite)
            assertTrue(requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }.contentEquals(bytes))
        } finally { store.close(); context.deleteDatabase(database); resolver.delete(uri, null, null); backups.deleteRecursively() }
    }

    @Test
    fun deviceMusicTagsAreWrittenToTheActualFileAndCanBeUndone() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} $permission")).use { it.readBytes() }
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val originals = listOf("fixture.flac", "fixture.mp3", "fixture.wav").associateWith { name ->
            instrumentation.context.assets.open(name).use { it.readBytes() }
        }
        val uris = originals.map { (name, bytes) ->
            val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-edits-$id")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }))
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            name to uri
        }
        val database = "file-edits-$id.db"
        val store = openMusicStore(context, database)
        val backups = File(context.filesDir, "file-edits-$id")
        try {
            val library = MusicLibrary(store)
            AndroidLocalSources(context, library).scan("device")
            val edits = AndroidFileEdits(context, store, library, backups.path)
            for ((name, uri) in uris) {
                val track = library.tracks().single { it.sources.any { source -> source.location == uri.toString() } }
                library.setFavorite(track.id, true)
                val metadata = track.metadata.copy(title = "Edited $name", artist = "Edited artist",
                    albumArtist = "Various artists", genres = listOf("Jazz", "Ambient"), discNumber = 2, year = 2026)
                val edit = edits.editTags(track.id, metadata)
                AndroidLocalSources(context, library).scan("device")
                val reread = library.findTrack(track.id)!!
                assertEquals(metadata, reread.metadata)
                assertEquals(true, reread.favorite)
                val player = MediaPlayer()
                try { player.setDataSource(context, uri); player.prepare(); assertTrue(player.duration in 2_900..3_100) }
                finally { player.release() }
                edits.undo(edit.id)
                assertTrue(requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }.contentEquals(originals.getValue(name)))
            }
        } finally {
            store.close(); context.deleteDatabase(database)
            uris.forEach { (_, uri) -> resolver.delete(uri, null, null) }
            backups.deleteRecursively()
        }
    }
}
