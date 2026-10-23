package me.d3sox.nevumi.sftp

import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.contentHash
import me.d3sox.nevumi.navidrome.*
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.playlists.Playlists

class SftpMusicFileEditsTest {
    @Test
    fun deletingAServerFileMakesItsSourceUnavailableAndUndoRestoresItsBytesAndIdentity() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val music = root.resolve(".test-environment/sftp-music")
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val file = music.resolve("Fixture Artist/Fixture Album/01 Northern Light.flac")
        val original = root.resolve("androidApp/src/androidTest/assets/fixture.flac")
        val directory = Files.createTempDirectory("nevumi-sftp-delete")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        try {
            client.rescan()
            val library = MusicLibrary(store)
            val track = NavidromeChanges(store, library) { client }.refreshLibrary(client, includeFilePaths = true)
                .first { it.first.metadata.title == "Northern Light" }.first
            val songId = track.sources.single().location
            val profile = SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint")))
            val authentication = SftpAuthentication(privateKey = Files.readString(fixture.resolve("client")))
            val edits = SftpMusicFileEdits(store, library, client, profile, authentication, directory.resolve("backups").toString())
            val edit = edits.delete(track.id)
            // A deletion keeps the whole file, so it is checked by every byte rather than only by its tags.
            assertEquals(Files.newInputStream(original).use(::contentHash), edit.originalHash)
            assertEquals(false, Files.exists(file))
            assertEquals(false, library.findTrack(track.id)!!.sources.single().available)
            assertEquals(false, client.songs().any { it.id == songId })
            store.close(); store = openMusicStore(database)
            val reopened = MusicLibrary(store)
            val recovered = SftpMusicFileEdits(store, reopened, client, profile, authentication, directory.resolve("backups").toString())
            recovered.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
            assertEquals(true, reopened.findTrack(track.id)!!.sources.single().available)
            assertEquals("Northern Light", client.songs().first { it.id == songId }.metadata.title)
            assertEquals(3, reopened.tracks().size)
            assertEquals(true, recovered.history().single().undone)
            Unit
        } finally {
            Files.copy(original, file, StandardCopyOption.REPLACE_EXISTING)
            client.rescan()
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun serverRenameAndUndoKeepTheCanonicalSongFavoriteQueueAndPlaylist() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val music = root.resolve(".test-environment/sftp-music")
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val file = music.resolve("Fixture Artist/Fixture Album/01 Northern Light.flac")
        val renamed = file.parent.resolve("renamed.flac")
        val original = root.resolve("androidApp/src/androidTest/assets/fixture.flac")
        val directory = Files.createTempDirectory("nevumi-sftp-rename")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        var originalFavorite = false
        try {
            client.rescan()
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { client }
            val track = changes.refreshLibrary(client, includeFilePaths = true).first { it.first.metadata.title == "Northern Light" }.first
            val song = client.songs().first { it.metadata.title == "Northern Light" }
            originalFavorite = song.favorite
            client.setFavorite(song.id, true)
            changes.refreshLibrary(client, includeFilePaths = true)
            val queue = PlaybackQueue(store)
            queue.replace(listOf(track.id, track.id))
            val playlists = Playlists(store)
            val playlist = playlists.create("Keep this song", listOf(track.id))
            val profile = SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint")))
            val authentication = SftpAuthentication(privateKey = Files.readString(fixture.resolve("client")))
            val edits = SftpMusicFileEdits(store, library, client, profile, authentication, directory.resolve("backups").toString())
            val edit = edits.rename(track.id, "renamed.flac")
            assertEquals(false, Files.exists(file))
            assertEquals(-1L, Files.mismatch(original, renamed))
            assertEquals("Fixture Artist/Fixture Album/renamed.flac", client.songs(includeFilePaths = true).first { it.metadata.title == "Northern Light" }.filePath)
            assertEquals(3, library.tracks().size)
            assertEquals(true, library.findTrack(track.id)!!.favorite)
            assertEquals(listOf(track.id, track.id), queue.snapshot().entries.map { it.trackId })
            assertEquals(listOf(track.id), playlists.get(playlist.id)!!.trackIds)
            store.close(); store = openMusicStore(database)
            val reopened = MusicLibrary(store)
            val recovered = SftpMusicFileEdits(store, reopened, client, profile, authentication, directory.resolve("backups").toString())
            recovered.undo(edit.id)
            assertEquals(false, Files.exists(renamed))
            assertEquals(-1L, Files.mismatch(original, file))
            assertEquals(3, reopened.tracks().size)
            assertEquals(true, reopened.findTrack(track.id)!!.favorite)
            assertEquals(listOf(track.id, track.id), PlaybackQueue(store).snapshot().entries.map { it.trackId })
            assertEquals(listOf(track.id), Playlists(store).get(playlist.id)!!.trackIds)
            Unit
        } finally {
            if (Files.exists(renamed)) Files.delete(renamed)
            Files.copy(original, file, StandardCopyOption.REPLACE_EXISTING)
            client.rescan()
            client.songs().firstOrNull { it.metadata.title == "Northern Light" }?.let { client.setFavorite(it.id, originalFavorite) }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun serverTagsChangeTheActualFileAndNavidromeAndCanBeUndoneAfterRestart() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val music = root.resolve(".test-environment/sftp-music")
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val file = music.resolve("Fixture Artist/Fixture Album/01 Northern Light.flac")
        val original = root.resolve("androidApp/src/androidTest/assets/fixture.flac")
        val directory = Files.createTempDirectory("nevumi-sftp-tag-edit")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val client = NavidromeClient(http, "http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
        var favoriteId: String? = null
        var originalFavorite = false
        try {
            val library = MusicLibrary(store)
            val changes = NavidromeChanges(store, library) { client }
            val track = changes.refreshLibrary(client, includeFilePaths = true).first { it.first.metadata.title == "Northern Light" }.first
            val serverSong = client.songs().first { it.metadata.title == "Northern Light" }
            favoriteId = serverSong.id; originalFavorite = serverSong.favorite
            client.setFavorite(serverSong.id, true)
            changes.refreshLibrary(client, includeFilePaths = true)
            val profile = SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint")))
            val authentication = SftpAuthentication(privateKey = Files.readString(fixture.resolve("client")))
            val edits = SftpMusicFileEdits(store, library, client, profile, authentication, directory.resolve("backups").toString())
            val edit = edits.editTags(track.id, track.metadata.copy(title = "Edited on the server", year = 2026))
            assertEquals("Edited on the server", AudioFileIO.read(file.toFile()).tag.getFirst(FieldKey.TITLE))
            assertEquals("Edited on the server", client.songs().first { it.id == serverSong.id }.metadata.title)
            assertEquals("Edited on the server", library.findTrack(track.id)!!.metadata.title)
            assertEquals(true, library.findTrack(track.id)!!.favorite)
            store.close(); store = openMusicStore(database)
            val reopened = MusicLibrary(store)
            val recovered = SftpMusicFileEdits(store, reopened, client, profile, authentication, directory.resolve("backups").toString())
            recovered.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
            assertEquals("Northern Light", client.songs().first { it.id == serverSong.id }.metadata.title)
            assertEquals("Northern Light", reopened.findTrack(track.id)!!.metadata.title)
            assertEquals(true, recovered.history().single().undone)
            // Backups of only the tags count by their tag bytes rather than the song's length when the history is trimmed.
            recovered.editTags(track.id, track.metadata.copy(title = "First"))
            recovered.editTags(track.id, track.metadata.copy(title = "Second"))
            recovered.trimHistory(maxAgeMs = Long.MAX_VALUE, maxBytes = 40_000)
            assertEquals(3, recovered.history().size)
            Unit
        } finally {
            Files.copy(original, file, StandardCopyOption.REPLACE_EXISTING)
            favoriteId?.let { client.setFavorite(it, originalFavorite) }
            try { client.rescan() } catch (_: Exception) { }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }
}
