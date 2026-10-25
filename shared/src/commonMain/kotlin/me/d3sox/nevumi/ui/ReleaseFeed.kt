package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.ArtistMatch
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.releases.ArtistRelease
import me.d3sox.nevumi.releases.FollowedArtist
import me.d3sox.nevumi.releases.ReleaseType
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.back
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.releases_choose_artist
import me.d3sox.nevumi.resources.releases_close_options
import me.d3sox.nevumi.resources.releases_cover
import me.d3sox.nevumi.resources.releases_follow
import me.d3sox.nevumi.resources.releases_follow_artist
import me.d3sox.nevumi.resources.releases_follow_hint
import me.d3sox.nevumi.resources.releases_followed_artists
import me.d3sox.nevumi.resources.releases_following
import me.d3sox.nevumi.resources.releases_in_library
import me.d3sox.nevumi.resources.releases_no_artist
import me.d3sox.nevumi.resources.releases_none
import me.d3sox.nevumi.resources.releases_notify
import me.d3sox.nevumi.resources.releases_notify_description
import me.d3sox.nevumi.resources.releases_open_album
import me.d3sox.nevumi.resources.releases_open_musicbrainz
import me.d3sox.nevumi.resources.releases_recent
import me.d3sox.nevumi.resources.releases_refresh
import me.d3sox.nevumi.resources.releases_refresh_failed
import me.d3sox.nevumi.resources.releases_release_options
import me.d3sox.nevumi.resources.releases_search_soundcloud
import me.d3sox.nevumi.resources.releases_searching
import me.d3sox.nevumi.resources.releases_source
import me.d3sox.nevumi.resources.releases_title
import me.d3sox.nevumi.resources.releases_type_album
import me.d3sox.nevumi.resources.releases_type_ep
import me.d3sox.nevumi.resources.releases_type_other
import me.d3sox.nevumi.resources.releases_type_single
import me.d3sox.nevumi.resources.releases_unfollow_artist
import me.d3sox.nevumi.resources.releases_upcoming
import me.d3sox.nevumi.resources.releases_updated
import me.d3sox.nevumi.resources.retry
import org.jetbrains.compose.resources.stringResource

/** Formats a MusicBrainz date, which may be only a year or a month. */
internal expect fun formatReleaseDate(date: String): String

/** The feed's two parts: upcoming releases soonest first, then released ones newest first. [today] is a YYYY-MM-DD date. */
internal data class ReleaseFeed(val upcoming: List<ArtistRelease>, val recent: List<ArtistRelease>) {
    val all get() = upcoming + recent
}

internal fun releaseFeed(releases: List<ArtistRelease>, today: String): ReleaseFeed {
    val (upcoming, recent) = releases.partition { it.date > today }
    return ReleaseFeed(upcoming.sortedWith(compareBy<ArtistRelease> { it.date }.thenBy { it.title.lowercase() }),
        recent.sortedWith(compareByDescending<ArtistRelease> { it.date }.thenBy { it.title.lowercase() }))
}

/**
 * Maps release IDs to the library album that holds them: by the MusicBrainz release in the songs' tags, or else by the
 * album title and the artist MusicBrainz credits or the library's name for the followed artist.
 */
internal fun releaseAlbums(releases: List<ArtistRelease>, followed: List<FollowedArtist>, tracks: List<Track>): Map<String, String> {
    if (releases.isEmpty()) return emptyMap()
    val byRelease = HashMap<String, String>()
    val byName = HashMap<Pair<String, String>, String>()
    for (track in tracks) {
        val metadata = track.metadata
        if (metadata.album.isBlank()) continue
        val album = albumCollectionId(metadata)
        if (metadata.releaseId.isNotBlank()) byRelease.putIfAbsent(metadata.releaseId.lowercase(), album)
        byName.putIfAbsent(metadata.album.trim().lowercase() to metadata.albumArtist.ifBlank { metadata.artist }.trim().lowercase(), album)
    }
    val names = followed.associate { it.id to listOf(it.name, it.libraryName) }
    return releases.mapNotNull { release ->
        val album = release.releaseIds.firstNotNullOfOrNull { byRelease[it.lowercase()] }
            ?: (listOf(release.artist) + release.artistIds.flatMap { names[it].orEmpty() }).firstNotNullOfOrNull { artist ->
                byName[release.title.trim().lowercase() to artist.trim().lowercase()]
            }
        album?.let { release.id to it }
    }.toMap()
}

@Composable
private fun ReleaseType.label(): String = stringResource(when (this) {
    ReleaseType.ALBUM -> Res.string.releases_type_album
    ReleaseType.SINGLE -> Res.string.releases_type_single
    ReleaseType.EP -> Res.string.releases_type_ep
    ReleaseType.OTHER -> Res.string.releases_type_other
})

@Composable
private fun ArtistRelease.details(): String = "${type.label()} · ${formatReleaseDate(date)}"

/** Follows or unfollows the artist of an artist page; following asks which artist is meant when MusicBrainz has several. */
@Composable
internal fun ArtistFollowButton(artist: String, state: AppState, controller: MusicController) {
    val releases = controller.releasesController
    if (!releases.available || artist.isBlank()) return
    val followed = state.followedArtists.firstOrNull { it.libraryName.equals(artist, ignoreCase = true) }
    var matching by remember(artist) { mutableStateOf(false) }
    if (followed != null) {
        val description = stringResource(Res.string.releases_unfollow_artist, artist)
        ActionButton(stringResource(Res.string.releases_following), AppIcon.FOLLOWING, Modifier.semantics { contentDescription = description },
            style = ActionStyle.OUTLINED) { releases.unfollow(followed.id) }
    } else {
        val description = stringResource(Res.string.releases_follow_artist, artist)
        ActionButton(stringResource(Res.string.releases_follow), AppIcon.FOLLOW, Modifier.semantics { contentDescription = description }) { matching = true }
    }
    if (matching) ArtistMatchDialog(artist, controller) { matching = false }
}

@Composable
private fun ArtistMatchDialog(artist: String, controller: MusicController, dismiss: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Result<ArtistMatch>?>(null) }
    LaunchedEffect(attempt) {
        result = null
        val found = try { Result.success(controller.releasesController.findArtist(artist)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        val match = found.getOrNull()
        if (match is ArtistMatch.Found) { controller.releasesController.follow(artist, match.artist); dismiss() }
        else result = found
    }
    val failure = result?.exceptionOrNull()
    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("artist-match"),
        title = { Text(stringResource(Res.string.releases_follow_artist, artist)) },
        text = {
            val candidates = (result?.getOrNull() as? ArtistMatch.Ambiguous)?.candidates
            when {
                failure != null -> Text(failure.message ?: failure.toString(), color = MaterialTheme.colorScheme.error)
                candidates == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(Res.string.releases_searching))
                }
                candidates.isEmpty() -> Text(stringResource(Res.string.releases_no_artist, artist))
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.releases_choose_artist, artist))
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(candidates, key = { it.id }) { candidate ->
                            ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable {
                                controller.releasesController.follow(artist, candidate); dismiss()
                            }.testTag("artist-candidate:${candidate.id}"),
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                headlineContent = { Text(candidate.name) },
                                supportingContent = listOf(candidate.disambiguation, candidate.type, candidate.country).filter { it.isNotBlank() }
                                    .takeIf { it.isNotEmpty() }?.let { details -> { Text(details.joinToString(" · ")) } },
                                leadingContent = { Icon(AppIcon.ARTIST.vector, null) })
                        }
                    }
                }
            }
        },
        confirmButton = { if (failure != null) TextButton({ attempt++ }) { Text(stringResource(Res.string.retry)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.cancel)) } })
}

// Covers of recently shown releases; a release without a cover is remembered as null, so it is not asked for again.
private val releaseCovers = mutableMapOf<String, ImageBitmap?>()

@Composable
private fun ReleaseCover(release: ArtistRelease, controller: MusicController, modifier: Modifier = Modifier) {
    val image by produceState(releaseCovers[release.id], release.id) {
        if (value != null || release.id in releaseCovers) return@produceState
        value = try {
            controller.releasesController.artwork(release.id)?.let { withContext(Dispatchers.Default) { decodeArtwork(it, 320) } }
        } catch (cancelled: CancellationException) { throw cancelled }
        // An unreachable archive is asked again next time.
        catch (_: Exception) { return@produceState }
        releaseCovers[release.id] = value
        if (releaseCovers.size > 200) releaseCovers.remove(releaseCovers.keys.first())
    }
    val description = stringResource(Res.string.releases_cover, release.title)
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        image?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Box(contentAlignment = Alignment.Center) {
                Icon(AppIcon.ALBUM.vector, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
    }
}

@Composable
private fun FollowHint(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(AppIcon.NEW_RELEASES.vector, null, tint = MaterialTheme.colorScheme.primary)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The Home section: the newest releases, or how to get some. */
@Composable
internal fun HomeReleases(title: String, state: AppState, feed: ReleaseFeed, albums: Map<String, String>, controller: MusicController,
    all: () -> Unit, open: (ArtistRelease) -> Unit) {
    HomeSection(title, all) {
        when {
            state.followedArtists.isEmpty() -> FollowHint(stringResource(Res.string.releases_follow_hint))
            feed.all.isEmpty() -> FollowHint(stringResource(Res.string.releases_none))
            else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(feed.all.take(20), key = { it.id }) { release ->
                    val description = stringResource(Res.string.releases_release_options, release.title)
                    Card(Modifier.width(152.dp).clip(RoundedCornerShape(16.dp)).clickable { open(release) }
                        .semantics { contentDescription = description }.testTag("home-release:${release.id}")) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ReleaseCover(release, controller, Modifier.fillMaxWidth().aspectRatio(1f))
                            Text(release.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(release.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (release.id in albums) "${release.details()} · ${stringResource(Res.string.releases_in_library)}" else release.details(),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Every release of the followed artists, the refresh state, notifications and the followed artists. */
@Composable
internal fun ReleaseFeedPage(state: AppState, feed: ReleaseFeed, albums: Map<String, String>, controller: MusicController,
    back: () -> Unit, open: (ArtistRelease) -> Unit) {
    val navigate = LocalLibraryNavigate.current
    val refresh = state.releaseRefresh
    SettingsScreen(back, title = { Text(stringResource(Res.string.releases_title)) }, actions = {
        AppIconButton(AppIcon.REFRESH, stringResource(Res.string.releases_refresh), enabled = !refresh.running && state.followedArtists.isNotEmpty()) {
            controller.releasesController.refresh()
        }
    }, text = {
        LazyColumn(Modifier.fillMaxSize().testTag("release-feed"), contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "notifications") {
                SettingsToggle(stringResource(Res.string.releases_notify), AppIcon.NOTIFICATIONS, stringResource(Res.string.releases_notify_description),
                    state.preferences.notifyNewReleases) { controller.releasesController.setNotifications(it) }
            }
            if (refresh.running) item(key = "refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("releases-refreshing")) }
            refresh.error?.let { error ->
                item(key = "error") {
                    Card(Modifier.fillMaxWidth().testTag("releases-error"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(Res.string.releases_refresh_failed, error.trimEnd('.')), color = MaterialTheme.colorScheme.onErrorContainer)
                            ActionButton(stringResource(Res.string.retry), AppIcon.REFRESH, enabled = !refresh.running) { controller.releasesController.refresh() }
                        }
                    }
                }
            }
            if (state.followedArtists.isEmpty()) item(key = "hint") { Text(stringResource(Res.string.releases_follow_hint), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (feed.all.isEmpty() && !refresh.running) item(key = "none") { Text(stringResource(Res.string.releases_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            listOf(Triple("upcoming", Res.string.releases_upcoming, feed.upcoming), Triple("recent", Res.string.releases_recent, feed.recent))
                .forEach { (part, heading, releases) ->
                if (releases.isNotEmpty()) item(key = "heading:$part") { FeedHeading(stringResource(heading)) }
                items(releases, key = { it.id }) { release ->
                    val description = stringResource(Res.string.releases_release_options, release.title)
                    ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { open(release) }
                        .semantics { contentDescription = description }.testTag("release:${release.id}"),
                        headlineContent = { Text(release.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${release.artist}\n${release.details()}", maxLines = 3, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { ReleaseCover(release, controller, Modifier.size(56.dp)) },
                        trailingContent = if (release.id in albums) { { Text(stringResource(Res.string.releases_in_library),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } else null)
                }
            }
            if (state.followedArtists.isNotEmpty()) item(key = "following") { FeedHeading(stringResource(Res.string.releases_followed_artists)) }
            items(state.followedArtists, key = { "artist:${it.id}" }) { artist ->
                ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { navigate(LibraryTab.ARTISTS, artist.libraryName) },
                    headlineContent = { Text(artist.libraryName) },
                    supportingContent = listOfNotNull(artist.name.takeIf { !it.equals(artist.libraryName, ignoreCase = true) },
                        artist.disambiguation.ifBlank { null }).takeIf { it.isNotEmpty() }?.let { details -> { Text(details.joinToString(" · ")) } },
                    leadingContent = { Icon(AppIcon.ARTIST.vector, null) },
                    trailingContent = { AppIconButton(AppIcon.REMOVE, stringResource(Res.string.releases_unfollow_artist, artist.libraryName)) {
                        controller.releasesController.unfollow(artist.id)
                    } })
            }
            item(key = "source") {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    refresh.refreshedAt?.let { Text(stringResource(Res.string.releases_updated, formatListenTime(it)), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(stringResource(Res.string.releases_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    })
}

@Composable
private fun FeedHeading(text: String) {
    Text(text, Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** What can be done with a release: open it in the library, look for it on SoundCloud, or read about it on MusicBrainz. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReleaseOptions(release: ArtistRelease, album: String?, state: AppState, dismiss: () -> Unit, searchSoundCloud: () -> Unit) {
    val navigate = LocalLibraryNavigate.current
    val browser = LocalUriHandler.current
    AppBottomSheet(onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(release.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Text("${release.artist} · ${release.details()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIconButton(AppIcon.CLOSE, stringResource(Res.string.releases_close_options), onClick = dismiss)
        }
        if (album != null) ListItem(headlineContent = { Text(stringResource(Res.string.releases_open_album)) }, leadingContent = { Icon(AppIcon.ALBUM.vector, null) },
            modifier = Modifier.clickable { dismiss(); navigate(LibraryTab.ALBUMS, album) })
        if (state.soundCloud != null) ListItem(headlineContent = { Text(stringResource(Res.string.releases_search_soundcloud)) },
            leadingContent = { Icon(AppIcon.CLOUD.vector, null) }, modifier = Modifier.clickable { dismiss(); searchSoundCloud() })
        ListItem(headlineContent = { Text(stringResource(Res.string.releases_open_musicbrainz)) }, leadingContent = { Icon(AppIcon.OPEN_EXTERNAL.vector, null) },
            modifier = Modifier.clickable { dismiss(); runCatching { browser.openUri(release.musicBrainzUrl) } })
        Spacer(Modifier.height(16.dp))
    }
}

/** Searches the existing SoundCloud integration for a release. */
@Composable
internal fun ReleaseSoundCloudSearch(release: ArtistRelease, state: AppState, controller: MusicController, back: () -> Unit) {
    if (!LocalPageBackHandled.current) PlatformBackHandler(back)
    Column(Modifier.fillMaxSize().testTag("release-soundcloud-search")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcon.BACK, stringResource(Res.string.back), onClick = back)
            Column(Modifier.weight(1f)) {
                Text(release.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(release.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SoundCloudSearchResults("${release.artist} ${release.title}", state, controller)
    }
}
