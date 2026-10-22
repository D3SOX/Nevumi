package me.d3sox.nevumi.sftp

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
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
            val conflict = assertFailsWith<SftpFailure> { files.replace("song.flac", edited.toFile(), "0".repeat(64)) }
            assertEquals(SftpFailure.Kind.CONFLICT, conflict.kind)
            assertEquals(-1L, Files.mismatch(original, directory.resolve("song.flac")))
            assertEquals(listOf("song.flac"), directory.toFile().list()!!.toList())
            files.replace("song.flac", edited.toFile(), files.hash("song.flac"))
            assertEquals(-1L, Files.mismatch(edited, directory.resolve("song.flac")))
            assertFailsWith<SftpFailure> { files.replace("missing.flac", edited.toFile(), files.hash("song.flac")) }
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
                files.replace("song.flac", original.toFile(), files.hash("song.flac"))
                files.download("song.flac", directory.resolve("received.flac").toFile())
            }
            assertEquals(1, connections.get())
            assertEquals(true, files.exists("song.flac"))
            assertEquals(true, files.exists("received.flac"))
            assertEquals(3, connections.get())
        } finally { proxy.close(); directory.toFile().deleteRecursively() }
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
