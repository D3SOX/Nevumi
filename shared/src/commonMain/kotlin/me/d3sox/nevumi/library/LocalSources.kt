package me.d3sox.nevumi.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface LocalSources {
    suspend fun scan(location: String, force: Boolean = false, onProgress: suspend (LocalScanProgress) -> Unit = {}): List<Track>
    /** Indexes one song the user chose, such as a dropped file, even where the folder rules keep it out of the library. */
    suspend fun scanSong(location: String): List<Track> = scan(location)
    /** Finds where the library indexes a song another app opened in Nevumi, such as a file manager's link to it. */
    suspend fun openedSong(uri: String): String = uri
    suspend fun roots(): List<String> = emptyList()
    suspend fun pendingRefreshLocations(): List<String> = emptyList()
    /** Locations to scan again after a change on disk; each emission names every location to rescan. */
    val changes: Flow<List<String>> get() = emptyFlow()
}

data class LocalScanProgress(val processed: Int, val total: Int)
