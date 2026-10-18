package me.d3sox.nevumi.sftp

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeChanges
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import okio.ByteString.Companion.encodeUtf8
import me.d3sox.nevumi.artwork.ArtworkLibrary
import org.jaudiotagger.audio.AudioFileIO
import me.d3sox.nevumi.tunnel.Tailscale

class SftpMusicFileEdits(store: MusicStore, library: MusicLibrary, client: NavidromeClient,
    profile: SftpProfile, authentication: SftpAuthentication, backupDirectory: String,
    libraryId: Long = 1, changes: NavidromeChanges = NavidromeChanges(store, library) { client },
    artwork: ArtworkLibrary? = null, tailscale: Tailscale? = null) : MusicFileEdits by LocalFileEdits(store, library, backupDirectory,
        files = SftpFileEditAccess(SftpFiles(profile, authentication, tailscale), profile, File(backupDirectory, ".staging"), client.serverId, libraryId),
        localSources = object : LocalSources {
            override suspend fun scan(location: String, force: Boolean, onProgress: suspend (LocalScanProgress) -> Unit): List<Track> {
                client.rescan()
                val songs = changes.refreshLibrary(client, includeFilePaths = true)
                if (artwork != null) songs.firstOrNull { (_, song) -> song.filePath == location && song.fileLibraryId == libraryId }?.let { (track, song) ->
                    val staging = Files.createDirectories(File(backupDirectory, ".staging").toPath())
                    val temporary = Files.createTempFile(staging, "read-artwork-", ".${song.format}")
                    try {
                        SftpFiles(profile, authentication, tailscale).download(location, temporary.toFile())
                        val bytes = AudioFileIO.read(temporary.toFile()).tag?.firstArtwork?.binaryData
                        if (bytes != null) artwork.save(track.id, bytes) else artwork.remove(track.id)
                    } finally { Files.deleteIfExists(temporary) }
                }
                return songs.map { it.first }
            }
        })

private class SftpFileEditAccess(private val remote: SftpFiles, profile: SftpProfile, private val staging: File,
    private val serverId: String, private val libraryId: Long) : FileEditAccess {
    override val providerId = "sftp:" + listOf(serverId, libraryId.toString(), profile.host, profile.port.toString(),
        profile.username, profile.musicDirectory, profile.hostKeySha256).joinToString("\u0000").encodeUtf8().sha256().hex()
    override val atomicWrites = true
    override fun sourceLocation(source: TrackSource): String? = source.filePath.takeIf {
        source.kind == SourceKind.SERVER && source.available && source.id.startsWith("server:$serverId:") && source.fileLibraryId == libraryId
    }
    override fun normalize(location: String): String {
        require(location.isNotBlank() && !location.startsWith('/') && location.none { it in "\\\u0000\r\n" } &&
            location.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Choose a file inside the server music directory" }
        return location
    }
    override fun name(location: String) = normalize(location).substringAfterLast('/')
    override fun folder(location: String) = normalize(location).substringBeforeLast('/', "")
    override fun exists(location: String) = remote.exists(normalize(location))
    override fun open(location: String): InputStream {
        val directory = Files.createDirectories(staging.toPath())
        val file = Files.createTempFile(directory, "read-server-", ".part")
        try {
            remote.download(normalize(location), file.toFile())
            return object : FilterInputStream(Files.newInputStream(file)) {
                override fun close() { try { super.close() } finally { Files.deleteIfExists(file) } }
            }
        } catch (failure: Exception) { Files.deleteIfExists(file); throw failure }
    }
    override fun copy(location: String, destination: File) = remote.download(normalize(location), destination)
    override fun stagingDirectory(location: String) = staging
    override suspend fun replace(location: String, staged: File, originalHash: String?) {
        if (exists(location)) remote.replace(normalize(location), staged, originalHash ?: remote.hash(normalize(location)))
        else remote.upload(normalize(location), staged)
    }
    override fun scanLocation(location: String) = normalize(location)
    override fun renamedLocation(location: String, name: String): String =
        normalize(folder(location).let { if (it.isBlank()) name else "$it/$name" })
    override suspend fun rename(location: String, name: String) = remote.rename(normalize(location), renamedLocation(location, name))
    override suspend fun reserveMove(library: MusicLibrary, trackId: String, location: String) {}
    override suspend fun releaseMove(library: MusicLibrary, trackId: String, location: String) {}
    override suspend fun relocate(library: MusicLibrary, trackId: String, sourceId: String, from: String, to: String) =
        library.relocateServerFile(trackId, sourceId, serverId, libraryId, from, to)
    override fun movedSourceId(originalId: String, location: String) = originalId
    override suspend fun delete(location: String) = remote.delete(normalize(location))
    override suspend fun restoreDeleted(location: String, original: File, name: String, folder: String,
        reserve: suspend (String) -> Unit): String {
        val target = normalize(location)
        reserve(target)
        remote.upload(target, original)
        return target
    }
}
