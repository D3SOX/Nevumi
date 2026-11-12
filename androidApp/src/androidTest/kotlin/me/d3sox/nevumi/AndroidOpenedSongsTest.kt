package me.d3sox.nevumi

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidOpenedSongsTest {
    @Test
    fun anOpenedSongIsIndexedUnderItsMediaStoreEntryAndStaysAvailableInAnExcludedFolder() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} $permission")).use { it.readBytes() }
        val id = UUID.randomUUID().toString()
        val folder = "Music/Nevumi-opened-$id"
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "opened-$id.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val name = "opened-$id.db"
        val store = openMusicStore(context, name)
        try {
            instrumentation.context.assets.open("fixture.flac").use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            val library = MusicLibrary(store)
            MusicPreferences(store).update { it.copy(excludeFolders = listOf(folder)) }
            val sources = AndroidLocalSources(context, library)
            val mediaId = ContentUris.parseId(uri)
            val indexed = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId).toString()
            // Apps link the same song through a volume's audio collection or the files collection.
            listOf(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), mediaId),
                ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), mediaId)).forEach { link ->
                assertEquals(link.toString(), indexed, sources.openedSong(link.toString()))
            }
            assertEquals(1, sources.scanSong(indexed).size)
            sources.scan("device")
            assertTrue(library.tracks().flatMap { it.sources }.single { it.location == indexed }.available)
        } finally { store.close(); context.deleteDatabase(name); resolver.delete(uri, null, null) }
    }
}
