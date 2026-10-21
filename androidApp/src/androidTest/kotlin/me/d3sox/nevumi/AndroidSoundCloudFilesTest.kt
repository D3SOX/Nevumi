package me.d3sox.nevumi

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.library.AndroidLocalSources
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.soundcloud.MediaStoreSoundCloudFiles
import me.d3sox.nevumi.storage.openMusicStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidSoundCloudFilesTest {
    @Test
    fun aSoundCloudDownloadIsPublishedToMusicAndJoinsTheLibrary() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val id = UUID.randomUUID().toString()
        val audio = instrumentation.context.assets.open("fixture.mp3").use { it.readBytes() }
        val store = openMusicStore(context, "soundcloud-$id.db")
        var location: String? = null
        try {
            location = MediaStoreSoundCloudFiles(context).save("Fixture Artist - $id.mp3") { it.write(audio) }
            val uri = Uri.parse(location)
            requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.IS_PENDING,
                MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)).use {
                assertTrue(it.moveToFirst())
                assertEquals("Music/Nevumi/SoundCloud/", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(audio.size.toLong(), it.getLong(2))
                assertEquals("Fixture Artist - $id.mp3", it.getString(3))
            }
            val track = AndroidLocalSources(context, MusicLibrary(store)).scan(location).single()
            assertEquals(location, track.sources.single { it.kind == SourceKind.LOCAL }.location)
        } finally {
            store.close(); context.deleteDatabase("soundcloud-$id.db")
            location?.let { resolver.delete(Uri.parse(it), null, null) }
        }
    }
}
