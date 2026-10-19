package me.d3sox.nevumi.history

import me.d3sox.nevumi.downloads.DownloadNetwork
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.navidrome.NavidromeClient
import me.d3sox.nevumi.navidrome.NavidromeException
import me.d3sox.nevumi.navidrome.NavidromeRequestException
import me.d3sox.nevumi.navidrome.isUnreachableHost
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.storage.MusicStore
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Clock

@Serializable
enum class ScrobblingService { LISTENBRAINZ, LASTFM, NAVIDROME }

@Serializable
data class ScrobblingProfile(val id: String, val service: ScrobblingService, val username: String, val baseUrl: String,
    val enabledSince: Long, val enabled: Boolean = true, val serverId: String? = null)

@Serializable
enum class ScrobbleState { PENDING, IN_FLIGHT, CONFIRMED, AUTHORIZATION, REJECTED, UNCERTAIN, DISCARDED }

@Serializable
data class ScrobbleSubmission(val id: String, val profileId: String, val playbackId: String, val listen: Listen?,
    val state: ScrobbleState = ScrobbleState.PENDING, val retryAt: Long = 0, val attempts: Int = 0, val error: String? = null,
    val songId: String? = null)

data class ScrobblingStatus(val profiles: List<ScrobblingProfile> = emptyList(),
    val submissions: List<ScrobbleSubmission> = emptyList(), val error: String? = null)

internal fun Listen.qualifiesForScrobbling(settings: MusicSettings): Boolean {
    val percentageMs = durationMs.takeIf { it > 0 }?.let { it * settings.scrobblePercent / 100 }
    val maximumMs = settings.scrobbleMaxSeconds.takeIf { it > 0 }?.let { it * 1_000L }
    val thresholdMs = listOfNotNull(percentageMs, maximumMs).minOrNull() ?: return false
    return playedMs >= thresholdMs.coerceAtLeast(1)
}

@Serializable
private data class LastFmCredentials(val apiKey: String, val sharedSecret: String, val sessionKey: String) {
    override fun toString() = "LastFmCredentials([redacted])"
}
@Serializable
private data class LastFmPending(val apiKey: String, val sharedSecret: String, val token: String, val url: String, val createdAt: Long) {
    override fun toString() = "LastFmPending([redacted])"
}

class Scrobbling(private val store: MusicStore, private val library: MusicLibrary, private val http: HttpClient,
    private val vault: CredentialVault, private val network: StateFlow<DownloadNetwork> = MutableStateFlow(DownloadNetwork()),
    private val navidrome: suspend () -> NavidromeClient? = { null },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : AutoCloseable {
    private val sending = Mutex()
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private val playback = MutableStateFlow(PlaybackStatus())
    private val nowPlayingSent = mutableMapOf<String, String>()
    private val nowPlayingRetryAt = mutableMapOf<String, Long>()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError
    fun onPlayback(status: PlaybackStatus) { playback.value = status }

    fun start() {
        if (worker != null || !supervisor.isActive) return
        scope.launch {
            combine(store.database.records().observe("listens").distinctUntilChanged(),
                store.database.records().observe("scrobbling-profiles").distinctUntilChanged(),
                store.database.records().observe("servers").distinctUntilChanged(), network,
                store.database.records().observe("settings").distinctUntilChanged()) { _, _, _, _, _ -> Unit }
                .collect { wake.trySend(Unit) }
        }
        scope.launch {
            playback.distinctUntilChanged { a, b -> a.playbackId == b.playbackId && a.trackId == b.trackId && a.playing == b.playing &&
                a.playWhenReady == b.playWhenReady && a.ended == b.ended && a.error == b.error }.collect { wake.trySend(Unit) }
        }
        worker = scope.launch {
            while (isActive) {
                try {
                    val server = navidrome()
                    if (server != null && profiles().none { it.service == ScrobblingService.NAVIDROME && it.serverId == server.serverId })
                        configureNavidrome()
                    val reportError = reportNowPlaying()
                    enqueueEligible(); runPending()
                    mutableError.value = reportError
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableError.value = "Scrobbling could not access its account or saved listens. Unlock the credential store and retry." }
                withTimeoutOrNull(30_000) { wake.receive() }
            }
        }
    }

    suspend fun connectListenBrainz(token: String, baseUrl: String = "https://api.listenbrainz.org"): ScrobblingProfile {
        val address = Url(baseUrl)
        require(address.protocol.name == "https" || address.protocol.name == "http" && address.host in setOf("127.0.0.1", "localhost", "::1")) {
            "Use HTTPS for ListenBrainz"
        }
        require(address.user.isNullOrEmpty() && address.password.isNullOrEmpty() && address.parameters.isEmpty()) { "Enter a service address without credentials" }
        val username = ListenBrainzClient(http, token.trim(), baseUrl).validateToken()
        return activate(ScrobblingService.LISTENBRAINZ, username, baseUrl.trimEnd('/'), token.trim())
    }

    suspend fun startLastFmAuthorization(apiKey: String, sharedSecret: String): LastFmAuthorization {
        require(apiKey.isNotBlank() && sharedSecret.isNotBlank()) { "Enter your Last.fm application API key and shared secret" }
        val authorization = LastFmClient(http, apiKey.trim(), sharedSecret.trim()).requestAuthorization()
        vault.save("lastfm:authorization", store.json.encodeToString(LastFmPending(apiKey.trim(), sharedSecret.trim(),
            authorization.token, authorization.url, now())))
        store.put("scrobbling:lastfm-sign-in", "scrobbling-sign-in", true)
        return authorization
    }
    private suspend fun pendingLastFm(): LastFmPending? {
        if (store.read<Boolean>("scrobbling:lastfm-sign-in") != true) return null
        val pending = vault.read("lastfm:authorization")?.let { store.json.decodeFromString<LastFmPending>(it) } ?: return null
        if (now() - pending.createdAt > 3_600_000) { cancelLastFmAuthorization(); return null }
        return pending
    }
    suspend fun pendingLastFmAuthorization(): LastFmAuthorization? = pendingLastFm()?.let { LastFmAuthorization(it.token, it.url) }
    suspend fun completeLastFmAuthorization(): ScrobblingProfile {
        val pending = requireNotNull(pendingLastFm()) { "Start signing in to Last.fm again" }
        val session = LastFmClient(http, pending.apiKey, pending.sharedSecret).finishAuthorization(pending.token)
        val profile = activate(ScrobblingService.LASTFM, session.username, "https://ws.audioscrobbler.com/2.0/",
            store.json.encodeToString(LastFmCredentials(pending.apiKey, pending.sharedSecret, session.key)))
        cancelLastFmAuthorization()
        return profile
    }
    suspend fun cancelLastFmAuthorization() {
        vault.delete("lastfm:authorization")
        store.database.records().delete("scrobbling:lastfm-sign-in")
    }

    suspend fun configureNavidrome(): ScrobblingProfile? {
        val client = navidrome() ?: return null
        return activate(ScrobblingService.NAVIDROME, client.username, "", credentials = null, serverId = client.serverId)
    }

    private suspend fun activate(service: ScrobblingService, username: String, baseUrl: String, credentials: String?,
        serverId: String? = null): ScrobblingProfile {
        val id = "${service.name.lowercase()}\u0000${serverId ?: baseUrl}\u0000$username".encodeUtf8().sha256().hex()
        return sending.withLock {
            val previous = store.read<ScrobblingProfile>("scrobbling-profile:$id")
            val profile = ScrobblingProfile(id, service, username, baseUrl,
                previous?.enabledSince?.takeIf { previous.enabled } ?: now(), serverId = serverId)
            if (credentials != null) vault.save("scrobbling:$id", credentials)
            store.transaction {
                for (other in profiles().filter { it.id != id && it.service == profile.service && it.enabled })
                    store.put("scrobbling-profile:${other.id}", "scrobbling-profiles", other.copy(enabled = false))
                store.put("scrobbling-profile:$id", "scrobbling-profiles", profile)
                for (entry in submissions().filter { it.profileId == id && it.state == ScrobbleState.AUTHORIZATION })
                    save(entry.copy(state = ScrobbleState.PENDING, retryAt = 0, error = null))
            }
            wake.trySend(Unit)
            nowPlayingRetryAt.remove(id)
            nowPlayingSent.remove(id)
            profile
        }
    }

    suspend fun profiles(): List<ScrobblingProfile> = store.list("scrobbling-profiles")
    suspend fun submissions(): List<ScrobbleSubmission> = store.list("scrobble-submissions")
    fun observe() = combine(store.database.records().observe("scrobbling-profiles").distinctUntilChanged(),
        store.database.records().observe("scrobble-submissions").distinctUntilChanged(), error) { profiles, submissions, error ->
        ScrobblingStatus(profiles.map { store.json.decodeFromString<ScrobblingProfile>(it.value) },
            submissions.map { store.json.decodeFromString<ScrobbleSubmission>(it.value) }, error)
    }.distinctUntilChanged()

    suspend fun setEnabled(id: String, enabled: Boolean) = sending.withLock {
        val profile = requireNotNull(store.read<ScrobblingProfile>("scrobbling-profile:$id")) { "The scrobbling account is missing" }
        store.transaction {
            if (enabled) for (other in profiles().filter { it.service == profile.service && it.id != id && it.enabled })
                store.put("scrobbling-profile:${other.id}", "scrobbling-profiles", other.copy(enabled = false))
            store.put("scrobbling-profile:$id", "scrobbling-profiles", profile.copy(enabled = enabled,
                enabledSince = if (enabled && !profile.enabled) now() else profile.enabledSince))
        }
    }

    suspend fun disconnect(id: String) = sending.withLock {
        val profile = requireNotNull(store.read<ScrobblingProfile>("scrobbling-profile:$id")) { "The scrobbling account is missing" }
        require(profile.service != ScrobblingService.NAVIDROME) { "Turn off sending listens to Navidrome or disconnect the music server" }
        vault.delete("scrobbling:$id")
        if (profile.service == ScrobblingService.LASTFM) cancelLastFmAuthorization()
        store.transaction {
            for (entry in submissions().filter { it.profileId == id && it.listen != null })
                save(entry.copy(state = ScrobbleState.DISCARDED, listen = null, songId = null, error = null, retryAt = 0))
            store.database.records().delete("scrobbling-profile:$id")
        }
        profile.service
    }

    suspend fun discardSubmission(id: String) = sending.withLock {
        val entry = requireNotNull(store.read<ScrobbleSubmission>("scrobble-submission:$id")) { "The submission is missing" }
        require(entry.state != ScrobbleState.CONFIRMED) { "This listen has already been submitted" }
        save(entry.copy(state = ScrobbleState.DISCARDED, listen = null, songId = null, error = null, retryAt = 0))
    }
    suspend fun discardPending() = sending.withLock { discardPendingEntries() }
    suspend fun clearHistory(history: ListeningHistory) = sending.withLock {
        history.clear()
        discardPendingEntries()
    }
    private suspend fun discardPendingEntries() {
        store.transaction {
            for (entry in submissions().filter { it.listen != null })
                save(entry.copy(state = ScrobbleState.DISCARDED, listen = null, songId = null, error = null, retryAt = 0))
        }
    }

    suspend fun retrySubmission(id: String) = sending.withLock {
        val entry = requireNotNull(store.read<ScrobbleSubmission>("scrobble-submission:$id")) { "The submission is missing" }
        require(entry.listen != null && entry.state !in setOf(ScrobbleState.CONFIRMED, ScrobbleState.DISCARDED, ScrobbleState.IN_FLIGHT)) {
            "This submission cannot be retried"
        }
        save(entry.copy(state = ScrobbleState.PENDING, retryAt = 0, error = null))
        wake.trySend(Unit)
    }

    suspend fun enqueueEligible() = sending.withLock {
        val settings = MusicPreferences(store).load()
        val listens = store.list<Listen>("listens").filter { it.qualifiesForScrobbling(settings) && it.originDeviceId == null }
        val profiles = profiles().filter { it.enabled }
        store.transaction {
            for (profile in profiles) for (listen in listens.filter { it.startedAt >= profile.enabledSince }) {
                if (profile.service != ScrobblingService.NAVIDROME && (listen.metadata.title.isBlank() || listen.metadata.artist.isBlank())) continue
                if (profile.service == ScrobblingService.LASTFM && listen.durationMs <= 30_000) continue
                val songId = if (profile.service == ScrobblingService.NAVIDROME)
                    library.findTrack(listen.trackId)?.sources?.firstOrNull {
                        it.kind == SourceKind.SERVER && it.id.startsWith("server:${profile.serverId}:")
                    }?.location ?: continue else null
                val id = "${profile.id}\u0000${listen.playbackId}".encodeUtf8().sha256().hex()
                if (store.read<ScrobbleSubmission>("scrobble-submission:$id") == null)
                    store.put("scrobble-submission:$id", "scrobble-submissions", ScrobbleSubmission(id, profile.id, listen.playbackId, listen, songId = songId))
            }
        }
    }

    private suspend fun reportNowPlaying(): String? = sending.withLock {
        val current = playback.value
        if (current.ended || !current.playWhenReady || current.error != null) {
            nowPlayingSent.clear(); nowPlayingRetryAt.clear()
            return@withLock null
        }
        if (!network.value.connected) {
            nowPlayingSent.clear(); nowPlayingRetryAt.clear()
            return@withLock null
        }
        if (!current.playing) return@withLock null
        val playbackId = current.playbackId ?: return@withLock null
        val track = current.trackId?.let { library.findTrack(it) } ?: return@withLock null
        val profiles = profiles().filter { it.enabled }
        val enabled = profiles.map { it.id }.toSet()
        nowPlayingSent.keys.retainAll(enabled); nowPlayingRetryAt.keys.retainAll(enabled)
        val listen = Listen(playbackId, track.id, playbackId, now(), track.metadata.copy(folder = ""),
            durationMs = current.durationMs.takeIf { it > 0 } ?: track.metadata.durationMs)
        var error: String? = null
        for (profile in profiles) {
            if (nowPlayingSent[profile.id] == playbackId || (nowPlayingRetryAt[profile.id] ?: 0) > now()) continue
            val connected = if (profile.service == ScrobblingService.NAVIDROME) navidrome() else null
            val songId = if (profile.service == ScrobblingService.NAVIDROME) track.sources.firstOrNull {
                it.kind == SourceKind.SERVER && it.id.startsWith("server:${profile.serverId}:")
            }?.location ?: continue else null
            if (profile.service == ScrobblingService.NAVIDROME && connected?.serverId != profile.serverId) continue
            if (profile.service != ScrobblingService.NAVIDROME && (listen.metadata.artist.isBlank() || listen.metadata.title.isBlank())) continue
            val token = if (profile.service == ScrobblingService.NAVIDROME) "" else vault.read("scrobbling:${profile.id}") ?: continue
            try {
                when (profile.service) {
                    ScrobblingService.LISTENBRAINZ -> ListenBrainzClient(http, token, profile.baseUrl).nowPlaying(listen)
                    ScrobblingService.LASTFM -> {
                        val credentials = store.json.decodeFromString<LastFmCredentials>(token)
                        LastFmClient(http, credentials.apiKey, credentials.sharedSecret, credentials.sessionKey, profile.baseUrl).nowPlaying(listen)
                    }
                    ScrobblingService.NAVIDROME -> sendNavidrome(requireNotNull(connected), requireNotNull(songId), listen.startedAt, submission = false)
                }
                nowPlayingSent[profile.id] = playbackId
                nowPlayingRetryAt.remove(profile.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ScrobbleException) {
                error = failure.message
                nowPlayingRetryAt[profile.id] = now() + 30_000
            }
        }
        error
    }

    suspend fun runPending() = sending.withLock {
        for (entry in submissions().filter { it.state == ScrobbleState.IN_FLIGHT })
            save(entry.copy(state = ScrobbleState.UNCERTAIN, error = "The previous submission was interrupted before confirmation"))
        if (!network.value.connected) return@withLock
        val profiles = profiles().filter { it.enabled }.associateBy { it.id }
        val unauthorized = submissions().filter { it.state == ScrobbleState.AUTHORIZATION }.map { it.profileId }.toMutableSet()
        for (entry in submissions().filter { it.state == ScrobbleState.PENDING && it.retryAt <= now() }) {
            val profile = profiles[entry.profileId] ?: continue
            if (profile.id in unauthorized) continue
            val listen = entry.listen ?: continue
            val connected = if (profile.service == ScrobblingService.NAVIDROME) navidrome() else null
            if (profile.service == ScrobblingService.NAVIDROME && connected?.serverId != profile.serverId) continue
            val token = if (profile.service == ScrobblingService.NAVIDROME) "" else vault.read("scrobbling:${profile.id}") ?: continue
            save(entry.copy(state = ScrobbleState.IN_FLIGHT, attempts = entry.attempts + 1))
            try {
                when (profile.service) {
                    ScrobblingService.LISTENBRAINZ -> ListenBrainzClient(http, token, profile.baseUrl).submit(listen)
                    ScrobblingService.LASTFM -> {
                        val credentials = store.json.decodeFromString<LastFmCredentials>(token)
                        LastFmClient(http, credentials.apiKey, credentials.sharedSecret, credentials.sessionKey, profile.baseUrl).submit(listen)
                    }
                    ScrobblingService.NAVIDROME -> sendNavidrome(requireNotNull(connected), requireNotNull(entry.songId), listen.startedAt)
                }
                save(entry.copy(state = ScrobbleState.CONFIRMED, listen = null, songId = null, attempts = entry.attempts + 1))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ScrobbleException) {
                val state = when (failure.failure) {
                    ScrobbleFailure.AUTHORIZATION -> ScrobbleState.AUTHORIZATION
                    ScrobbleFailure.REJECTED -> ScrobbleState.REJECTED
                    ScrobbleFailure.UNCERTAIN -> ScrobbleState.UNCERTAIN
                    ScrobbleFailure.RETRYABLE -> ScrobbleState.PENDING
                }
                if (state == ScrobbleState.AUTHORIZATION) unauthorized += profile.id
                val attempts = entry.attempts + 1
                // Back off from 30 s up to 30 min so an unreachable service is not polled forever at full rate.
                val delay = (30_000L shl (attempts - 1).coerceIn(0, 6)).coerceAtMost(1_800_000)
                save(entry.copy(state = state, attempts = attempts, error = failure.message,
                    retryAt = if (state == ScrobbleState.PENDING) now() + delay else 0))
            }
        }
    }

    private suspend fun sendNavidrome(client: NavidromeClient, songId: String, startedAt: Long, submission: Boolean = true) {
        try { client.scrobble(songId, startedAt, submission) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: NavidromeException) {
            val kind = when (failure.code) {
                40, 50 -> ScrobbleFailure.AUTHORIZATION
                10, 20, 30, 60, 70 -> ScrobbleFailure.REJECTED
                else -> ScrobbleFailure.UNCERTAIN
            }
            throw ScrobbleException(kind, "Navidrome submission failed: code ${failure.code}")
        } catch (error: Exception) {
            if (error is NavidromeRequestException && error.unreachable || error.isUnreachableHost())
                throw ScrobbleException(ScrobbleFailure.RETRYABLE, "The music server could not be reached")
            throw ScrobbleException(ScrobbleFailure.UNCERTAIN, "Navidrome did not confirm the submission")
        }
    }

    private suspend fun save(entry: ScrobbleSubmission) = store.put("scrobble-submission:${entry.id}", "scrobble-submissions", entry)
    override fun close() { scope.cancel() }
    suspend fun closeAndJoin() { close(); supervisor.join() }
}
