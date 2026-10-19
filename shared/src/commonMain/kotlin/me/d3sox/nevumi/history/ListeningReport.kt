package me.d3sox.nevumi.history

import me.d3sox.nevumi.library.Track
import kotlin.time.Clock

enum class ListeningPeriod(val days: Int?) {
    ALL_TIME(null), LAST_7_DAYS(7), LAST_30_DAYS(30), LAST_365_DAYS(365)
}

data class ListeningRank(val name: String, val detail: String = "", val trackIds: List<String>,
    val playCount: Int, val listenedMs: Long)

data class ListeningReport(val playCount: Int, val listenedMs: Long, val songs: List<ListeningRank>,
    val artists: List<ListeningRank>, val albums: List<ListeningRank>, val genres: List<ListeningRank>)

fun listeningReport(listens: List<Listen>, tracks: List<Track>, period: ListeningPeriod = ListeningPeriod.ALL_TIME,
    now: Long = Clock.System.now().toEpochMilliseconds()): ListeningReport {
    val catalog = tracks.associateBy { it.id }
    val cutoff = period.days?.let { now - it * 86_400_000L } ?: Long.MIN_VALUE
    val events = listens.filter { it.trackId in catalog && it.startedAt in cutoff..now }
    // Songs are usually played many times, so total the listens per song before grouping by lowercased names.
    class Totals(val track: Track, var plays: Int = 0, var milliseconds: Long = 0)
    val totals = linkedMapOf<String, Totals>()
    for (event in events) totals.getOrPut(event.trackId) { Totals(catalog.getValue(event.trackId)) }.apply {
        if (event.qualified) plays++
        milliseconds += event.listenedMs
    }
    fun ranks(keys: (Track) -> List<Pair<String, String>>): List<ListeningRank> {
        data class Group(val name: String, val detail: String, val ids: MutableSet<String> = linkedSetOf(),
            var plays: Int = 0, var milliseconds: Long = 0)
        val groups = linkedMapOf<Pair<String, String>, Group>()
        for (total in totals.values) {
            for ((name, detail) in keys(total.track).distinctBy { it.first.lowercase() to it.second.lowercase() }) {
                val group = groups.getOrPut(name.lowercase() to detail.lowercase()) { Group(name, detail) }
                group.ids += total.track.id
                group.plays += total.plays
                group.milliseconds += total.milliseconds
            }
        }
        return groups.values.map { ListeningRank(it.name, it.detail, it.ids.toList(), it.plays, it.milliseconds) }
            .sortedByRank { it.detail.lowercase() }
    }
    // Song identity is the canonical ID; titles can be identical for different recordings.
    val songs = totals.map { (id, total) ->
        ListeningRank(total.track.metadata.title, total.track.metadata.artist, listOf(id), total.plays, total.milliseconds)
    }.sortedByRank { it.trackIds.single() }
    return ListeningReport(events.count { it.qualified }, events.sumOf { it.listenedMs }, songs,
        ranks { listOf(it.metadata.artist.ifBlank { "Unknown artist" } to "") },
        ranks { listOf(it.metadata.album.ifBlank { "Unknown album" } to it.metadata.albumArtist.ifBlank { it.metadata.artist }) },
        ranks { it.metadata.genres.ifEmpty { listOf("Unknown genre") }.map { genre -> genre to "" } })
}

/** Lowercases each name once instead of on every comparison. */
private fun List<ListeningRank>.sortedByRank(tiebreak: (ListeningRank) -> String): List<ListeningRank> =
    map { Triple(it, it.name.lowercase(), tiebreak(it)) }
        .sortedWith(compareByDescending<Triple<ListeningRank, String, String>> { it.first.playCount }
            .thenByDescending { it.first.listenedMs }.thenBy { it.second }.thenBy { it.third })
        .map { it.first }
