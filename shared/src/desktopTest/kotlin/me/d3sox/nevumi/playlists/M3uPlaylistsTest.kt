package me.d3sox.nevumi.playlists

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class M3uPlaylistsTest {
    @Test
    fun aPlaylistWithNoProviderFolderResolvesUniqueIndexedPathsAndUrisWithoutGuessingAmbiguousNames() = runBlocking {
        val directory = Files.createTempDirectory("provider-m3u")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val a = library.importTrack(TrackMetadata("Song A"), TrackSource("local:42", SourceKind.LOCAL,
                "content://media/external/audio/media/42", filePath = "/storage/emulated/0/Music/Album A/song.flac"))
            library.importTrack(TrackMetadata("Song B"), TrackSource("local:43", SourceKind.LOCAL,
                "content://media/external/audio/media/43", filePath = "/storage/emulated/0/Music/Album B/song.flac"))
            val night = library.importTrack(TrackMetadata("Night Walk"), TrackSource("local:44", SourceKind.LOCAL,
                "content://media/external/audio/media/44", filePath = "/storage/emulated/0/Music/Night Walk.wav"))
            val result = M3uPlaylists(library, Playlists(store)).import("From Downloads",
                "#EXTM3U\nMusic/Album A/song.flac\nsong.flac\n./Night Walk.wav\ncontent://media/external/audio/media/42\n../Music/Album B/song.flac\n")
            assertEquals(listOf(a.id, night.id, a.id), result.playlist.trackIds)
            assertEquals(listOf("song.flac", "../Music/Album B/song.flac"), result.unresolved)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aDevicePlaylistCanReferToAFilePathWhilePlaybackKeepsItsMediaStoreUri() = runBlocking {
        val directory = Files.createTempDirectory("device-m3u")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val path = "/storage/emulated/0/Music/Northern Light.flac"
            val source = TrackSource("local:content://media/external/audio/media/42", SourceKind.LOCAL,
                "content://media/external/audio/media/42", filePath = path)
            val song = library.importTrack(TrackMetadata("Northern Light"), source)
            val files = M3uPlaylists(library, Playlists(store))
            val imported = files.import("Device", "#EXTM3U\nfile:///storage/emulated/0/Music/Northern%20Light.flac\n")
            assertEquals(listOf(song.id), imported.playlist.trackIds)
            assertTrue(path in files.export(imported.playlist.id))
            assertEquals(source.location, SourceResolver.resolve(library.findTrack(song.id)!!.sources)!!.location)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun exportingPrefersAnAvailableCopyOverAMissingLocalFile() = runBlocking {
        val directory = Files.createTempDirectory("available-m3u")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val song = library.importTrack(TrackMetadata("Moved"), TrackSource("local:moved", SourceKind.LOCAL, "/music/moved.flac", available = false))
            library.importTrack(song.metadata, TrackSource("download:moved", SourceKind.DOWNLOAD, "/downloads/moved.flac"), linkTo = song.id)
            val missing = library.importTrack(TrackMetadata("Gone"), TrackSource("local:gone", SourceKind.LOCAL, "/music/gone.flac", available = false))
            val exported = M3uPlaylists(library, Playlists(store)).exportTracks(listOf(song.id, missing.id)).lines()
            assertTrue("/downloads/moved.flac" in exported)
            assertTrue("/music/moved.flac" !in exported)
            // With no available copy, the export still names the song's file so the playlist keeps its entry.
            assertTrue("/music/gone.flac" in exported)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun importingAndExportingAMixedM3uPreservesOrderRepeatsAndReportsMissingSongs() = runBlocking {
        val directory = Files.createTempDirectory("m3u-playlist")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val local = library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", durationMs = 180_000),
                TrackSource("local:song", SourceKind.LOCAL, "/music/Evening/01 Northern Light.flac"))
            val server = library.importTrack(TrackMetadata("Night Walk", "Fixture Artist", durationMs = 200_000),
                TrackSource("server:test-server:opaque-song", SourceKind.SERVER, "opaque-song"))
            val playlists = Playlists(store)
            val files = M3uPlaylists(library, playlists)
            val result = files.import("Evening", "\uFEFF#EXTM3U\r\n#EXTINF:180,Fixture Artist - Northern Light\r\n01 Northern Light.flac\r\nnavidrome://test-server/opaque-song\r\n01 Northern Light.flac\r\nmissing.flac\r\n", "/music/Evening")
            assertEquals(listOf(local.id, server.id, local.id), result.playlist.trackIds)
            assertEquals(listOf("missing.flac"), result.unresolved)
            val exported = files.export(result.playlist.id)
            assertTrue(exported.startsWith("#EXTM3U\n"))
            assertTrue("#EXTINF:180,Fixture Artist - Northern Light\n" in exported)
            assertTrue("navidrome://test-server/opaque-song\n" in exported)
            val reopened = files.import("Imported again", exported)
            assertEquals(result.playlist.trackIds, reopened.playlist.trackIds)
            assertEquals(emptyList(), reopened.unresolved)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
