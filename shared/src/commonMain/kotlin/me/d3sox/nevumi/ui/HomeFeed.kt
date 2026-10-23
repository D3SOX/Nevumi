package me.d3sox.nevumi.ui

import me.d3sox.nevumi.history.*
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.recentAt
import me.d3sox.nevumi.playlists.Playlist
import me.d3sox.nevumi.playlists.folder
import me.d3sox.nevumi.playlists.folderPath
import me.d3sox.nevumi.playlists.folderSegments
import me.d3sox.nevumi.playlists.title
import me.d3sox.nevumi.settings.CollectionLayout
import kotlin.random.Random
import org.jetbrains.compose.resources.StringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.library_mix_discovery
import me.d3sox.nevumi.resources.library_mix_discovery_description
import me.d3sox.nevumi.resources.library_mix_favorites
import me.d3sox.nevumi.resources.library_mix_favorites_description
import me.d3sox.nevumi.resources.library_mix_random
import me.d3sox.nevumi.resources.library_mix_random_description
import me.d3sox.nevumi.resources.library_mix_recommendations
import me.d3sox.nevumi.resources.library_mix_recommendations_description
import me.d3sox.nevumi.resources.library_mix_rediscover
import me.d3sox.nevumi.resources.library_mix_rediscover_description
import me.d3sox.nevumi.resources.library_mix_top
import me.d3sox.nevumi.resources.library_mix_top_description

internal data class HomeMix(val id: String, val title: StringResource, val description: StringResource, val tracks: List<Track>)
internal data class HomeFeed(val recent: List<Track>, val top: List<Track>, val rediscover: List<Track>,
    val added: List<Track>, val recentAlbums: List<TrackCollection>, val recentArtists: List<TrackCollection>,
    val topAlbums: List<TrackCollection>, val topArtists: List<TrackCollection>, val mixes: List<HomeMix>)

/** Builds the Home sections; [unknownAlbum] and [unknownArtist] name collections whose tag is blank. */
internal fun homeFeed(tracks: List<Track>, listens: List<Listen>, now: Long, randomSeed: Int,
    unknownAlbum: String, unknownArtist: String): HomeFeed {
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
            TrackCollection(id, first.album.ifBlank { unknownAlbum },
                songs.sortedWith(compareBy<Track> { it.metadata.discNumber }.thenBy { it.metadata.trackNumber }.thenBy { it.metadata.title }),
                first.albumArtist.ifBlank { first.artist })
        }
    }
    fun artist(track: Track) = track.metadata.artist.let { artist ->
        artistSongs[artist]?.let { TrackCollection(artist, artist.ifBlank { unknownArtist }, it) }
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
        fun mix(id: String, title: StringResource, description: StringResource, songs: List<Track>) {
            if (songs.isNotEmpty()) add(HomeMix(id, title, description, songs))
        }
        mix("random", Res.string.library_mix_random, Res.string.library_mix_random_description, playable.shuffled(Random(randomSeed)).take(50))
        mix("favorites", Res.string.library_mix_favorites, Res.string.library_mix_favorites_description, favorites)
        mix("discovery", Res.string.library_mix_discovery, Res.string.library_mix_discovery_description, discovery)
        mix("recommendations", Res.string.library_mix_recommendations, Res.string.library_mix_recommendations_description, recommendations)
        mix("rediscover", Res.string.library_mix_rediscover, Res.string.library_mix_rediscover_description, rediscover)
        mix("top", Res.string.library_mix_top, Res.string.library_mix_top_description, top)
    }
    return HomeFeed(recent, top, rediscover, added, recentAlbums, recentArtists, topAlbums, topArtists, mixes)
}

/** Playlists in the order the Playlists page shows them, with each folder's contents where the folder sits. */
internal fun homePlaylists(playlists: List<Playlist>, layout: CollectionLayout, recentAt: Map<String, Long>): List<Playlist> {
    val comparator = compareBy(layout.comparator()) { entry: Pair<CollectionItem, List<Playlist>> -> entry.first }
    fun item(id: String, title: String, trackIds: List<String>) = CollectionItem(id, title, "", null, AppIcon.QUEUE,
        songCount = trackIds.size, recentAt = trackIds.maxOfOrNull { recentAt[it] ?: 0 } ?: 0)
    fun ordered(folder: List<String>, contents: List<Playlist>): List<Playlist> {
        val (own, nested) = contents.partition { it.folder?.let(::folderSegments).orEmpty().size == folder.size }
        return (own.map { item(it.id, it.title, it.trackIds) to listOf(it) } +
            nested.groupBy { folderSegments(it.folder!!)[folder.size] }.map { (name, inside) ->
                item(PLAYLIST_FOLDER_PREFIX + folderPath(folder + name), name, inside.flatMap(Playlist::trackIds).distinct()) to
                    ordered(folder + name, inside)
            }).sortedWith(comparator).flatMap { it.second }
    }
    return ordered(emptyList(), playlists).take(20)
}
