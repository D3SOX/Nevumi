package me.d3sox.nevumi.navidrome

enum class ServerRefreshStage {
    CONNECTING, WAITING, SONGS, FILES, IMPORTING, PLAYLISTS, ARTWORK, MATCHING, COMPLETE, CANCELLED, FAILED
}

data class ServerRefreshProgress(
    val stage: ServerRefreshStage,
    val processed: Int = 0,
    val total: Int? = null,
    val message: String? = null,
) {
    val running: Boolean get() = stage !in setOf(ServerRefreshStage.COMPLETE, ServerRefreshStage.CANCELLED, ServerRefreshStage.FAILED)
}

internal val NavidromeRequestException.serverUnavailable: Boolean get() = connectionFailure ||
    httpStatus?.let { it in setOf(401, 403, 429) || it >= 500 } == true
