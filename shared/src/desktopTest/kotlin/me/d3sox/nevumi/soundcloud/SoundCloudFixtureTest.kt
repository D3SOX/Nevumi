package me.d3sox.nevumi.soundcloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.navidrome.NavidromeChanges
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs against scripts/test-sc-gate.py, which saves into the music folder of the SFTP Navidrome fixture. */
class SoundCloudFixtureTest {
    @Test
    fun aSongSavedToTheMusicServerIsIndexedAndAddedToTheLibrary() = runBlocking {
        val root = Path.of(System.getProperty("nevumi.testRoot"))
        val saved = root.resolve(".test-environment/sftp-music/SoundCloud")
        val directory = Files.createTempDirectory("nevumi-soundcloud-fixture")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val http = HttpClient(OkHttp)
        val secrets = mutableMapOf<String, String>()
        val vault = object : CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String): String? = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        val library = MusicLibrary(store)
        val navidrome = NavidromeConnection(store, http, vault)
        val downloads = SoundCloudDownloads(store, http, server = navidrome::client,
            serverChanges = NavidromeChanges(store, library, client = navidrome::client), pollMillis = 50)
        try {
            navidrome.connect("http://127.0.0.1:14536", ServerCredentials("fixture", "fixture-password"))
            downloads.connect("http://127.0.0.1:14538", ScGateFormat.MP3_320, SoundCloudDestination.SERVER)
            assertEquals(true, downloads.profile.value?.libraryDestination)
            val song = downloads.search("fixture gate").results.single()
            val completed = async(start = CoroutineStart.UNDISPATCHED) { downloads.completed.first() }
            downloads.download(song, ScGateDestination.LIBRARY)
            val job = withTimeout(60_000) { completed.await() }
            val track = assertNotNull(library.findTrack(assertNotNull(job.trackId)))
            val source = track.sources.single { it.kind == SourceKind.SERVER }
            assertEquals("SoundCloud/${job.fileName}", source.filePath)
        } finally {
            saved.toFile().deleteRecursively()
            try { navidrome.client()?.rescan() } finally {
                downloads.closeAndJoin(); http.close(); store.close(); directory.toFile().deleteRecursively()
            }
        }
    }
}
