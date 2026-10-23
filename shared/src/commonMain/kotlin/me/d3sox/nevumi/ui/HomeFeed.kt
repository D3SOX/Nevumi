package me.d3sox.nevumi.ui

import me.d3sox.nevumi.history.*
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.recentAt
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.title
import me.d3sox.nevumi.settings.CollectionLayout
import kotlin.random.Random

internal data class HomeMix(val id: String, val title: String, val description: String, val tracks: List<Track>)
internal data class HomeFeed(val recent: List<Track>, val top: List<Track>, val rediscover: List<Track>,
    val added: List<Track>, val recentAlbums: List<TrackCollection>, val recentArtists: List<TrackCollection>,
    val topAlbums: List<TrackCollection>, val topArtists: List<TrackCollection>, val mixes: List<HomeMix>)

internal fun homeFeed(tracks: List<Track>, listens: List<Listen>, now: Long, randomSeed: Int): HomeFeed {
    val playable = tracks.filter { track -> track.sources.any { it.available } }
    val catalog = playable.associateBy { it.id }
    val events = listens.filter { it.trackId in catalog && it.startedAt <= now }
    val statistics = listeningStatistics(events)
    val recent = events.sortedByDescending { it.startedAt }.distinctBy { it.trackId }.map { catalog.getValue(it.trackId) }.take(20)
    val report = listeningReport(events, playable, ListeningPeriod.LAST_30_DAYS, now)
    val top = report.songs.filter { it.playCount > 0 }.map { catalog.getValue(it.trackIds.single()) }.take(20)
    val rediscover = playable.filter { track -> statistics[track.id]?.let { it.playCount > 0 && it.lastPlayedAt < now - 30 * 86_400_000L } == true }
        .sortedWith(compareByDescending<Track> { statistics.getValue(it.id).playCount }.thenBy { statistics.getValue(it.id).lastPlayedAt }).take(20)
    val added = playable.map { Triple(it, it.recentAt, it.metadata.title.lowercase()) }
        .sortedWith(compareByDescending<Triple<Track, Long, String>> { it.second }.thenBy { it.third }).take(20).map { it.first }
    val albumSongs = playable.groupBy { albumCollectionId(it.metadata) }
    val artistSongs = playable.groupBy { it.metadata.artist }
    // Only a few albums and artists are shown, so build their collections on demand.
    val albums = mutableMapOf<String, TrackCollection>()
    fun album(track: Track): TrackCollection? {
        val id = albumCollectionId(track.metadata)
        val songs = albumSongs[id] ?: return null
        return albums.getOrPut(id) {
            val first = songs.first().metadata
            TrackCollection(id, first.album.ifBlank { "Unknown album" },
                songs.sortedWith(compareBy<Track> { it.metadata.discNumber }.thenBy { it.metadata.trackNumber }.thenBy { it.metadata.title }),
                first.albumArtist.ifBlank { first.artist })
        }
    }
    fun artist(track: Track) = track.metadata.artist.let { artist ->
        artistSongs[artist]?.let { TrackCollection(artist, artist.ifBlank { "Unknown artist" }, it) }
    }
    val recentAlbums = recent.mapNotNull(::album).distinctBy { it.id }.take(12)
    val recentArtists = recent.distinctBy { it.metadata.artist }.mapNotNull(::artist).take(12)
    val topAlbums = report.albums.filter { it.playCount > 0 }.mapNotNull { rank ->
        rank.trackIds.firstOrNull()?.let(catalog::get)?.let(::album)
    }.distinctBy { it.id }.take(12)
    val topArtists = report.artists.filter { it.playCount > 0 }.mapNotNull { rank ->
        rank.trackIds.firstOrNull()?.let(catalog::get)
    }.distinctBy { it.metadata.artist }.mapNotNull(::artist).take(12)
    val discovery = playable.filter { (statistics[it.id]?.playCount ?: 0) == 0 }.shuffled(Random(randomSeed)).take(50)
    val recommendations = listeningRecommendations(events, playable, limit = 50, now = now).map { it.track }
    val favorites = playable.filter { it.favorite }.shuffled(Random(randomSeed)).take(50)
    val mixes = buildList {
        fun mix(id: String, title: String, description: String, songs: List<Track>) {
            if (songs.isNotEmpty()) add(HomeMix(id, title, description, songs))
        }
        mix("random", "Random mix", "A shuffle of your library", playable.shuffled(Random(randomSeed)).take(50))
        mix("favorites", "Favorites mix", "The songs you love", favorites)
        mix("discovery", "Discovery mix", "Songs you haven’t played yet", discovery)
        mix("recommendations", "For you", "From your listening history", recommendations)
        mix("rediscover", "Rediscover mix", "Songs you haven’t heard in a while", rediscover)
        mix("top", "Top listens mix", "Your most played in the last 30 days", top)
    }
    return HomeFeed(recent, top, rediscover, added, recentAlbums, recentArtists, topAlbums, topArtists, mixes)
}

/** Playlists in the order the Playlists page sorts them, so Home shows the same ones first. */
internal fun homePlaylists(playlists: List<Playlist>, layout: CollectionLayout, recentAt: Map<String, Long>): List<Playlist> {
    val byId = playlists.associateBy { it.id }
    return playlists.map { playlist ->
        CollectionItem(playlist.id, playlist.title, "", null, AppIcon.QUEUE, songCount = playlist.trackIds.size,
            recentAt = playlist.trackIds.maxOfOrNull { recentAt[it] ?: 0 } ?: 0)
    }.sortedWith(layout.comparator()).take(20).map { byId.getValue(it.id) }
}
