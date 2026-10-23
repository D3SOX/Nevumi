package me.d3sox.nevumi.sftp

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import me.d3sox.nevumi.library.contentHash
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SftpFilesTest {
    @Test
    fun anUploadDestinationInAMissingMusicFolderIsReportedAsAbsent() {
        val directory = Files.createTempDirectory("nevumi-sftp-missing-destination")
        try {
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "sftp-fixture")
            val files = SftpFiles(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), directory.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            assertEquals(false, files.exists("New Artist/New Album/song.flac"))
            assertEquals(false, Files.exists(directory.resolve("New Artist")))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun anUploadCanCreateMusicFoldersButCannotTraverseASymbolicLink() {
        val directory = Files.createTempDirectory("nevumi-sftp-folders")
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val outside = Files.createDirectory(directory.resolve("outside"))
            Files.createSymbolicLink(music.resolve("linked"), outside)
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "sftp-fixture")
            val original = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            val files = SftpFiles(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            files.upload("New Artist/New Album/song.flac", original.toFile())
            assertEquals(-1L, Files.mismatch(original, music.resolve("New Artist/New Album/song.flac")))
            assertFailsWith<IllegalArgumentException> { files.upload("linked/Album/song.flac", original.toFile()) }
            assertEquals(emptyList(), outside.toFile().list()!!.toList())
            assertFailsWith<IllegalArgumentException> { files.upload("../outside/song.flac", original.toFile()) }
            Unit
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun anUploadBecomesACompleteMusicFileWithoutOverwritingAnExistingSong() {
        val directory = Files.createTempDirectory("nevumi-sftp-upload")
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            Files.createDirectories(music.resolve("Artist/Album"))
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "sftp-fixture")
            val original = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            val files = SftpFiles(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), music.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            files.upload("Artist/Album/song.flac", original.toFile())
            assertEquals(-1L, Files.mismatch(original, music.resolve("Artist/Album/song.flac")))
            val collision = assertFailsWith<SftpFailure> { files.upload("Artist/Album/song.flac", original.toFile()) }
            assertEquals(SftpFailure.Kind.CONFLICT, collision.kind)
            assertEquals(-1L, Files.mismatch(original, music.resolve("Artist/Album/song.flac")))
            assertEquals(listOf("song.flac"), music.resolve("Artist/Album").toFile().list()!!.sorted())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun aReplacementKeepsAChangedOrMissingOriginalUntouched() {
        val directory = Files.createTempDirectory("nevumi-sftp-replace")
        try {
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "sftp-fixture")
            val original = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            val edited = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.mp3")
            Files.copy(original, directory.resolve("song.flac"))
            val files = SftpFiles(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), directory.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            val conflict = assertFailsWith<SftpFailure> { files.replace("song.flac", edited.toFile(), "tags:" + "0".repeat(64)) }
            assertEquals(SftpFailure.Kind.CONFLICT, conflict.kind)
            assertEquals(-1L, Files.mismatch(original, directory.resolve("song.flac")))
            assertEquals(listOf("song.flac"), directory.toFile().list()!!.toList())
            files.replace("song.flac", edited.toFile(), files.fingerprint("song.flac"))
            assertEquals(-1L, Files.mismatch(edited, directory.resolve("song.flac")))
            val expected = files.fingerprint("song.flac")
            val missing = assertFailsWith<SftpFailure> { files.replace("missing.flac", edited.toFile(), expected) }
            assertEquals(SftpFailure.Kind.FILE_OPERATION, missing.kind)
            assertEquals(listOf("song.flac"), directory.toFile().list()!!.toList())
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun theStepsOfASessionShareOneConnection() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-sftp-session")
        val connections = AtomicInteger()
        val proxy = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!proxy.isClosed) {
                val client = try { proxy.accept() } catch (_: SocketException) { break }
                connections.incrementAndGet()
                val server = Socket("127.0.0.1", 14534)
                for ((from, to) in listOf(client to server, server to client)) thread(isDaemon = true) {
                    try { from.getInputStream().copyTo(to.getOutputStream()) } catch (_: IOException) { }
                    runCatching { to.shutdownOutput() }
                }
            }
        }
        try {
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "sftp-fixture")
            val original = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            Files.copy(original, directory.resolve("song.flac"))
            val files = SftpFiles(SftpProfile("127.0.0.1", proxy.localPort, System.getProperty("user.name"), directory.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            files.session {
                assertEquals(true, files.exists("song.flac"))
                files.replace("song.flac", original.toFile(), files.fingerprint("song.flac"))
                files.download("song.flac", directory.resolve("received.flac").toFile())
            }
            assertEquals(1, connections.get())
            assertEquals(true, files.exists("song.flac"))
            assertEquals(true, files.exists("received.flac"))
            assertEquals(3, connections.get())
            // Saving soon after the tag editor opened reuses the editor's connection.
            files.session { assertEquals(true, files.exists("song.flac")) }
            assertEquals(3, connections.get())
            // Disconnecting closes the kept connection, so the next session connects again.
            files.close()
            files.session { assertEquals(true, files.exists("song.flac")) }
            assertEquals(4, connections.get())
        } finally { proxy.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aTagEditSendsOnlyTheTagsWhileTheServerCopiesTheAudio() {
        val directory = Files.createTempDirectory("nevumi-sftp-tags")
        val traffic = AtomicLong()
        val proxy = proxy(traffic)
        try {
            val assets = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets")
            val files = SftpFiles(SftpProfile("127.0.0.1", proxy.localPort, System.getProperty("user.name"), directory.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            for (name in listOf("waveform-long.flac", "waveform-four-minutes.mp3", "fixture.wav")) {
                val extension = name.substringAfterLast('.')
                Files.copy(assets.resolve(name), directory.resolve("song.$extension"))
                val expected = Files.copy(assets.resolve(name), directory.resolve("expected.$extension")).toFile()
                val image = directory.resolve("image.$extension").toFile()
                // The year fits the existing room, while the long comment moves the audio further into the file.
                for ((field, value) in listOf(FieldKey.YEAR to "2026", FieldKey.COMMENT to "x".repeat(100_000))) {
                    val before = traffic.get()
                    files.image("song.$extension", image)
                    assertEquals(expected.length(), image.length())
                    assertEquals(AudioFileIO.read(expected).tag?.getFirst(FieldKey.TITLE), AudioFileIO.read(image).tag?.getFirst(FieldKey.TITLE))
                    val fingerprint = files.fingerprint("song.$extension")
                    assertEquals(tagFingerprint(expected, name), fingerprint)
                    assertEquals(fingerprint, tagFingerprint(image, name))
                    for (copy in listOf(expected, image)) AudioFileIO.read(copy).apply { tagOrCreateAndSetDefault.setField(field, value); commit() }
                    files.replaceTags("song.$extension", image, fingerprint)
                    assertEquals(-1L, Files.mismatch(expected.toPath(), directory.resolve("song.$extension")))
                    if (extension == "flac") assert(traffic.get() - before < expected.length() / 20) { "Moved ${traffic.get() - before} bytes for a ${expected.length()} byte file" }
                }
            }
            assertEquals(listOf("expected.flac", "expected.mp3", "expected.wav", "image.flac", "image.mp3", "image.wav", "song.flac", "song.mp3", "song.wav"),
                directory.toFile().list()!!.sorted())
        } finally { proxy.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aTagEditKeepsAServerFileWhoseTagsChangedMeanwhile() {
        val directory = Files.createTempDirectory("nevumi-sftp-tag-conflict")
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            val song = Files.copy(original, directory.resolve("song.flac"))
            val files = SftpFiles(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), directory.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            val image = Files.createTempFile("nevumi-image", ".flac").toFile()
            try {
                files.image("song.flac", image)
                val fingerprint = files.fingerprint("song.flac")
                AudioFileIO.read(image).apply { tag.setField(FieldKey.YEAR, "2026"); commit() }
                AudioFileIO.read(song.toFile()).apply { tag.setField(FieldKey.TITLE, "Changed elsewhere"); commit() }
                val changed = Files.readAllBytes(song)
                val conflict = assertFailsWith<SftpFailure> { files.replaceTags("song.flac", image, fingerprint) }
                assertEquals(SftpFailure.Kind.CONFLICT, conflict.kind)
                assertEquals(true, changed.contentEquals(Files.readAllBytes(song)))
                assertEquals(listOf("song.flac"), directory.toFile().list()!!.toList())
            } finally { image.delete() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun anUnchangedFileIsReadOnceWhileALaterWriteIsStillNoticed() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-sftp-settled")
        val traffic = AtomicLong()
        val proxy = proxy(traffic)
        val image = Files.createTempFile("nevumi-image", ".flac").toFile()
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            val song = Files.copy(original, directory.resolve("song.flac"))
            val expected = Files.copy(original, directory.resolve("expected.flac")).toFile()
            val hourAgo = FileTime.fromMillis(System.currentTimeMillis() - 3_600_000)
            Files.setLastModifiedTime(song, hourAgo)
            val files = SftpFiles(SftpProfile("127.0.0.1", proxy.localPort, System.getProperty("user.name"), directory.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            files.session {
                assertEquals(true, files.exists("song.flac"))
                var before = traffic.get()
                files.image("song.flac", image)
                val read = traffic.get() - before
                before = traffic.get()
                // Opening the tag editor again and checking the file before saving only ask for its size and time.
                files.image("song.flac", image)
                val fingerprint = files.fingerprint("song.flac")
                assert(traffic.get() - before < read / 4) { "Moved ${traffic.get() - before} bytes after reading ${read} bytes of tags" }
                for (copy in listOf(expected, image)) AudioFileIO.read(copy).apply { tag.setField(FieldKey.YEAR, "2026"); commit() }
                files.replaceTags("song.flac", image, fingerprint)
                assertEquals(-1L, Files.mismatch(expected.toPath(), song))

                Files.setLastModifiedTime(song, hourAgo)
                files.image("song.flac", image)
                val edited = files.fingerprint("song.flac")
                // Another tagger's write moves the file's time, so the tags read before are not trusted.
                AudioFileIO.read(song.toFile()).apply { tag.setField(FieldKey.TITLE, "Changed elsewhere"); commit() }
                val changed = Files.readAllBytes(song)
                AudioFileIO.read(image).apply { tag.setField(FieldKey.YEAR, "2027"); commit() }
                assertEquals(SftpFailure.Kind.CONFLICT, assertFailsWith<SftpFailure> { files.replaceTags("song.flac", image, edited) }.kind)
                assertEquals(true, changed.contentEquals(Files.readAllBytes(song)))
                files.image("song.flac", image)
                assertEquals("Changed elsewhere", AudioFileIO.read(image).tag.getFirst(FieldKey.TITLE))
            }
        } finally { proxy.close(); image.delete(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aFileWithoutKnownTagLocationsIsCopiedAndComparedWhole() {
        val directory = Files.createTempDirectory("nevumi-sftp-whole")
        val image = Files.createTempFile("nevumi-image", ".ogg").toFile()
        try {
            val bytes = kotlin.random.Random(7).nextBytes(100_000)
            Files.write(directory.resolve("song.ogg"), bytes)
            val files = SftpFiles(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), directory.toString(),
                Files.readString(fixture.resolve("fingerprint"))), SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            files.image("song.ogg", image)
            assertEquals(true, bytes.contentEquals(image.readBytes()))
            val fingerprint = files.fingerprint("song.ogg")
            assertEquals(bytes.inputStream().use(::contentHash), fingerprint)
            assertEquals(tagFingerprint(image, "song.ogg"), fingerprint)
        } finally { image.delete(); directory.toFile().deleteRecursively() }
    }

    private val fixture: Path get() = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "sftp-fixture")

    // Forwards connections to the SFTP fixture, counting the bytes sent both ways.
    private fun proxy(traffic: AtomicLong): ServerSocket {
        val proxy = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!proxy.isClosed) {
                val client = try { proxy.accept() } catch (_: SocketException) { break }
                val server = Socket("127.0.0.1", 14534)
                for ((from, to) in listOf(client to server, server to client)) thread(isDaemon = true) {
                    try {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val size = from.getInputStream().read(buffer)
                            if (size < 0) break
                            traffic.addAndGet(size.toLong())
                            to.getOutputStream().write(buffer, 0, size)
                        }
                    } catch (_: IOException) { }
                    runCatching { to.shutdownOutput() }
                }
            }
        }
        return proxy
    }

    @Test
    fun anUntrustedHostKeyStopsTheConnectionBeforeCredentialsCanBeUsed() {
        val directory = Files.createTempDirectory("nevumi-sftp-host-key")
        try {
            val profile = SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), directory.toString(),
                "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            val failure = assertFailsWith<SftpFailure> {
                SftpFiles(profile, SftpAuthentication(password = "must-not-be-sent")).download("song.flac", directory.resolve("received.flac").toFile())
            }
            assertEquals(SftpFailure.Kind.HOST_KEY, failure.kind)
            assertEquals(null, failure.cause)
            assertEquals(false, Files.exists(directory.resolve("received.flac")))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun aPinnedOpenSshServerCanTransferTheOriginalMusicFile() {
        val directory = Files.createTempDirectory("nevumi-sftp-transfer")
        try {
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "sftp-fixture")
            val original = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            Files.copy(original, directory.resolve("song.flac"))
            val files = SftpFiles(SftpProfile("127.0.0.1", 14534, System.getProperty("user.name"), directory.toString(),
                Files.readString(fixture.resolve("fingerprint"))),
                SftpAuthentication(privateKey = Files.readString(fixture.resolve("client"))))
            val received = directory.resolve("received.flac")
            files.download("song.flac", received.toFile())
            assertEquals(-1L, Files.mismatch(original, received))
        } finally { directory.toFile().deleteRecursively() }
    }
}
