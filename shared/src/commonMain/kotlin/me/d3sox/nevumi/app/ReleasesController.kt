package me.d3sox.nevumi.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.d3sox.nevumi.history.LastFmPeriod
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.navidrome.ServerDiscovery
import me.d3sox.nevumi.releases.ArtistCollection
import me.d3sox.nevumi.releases.ArtistCandidate
import me.d3sox.nevumi.releases.ArtistRelease
import me.d3sox.nevumi.releases.ArtistReleases
import me.d3sox.nevumi.releases.FollowedArtist
import me.d3sox.nevumi.releases.ImportableArtist
import me.d3sox.nevumi.releases.musicBrainzId
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.notice_following_artist
import me.d3sox.nevumi.resources.notice_imported_artists
import me.d3sox.nevumi.resources.notice_imported_artists_unmatched
import me.d3sox.nevumi.resources.notice_unfollowed_artist
import me.d3sox.nevumi.resources.releases_notification_many
import me.d3sox.nevumi.resources.releases_notification_one
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import kotlin.time.Clock

/** The release feed's refresh: whether one runs, why the last one failed and when the last complete one finished. */
data class ReleaseRefresh(val running: Boolean = false, val error: String? = null, val refreshedAt: Long? = null)

/** How an artist name matched MusicBrainz: one artist to follow, or the [candidates] to choose from, which may be none. */
sealed interface ArtistMatch {
    data class Found(val artist: ArtistCandidate) : ArtistMatch
    data class Ambiguous(val candidates: List<ArtistCandidate>) : ArtistMatch
}

/** Posts a system notification about new releases. */
fun interface ReleaseNotifier {
    suspend fun notify(title: String, text: String)
}

/**
 * Follows artists and keeps their recent and upcoming releases from MusicBrainz. Nothing is looked up until an artist is
 * followed; then the releases are refreshed when they are older than [REFRESH_INTERVAL_MS].
 */
class ReleasesController internal constructor(private val context: ControllerContext, private val libraryController: LibraryController,
    private val releases: ArtistReleases?, private val discovery: ServerDiscovery?, private val notifier: ReleaseNotifier?,
    private val scrobbling: Scrobbling? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val refreshing = Mutex()
    private val mutableNotificationRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val available: Boolean get() = releases != null
    /** Emits when the user turns on release notifications, so the platform can ask for permission to post them. */
    val notificationRequests = mutableNotificationRequests.asSharedFlow()

    internal fun start() {
        val releases = releases ?: return
        context.scope.launch {
            try { publish(releases) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@launch }
            while (true) {
                refreshIfStale()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Matches [libraryName] to a MusicBrainz artist, preferring the ID the music server has from the artist's tags. */
    suspend fun findArtist(libraryName: String): ArtistMatch {
        val releases = requireNotNull(releases) { "Release tracking is unavailable" }
        val serverId = discovery?.artist(libraryName)?.info?.musicBrainzId?.takeIf { it.matches(musicBrainzId) }
        if (serverId != null) return ArtistMatch.Found(ArtistCandidate(serverId.lowercase(), libraryName))
        val candidates = releases.search(libraryName)
        // Several artists can share a name; only a single exact match is followed without asking.
        return candidates.filter { it.name.equals(libraryName.trim(), ignoreCase = true) }.singleOrNull()?.let(ArtistMatch::Found)
            ?: ArtistMatch.Ambiguous(candidates)
    }

    fun follow(libraryName: String, artist: ArtistCandidate): Job = context.action(serialized = false) {
        val releases = requireNotNull(releases) { "Release tracking is unavailable" }
        val followed = FollowedArtist(artist.id, artist.name, artist.disambiguation, libraryName)
        releases.follow(followed)
        publish(releases)
        context.confirm(Res.string.notice_following_artist, arguments = listOf(artist.name), serializedUndo = false,
            undo = { releases.unfollow(followed.id); publish(releases) }, redo = { releases.follow(followed); publish(releases); fetch(listOf(followed.id)) })
        fetch(listOf(followed.id))
    }

    /** The connected Last.fm account's most played artists over [period], with the MusicBrainz IDs Last.fm knows. */
    suspend fun lastFmTopArtists(period: LastFmPeriod): List<ImportableArtist> =
        requireNotNull(scrobbling) { "Last.fm is unavailable" }.lastFmTopArtists(period, LAST_FM_LIMIT)
            .map { ImportableArtist(it.name, it.mbid.takeIf { id -> id.matches(musicBrainzId) }?.lowercase(), plays = it.plays) }

    suspend fun artistCollections(editor: String): List<ArtistCollection> =
        requireNotNull(releases) { "Release tracking is unavailable" }.collections(editor)

    suspend fun collectionArtists(collection: ArtistCollection): List<ImportableArtist> {
        require(collection.artistCount <= IMPORT_LIMIT) { "Collections of more than $IMPORT_LIMIT artists can't be imported" }
        return requireNotNull(releases) { "Release tracking is unavailable" }.collectionArtists(collection.id)
    }

    /**
     * Follows [artists] that aren't followed yet, looking up those without an ID like [findArtist]; a name with no single
     * match is skipped. The lookups show as a refresh, as each takes a second. A failed lookup ends them; the artists
     * found so far are still followed, and the failure shows as the app error.
     */
    fun import(artists: List<ImportableArtist>): Job = context.action(serialized = false) {
        val releases = requireNotNull(releases) { "Release tracking is unavailable" }
        val followed = releases.followed()
        val wanted = artists.filter { artist ->
            followed.none { it.id == artist.id?.lowercase() || artist.id == null && it.libraryName.equals(artist.name, ignoreCase = true) }
        }
        var unmatched = 0
        var failure: Exception? = null
        val resolved = refreshing.withLock {
            try {
                context.update { it.copy(releaseRefresh = it.releaseRefresh.copy(running = true)) }
                val found = mutableListOf<FollowedArtist>()
                for (artist in wanted) {
                    val id = artist.id ?: try { (findArtist(artist.name) as? ArtistMatch.Found)?.artist?.id }
                        catch (error: Exception) { rethrowCancellation(error); failure = error; break }
                    if (id == null) unmatched++ else found += FollowedArtist(id, artist.name, artist.disambiguation)
                }
                found
            } finally { context.update { it.copy(releaseRefresh = it.releaseRefresh.copy(running = false)) } }
        }
        val added = releases.follow(resolved)
        publish(releases)
        val (message, arguments) = if (unmatched == 0) Res.string.notice_imported_artists to listOf(added.size)
            else Res.string.notice_imported_artists_unmatched to listOf(added.size, unmatched)
        if (added.isEmpty()) context.confirm(message, arguments = arguments)
        else {
            context.confirm(message, arguments = arguments, serializedUndo = false,
                undo = { added.forEach { releases.unfollow(it.id) }; publish(releases) },
                redo = { releases.follow(added); publish(releases); fetch(added.map { it.id }) })
            fetch(added.map { it.id })
        }
        failure?.let { throw it }
    }

    fun unfollow(id: String): Job = context.action(serialized = false) {
        val releases = requireNotNull(releases) { "Release tracking is unavailable" }
        val artist = releases.followed().firstOrNull { it.id == id } ?: return@action
        releases.unfollow(id)
        publish(releases)
        context.confirm(Res.string.notice_unfollowed_artist, arguments = listOf(artist.name), serializedUndo = false,
            undo = { releases.follow(artist); publish(releases); fetch(listOf(artist.id)) }, redo = { releases.unfollow(id); publish(releases) })
    }

    /** Refreshes every followed artist's releases, reporting a failure in [AppState.releaseRefresh]. */
    fun refresh(): Job = context.scope.launch { fetch(null) }

    /**
     * Refreshes when the last complete refresh is older than [maxAgeMs]. A periodic job passes less than its period, as its
     * runs are not evenly spaced and one just short of the period would otherwise skip a whole period.
     */
    suspend fun refreshIfStale(maxAgeMs: Long = REFRESH_INTERVAL_MS) = fetch(null, maxAgeMs)

    fun setNotifications(enabled: Boolean) {
        libraryController.updatePreferences { it.copy(notifyNewReleases = enabled) }
        if (enabled) mutableNotificationRequests.tryEmit(Unit)
    }

    suspend fun artwork(releaseId: String): ByteArray? = releases?.artwork(releaseId)

    /** Refreshes [artistIds], or every followed artist; with [maxAgeMs], only when the last complete refresh is older. */
    private suspend fun fetch(artistIds: List<String>?, maxAgeMs: Long? = null) {
        val releases = releases ?: return
        // Every exit clears the flag, so a cancelled background job does not leave the feed showing a refresh forever.
        refreshing.withLock { try {
            var error: String? = null
            val announced = try {
                // Checked under the lock, so a refresh that just finished elsewhere is not repeated.
                if (maxAgeMs != null && !stale(releases, maxAgeMs)) return
                context.update { it.copy(releaseRefresh = it.releaseRefresh.copy(running = true)) }
                val followed = releases.followed()
                val update = releases.refresh(artistIds?.let { ids -> followed.filter { it.id in ids } })
                update.failure?.let { error = it.message ?: "Could not refresh releases" }
                update.announced
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not refresh releases"; emptyList() }
            try { publish(releases) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The feed keeps what it showed. */ }
            context.update { it.copy(releaseRefresh = it.releaseRefresh.copy(running = false, error = error)) }
            if (announced.isNotEmpty() && context.state.value.preferences.notifyNewReleases) announce(announced)
        } finally { context.update { it.copy(releaseRefresh = it.releaseRefresh.copy(running = false)) } } }
    }

    private suspend fun stale(releases: ArtistReleases, maxAgeMs: Long): Boolean {
        if (releases.followed().isEmpty()) return false
        val last = releases.refreshedAt() ?: return true
        return now() - last !in 0 until maxAgeMs
    }

    private suspend fun announce(new: List<ArtistRelease>) {
        val notifier = notifier ?: return
        val (title, text) = if (new.size == 1) getString(Res.string.releases_notification_one, new.single().artist) to new.single().title
            else getPluralString(Res.plurals.releases_notification_many, new.size, new.size) to
                new.joinToString(", ") { "${it.artist} – ${it.title}" }
        try { notifier.notify(title, text) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A notification is a courtesy; the feed still lists the release. */ }
    }

    private suspend fun publish(releases: ArtistReleases) {
        val followed = releases.followed()
        val known = releases.releases()
        val refreshedAt = releases.refreshedAt()
        context.update { it.copy(followedArtists = followed, releases = known, releaseRefresh = it.releaseRefresh.copy(refreshedAt = refreshedAt)) }
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1_000L
        /** The most artists one import offers, as each followed artist costs a MusicBrainz request per refresh. */
        const val IMPORT_LIMIT = 500
        private const val LAST_FM_LIMIT = 50
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1_000L
    }
}
