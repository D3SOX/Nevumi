package me.d3sox.nevumi.app

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.navidrome.NavidromeConnection
import me.d3sox.nevumi.navidrome.ServerCredentials
import me.d3sox.nevumi.navidrome.ServerPlayQueue
import me.d3sox.nevumi.navidrome.ServerProfile
import me.d3sox.nevumi.playback.AudioEffects
import me.d3sox.nevumi.playback.PlayableTrack
import me.d3sox.nevumi.playback.PlaybackEngine
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_continue_queue_from
import me.d3sox.nevumi.security.SecretServiceVault
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerQueueSyncTest {
    @Test
    fun aQueueFromAnotherAppIsOfferedResumedAtItsPositionAndThenSavedBackButNeverOfferedToItsOwnDevice() = withQueueServer {
        val engine = SilentEngine()
        val player = start(engine)
        val offer = withTimeout(5_000) { player.notices.first() }
        assertEquals(Res.string.notice_continue_queue_from, offer.message)
        assertEquals(listOf("Song 2", "Feishin"), offer.arguments)
        requireNotNull(offer.play).invoke().join()
        assertEquals(tracks.map { it.id }, player.state.value.queue.entries.map { it.trackId })
        assertEquals(tracks[1].id, player.state.value.queue.currentTrackId)
        assertEquals(42_000, player.state.value.queue.positionMs)
        assertEquals(42_000, engine.status.value.positionMs)
        assertTrue(engine.status.value.playWhenReady)
        // Resuming changed the queue, so the device now owns the server's queue.
        val upload = withTimeout(10_000) { saved.await() }
        assertEquals(ServerPlayQueue(listOf("s1", "s2", "s3"), "s2", 42_000), upload)
        // Closing keeps a later playhead locally, so only the remembered upload tells the restarted app that the queue is its own.
        player.seek(100_000).join()
        player.closeAndJoin()

        assertEquals(100_000, PlaybackQueue(store).snapshot().positionMs)
        remote = """{"current":"s2","position":42000,"changedBy":"Nevumi","entry":[{"id":"s1"},{"id":"s2"},{"id":"s3"}]}"""
        val checks = queueChecks.value
        val restarted = start(SilentEngine())
        val notices = mutableListOf<ActionNotice>()
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { restarted.notices.collect { notices += it } }
        withTimeout(5_000) { queueChecks.first { it > checks } }
        delay(500)
        listening.cancel()
        assertEquals(emptyList(), notices)
    }

    @Test
    fun seeksAfterThePauseUploadSaveTheNewPosition() = withQueueServer {
        val player = start(SilentEngine())
        val offer = withTimeout(5_000) { player.notices.first() }
        requireNotNull(offer.play).invoke().join()
        withTimeout(10_000) { saved.await() }
        player.seek(50_000).join()
        player.pause().join()
        withTimeout(10_000) { uploads.first { it.lastOrNull()?.positionMs == 50_000L } }
        player.seek(80_000).join()
        withTimeout(10_000) { uploads.first { it.lastOrNull()?.positionMs == 80_000L } }
        player.seekCurrentEntry(requireNotNull(player.state.value.queue.currentEntryId), 100_000).join()
        withTimeout(10_000) { uploads.first { it.lastOrNull()?.positionMs == 100_000L } }
    }

    @Test
    fun turningSyncOnReadsTheServerQueueAndOffersItEvenWhenTheOfferWaitsForTheWindow() = withQueueServer {
        preferences.update { it.copy(syncPlayQueue = false) }
        val player = start(SilentEngine())
        withTimeout(5_000) { player.state.first { !it.loading } }
        delay(500)
        assertEquals(0, queueChecks.value)
        player.updatePreferences { it.copy(syncPlayQueue = true) }.join()
        withTimeout(5_000) { queueChecks.first { it > 0 } }
        // Nothing collected notices when the check finished; the offer is still delivered once something does.
        delay(500)
        val offer = withTimeout(5_000) { player.notices.first() }
        assertEquals(listOf("Song 2", "Feishin"), offer.arguments)
    }
}

private class QueueServer(scope: CoroutineScope, val store: MusicStore, val library: MusicLibrary, val tracks: List<Track>, val connection: NavidromeConnection,
    val preferences: MusicPreferences) : CoroutineScope by scope {
    @Volatile var remote = """{"current":"s2","position":42000,"changedBy":"Feishin","entry":[{"id":"s1"},{"id":"s2"},{"id":"s3"}]}"""
    val queueChecks = MutableStateFlow(0)
    val saved = CompletableDeferred<ServerPlayQueue>()
    val uploads = MutableStateFlow<List<ServerPlayQueue>>(emptyList())
    val controllers = mutableListOf<MusicController>()

    fun start(engine: PlaybackEngine) = MusicController(library, PlaybackQueue(store), engine, connection, preferences = preferences)
        .also { controllers += it }
}

/** A library of three server songs and a server that holds a queue Feishin saved. */
private fun withQueueServer(block: suspend QueueServer.() -> Unit) = runBlocking {
    val directory = Files.createTempDirectory("server-queue-sync")
    val store = openMusicStore(directory.resolve("library.db").toString())
    lateinit var server: QueueServer
    val http = HttpClient(MockEngine { request ->
        val body = when (request.url.encodedPath) {
            "/rest/getPlayQueue.view" -> """"playQueue":${server.remote}""".also { server.queueChecks.update { it + 1 } }
            "/rest/savePlayQueue.view" -> "".also {
                val upload = ServerPlayQueue(request.url.parameters.getAll("id").orEmpty(), request.url.parameters["current"]!!,
                    request.url.parameters["position"]!!.toLong())
                server.saved.complete(upload)
                server.uploads.update { it + upload }
            }
            else -> ""
        }
        respond("""{"subsonic-response":{"status":"ok"${if (body.isEmpty()) "" else ",$body"}}}""",
            headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    val connection = NavidromeConnection(store, http, SecretServiceVault("music-player-test-${UUID.randomUUID()}"))
    val serverId = ServerProfile("https://music.example", "test").serverId
    val library = MusicLibrary(store)
    val tracks = (1..3).map { library.importTrack(TrackMetadata("Song $it"), TrackSource("server:$serverId:s$it", SourceKind.SERVER, "s$it")) }
    val preferences = MusicPreferences(store)
    preferences.update { it.copy(syncPlayQueue = true) }
    server = QueueServer(this, store, library, tracks, connection, preferences)
    try {
        connection.connect("https://music.example", ServerCredentials("test", "test"))
        server.block()
    } finally {
        server.controllers.forEach { it.closeAndJoin() }
        connection.disconnect(); http.close(); store.close()
        directory.toFile().deleteRecursively()
    }
}

/** Reports what it was asked to play without decoding audio. */
private class SilentEngine : PlaybackEngine {
    private val mutableStatus = MutableStateFlow(PlaybackStatus())
    override val status = mutableStatus.asStateFlow()
    override fun load(track: PlayableTrack, positionMs: Long, play: Boolean) = loadQueue(listOf(track), 0, positionMs, play)
    override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
        val track = tracks[startIndex]
        mutableStatus.value = PlaybackStatus(track.id, play, positionMs, 180_000, entryId = track.entryId, sourceId = track.sourceId,
            playWhenReady = play, playbackId = UUID.randomUUID().toString())
    }
    override fun updateQueue(tracks: List<PlayableTrack>) {}
    override fun play() = mutableStatus.update { it.copy(playing = true, playWhenReady = true) }
    override fun pause() = mutableStatus.update { it.copy(playing = false, playWhenReady = false) }
    override fun seek(positionMs: Long) = mutableStatus.update { it.copy(positionMs = positionMs) }
    override fun setVolume(volume: Double) {}
    override fun setSpeed(speed: Double) {}
    override fun setPitch(pitch: Double) {}
    override suspend fun setAudioEffects(effects: AudioEffects) {}
    override fun setRepeat(mode: RepeatMode, playCount: Int?) = mutableStatus.update { it.copy(repeatMode = mode, repeatPlays = playCount) }
    override fun close() {}
}
