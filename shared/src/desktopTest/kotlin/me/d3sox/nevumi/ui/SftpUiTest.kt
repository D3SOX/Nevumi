package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.LocalFileEdits
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.sftp.SftpConnection
import me.d3sox.nevumi.sftp.SftpProfile
import me.d3sox.nevumi.sftp.SftpAuthentication
import me.d3sox.nevumi.storage.openMusicStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import me.d3sox.nevumi.artwork.ArtworkLibrary
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import io.ktor.client.plugins.api.createClientPlugin
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SftpUiTest {
    @Test
    fun anInterruptedServerTagEditCanBeAppliedFromFileRecoveryAfterRestart() = recoverInterruptedServerEdit("Apply prepared edit")

    @Test
    fun anInterruptedServerTagEditCanBeDiscardedFromFileRecoveryAfterRestart() = recoverInterruptedServerEdit("Discard prepared edit")

    @Test
    fun aDamagedInterruptedServerEditCanRestoreItsOriginalBackupAfterRestart() = recoverInterruptedServerEdit("Restore original file")

    private fun recoverInterruptedServerEdit(action: String) {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val album = music.resolve("Fixture Artist/Fixture Album")
        val file = album.resolve("01 Northern Light.flac")
        val original = root.resolve("androidApp/src/androidTest/assets/fixture.flac")
        val permissions = Files.getPosixFilePermissions(album)
        val directory = Files.createTempDirectory("nevumi-sftp-prepared-recovery-ui")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        var controller: MusicController? = null
        var navidrome = NavidromeConnection(store, http, vault)
        try {
            var library = MusicLibrary(store)
            runBlocking { navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password")) }
            var files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
            runBlocking {
                files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint"))),
                    1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
                val track = library.tracks().first { it.metadata.title == "Northern Light" }
                Files.setPosixFilePermissions(album, permissions - java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
                kotlin.test.assertFailsWith<me.d3sox.nevumi.sftp.SftpFailure> {
                    files.editTags(track.id, track.metadata.copy(title = "Recovered server edit"))
                }
                assertEquals(-1L, Files.mismatch(original, file))
            }
            Files.setPosixFilePermissions(album, permissions)
            if (action == "Restore original file") Files.write(file, byteArrayOf(1, 2, 3, 4))
            store.close(); store = openMusicStore(database)
            library = MusicLibrary(store); navidrome = NavidromeConnection(store, http, vault)
            files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
            val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome, serverFiles = files)
            controller = player
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 5_000) { !player.state.value.loading && player.state.value.fileEdits.isNotEmpty() }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Files & recovery").performClick()
                onNodeWithText("Review interrupted edit").performClick()
                onNodeWithText(action).performClick()
                waitUntil(timeoutMillis = 8_000) { !player.state.value.loading &&
                    (player.state.value.fileEdits.isEmpty() || player.state.value.fileEdits.single().applied ||
                        player.state.value.fileEdits.single().undone || player.state.value.error != null) }
                assertEquals(null, player.state.value.error)
                val expected = if (action == "Apply prepared edit") "Recovered server edit" else "Northern Light"
                assertEquals(expected, AudioFileIO.read(file.toFile()).tag.getFirst(FieldKey.TITLE))
                kotlin.test.assertTrue(runBlocking { navidrome.client()!!.songs().any { it.metadata.title == expected } })
                if (action != "Apply prepared edit") assertEquals(-1L, Files.mismatch(original, file))
                if (action == "Restore original file") kotlin.test.assertTrue(runBlocking { files.history().single().undone })
                if (action == "Discard prepared edit") {
                    kotlin.test.assertTrue(runBlocking { files.history().isEmpty() })
                }
            }
        } finally {
            Files.setPosixFilePermissions(album, permissions)
            controller?.let { runBlocking { it.closeAndJoin() } }
            Files.copy(original, file, StandardCopyOption.REPLACE_EXISTING)
            runBlocking { navidrome.client()?.rescan() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun settingsCanRetryAnUploadAfterRestartWhenItsLocalSourceIsNoLongerAvailable() {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val relative = "Fixture Artist/Fixture Album/restart-upload.flac"
        val destination = music.resolve(relative)
        val directory = Files.createTempDirectory("nevumi-sftp-upload-retry-ui")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var failScan = false
        val http = HttpClient(OkHttp) {
            install(createClientPlugin("FixtureScanFailure") {
                onRequest { request, _ -> if (failScan && request.url.build().encodedPath.endsWith("/rest/startScan.view")) throw IOException("Fixture disconnected") }
            })
        }
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        var library = MusicLibrary(store)
        var navidrome = NavidromeConnection(store, http, vault)
        val local = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), directory.resolve("local.flac"))
        val track = runBlocking { LocalFiles(library).scan(local.toString()).single() }
        runBlocking { navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password")) }
        var files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
        runBlocking {
            files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint"))),
                1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            failScan = true
            kotlin.test.assertFailsWith<me.d3sox.nevumi.navidrome.NavidromeRequestException> { files.upload(track.id, relative) }
            Files.delete(local)
            LocalFiles(library).scan(local.toString())
        }
        store.close(); store = openMusicStore(database)
        library = MusicLibrary(store); navidrome = NavidromeConnection(store, http, vault)
        files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
        failScan = false
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome, serverFiles = files)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Files & recovery").performScrollTo().performClick()
                onNodeWithText("Server files (SFTP)").performScrollTo().performClick()
                onNodeWithText("Retry upload").performScrollTo().performClick()
                waitUntil(timeoutMillis = 8_000) { runBlocking { files.pendingUploads().isEmpty() } }
                val restored = runBlocking { library.findTrack(track.id) }!!
                assertEquals(relative, restored.sources.single { it.kind == me.d3sox.nevumi.library.SourceKind.SERVER }.filePath)
                assertEquals(false, restored.sources.single { it.kind == me.d3sox.nevumi.library.SourceKind.LOCAL }.available)
                assertEquals(-1L, Files.mismatch(root.resolve("androidApp/src/androidTest/assets/fixture.flac"), destination))
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            Files.deleteIfExists(destination)
            runBlocking { navidrome.client()!!.rescan(); files.disconnect() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun bulkServerTagEditingChangesMp3WavAndFlacFilesAndEachChangeCanBeUndone() {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val names = listOf("01 Northern Light.flac", "02 Long Way Home.mp3", "03 Night Walk.wav")
        val directory = Files.createTempDirectory("nevumi-sftp-bulk-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        val library = MusicLibrary(store)
        val navidrome = NavidromeConnection(store, http, vault)
        runBlocking { navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password")) }
        val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
        runBlocking { files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
            Files.readString(fixture.resolve("fingerprint"))), 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client")))) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome, serverFiles = files, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithContentDescription("Select all").performClick()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Edit server tags").performClick()
                onNodeWithText("Change album").performClick()
                onNode(hasText("Album") and hasSetTextAction()).performTextReplacement("Server bulk album")
                onNodeWithText("Change year").performScrollTo().performClick()
                onNode(hasText("Year") and hasSetTextAction()).performScrollTo().performTextReplacement("2026")
                onNodeWithText("Save changes").performClick()
                waitUntil(timeoutMillis = 15_000) { controller.state.value.fileEdits.count { it.applied && !it.undone } == 3 }
                assertEquals(setOf("Server bulk album"), runBlocking { navidrome.client()!!.songs() }.map { it.metadata.album }.toSet())
                assertEquals(setOf(2026), runBlocking { navidrome.client()!!.songs() }.map { it.metadata.year }.toSet())
                for (edit in controller.state.value.fileEdits.toList()) runBlocking { controller.undoFileEdit(edit.id).join() }
                assertEquals(true, controller.state.value.fileEdits.all { it.undone })
                for (name in names) assertEquals(-1L, Files.mismatch(root.resolve("androidApp/src/androidTest/assets/fixture.${name.substringAfterLast('.')}"),
                    music.resolve("Fixture Artist/Fixture Album/$name")))
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            for (name in names) Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.${name.substringAfterLast('.')}"),
                music.resolve("Fixture Artist/Fixture Album/$name"), StandardCopyOption.REPLACE_EXISTING)
            runBlocking { navidrome.client()!!.rescan(); files.disconnect() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun replacingAndUndoingServerArtworkUpdatesTheActualFileAndVisibleCover() {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val original = root.resolve("androidApp/src/androidTest/assets/fixture.flac")
        val audio = music.resolve("Fixture Artist/Fixture Album/01 Northern Light.flac")
        val directory = Files.createTempDirectory("nevumi-sftp-artwork-ui")
        fun image(color: Int): ByteArray {
            val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
            for (x in 0..31) for (y in 0..31) bitmap.setRGB(x, y, color)
            return ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
        }
        val red = image(0xE05040)
        val blue = image(0x3050E0)
        val redFile = directory.resolve("red.png").toFile().apply { writeBytes(red) }
        AudioFileIO.read(audio.toFile()).also { it.tag.setField(org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(redFile)); it.commit() }
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        val library = MusicLibrary(store)
        val navidrome = NavidromeConnection(store, http, vault)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        runBlocking { navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password")); navidrome.client()!!.rescan() }
        val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString(), artwork = artwork)
        runBlocking {
            files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(), Files.readString(fixture.resolve("fingerprint"))),
                1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            artwork.save(library.tracks().single { it.metadata.title == "Northern Light" }.id, red)
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome, artwork = artwork, serverFiles = files, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, artworkPicker = ArtworkPicker { blue }) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val track = controller.state.value.tracks.single { it.metadata.title == "Northern Light" }
                runBlocking { controller.playTrack(track.id, listOf(track.id)).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                fun coverColor(): Color = onNodeWithContentDescription("Album artwork: Northern Light").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFFE05040) }
                onNodeWithContentDescription("Collapse").performClick()
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Replace server artwork").performClick()
                waitUntil(timeoutMillis = 8_000) { controller.state.value.fileEdits.any { it.applied } }
                kotlin.test.assertTrue(AudioFileIO.read(audio.toFile()).tag.firstArtwork.binaryData.contentEquals(blue))
                onNodeWithTag("mini-player").performClick()
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFF3050E0) }
                onNodeWithContentDescription("Collapse").performClick()
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").performClick()
                waitUntil(timeoutMillis = 8_000) { controller.state.value.fileEdits.single().undone }
                kotlin.test.assertTrue(AudioFileIO.read(audio.toFile()).tag.firstArtwork.binaryData.contentEquals(red))
                onNodeWithTag("mini-player").performClick()
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFFE05040) }
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            Files.copy(original, audio, StandardCopyOption.REPLACE_EXISTING)
            runBlocking { navidrome.client()!!.rescan(); files.disconnect() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aLocalSongCanBeUploadedIntoNewServerFoldersFromItsTrackMenu() {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val destination = music.resolve("Uploaded Artist/Uploaded Album/upload.mp3")
        val directory = Files.createTempDirectory("nevumi-sftp-upload-ui")
        val local = Files.copy(root.resolve("androidApp/src/androidTest/assets/fixture.mp3"), directory.resolve("local.mp3"))
        val audio = AudioFileIO.read(local.toFile())
        audio.tag.deleteField(FieldKey.MUSICBRAINZ_TRACK_ID)
        audio.tag.deleteField(FieldKey.MUSICBRAINZ_RELEASEID)
        audio.tag.setField(FieldKey.TITLE, "Upload from Nevumi")
        audio.commit()
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        val library = MusicLibrary(store)
        val navidrome = NavidromeConnection(store, http, vault)
        val track = runBlocking { LocalFiles(library).scan(local.toString()).single() }
        runBlocking { navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password")) }
        val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
        runBlocking { files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
            Files.readString(fixture.resolve("fingerprint"))), 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client")))) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome,
            fileEdits = LocalFileEdits(store, library, directory.resolve("local-edits").toString()), serverFiles = files, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Upload from Nevumi").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Upload to server").performClick()
                onNode(hasText("Server filename") and hasSetTextAction()).performTextReplacement("Uploaded Artist/Uploaded Album/upload.mp3")
                onNodeWithText("Upload", substring = false).performClick()
                waitUntil(timeoutMillis = 8_000) {
                    controller.state.value.tracks.first { it.id == track.id }.sources.any { it.kind == me.d3sox.nevumi.library.SourceKind.SERVER }
                }
                assertEquals(-1L, Files.mismatch(local, destination))
                assertEquals(4, controller.state.value.tracks.size)
                onNodeWithText("Upload to server", substring = false).assertDoesNotExist()
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            Files.deleteIfExists(destination)
            if (Files.exists(destination.parent)) Files.delete(destination.parent)
            if (Files.exists(destination.parent.parent)) Files.delete(destination.parent.parent)
            runBlocking { navidrome.client()!!.rescan(); files.disconnect() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aMixedSongsServerFileCanBeRenamedDeletedAndRestoredWithoutChangingItsLocalCopy() {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val original = root.resolve("androidApp/src/androidTest/assets/fixture.flac")
        val audio = music.resolve("Fixture Artist/Fixture Album/01 Northern Light.flac")
        val renamed = audio.parent.resolve("server-renamed.flac")
        val directory = Files.createTempDirectory("nevumi-sftp-file-actions-ui")
        val local = Files.copy(original, directory.resolve("local.flac"))
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        val library = MusicLibrary(store)
        val navidrome = NavidromeConnection(store, http, vault)
        val localTrack = runBlocking { LocalFiles(library).scan(local.toString()).single() }
        runBlocking { navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password")) }
        val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
        runBlocking { files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
            Files.readString(fixture.resolve("fingerprint"))), 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client")))) }
        runBlocking {
            val serverTrack = library.tracks().single { it.metadata.title == "Northern Light" && it.sources.any { source -> source.kind == me.d3sox.nevumi.library.SourceKind.SERVER } }
            if (serverTrack.id != localTrack.id) library.linkTracks(localTrack.id, serverTrack.id)
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome,
            fileEdits = LocalFileEdits(store, library, directory.resolve("local-edits").toString()), serverFiles = files, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Rename server file").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodes(hasText("01 Northern Light.flac") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("File name") and hasSetTextAction()).performTextReplacement("server-renamed.flac")
                onNodeWithText("Rename", substring = false).performClick()
                waitUntil(timeoutMillis = 8_000) { controller.state.value.fileEdits.any { it.applied } }
                assertEquals(-1L, Files.mismatch(original, renamed))
                assertEquals(-1L, Files.mismatch(original, local))
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").performClick()
                waitUntil(timeoutMillis = 8_000) { controller.state.value.fileEdits.single().undone }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Delete server file").performClick()
                onNodeWithText("Delete server file?").assertIsDisplayed()
                assertEquals(-1L, Files.mismatch(original, audio))
                onNodeWithText("Delete", substring = false).performClick()
                waitUntil(timeoutMillis = 8_000) { controller.state.value.fileEdits.any { it.applied && !it.undone } }
                assertEquals(false, Files.exists(audio))
                assertEquals(-1L, Files.mismatch(original, local))
                onNodeWithText("Server file deleted").assertIsDisplayed()
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").performClick()
                waitUntil(timeoutMillis = 8_000) { controller.state.value.fileEdits.all { it.undone } }
                assertEquals(-1L, Files.mismatch(original, audio))
                assertEquals(-1L, Files.mismatch(original, local))
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            Files.deleteIfExists(renamed)
            Files.copy(original, audio, StandardCopyOption.REPLACE_EXISTING)
            runBlocking { navidrome.client()!!.rescan(); files.disconnect() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aServerSongsTagsCanBeEditedAndUndoneThroughItsTrackMenu() {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val music = root.resolve(".test-environment/sftp-music")
        val original = root.resolve("androidApp/src/androidTest/assets/fixture.flac")
        val audio = music.resolve("Fixture Artist/Fixture Album/01 Northern Light.flac")
        val directory = Files.createTempDirectory("nevumi-sftp-tag-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        val library = MusicLibrary(store)
        val navidrome = NavidromeConnection(store, http, vault)
        runBlocking { navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password")) }
        val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
        runBlocking { files.connect(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
            Files.readString(fixture.resolve("fingerprint"))), 1, SftpAuthentication(privateKey = Files.readString(fixture.resolve("client")))) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome, serverFiles = files, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Edit server tags").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodes(hasText("Northern Light") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("SFTP edited from Nevumi")
                onNodeWithText("Save changes").performClick()
                waitUntil(timeoutMillis = 8_000) { controller.state.value.fileEdits.any { it.applied } }
                assertEquals("SFTP edited from Nevumi", runBlocking { navidrome.client()!!.songs().first { it.metadata.title == "SFTP edited from Nevumi" }.metadata.title })
                onNodeWithContentDescription("Track options: SFTP edited from Nevumi").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").performClick()
                waitUntil(timeoutMillis = 8_000) { controller.state.value.fileEdits.single().undone }
                assertEquals(-1L, Files.mismatch(original, audio))
            }
        } finally {
            runBlocking { controller.closeAndJoin() }
            Files.copy(original, audio, StandardCopyOption.REPLACE_EXISTING)
            runBlocking { navidrome.client()!!.rescan(); files.disconnect() }
            http.close(); store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aVerifiedSftpConnectionCanBeConfiguredFromSettings() {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val fixture = root.resolve(".test-environment/sftp-fixture")
        val directory = Files.createTempDirectory("nevumi-sftp-settings-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        val library = MusicLibrary(store)
        val navidrome = NavidromeConnection(store, http, vault)
        runBlocking { navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password")) }
        val files = SftpConnection(store, library, navidrome::client, vault, directory.resolve("server-edits").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), navidrome,
            serverChanges = NavidromeChanges(store, library, client = navidrome::client), serverFiles = files)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Files & recovery").performScrollTo().performClick()
                onNodeWithText("Server files (SFTP)").performScrollTo().performClick()
                onNode(hasText("SFTP host") and hasSetTextAction()).performTextInput("127.0.0.1")
                onNode(hasText("SFTP port") and hasSetTextAction()).performTextReplacement("14534")
                onNode(hasText("SSH username") and hasSetTextAction()).performTextInput(System.getProperty("user.name"))
                onNode(hasText("Server music directory") and hasSetTextAction()).performScrollTo().performTextInput(root.resolve(".test-environment/sftp-music").toString())
                onNode(hasText("Host-key fingerprint") and hasSetTextAction()).performScrollTo().performTextInput(Files.readString(fixture.resolve("fingerprint")))
                onNode(hasText("SSH private key") and hasSetTextAction()).performScrollTo().performTextInput(Files.readString(fixture.resolve("client")))
                onNodeWithText("Connect SFTP").performClick()
                waitUntil(timeoutMillis = 8_000) { onAllNodesWithText("SFTP connected").fetchSemanticsNodes().isNotEmpty() }
                assertEquals(1, runBlocking { files.configuration()!!.libraryId })
                assertEquals("127.0.0.1", runBlocking { files.configuration()!!.connection.host })
                onNodeWithText("SFTP connected").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin(); files.disconnect() }; http.close(); store.close(); directory.toFile().deleteRecursively() }
    }
}
