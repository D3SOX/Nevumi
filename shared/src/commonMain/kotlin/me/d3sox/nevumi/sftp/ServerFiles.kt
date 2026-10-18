package me.d3sox.nevumi.sftp

import me.d3sox.nevumi.library.MusicFileEdits
import me.d3sox.nevumi.library.Track
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8

@Serializable
data class SftpProfile(val host: String, val port: Int = 22, val username: String,
    val musicDirectory: String, val hostKeySha256: String)

@Serializable
class SftpAuthentication(val password: String? = null, val privateKey: String? = null,
    val passphrase: String? = null) {
    override fun toString() = "SftpAuthentication([redacted])"
}

@Serializable
data class SftpConfiguration(val serverId: String, val libraryId: Long, val libraryName: String, val connection: SftpProfile) {
    val id: String get() = "sftp:" + listOf(serverId, libraryId.toString(), connection.host, connection.port.toString(),
        connection.username, connection.musicDirectory, connection.hostKeySha256).joinToString("\u0000").encodeUtf8().sha256().hex()
}

data class ServerMusicDirectory(val id: Long, val name: String)

@Serializable
data class PendingServerUpload(val id: String, val configurationId: String, val trackId: String,
    val relativePath: String, val stagedPath: String, val sha256: String)

internal suspend fun remapServerUploads(store: me.d3sox.nevumi.storage.MusicStore, from: String, to: String) {
    for (upload in store.list<PendingServerUpload>("server-uploads").filter { it.trackId == from })
        store.put("server-upload:${upload.id}", "server-uploads", upload.copy(trackId = to))
}

interface ServerFiles : MusicFileEdits {
    suspend fun configuration(): SftpConfiguration?
    suspend fun libraries(): List<ServerMusicDirectory>
    suspend fun connect(profile: SftpProfile, libraryId: Long, authentication: SftpAuthentication)
    suspend fun disconnect()
    suspend fun upload(trackId: String, relativePath: String): Track
    suspend fun pendingUploads(): List<PendingServerUpload>
    suspend fun retryUpload(id: String): Track
    suspend fun discardUpload(id: String)
}
