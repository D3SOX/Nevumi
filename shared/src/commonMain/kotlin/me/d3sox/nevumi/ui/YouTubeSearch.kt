package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.inLibrary
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.add_to_queue
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.play_next
import me.d3sox.nevumi.resources.retry
import me.d3sox.nevumi.resources.search_type_albums
import me.d3sox.nevumi.resources.search_type_artists
import me.d3sox.nevumi.resources.search_type_songs
import me.d3sox.nevumi.resources.sources_check_tags_after_download
import me.d3sox.nevumi.resources.sources_check_tags_before_saving
import me.d3sox.nevumi.resources.sources_close_youtube_options
import me.d3sox.nevumi.resources.sources_in_library
import me.d3sox.nevumi.resources.sources_no_youtube_results
import me.d3sox.nevumi.resources.sources_open_on_youtube
import me.d3sox.nevumi.resources.sources_save_to_this_device
import me.d3sox.nevumi.resources.sources_search_youtube_description
import me.d3sox.nevumi.resources.sources_search_youtube_for
import me.d3sox.nevumi.resources.sources_show_more
import me.d3sox.nevumi.resources.sources_youtube_options_for
import me.d3sox.nevumi.resources.sources_youtube_save_to_device_description
import me.d3sox.nevumi.resources.sources_youtube_save_to_server
import me.d3sox.nevumi.resources.sources_youtube_save_to_server_description
import me.d3sox.nevumi.resources.sources_youtube_search_failed
import me.d3sox.nevumi.resources.sources_youtube_song_description
import me.d3sox.nevumi.resources.sources_youtube_video
import me.d3sox.nevumi.resources.sources_youtube_videos
import me.d3sox.nevumi.youtube.YouTubeCollection
import me.d3sox.nevumi.youtube.YouTubeContinuation
import me.d3sox.nevumi.youtube.YouTubeDestination
import me.d3sox.nevumi.youtube.YouTubeException
import me.d3sox.nevumi.youtube.YouTubeItem
import me.d3sox.nevumi.youtube.YouTubeSearchKind
import me.d3sox.nevumi.youtube.YouTubeTrack
import me.d3sox.nevumi.youtube.smallArtwork
import org.jetbrains.compose.resources.stringResource

private fun songKey(artist: String, title: String) = "${artist.trim().lowercase()}\u0000${title.trim().lowercase()}"

/**
 * Searches YouTube once [query] stops changing, preferring songs from YouTube Music. A pasted video link finds that video.
 * Tapping a result plays it, or opens an artist's or album's YouTube Music page; its options add it to the queue or download it, so another
 * source's query only leads to a recording the user picked.
 */
@Composable
internal fun YouTubeSearchResults(query: String, state: AppState, controller: MusicController) {
    var kind by rememberSaveable { mutableStateOf(YouTubeSearchKind.SONGS) }
    val results = remember(query, kind) { mutableStateListOf<YouTubeItem>() }
    var next by remember(query, kind) { mutableStateOf<YouTubeContinuation?>(null) }
    var loading by remember(query, kind) { mutableStateOf(true) }
    var error by remember(query, kind) { mutableStateOf<String?>(null) }
    // Trying again cannot bring back a private or removed video that a pasted link found.
    var permanent by remember(query, kind) { mutableStateOf(false) }
    var attempt by remember(query, kind) { mutableStateOf(0) }
    var picked by remember { mutableStateOf<YouTubeTrack?>(null) }
    val scope = rememberCoroutineScope()
    val searchFailed = stringResource(Res.string.sources_youtube_search_failed)
    suspend fun load(continuation: YouTubeContinuation?) {
        loading = true; error = null
        try {
            val page = controller.searchYouTube(query, kind, continuation)
            if (continuation == null) results.clear()
            results += page.results.distinctBy { it.key }.filter { result -> results.none { it.key == result.key } }
            next = page.next
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: searchFailed; permanent = (failure as? YouTubeException)?.retryable == false }
        finally { loading = false }
    }
    LaunchedEffect(query, kind, attempt) {
        if (attempt == 0) delay(500)
        load(null)
    }
    val owned = rememberOwnedSongs(state)
    Column(Modifier.fillMaxSize()) {
        val labels = mapOf(YouTubeSearchKind.SONGS to Res.string.search_type_songs, YouTubeSearchKind.ARTISTS to Res.string.search_type_artists,
            YouTubeSearchKind.ALBUMS to Res.string.search_type_albums, YouTubeSearchKind.VIDEOS to Res.string.sources_youtube_videos)
        OverflowChipRow(YouTubeSearchKind.entries, kind, { stringResource(labels.getValue(it)) }, { "youtube-kind:${it.name}" },
            Modifier.padding(horizontal = 16.dp)) { kind = it }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("youtube-results")) {
            items(results, key = { "youtube:${it.key}" }) { result ->
                when (result) {
                    is YouTubeTrack -> YouTubeTrackRow(result, owned, controller, onOptions = { picked = result }) { controller.playYouTube(result) }
                    is YouTubeCollection -> YouTubeCollectionRow(result, controller)
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            error?.let { message ->
                item {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        if (!permanent) ActionButton(stringResource(Res.string.retry), AppIcon.REFRESH, style = ActionStyle.TONAL) {
                            if (results.isEmpty()) attempt++ else scope.launch { load(next) }
                        }
                    }
                }
            }
            if (!loading && error == null && results.isEmpty())
                item { Text(stringResource(Res.string.sources_no_youtube_results, query.trim()), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            next?.let { continuation ->
                if (!loading && error == null) item {
                    TextButton(onClick = { scope.launch { load(continuation) } }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(Res.string.sources_show_more)) }
                }
            }
        }
    }
    picked?.let { track -> YouTubeOptionsSheet(track, state, controller) { picked = null } }
}

/** The artist and title of every song in the library, which marks YouTube results the user already has. */
@Composable
internal fun rememberOwnedSongs(state: AppState): Set<String> =
    remember(state.tracks) { state.tracks.filter { it.inLibrary }.map { songKey(it.metadata.artist, it.metadata.title) }.toSet() }

/** A song or video; [play] runs on a tap, [onOptions] on a long press or its options button. */
@Composable
internal fun YouTubeTrackRow(track: YouTubeTrack, owned: Set<String>, controller: MusicController, onOptions: () -> Unit, play: () -> Unit) {
    val inLibrary = songKey(track.artist, track.title) in owned
    val description = stringResource(Res.string.sources_youtube_song_description, track.title, track.artist)
    ListItem(modifier = Modifier.combinedClickable(onClick = play, onLongClick = onOptions).semantics { contentDescription = description },
        headlineContent = { Text(track.title, maxLines = 2) },
        supportingContent = {
            Text(listOfNotNull(track.artist.ifBlank { null }, track.durationMs.takeIf { it > 0 }?.let(::playbackTime),
                if (track.song) null else stringResource(Res.string.sources_youtube_video)).joinToString(" · "), maxLines = 1)
        },
        leadingContent = { YouTubeImage(smallArtwork(track), controller, Modifier.size(48.dp)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (inLibrary) Text(stringResource(Res.string.sources_in_library), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppIconButton(AppIcon.MORE, stringResource(Res.string.sources_youtube_options_for, track.title), onClick = onOptions)
            }
        })
}

// Recently shown YouTube images, so scrolling back or returning to a page does not fetch them again.
private val youTubeImages = mutableMapOf<String, ImageBitmap>()

/** An image from YouTube's image servers, at most [maxDimension] pixels wide or high once decoded. */
@Composable
internal fun rememberYouTubeImage(url: String?, maxDimension: Int, controller: MusicController): ImageBitmap? {
    val key = url?.let { "$maxDimension:$it" }
    val image by produceState(key?.let(youTubeImages::get), key) {
        if (url == null || key == null || value != null) return@produceState
        value = try {
            val bytes = controller.youTubeArtwork(url)
            withContext(Dispatchers.Default) { decodeArtwork(bytes, maxDimension) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        value?.let { image ->
            youTubeImages[key] = image
            if (youTubeImages.size > 200) youTubeImages.remove(youTubeImages.keys.first())
        }
    }
    return image
}

@Composable
internal fun YouTubeImage(url: String?, controller: MusicController, modifier: Modifier, shape: Shape = RoundedCornerShape(12.dp),
    icon: AppIcon = AppIcon.YOUTUBE, maxDimension: Int = 160) {
    val image = rememberYouTubeImage(url, maxDimension, controller)
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = shape) {
        image?.let { Image(it, null, Modifier.fillMaxSize().testTag("youtube-artwork"), contentScale = ContentScale.Crop) }
            ?: Box(contentAlignment = Alignment.Center) {
                Icon(icon.vector, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
    }
}

/** Offers a YouTube search for [query] at the end of the library results. */
@Composable
internal fun SearchYouTubeRow(query: String, open: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = open).testTag("search-youtube"),
        headlineContent = { Text(stringResource(Res.string.sources_search_youtube_for, query.trim())) },
        supportingContent = { Text(stringResource(Res.string.sources_search_youtube_description)) },
        leadingContent = { Icon(AppIcon.YOUTUBE.vector, null) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun YouTubeOptionsSheet(track: YouTubeTrack, state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val browser = LocalUriHandler.current
    var reviewTags by remember { mutableStateOf(state.preferences.youTubeReviewTags) }
    fun choose(action: () -> Unit) { action(); dismiss() }
    AppBottomSheet(onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIconButton(AppIcon.CLOSE, stringResource(Res.string.sources_close_youtube_options), onClick = dismiss)
        }
        ListItem(headlineContent = { Text(stringResource(Res.string.play)) }, leadingContent = { Icon(AppIcon.PLAY.vector, null) },
            modifier = Modifier.clickable { choose { controller.playYouTube(track) } })
        ListItem(headlineContent = { Text(stringResource(Res.string.play_next)) }, leadingContent = { Icon(AppIcon.PLAY_NEXT.vector, null) },
            modifier = Modifier.clickable { choose { controller.playYouTube(track, QueueAction.PLAY_NEXT) } })
        ListItem(headlineContent = { Text(stringResource(Res.string.add_to_queue)) }, leadingContent = { Icon(AppIcon.QUEUE.vector, null) },
            modifier = Modifier.clickable { choose { controller.playYouTube(track, QueueAction.APPEND) } })
        if (controller.youTubeDeviceDownloads) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ListItem(headlineContent = { Text(stringResource(Res.string.sources_check_tags_before_saving)) },
                supportingContent = { Text(stringResource(Res.string.sources_check_tags_after_download)) },
                leadingContent = { Icon(AppIcon.TAGS.vector, null) }, trailingContent = { Switch(reviewTags, null) },
                modifier = Modifier.toggleable(reviewTags, role = Role.Switch) { reviewTags = it })
            ListItem(headlineContent = { Text(stringResource(Res.string.sources_save_to_this_device)) },
                supportingContent = { Text(stringResource(Res.string.sources_youtube_save_to_device_description)) },
                leadingContent = { Icon(AppIcon.DEVICE.vector, null) },
                modifier = Modifier.clickable { choose { controller.downloadFromYouTube(track, YouTubeDestination.DEVICE, reviewTags) } })
            val sftp = state.sftp
            if (controller.youTubeServerUploads && sftp != null) ListItem(headlineContent = { Text(stringResource(Res.string.sources_youtube_save_to_server)) },
                supportingContent = { Text(stringResource(Res.string.sources_youtube_save_to_server_description, "${sftp.libraryName} · ${sftp.connection.host}")) },
                leadingContent = { Icon(AppIcon.SERVER.vector, null) },
                modifier = Modifier.clickable { choose { controller.downloadFromYouTube(track, YouTubeDestination.SERVER, reviewTags) } })
        }
        ListItem(headlineContent = { Text(stringResource(Res.string.sources_open_on_youtube)) }, leadingContent = { Icon(AppIcon.OPEN_EXTERNAL.vector, null) },
            modifier = Modifier.clickable { choose { runCatching { browser.openUri(track.url) } } })
        Spacer(Modifier.height(16.dp))
    }
}
