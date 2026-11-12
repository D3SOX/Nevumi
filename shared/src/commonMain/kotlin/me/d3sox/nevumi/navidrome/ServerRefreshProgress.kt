package me.d3sox.nevumi.navidrome

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ServerRefreshStage {
    CONNECTING, WAITING, SONGS, FILES, IMPORTING, PLAYLISTS, ARTWORK, MATCHING, COMPLETE, CANCELLED, FAILED
}

data class ServerRefreshProgress(
    val stage: ServerRefreshStage,
    val processed: Int = 0,
    val total: Int? = null,
    val message: String? = null,
    /** Playlists and covers the server did not deliver; the refresh went on without them and the next one fetches them again. */
    val skippedPlaylists: Int = 0,
    val skippedCovers: Int = 0,
) {
    val running: Boolean get() = stage !in setOf(ServerRefreshStage.COMPLETE, ServerRefreshStage.CANCELLED, ServerRefreshStage.FAILED)
}

internal val NavidromeRequestException.serverUnavailable: Boolean get() = connectionFailure ||
    httpStatus?.let { it in setOf(401, 403, 429) || it >= 500 } == true

/** The server is gone, rejects the account or asks clients to back off, so every further request would fail as well. */
private val NavidromeRequestException.stopsRefresh: Boolean get() = unreachable || httpStatus in setOf(401, 403, 429)

/**
 * Lets a refresh skip playlists and covers the server fails to deliver, even after retries, such as a huge playlist during a stall.
 * Failures that every further request would share still stop the refresh, and so do [limit] skipped items in a row,
 * because by then the server is down rather than briefly busy.
 */
class SkippedItems(private val limit: Int = 10) {
    private val lock = Mutex()
    private var inARow = 0
    var playlists = 0
        private set
    var covers = 0
        private set

    internal suspend fun delivered() = lock.withLock { inARow = 0 }
    internal suspend fun playlist(failure: NavidromeRequestException) = skip(failure) { playlists++ }
    internal suspend fun cover(failure: NavidromeRequestException) = skip(failure) { covers++ }

    private suspend fun skip(failure: NavidromeRequestException, count: () -> Unit) {
        if (failure.stopsRefresh) throw failure
        lock.withLock {
            count()
            if (++inARow >= limit) throw failure
        }
    }
}
