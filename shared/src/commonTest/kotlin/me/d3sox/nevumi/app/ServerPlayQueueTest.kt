package me.d3sox.nevumi.app

import me.d3sox.nevumi.navidrome.ServerPlayQueue
import me.d3sox.nevumi.queue.QueueEntry
import me.d3sox.nevumi.queue.QueueSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerPlayQueueTest {
    private fun queue(trackIds: List<String>, current: Int) = trackIds.mapIndexed { index, id -> QueueEntry("entry-$index", id) }
        .let { QueueSnapshot(it, it[current].id) }

    @Test
    fun leavesOutLocalSongsAndEarlierPlaysOfTheCurrentSong() {
        val snapshot = queue(listOf("b", "local", "a", "b", "c", "local"), current = 3)
        val shared = snapshot.serverPlayQueue(42_000) { if (it == "local") null else "song-$it" }
        assertEquals(ServerPlayQueue(listOf("song-a", "song-b", "song-c"), "song-b", 42_000), shared)
    }

    @Test
    fun keepsAShortHistoryAndFillsTheRestWithUpcomingSongs() {
        val tracks = (0 until 500).map { "t$it" }
        val shared = requireNotNull(queue(tracks, current = 100).serverPlayQueue(0) { it })
        assertEquals(SERVER_QUEUE_LIMIT, shared.songIds.size)
        assertEquals("t80", shared.songIds.first())
        assertEquals("t100", shared.songIds[SERVER_QUEUE_HISTORY])
        assertEquals("t279", shared.songIds.last())
    }

    @Test
    fun aQueueWhoseCurrentSongIsOnlyLocalIsNotShared() {
        assertNull(queue(listOf("a", "local"), current = 1).serverPlayQueue(0) { if (it == "local") null else it })
        assertNull(QueueSnapshot().serverPlayQueue(0) { it })
    }
}
