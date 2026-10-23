package me.d3sox.nevumi.lyrics

import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackSource
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTagField
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class FileLyricsTest {
    private val fixtures = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets")

    private fun copy(directory: Path, fixture: String, name: String): File =
        fixtures.resolve(fixture).toFile().copyTo(directory.resolve(name).toFile())

    private fun writeLyrics(file: File, change: (org.jaudiotagger.tag.Tag) -> Unit) {
        val audio = AudioFileIO.read(file)
        change(audio.tagOrCreateAndSetDefault)
        audio.commit()
    }

    @Test fun readsSyncedLyricsFromMp3UsltAndPlainLyricsFromFlac() = runBlocking {
        val directory = Files.createTempDirectory("embedded-lyrics")
        try {
            val mp3 = copy(directory, "fixture.mp3", "song.mp3")
            writeLyrics(mp3) { it.setField(FieldKey.LYRICS, "[00:01.00]Hello\n[00:02.50]World") }
            val flac = copy(directory, "fixture.flac", "song.flac")
            writeLyrics(flac) { it.setField(FieldKey.LYRICS, "Plain words\nSecond line") }
            val files = FileLyrics()
            val synced = assertNotNull(files.embedded(TrackSource("local:mp3", SourceKind.LOCAL, mp3.path)))
            assertEquals(LyricsSource.EMBEDDED, synced.source)
            assertEquals(listOf(LyricLine(1_000, "Hello"), LyricLine(2_500, "World")), synced.lines)
            val plain = assertNotNull(files.embedded(TrackSource("download:flac", SourceKind.DOWNLOAD, flac.path)))
            assertEquals("Plain words\nSecond line", plain.plain)
            // A server source's path lives on the server, never on this device.
            assertNull(files.embedded(TrackSource("server:x:1", SourceKind.SERVER, "1", filePath = flac.path)))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun readsTheVorbisUnsyncedLyricsFieldAndContentThroughACopy() = runBlocking {
        val directory = Files.createTempDirectory("unsynced-lyrics")
        try {
            val flac = copy(directory, "fixture.flac", "song.flac")
            writeLyrics(flac) { (it as FlacTag).vorbisCommentTag.setField(VorbisCommentTagField("UNSYNCEDLYRICS", "[00:03.00]Vorbis line")) }
            assertEquals(listOf(LyricLine(3_000, "Vorbis line")),
                FileLyrics().embedded(TrackSource("local:flac", SourceKind.LOCAL, flac.path))?.lines)
            var opened: String? = null
            val content = FileLyrics { opened = it; flac.inputStream() }
            val source = TrackSource("local:content", SourceKind.LOCAL, "content://media/external/audio/media/7",
                filePath = "/storage/emulated/0/Music/missing/song.flac")
            assertEquals(3_000, content.embedded(source)?.lines?.single()?.startMs)
            assertEquals(source.location, opened)
            assertNull(content.sidecar(source))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun prefersAnLrcSidecarOverATextOne() = runBlocking {
        val directory = Files.createTempDirectory("sidecar-lyrics")
        try {
            val flac = copy(directory, "fixture.flac", "01 Northern Light.flac")
            val source = TrackSource("local:flac", SourceKind.LOCAL, flac.path)
            val files = FileLyrics()
            assertNull(files.embedded(source))
            assertNull(files.sidecar(source))
            directory.resolve("01 Northern Light.txt").toFile().writeText("Text lyrics")
            assertEquals(Lyrics(LyricsSource.SIDECAR, plain = "Text lyrics"), files.sidecar(source))
            directory.resolve("01 Northern Light.lrc").toFile().writeText("[ti:Northern Light]\n[00:04.20]Timed")
            assertEquals(Lyrics(LyricsSource.SIDECAR, listOf(LyricLine(4_200, "Timed"))), files.sidecar(source))
        } finally { directory.toFile().deleteRecursively() }
    }
}
