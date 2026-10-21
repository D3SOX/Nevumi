package me.d3sox.nevumi.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.history.LastFmAuthorization
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.history.ScrobblingService

/** Keeps the listening history and the scrobbling services: sign-in, submissions and clearing the history. */
class HistoryController internal constructor(private val context: ControllerContext, private val listeningHistory: ListeningHistory?,
    private val scrobbling: Scrobbling?) {
    val scrobblingAvailable: Boolean get() = scrobbling != null

    init {
        scrobbling?.start()
        scrobbling?.let { service -> context.scope.launch {
            service.observe().collect { status -> context.operations.withLock { context.update { it.copy(scrobbling = status) } } }
        } }
        listeningHistory?.let { history -> context.scope.launch {
            history.observe().collect { listens ->
                context.operations.withLock { context.update { it.copy(listens = listens) } }
            }
        } }
    }

    /** Shows a Last.fm sign-in again that was started but not finished before the app closed. */
    internal suspend fun loadPendingLastFmAuthorization() {
        val authorization = scrobbling?.pendingLastFmAuthorization()
        context.update { it.copy(lastFmAuthorization = authorization) }
    }
    fun clearListeningHistory() = context.action {
        val history = requireNotNull(listeningHistory) { "Listening history is unavailable" }
        if (scrobbling != null) scrobbling.clearHistory(history) else history.clear()
        context.update { it.copy(listens = emptyList()) }
    }
    suspend fun connectListenBrainz(token: String, address: String = "https://api.listenbrainz.org") =
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.connectListenBrainz(token, address)
    suspend fun startLastFmSignIn(apiKey: String, sharedSecret: String): LastFmAuthorization {
        val authorization = requireNotNull(scrobbling) { "Scrobbling is unavailable" }.startLastFmAuthorization(apiKey, sharedSecret)
        context.update { it.copy(lastFmAuthorization = authorization) }
        return authorization
    }
    suspend fun completeLastFmSignIn() {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.completeLastFmAuthorization()
        context.update { it.copy(lastFmAuthorization = null) }
    }
    suspend fun cancelLastFmSignIn() {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.cancelLastFmAuthorization()
        context.update { it.copy(lastFmAuthorization = null) }
    }
    fun setScrobblingEnabled(id: String, enabled: Boolean) = context.action(serialized = false) {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.setEnabled(id, enabled)
    }
    fun retryScrobbleSubmission(id: String) = context.action(serialized = false) {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.retrySubmission(id)
    }
    fun discardScrobbleSubmission(id: String) = context.action(serialized = false) {
        requireNotNull(scrobbling) { "Scrobbling is unavailable" }.discardSubmission(id)
    }
    fun disconnectScrobbling(id: String) = context.action(serialized = false) {
        if (requireNotNull(scrobbling) { "Scrobbling is unavailable" }.disconnect(id) == ScrobblingService.LASTFM)
            context.update { it.copy(lastFmAuthorization = null) }
    }
}
