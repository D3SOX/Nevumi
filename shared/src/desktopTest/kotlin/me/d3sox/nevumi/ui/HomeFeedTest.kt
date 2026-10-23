package me.d3sox.nevumi.ui

import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.settings.CollectionLayout
import me.d3sox.nevumi.settings.CollectionSort
import kotlin.test.*

class HomeFeedTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private fun track(id: String, artist: String = "Artist", album: String = "Album", number: Int = 1,
        available: Boolean = true, favorite: Boolean = false, added: Long = now) = Track(id,
        TrackMetadata(id, artist, album, durationMs = 1_000, trackNumber = number),
        listOf(TrackSource("source:$id", SourceKind.LOCAL, "/music/$id.flac", available = available)), favorite, added)
    private fun listen(track: Track, ago: Long, qualified: Boolean = true) = Listen("${track.id}:$ago", track.id, "playback:$ago",
        now - ago, track.metadata, listenedMs = if (qualified) 800 else 100, playedMs = if (qualified) 800 else 100, durationMs = 1_000)

    @Test fun sectionsRespectListeningDatesQualificationsAlbumIdentityAndAvailability() {
        val first = track("First", number = 2, favorite = true)
        val second = track("Second", number = 1, added = now + 10)
        val old = track("Old", "Other artist", "Album", added = now - 100 * day)
        val unavailable = track("Unavailable", available = false)
        val listens = listOf(listen(first, 1_000), listen(first, day), listen(second, 500, qualified = false),
            listen(old, 90 * day), listen(unavailable, 0), listen(first, -day), listen(track("Removed"), 0))
        val feed = homeFeed(listOf(first, second, old, unavailable), listens, now, 42, "Unknown album", "Unknown artist")
        assertEquals(listOf(second, first, old), feed.recent)
        assertEquals(listOf(first), feed.top, "Partial, future and old listens do not count as top listens")
        assertEquals(listOf(old), feed.rediscover)
        assertEquals(second, feed.added.first())
        assertEquals(2, feed.recentAlbums.size, "Albums with the same title but different artists stay separate")
        assertEquals(listOf(second, first), feed.recentAlbums.first().tracks, "Album cards open the entire album in track order")
        assertEquals(listOf(first), feed.mixes.first { it.id == "favorites" }.tracks)
        assertEquals(listOf(second), feed.mixes.first { it.id == "discovery" }.tracks)
        assertTrue(feed.mixes.flatMap { it.tracks }.none { it == unavailable })
    }

    @Test fun filtersAndStableRefreshSeedsAreAppliedToEverySectionAndMix() {
        val tracks = (0 until 60).map { track("Track $it", "Artist $it", "Album $it") }
        val first = homeFeed(tracks, emptyList(), now, 42, "Unknown album", "Unknown artist")
        val second = homeFeed(tracks, emptyList(), now, 42, "Unknown album", "Unknown artist")
        assertEquals(first, second)
        assertNotEquals(first.mixes.first().tracks, homeFeed(tracks, emptyList(), now, 99, "Unknown album", "Unknown artist").mixes.first().tracks)
        assertTrue(first.mixes.all { it.tracks.size <= 50 })
        val filtered = homeFeed(tracks.take(2), tracks.map { listen(it, day) }, now, 42, "Unknown album", "Unknown artist")
        assertEquals(tracks.take(2).toSet(), filtered.recent.toSet())
        assertTrue(filtered.mixes.flatMap { it.tracks }.all { it in tracks.take(2) })
        val empty = homeFeed(emptyList(), tracks.map { listen(it, day) }, now, 42, "Unknown album", "Unknown artist")
        assertTrue(empty.mixes.isEmpty() && empty.recent.isEmpty() && empty.top.isEmpty())
    }

    @Test fun playlistsFollowTheOrderOfThePlaylistsPage() {
        val road = Playlist("road", "Music ▸ Road", listOf("a", "b", "c"))
        val alpha = Playlist("alpha", "Music ▸ Alpha", listOf("a"))
        val calm = Playlist("calm", "Calm", listOf("c"))
        val new = Playlist("new", "New", listOf("d"))
        val recentAt = mapOf("a" to 1L, "b" to 2L, "c" to 3L, "d" to 9L)
        val playlists = listOf(road, alpha, calm, new)
        fun order(layout: CollectionLayout) = homePlaylists(playlists, layout, recentAt).map { it.id }
        assertEquals(listOf("calm", "alpha", "road", "new"), order(CollectionLayout()), "Folders sort by their own name and keep their playlists together")
        assertEquals(listOf("new", "road", "alpha", "calm"), order(CollectionLayout(sort = CollectionSort.NAME, descending = true)))
        assertEquals(listOf("road", "alpha", "new", "calm"), order(CollectionLayout(sort = CollectionSort.SONG_COUNT, descending = true)))
        assertEquals(listOf("new", "road", "alpha", "calm"), order(CollectionLayout(sort = CollectionSort.RECENT, descending = true)))
        assertEquals(listOf("road", "alpha", "new", "calm"), order(CollectionLayout(sort = CollectionSort.CUSTOM,
            order = listOf(PLAYLIST_FOLDER_PREFIX + "Music", "new", "road", "alpha"))), "Custom order places folders like the Playlists page")
    }
}
