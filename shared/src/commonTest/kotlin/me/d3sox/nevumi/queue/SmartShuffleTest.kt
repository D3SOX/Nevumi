package me.d3sox.nevumi.queue

import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SmartShuffleTest {
    @Test
    fun favoritesAndHigherRatingsWeighMoreAndAnUnratedSongCountsAsThreeStars() {
        fun weight(rating: Int, favorite: Boolean = false) = smartShuffleWeight(rating, favorite, lastPlayedAt = null, NOW)
        assertEquals(1.0, weight(0))
        assertEquals(1.0, weight(3))
        assertTrue(weight(1) < weight(2) && weight(2) < weight(0) && weight(0) < weight(4) && weight(4) < weight(5))
        assertEquals(2.25, weight(5))
        assertEquals(2.0, weight(0, favorite = true))
        assertEquals(2 * weight(5), weight(5, favorite = true))
    }

    @Test
    fun aPlayedSongWeighsLessAndRecoversOverTheFollowingDaysWithoutReachingZero() {
        fun weight(ageMs: Long) = smartShuffleWeight(0, false, NOW - ageMs, NOW)
        assertEquals(0.1, weight(0), 1e-9)
        assertEquals(0.55, weight(SMART_SHUFFLE_RECENT_HALF_LIFE_MS), 1e-9)
        assertTrue(weight(HOUR) < weight(DAY) && weight(DAY) < weight(WEEK))
        assertTrue(weight(4 * WEEK) > 0.99)
        // A listen from a paired device whose clock runs ahead counts as just played.
        assertEquals(weight(0), smartShuffleWeight(0, false, NOW + HOUR, NOW))
        assertTrue(smartShuffleWeight(1, false, NOW, NOW) > 0)
    }

    @Test
    fun aWeightedShuffleKeepsEveryOccurrenceAndRepeatsWithTheSameSeed() {
        val items = (0 until 50).toList() + listOf(7, 7)
        val weight = { item: Int -> 1.0 + item % 5 }
        val shuffled = items.weightedShuffled(Random(3), weight)
        assertEquals(items.sorted(), shuffled.sorted())
        assertEquals(shuffled, items.weightedShuffled(Random(3), weight))
        assertNotEquals(items, shuffled)
    }

    @Test
    fun anItemWeighingAsMuchAsNineOthersTogetherComesFirstHalfTheTime() {
        val random = Random(17)
        val items = (0 until 10).toList()
        var first = 0
        var positions = 0
        repeat(TRIALS) {
            val order = items.weightedShuffled(random) { if (it == 0) 9.0 else 1.0 }
            if (order.first() == 0) first++
            positions += order.indexOf(0)
        }
        // 9 / (9 + 9) is one half, with a standard deviation of 50 over 10 000 shuffles.
        assertTrue(first in 4_800..5_200, "First in $first of $TRIALS shuffles")
        // Its expected position is 0.9; a uniform shuffle would place it at 4.5.
        assertTrue(positions.toDouble() / TRIALS in 0.8..1.0, "Average position ${positions.toDouble() / TRIALS}")
    }

    @Test
    fun equalWeightsShuffleUniformly() {
        val random = Random(5)
        val items = (0 until 5).toList()
        val firsts = IntArray(items.size)
        repeat(TRIALS) { firsts[items.weightedShuffled(random) { 1.0 }.first()]++ }
        // 2 000 each, with a standard deviation of 40.
        assertTrue(firsts.all { it in 1_840..2_160 }, firsts.joinToString())
    }

    @Test
    fun theStartingSongIsPickedByTheSameWeights() {
        val random = Random(11)
        val weights = listOf(1.0, 2.0, 7.0)
        val picks = IntArray(weights.size)
        repeat(TRIALS) { picks[weights.weightedIndex(random) { it }]++ }
        assertTrue(picks[0] in 880..1_120 && picks[1] in 1_840..2_160 && picks[2] in 6_820..7_180, picks.joinToString())
        assertEquals(-1, emptyList<Double>().weightedIndex(random) { it })
    }

    @Test
    fun favoritesAndHighRatingsComeEarlierAndRecentSongsLaterButNoSongIsLeftOut() {
        val plain = (1..20).map { track("plain-$it") }
        val tracks = plain + listOf(track("loved", rating = 5, favorite = true), track("liked", rating = 4, kind = SourceKind.SERVER),
            track("disliked", rating = 1), track("recent"))
        // Only the newest listen counts, and a listen two months ago barely matters.
        val listens = listOf(listen("recent", NOW - 10 * MINUTE), listen("recent", NOW - 30 * DAY), listen("plain-1", NOW - 60 * DAY))
        val weight = smartShuffleWeights(tracks, listens, NOW)
        val ids = tracks.map { it.id }
        val random = Random(29)
        val positions = mutableMapOf<String, Long>()
        var recentNearTheTop = 0
        repeat(TRIALS) {
            val order = ids.weightedShuffled(random, weight)
            assertEquals(ids.sorted(), order.sorted())
            order.forEachIndexed { index, id -> positions[id] = (positions[id] ?: 0) + index }
            if (order.indexOf("recent") < 5) recentNearTheTop++
        }
        fun average(id: String) = positions.getValue(id).toDouble() / TRIALS
        val plainAverage = plain.map { average(it.id) }.average()
        assertTrue(average("loved") < average("liked") && average("liked") < plainAverage && plainAverage < average("disliked") &&
            average("disliked") < average("recent"), positions.toString())
        assertTrue(recentNearTheTop > 0)
    }

    @Test
    fun aLinkedLocalAndServerCopyIsOneSongWithOneWeight() {
        val linked = Track("song", TrackMetadata("Song"), listOf(TrackSource("local:/music/song.flac", SourceKind.LOCAL, "/music/song.flac"),
            TrackSource("server:home:42", SourceKind.SERVER, "42")), favorite = true, rating = 4)
        val weight = smartShuffleWeights(listOf(linked), listOf(listen("song", NOW - DAY)), NOW)
        assertEquals(smartShuffleWeight(4, true, NOW - DAY, NOW), weight("song"))
        assertEquals(1.0, weight("missing"))
    }

    private fun track(id: String, rating: Int = 0, favorite: Boolean = false, kind: SourceKind = SourceKind.LOCAL) =
        Track(id, TrackMetadata(id), listOf(TrackSource("${kind.name.lowercase()}:$id", kind, id)), favorite, rating = rating)

    private fun listen(trackId: String, startedAt: Long) =
        Listen("$trackId@$startedAt", trackId, "playback-$trackId@$startedAt", startedAt, TrackMetadata(trackId))

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        const val WEEK = 7 * DAY
        const val TRIALS = 10_000
    }
}
