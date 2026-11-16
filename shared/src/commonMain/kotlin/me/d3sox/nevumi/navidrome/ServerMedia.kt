package me.d3sox.nevumi.navidrome

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** An internet radio station the music server lists; players open [streamUrl] themselves. */
data class RadioStation(val id: String, val name: String, val streamUrl: String, val homepageUrl: String = "")

enum class PodcastStatus { NEW, DOWNLOADING, COMPLETED, ERROR, DELETED, SKIPPED;
    companion object {
        fun of(value: String): PodcastStatus = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NEW
    }
}

/**
 * A podcast episode. The server streams it under [streamId] once it has downloaded it ([PodcastStatus.COMPLETED]); until
 * then it can be asked to download it.
 */
data class PodcastEpisode(val id: String, val channelId: String, val title: String, val streamId: String? = null,
    val description: String = "", val publishDate: String = "", val status: PodcastStatus = PodcastStatus.NEW,
    val durationMs: Long = 0, val coverArt: String? = null, val channelTitle: String = "", val artist: String = "") {
    val playable: Boolean get() = status == PodcastStatus.COMPLETED && streamId != null
}

data class PodcastChannel(val id: String, val url: String, val title: String, val description: String = "",
    val coverArt: String? = null, val status: PodcastStatus = PodcastStatus.NEW, val error: String = "",
    val episodes: List<PodcastEpisode> = emptyList())

/** A public link to songs, an album or a playlist; [url] opens a page anyone can listen on. */
data class ServerShare(val id: String, val url: String, val description: String = "", val created: String = "",
    val expires: String? = null, val visitCount: Int = 0, val entryTitles: List<String> = emptyList())

/** What the account may do on the server, from `getUser`; a server that does not say grants everything it supports. */
data class ServerRoles(val admin: Boolean = true, val podcast: Boolean = true, val share: Boolean = true)

private fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Subsonic servers write a list with one entry as a single object and an empty list as null, an empty object or nothing. */
private fun JsonElement?.entries(name: String): List<JsonObject> = when (val value = (this as? JsonObject)?.get(name)) {
    is JsonArray -> value.mapNotNull { it as? JsonObject }
    is JsonObject -> listOf(value)
    else -> emptyList()
}

internal fun parseRadioStations(envelope: JsonObject): List<RadioStation> =
    envelope["internetRadioStations"].entries("internetRadioStation").mapNotNull { station ->
        val id = station.string("id").ifBlank { return@mapNotNull null }
        val stream = station.string("streamUrl").ifBlank { return@mapNotNull null }
        // The API documents homePageUrl; some servers write homepageUrl, as the request parameter is named.
        RadioStation(id, station.string("name").ifBlank { stream }, stream, station.string("homePageUrl").ifBlank { station.string("homepageUrl") })
    }

private fun parseEpisode(episode: JsonObject, channel: JsonObject? = null): PodcastEpisode? {
    val id = episode.string("id").ifBlank { return null }
    return PodcastEpisode(id, episode.string("channelId").ifBlank { channel?.string("id").orEmpty() },
        episode.string("title").ifBlank { id }, episode.string("streamId").ifBlank { null }, episode.string("description"),
        episode.string("publishDate"), PodcastStatus.of(episode.string("status")),
        ((episode["duration"] as? JsonPrimitive)?.longOrNull ?: 0).coerceAtLeast(0) * 1_000,
        episode.string("coverArt").ifBlank { null } ?: channel?.string("coverArt")?.ifBlank { null },
        channel?.string("title")?.ifBlank { null } ?: episode.string("album"), episode.string("artist"))
}

internal fun parsePodcasts(envelope: JsonObject): List<PodcastChannel> = envelope["podcasts"].entries("channel").mapNotNull { channel ->
    val id = channel.string("id").ifBlank { return@mapNotNull null }
    PodcastChannel(id, channel.string("url"), channel.string("title").ifBlank { channel.string("url") }, channel.string("description"),
        channel.string("coverArt").ifBlank { null }, PodcastStatus.of(channel.string("status")), channel.string("errorMessage"),
        channel.entries("episode").mapNotNull { parseEpisode(it, channel) }.sortedByDescending { it.publishDate })
}

internal fun parseNewestPodcasts(envelope: JsonObject, channels: List<PodcastChannel> = emptyList()): List<PodcastEpisode> {
    val titles = channels.associate { it.id to it.title }
    return envelope["newestPodcasts"].entries("episode").mapNotNull { parseEpisode(it) }
        .map { episode -> titles[episode.channelId]?.let { episode.copy(channelTitle = it) } ?: episode }
}

internal fun parseShares(envelope: JsonObject): List<ServerShare> = envelope["shares"].entries("share").mapNotNull(::parseShare)

internal fun parseShare(share: JsonObject): ServerShare? {
    val id = share.string("id").ifBlank { return null }
    val url = share.string("url").ifBlank { return null }
    return ServerShare(id, url, share.string("description"), share.string("created"), share.string("expires").ifBlank { null },
        (share["visitCount"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
        share.entries("entry").map { it.string("title").ifBlank { it.string("name") } }.filter { it.isNotBlank() })
}

internal fun parseRoles(envelope: JsonObject): ServerRoles {
    val user = envelope["user"] as? JsonObject ?: return ServerRoles()
    fun role(name: String) = (user[name] as? JsonPrimitive)?.booleanOrNull ?: true
    return ServerRoles(role("adminRole"), role("podcastRole"), role("shareRole"))
}

internal fun parseBookmarks(envelope: JsonObject): Map<String, Long> = envelope["bookmarks"].entries("bookmark").mapNotNull { bookmark ->
    val id = (bookmark["entry"] as? JsonObject)?.string("id")?.ifBlank { null } ?: return@mapNotNull null
    id to ((bookmark["position"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null).coerceAtLeast(0)
}.toMap()
