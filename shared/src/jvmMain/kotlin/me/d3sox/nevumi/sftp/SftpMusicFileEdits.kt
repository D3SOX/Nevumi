package me.d3sox.nevumi.sftp

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.ServerSong
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import okio.ByteString.Companion.encodeUtf8
import me.d3sox.nevumi.artwork.ArtworkLibrary
import org.jaudiotagger.audio.AudioFileIO
import me.d3sox.nevumi.tunnel.Tailscale

class SftpMusicFileEdits private constructor(private val access: SftpFileEditAccess, store: MusicStore, library: MusicLibrary,
    backupDirectory: String) : MusicFileEdits by LocalFileEdits(store, library, backupDirectory, files = access, localSources = access) {
    constructor(store: MusicStore, library: MusicLibrary, client: NavidromeClient, profile: SftpProfile, authentication: SftpAuthentication,
        backupDirectory: String, libraryId: Long = 1, changes: NavidromeChanges = NavidromeChanges(store, library) { client },
        artwork: ArtworkLibrary? = null, tailscale: Tailscale? = null) :
        this(store, library, client, SftpFiles(profile, authentication, tailscale), profile, backupDirectory, libraryId, changes, artwork)

    /** Uses [files], whose connection outlives this editor, so the next edit can reuse it. */
    constructor(store: MusicStore, library: MusicLibrary, client: NavidromeClient, files: SftpFiles, profile: SftpProfile,
        backupDirectory: String, libraryId: Long, changes: NavidromeChanges, artwork: ArtworkLibrary?) :
        this(SftpFileEditAccess(files, profile, File(backupDirectory, ".staging"), client, libraryId, library, changes, artwork), store, library, backupDirectory)

    /** Runs [block] with one SSH connection for all of its file steps. */
    suspend fun <T> session(block: suspend MusicFileEdits.() -> T): T = access.remote.session { block() }
}

private class SftpFileEditAccess(val remote: SftpFiles, profile: SftpProfile, private val staging: File,
    private val client: NavidromeClient, private val libraryId: Long, private val library: MusicLibrary,
    private val serverChanges: NavidromeChanges, private val artwork: ArtworkLibrary?) : FileEditAccess, LocalSources {
    private val serverId = client.serverId
    // A written file stays staged until its scan, which then reads its artwork without downloading it again.
    private var written: Pair<String, File>? = null

    override suspend fun scan(location: String, force: Boolean, onProgress: suspend (LocalScanProgress) -> Unit): List<Track> {
        client.rescan(folder = libraryId to folder(location))
        val songs = changedSong(location)?.let { listOf(serverChanges.refreshSong(client, it) to it) }
            ?: serverChanges.refreshLibrary(client, includeFilePaths = true)
        songs.firstOrNull { (_, song) -> song.filePath == location && song.fileLibraryId == libraryId }?.let { (track, _) -> readArtwork(track, location) }
        written = null
        return songs.map { it.first }
    }

    // Navidrome keeps a song's identity when its file changes, so only that song is fetched instead of the whole catalogue.
    // A song the server no longer has at this location, such as a deleted file, takes a full refresh.
    private suspend fun changedSong(location: String): ServerSong? {
        val songId = library.tracks().firstNotNullOfOrNull { track -> track.sources.firstOrNull {
            it.kind == SourceKind.SERVER && it.id.startsWith("server:$serverId:") && it.fileLibraryId == libraryId && it.filePath == location
        } }?.location ?: return null
        val file = client.musicFile(songId)?.takeIf { it.path == location && it.libraryId == libraryId } ?: return null
        return client.song(songId).copy(filePath = file.path, fileLibraryId = file.libraryId)
    }

    private suspend fun readArtwork(track: Track, location: String) {
        if (artwork == null) return
        val local = written?.takeIf { (path, file) -> path == location && file.isFile }?.second
        val temporary = if (local == null) Files.createTempFile(Files.createDirectories(staging.toPath()), "read-artwork-", ".part") else null
        try {
            if (temporary != null) remote.image(location, temporary.toFile())
            val bytes = AudioFileIO.readAs(local ?: temporary!!.toFile(), location.substringAfterLast('.')).tag?.firstArtwork?.binaryData
            if (bytes != null) artwork.save(track.id, bytes) else artwork.remove(track.id)
        } finally { temporary?.let(Files::deleteIfExists) }
    }

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
    // Tag edits copy only the tags, so opening the editor or saving never moves the audio through the tunnel.
    override val partialCopies = true
    override fun image(location: String, destination: File) = remote.image(normalize(location), destination)
    override fun contentHash(location: String) = remote.fingerprint(normalize(location))
    override fun contentHash(file: File, location: String) = tagFingerprint(file, name(location))
    override fun recognizes(hash: String) = hash.startsWith(TagLayout.FINGERPRINT)
    override fun stagingDirectory(location: String) = staging
    override suspend fun replace(location: String, staged: File, originalHash: String?) {
        // An expected original must still be there, so a file deleted meanwhile is not recreated.
        if (originalHash != null) remote.replace(normalize(location), staged, originalHash)
        else if (exists(location)) remote.replace(normalize(location), staged, remote.fingerprint(normalize(location)))
        else remote.upload(normalize(location), staged)
        written = normalize(location) to staged
    }
    override suspend fun replaceTags(location: String, staged: File, originalHash: String) {
        remote.replaceTags(normalize(location), staged, originalHash)
        written = normalize(location) to staged
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
        written = target to original
        return target
    }
}
