package me.d3sox.nevumi.releases

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.d3sox.nevumi.artwork.readArtwork
import me.d3sox.nevumi.library.musicBrainzId
import me.d3sox.nevumi.storage.MusicStore
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Serializable
enum class ReleaseType { ALBUM, SINGLE, EP, OTHER }

/** An artist whose releases are tracked by MusicBrainz [id]; [libraryName] names the library's artist page it was followed from. */
@Serializable
data class FollowedArtist(val id: String, val name: String, val disambiguation: String = "", val libraryName: String = name,
    val followedAt: Long = 0, val checkedAt: Long? = null)

/** A MusicBrainz artist found by name, for the user to confirm. */
data class ArtistCandidate(val id: String, val name: String, val disambiguation: String = "", val type: String = "",
    val country: String = "")

/** A public MusicBrainz collection of [artistCount] artists. */
data class ArtistCollection(val id: String, val name: String, val artistCount: Int)

/** An artist offered for import; one without a MusicBrainz [id] is looked up by [name] when it is followed. */
data class ImportableArtist(val name: String, val id: String? = null, val disambiguation: String = "", val plays: Int? = null)

/**
 * A release group of a followed artist. [date] is its first release date as MusicBrainz has it: YYYY-MM-DD, YYYY-MM or YYYY.
 * [releaseIds] are its releases, which music files name in their MusicBrainz tags.
 */
@Serializable
data class ArtistRelease(val id: String, val title: String, val artist: String, val artistIds: List<String>, val date: String,
    val type: ReleaseType = ReleaseType.OTHER, val releaseIds: List<String> = emptyList(), val discoveredAt: Long = 0) {
    val musicBrainzUrl: String get() = "https://musicbrainz.org/release-group/$id"
}

/** The UTC date of [epochMillis] as YYYY-MM-DD, which compares like MusicBrainz dates. */
internal fun releaseDate(epochMillis: Long): String = Instant.fromEpochMilliseconds(epochMillis).toString().take(10)

/**
 * Looks up artists and their release groups on MusicBrainz and their covers on the Cover Art Archive. Requests are spaced
 * by [spacingMs], as MusicBrainz asks clients to send at most one per second.
 */
class MusicBrainzClient(private val http: HttpClient, version: String, private val baseUrl: String = "https://musicbrainz.org/ws/2",
    private val coverArtUrl: String = "https://coverartarchive.org", private val spacingMs: Long = 1_100) {
    private val userAgent = "Nevumi/$version (https://github.com/D3SOX/Nevumi)"
    private val pacing = Mutex()
    private var lastRequest: TimeMark? = null

    suspend fun searchArtists(name: String): List<ArtistCandidate> {
        if (name.isBlank()) return emptyList()
        val body = request("artist", mapOf("query" to "\"${escape(name.trim())}\"", "limit" to "25"))
        return body.array("artists").map { artist ->
            ArtistCandidate(artist.text("id"), artist.text("name"), artist.text("disambiguation"), artist.text("type"),
                artist.text("country"))
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    /** The release groups crediting [artistId] that were first released on or after [since], a YYYY-MM-DD date. */
    suspend fun releases(artistId: String, since: String): List<ArtistRelease> {
        require(artistId.matches(musicBrainzId)) { "Not a MusicBrainz artist ID" }
        // A search returns at most 100 release groups at a time; a missed page would never be announced.
        val groups = mutableListOf<JsonObject>()
        do {
            val body = request("release-group", mapOf("query" to "arid:$artistId AND firstreleasedate:[$since TO *]", "limit" to "100",
                "offset" to groups.size.toString()))
            val page = body.array("release-groups")
            groups += page
        } while (page.isNotEmpty() && groups.size < body.count("count"))
        return groups.mapNotNull { group ->
            val credits = group.array("artist-credit")
            ArtistRelease(id = group.text("id").ifBlank { return@mapNotNull null }, title = group.text("title"),
                // A credit may leave out its name when it is the artist's own; join phrases such as " & " carry their own spacing.
                artist = credits.joinToString("") { credit ->
                    credit.text("name").ifBlank { (credit["artist"] as? JsonObject)?.text("name").orEmpty() } +
                        (credit["joinphrase"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                }.trim(),
                artistIds = credits.mapNotNull { (it["artist"] as? JsonObject)?.text("id")?.ifBlank { null } },
                date = group.text("first-release-date").ifBlank { return@mapNotNull null },
                type = when (group.text("primary-type")) {
                    "Album" -> ReleaseType.ALBUM
                    "Single" -> ReleaseType.SINGLE
                    "EP" -> ReleaseType.EP
                    else -> ReleaseType.OTHER
                }, releaseIds = group.array("releases").map { it.text("id") }.filter { it.isNotBlank() })
        }
    }

    /** The artist collections [editor] has made public; none when MusicBrainz has no such user. */
    suspend fun artistCollections(editor: String): List<ArtistCollection> {
        if (editor.isBlank()) return emptyList()
        val collections = mutableListOf<JsonObject>()
        do {
            val body = requestOrNull("collection", mapOf("editor" to editor.trim(), "limit" to "100", "offset" to collections.size.toString()))
                ?: return emptyList()
            val page = body.array("collections")
            collections += page
        } while (page.isNotEmpty() && collections.size < body.count("collection-count"))
        return collections.filter { it.text("entity-type") == "artist" && it.text("id").isNotBlank() }
            .map { ArtistCollection(it.text("id"), it.text("name"), it.count("artist-count")) }
    }

    /** Every artist in the collection, in pages of 100. */
    suspend fun collectionArtists(collectionId: String): List<ImportableArtist> {
        require(collectionId.matches(musicBrainzId)) { "Not a MusicBrainz collection ID" }
        val artists = mutableListOf<JsonObject>()
        do {
            val body = request("artist", mapOf("collection" to collectionId, "limit" to "100", "offset" to artists.size.toString()))
            val page = body.array("artists")
            artists += page
        } while (page.isNotEmpty() && artists.size < body.count("artist-count"))
        return artists.mapNotNull { artist ->
            ImportableArtist(artist.text("name").ifBlank { return@mapNotNull null }, artist.text("id").ifBlank { return@mapNotNull null },
                artist.text("disambiguation"))
        }
    }

    /** The release group's front cover, or null when it has none. */
    suspend fun artwork(releaseGroupId: String): ByteArray? {
        return http.prepareGet("${coverArtUrl.trimEnd('/')}/release-group/$releaseGroupId/front-250") { header(HttpHeaders.UserAgent, userAgent) }
            .execute { response ->
                if (response.status == HttpStatusCode.NotFound) return@execute null
                check(response.status.isSuccess()) { "Cover Art Archive request failed: HTTP ${response.status.value}" }
                response.readArtwork()
            }
    }

    private suspend fun request(entity: String, parameters: Map<String, String>): JsonObject =
        requestOrNull(entity, parameters) ?: error("MusicBrainz request failed: HTTP 404")

    /** The response, or null when MusicBrainz has no such resource. */
    private suspend fun requestOrNull(entity: String, parameters: Map<String, String>): JsonObject? = pacing.withLock {
        lastRequest?.let { delay(spacingMs - it.elapsedNow().inWholeMilliseconds) }
        try {
            val response = http.get("${baseUrl.trimEnd('/')}/$entity") {
                header(HttpHeaders.UserAgent, userAgent)
                parameters.forEach { (key, value) -> parameter(key, value) }
                parameter("fmt", "json")
            }
            if (response.status == HttpStatusCode.NotFound) return@withLock null
            check(response.status != HttpStatusCode.ServiceUnavailable) { "MusicBrainz is busy. Try again in a moment." }
            check(response.status.isSuccess()) { "MusicBrainz request failed: HTTP ${response.status.value}" }
            Json.parseToJsonElement(response.bodyAsText()).jsonObject
        } finally { lastRequest = TimeSource.Monotonic.markNow() }
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    private fun JsonObject.count(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
    private fun JsonObject.array(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** Escapes Lucene's special characters, so a name is searched as written. */
    private fun escape(text: String) = text.replace(Regex("""[\\+\-!():^\[\]"{}~*?|&/]""")) { "\\${it.value}" }
}

/** What a refresh found: the new releases recent enough to announce, and the first failure if any artist could not be checked. */
data class ReleaseUpdate(val announced: List<ArtistRelease>, val failure: Exception? = null)

/**
 * Keeps the followed artists and their releases from the last [WINDOW_DAYS] days and later. Releases are keyed by their
 * release group, so refreshing updates them instead of adding them again, and only a release first seen after its artist's
 * initial check counts as new.
 */
class ArtistReleases(private val store: MusicStore, private val musicBrainz: MusicBrainzClient,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val writes = Mutex()

    suspend fun followed(): List<FollowedArtist> = store.list<FollowedArtist>(FOLLOWED_KIND).sortedBy { it.name.lowercase() }
    suspend fun releases(): List<ArtistRelease> = store.list(RELEASE_KIND)
    suspend fun refreshedAt(): Long? = store.read(REFRESHED_KEY)
    suspend fun search(name: String): List<ArtistCandidate> = musicBrainz.searchArtists(name)
    suspend fun artwork(releaseId: String): ByteArray? = musicBrainz.artwork(releaseId)
    suspend fun collections(editor: String): List<ArtistCollection> = musicBrainz.artistCollections(editor)
    suspend fun collectionArtists(id: String): List<ImportableArtist> = musicBrainz.collectionArtists(id)

    /** Starts tracking [artist]; its current releases are fetched by the next [refresh] without counting as new. */
    suspend fun follow(artist: FollowedArtist) { follow(listOf(artist)) }

    /** Starts tracking each of [artists] not followed yet, which it returns. */
    suspend fun follow(artists: List<FollowedArtist>): List<FollowedArtist> = writes.withLock {
        require(artists.all { it.id.matches(musicBrainzId) }) { "Not a MusicBrainz artist ID" }
        store.transaction {
            artists.distinctBy { it.id.lowercase() }.filter { store.read<FollowedArtist>(followedKey(it.id)) == null }
                .map { it.copy(id = it.id.lowercase(), followedAt = now(), checkedAt = null) }
                .onEach { store.put(followedKey(it.id), FOLLOWED_KIND, it) }
        }
    }

    /** Stops tracking the artist and forgets the releases no other followed artist is credited on. */
    suspend fun unfollow(id: String) = writes.withLock {
        store.transaction {
            store.database.records().delete(followedKey(id))
            val remaining = store.database.records().keys(FOLLOWED_KIND).map { it.removePrefix("$FOLLOWED_KIND:") }.toSet()
            releases().filter { id in it.artistIds && it.artistIds.none(remaining::contains) }
                .forEach { store.database.records().delete(releaseKey(it.id)) }
        }
    }

    /**
     * Fetches the releases of [artists], by default every followed one. Artists that fail keep their earlier releases; the
     * others' new releases are still returned, as they will not count as new again.
     */
    suspend fun refresh(artists: List<FollowedArtist>? = null): ReleaseUpdate {
        val since = releaseDate(now() - WINDOW_DAYS * DAY_MS)
        val announceSince = releaseDate(now() - ANNOUNCE_DAYS * DAY_MS)
        val announced = mutableListOf<ArtistRelease>()
        var failure: Exception? = null
        for (artist in artists ?: followed()) {
            val fetched = try { musicBrainz.releases(artist.id, since) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { if (failure == null) failure = error; continue }
            writes.withLock {
                // The artist may have been unfollowed while its releases were fetched.
                val current = store.read<FollowedArtist>(followedKey(artist.id)) ?: return@withLock
                val existing = releases().associateBy { it.id }
                store.transaction {
                    for (release in fetched) {
                        val known = existing[release.id]
                        store.put(releaseKey(release.id), RELEASE_KIND, release.copy(discoveredAt = known?.discoveredAt ?: now()))
                        if (known == null && current.checkedAt != null && release.date >= announceSince) announced += release
                    }
                    store.put(followedKey(artist.id), FOLLOWED_KIND, current.copy(checkedAt = now()))
                }
            }
        }
        writes.withLock {
            store.transaction {
                releases().filter { it.date < since }.forEach { store.database.records().delete(releaseKey(it.id)) }
                if (failure == null && artists == null) store.put(REFRESHED_KEY, REFRESHED_KIND, now())
            }
        }
        return ReleaseUpdate(announced.distinctBy { it.id }, failure)
    }

    companion object {
        const val WINDOW_DAYS = 365L
        /** New releases older than this are listed but not announced, such as an old release MusicBrainz only just added. */
        const val ANNOUNCE_DAYS = 30L
        private const val DAY_MS = 86_400_000L
        private const val FOLLOWED_KIND = "followed-artist"
        private const val RELEASE_KIND = "artist-release"
        private const val REFRESHED_KIND = "artist-releases-refreshed"
        private const val REFRESHED_KEY = "artist-releases:refreshed-at"
        private fun followedKey(id: String) = "$FOLLOWED_KIND:${id.lowercase()}"
        private fun releaseKey(id: String) = "$RELEASE_KIND:${id.lowercase()}"
    }
}
