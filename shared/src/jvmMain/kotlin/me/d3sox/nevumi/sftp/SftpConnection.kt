package me.d3sox.nevumi.sftp

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeChanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.io.InputStream
import java.nio.file.Files
import org.jaudiotagger.audio.AudioFileIO
import java.util.UUID
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.tunnel.Tailscale

class SftpConnection(private val store: MusicStore, private val library: MusicLibrary,
    private val client: suspend () -> NavidromeClient?, private val vault: CredentialVault,
    private val backupDirectory: String,
    private val readLocal: (String) -> InputStream = { File(it).inputStream() },
    private val changes: NavidromeChanges = NavidromeChanges(store, library, client = client),
    private val artwork: ArtworkLibrary? = null, private val tailscale: suspend () -> Tailscale? = { null }) : ServerFiles {
    private val work = Mutex()
    override suspend fun pendingUploads(): List<PendingServerUpload> = store.list("server-uploads")
    override suspend fun retryUpload(id: String): Track = work.withLock {
        val pending = requireNotNull(store.read<PendingServerUpload>("server-upload:$id")) { "The upload is no longer pending" }
        check(configuration()?.id == pending.configurationId) { "Reconnect to the original SFTP directory before retrying this upload" }
        performUpload(pending.trackId, pending.relativePath)
    }
    override suspend fun discardUpload(id: String) = work.withLock { withContext(Dispatchers.IO) {
        val pending = store.read<PendingServerUpload>("server-upload:$id") ?: return@withContext
        Files.deleteIfExists(File(pending.stagedPath).toPath())
        store.database.records().delete("server-upload:$id")
    } }
    override suspend fun configuration(): SftpConfiguration? = store.read<String>("sftp:current")?.let { store.read("sftp:profile:$it") }
    override suspend fun libraries(): List<ServerMusicDirectory> = withContext(Dispatchers.IO) {
        requireNotNull(client()) { "Connect to Navidrome first" }.musicDirectories().map { ServerMusicDirectory(it.id, it.name) }
            .distinctBy { it.id }.sortedBy { it.id }
    }
    override suspend fun connect(profile: SftpProfile, libraryId: Long, authentication: SftpAuthentication) = work.withLock {
        withContext(Dispatchers.IO) {
            val server = requireNotNull(client()) { "Connect to Navidrome first" }
            val directory = requireNotNull(libraries().firstOrNull { it.id == libraryId }) { "Choose a Navidrome music library" }
            val files = SftpFiles(profile, authentication, tailscale())
            files.verifyDirectory()
            val sample = server.songs(includeFilePaths = true).filter { it.fileLibraryId == libraryId }.minByOrNull { it.sizeBytes }
            if (sample != null) {
                val hash = MessageDigest.getInstance("SHA-256")
                server.downloadSong(sample.id) { hash.update(it) }
                val expected = hash.digest().joinToString("") { "%02x".format(it) }
                check(files.hash(requireNotNull(sample.filePath)) == expected) {
                    "This SFTP directory does not contain the music files served by Navidrome"
                }
            }
            changes.refreshLibrary(server, includeFilePaths = true)
            val configuration = SftpConfiguration(server.serverId, libraryId, directory.name, profile)
            vault.save(configuration.id, store.json.encodeToString(authentication))
            store.transaction {
                store.put("sftp:profile:${configuration.id}", "sftp-profiles", configuration)
                store.put("sftp:current", "sftp-settings", configuration.id)
            }
        }
    }
    override suspend fun disconnect() = work.withLock {
        for (profile in store.list<SftpConfiguration>("sftp-profiles")) vault.delete(profile.id)
        store.database.records().delete("sftp:current")
    }
    override suspend fun upload(trackId: String, relativePath: String): Track = work.withLock { performUpload(trackId, relativePath) }
    private suspend fun performUpload(trackId: String, relativePath: String): Track = withContext(Dispatchers.IO) {
        val configuration = checkNotNull(configuration()) { "Connect to SFTP first" }
        val server = requireNotNull(client()) { "Connect to Navidrome first" }
        check(configuration.serverId == server.serverId) { "Reconnect to the original Navidrome server" }
        val secret = checkNotNull(vault.read(configuration.id)) { "Unlock your credential store or reconnect to SFTP" }
        val authentication = store.json.decodeFromString<SftpAuthentication>(secret)
        requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        val previous = store.list<PendingServerUpload>("server-uploads").singleOrNull {
            it.configurationId == configuration.id && it.trackId == trackId && it.relativePath == relativePath
        }
        val record = previous ?: prepareUpload(configuration, trackId, relativePath)
        val files = SftpFiles(configuration.connection, authentication, tailscale())
        try {
            if (previous != null && files.exists(relativePath)) {
                if (files.hash(relativePath) != record.sha256) throw SftpFailure(SftpFailure.Kind.CONFLICT)
            } else {
                val staged = File(record.stagedPath)
                check(staged.isFile && fileHash(staged) == record.sha256) {
                    "The staged upload is unavailable or damaged. Discard it and upload the song again."
                }
                files.upload(relativePath, staged, record.id)
            }
            server.rescan()
            // The refresh already downloads the catalogue; find the uploaded song in it instead of downloading it twice.
            val catalogue = changes.refreshLibrary(server, includeFilePaths = true)
            val song = requireNotNull(catalogue.map { it.second }.singleOrNull {
                it.filePath == relativePath && it.fileLibraryId == configuration.libraryId
            }) { "Navidrome has not indexed the uploaded song yet. Refresh the library." }
            changes.importSong(server, song, linkTo = trackId)
            store.database.records().delete("server-upload:${record.id}")
            Files.deleteIfExists(File(record.stagedPath).toPath())
            requireNotNull(library.findTrack(trackId))
        } catch (failure: SftpFailure) {
            if (previous == null && failure.kind == SftpFailure.Kind.CONFLICT) {
                store.database.records().delete("server-upload:${record.id}")
                Files.deleteIfExists(File(record.stagedPath).toPath())
            }
            throw failure
        }
    }

    private fun fileHash(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun prepareUpload(configuration: SftpConfiguration, trackId: String, relativePath: String): PendingServerUpload {
        val track = requireNotNull(library.findTrack(trackId)) { "Track is missing" }
        val source = requireNotNull(track.sources.firstOrNull { it.kind == SourceKind.LOCAL && it.available }) { "Choose an available local song to upload" }
        val extension = relativePath.substringAfterLast('.').lowercase()
        require(extension in setOf("mp3", "wav", "flac")) { "Use an MP3, WAV or FLAC filename" }
        val staging = Files.createDirectories(File(backupDirectory, ".uploads").toPath())
        val staged = Files.createTempFile(staging, "upload-", ".$extension")
        try {
            readLocal(source.location).use { input -> Files.newOutputStream(staged).use { input.copyTo(it) } }
            AudioFileIO.read(staged.toFile())
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(staged).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
            }
            val record = PendingServerUpload(UUID.randomUUID().toString(), configuration.id, trackId, relativePath, staged.toString(),
                hash.digest().joinToString("") { "%02x".format(it) })
            store.put("server-upload:${record.id}", "server-uploads", record)
            return record
        } catch (failure: Exception) { Files.deleteIfExists(staged); throw failure }
    }

    private suspend fun editor(configuration: SftpConfiguration, credentialsRequired: Boolean = true): SftpMusicFileEdits {
        val server = requireNotNull(client()) { "Reconnect to the original Navidrome server" }
        check(server.serverId == configuration.serverId) { "Reconnect to the original Navidrome server" }
        val secret = vault.read(configuration.id)
        check(secret != null || !credentialsRequired) { "Unlock your credential store or reconnect to the original SFTP directory" }
        val authentication = secret?.let { store.json.decodeFromString<SftpAuthentication>(it) } ?: SftpAuthentication()
        val key = Triple(configuration.connection, secret, tailscale())
        val files = remote?.takeIf { it.first == key }?.second ?: SftpFiles(key.first, authentication, key.third).also { remote = key to it }
        return SftpMusicFileEdits(store, library, server, files, configuration.connection,
            File(backupDirectory, configuration.id.substringAfter(':')).path, configuration.libraryId, changes, artwork)
    }
    // Kept across editors, so saving reuses the connection the tag editor just opened.
    private var remote: Pair<Triple<SftpProfile, String?, Tailscale?>, SftpFiles>? = null

    private suspend fun currentEditor(): SftpMusicFileEdits = editor(checkNotNull(configuration()) { "Connect to SFTP first" })
    private suspend fun journalEditor(id: String): SftpMusicFileEdits {
        val record = requireNotNull(store.read<LocalFileEdit>("file-edit:$id")) { "File edit is missing" }
        val configuration = checkNotNull(store.read<SftpConfiguration>("sftp:profile:${record.providerId}")) {
            "Reconnect to the original SFTP directory"
        }
        return editor(configuration)
    }

    // Each operation runs over one SSH connection instead of connecting for every file step.
    private suspend fun <T> current(operation: suspend MusicFileEdits.() -> T): T = work.withLock { currentEditor().session(operation) }
    private suspend fun <T> journal(id: String, operation: suspend MusicFileEdits.() -> T): T = work.withLock { journalEditor(id).session(operation) }

    override suspend fun fileName(trackId: String) = work.withLock { currentEditor().fileName(trackId) }
    override suspend fun readTags(trackId: String) = current { readTags(trackId) }
    override suspend fun linkTracks(keepId: String, otherId: String) = work.withLock { library.linkTracks(keepId, otherId) }
    override suspend fun history() = store.list<LocalFileEdit>("file-edits").filter { it.providerId.startsWith("sftp:") }.sortedBy { it.createdAt }
    override suspend fun trimHistory(maxAgeMs: Long, maxBytes: Long) = work.withLock {
        val server = client() ?: return@withLock
        for (configuration in store.list<SftpConfiguration>("sftp-profiles").filter { it.serverId == server.serverId })
            editor(configuration, credentialsRequired = false).trimHistory(maxAgeMs, maxBytes)
    }
    override suspend fun recover() = work.withLock {
        val server = client() ?: return@withLock
        for (configuration in store.list<SftpConfiguration>("sftp-profiles").filter { it.serverId == server.serverId })
            if (vault.read(configuration.id) != null) editor(configuration).session { recover() }
    }
    override suspend fun apply(editId: String) = journal(editId) { apply(editId) }
    override suspend fun discard(editId: String) = journal(editId) { discard(editId) }
    override suspend fun editTags(trackId: String, metadata: TrackMetadata) = current { editTags(trackId, metadata) }
    override suspend fun editTags(trackIds: List<String>, changes: TagChanges) = current { editTags(trackIds, changes) }
    override suspend fun replaceArtwork(trackId: String, bytes: ByteArray) = current { replaceArtwork(trackId, bytes) }
    override suspend fun rename(trackId: String, name: String) = current { rename(trackId, name) }
    override suspend fun delete(trackId: String) = current { delete(trackId) }
    override suspend fun undo(editId: String) = journal(editId) { undo(editId) }
    override suspend fun restoreOriginal(editId: String) = journal(editId) { restoreOriginal(editId) }
}
