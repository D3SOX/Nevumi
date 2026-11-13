package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
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
import me.d3sox.nevumi.settings.TrackAction
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.player_song_menu_artwork
import me.d3sox.nevumi.resources.retry
import me.d3sox.nevumi.resources.search_type_albums
import me.d3sox.nevumi.resources.search_type_artists
import me.d3sox.nevumi.resources.search_type_songs
import me.d3sox.nevumi.resources.sources_in_library
import me.d3sox.nevumi.resources.sources_no_youtube_results
import me.d3sox.nevumi.resources.sources_open_on_youtube
import me.d3sox.nevumi.resources.sources_save_to_this_device
import me.d3sox.nevumi.resources.sources_search_youtube_description
import me.d3sox.nevumi.resources.sources_search_youtube_for
import me.d3sox.nevumi.resources.sources_show_more
import me.d3sox.nevumi.resources.sources_youtube_options_for
import me.d3sox.nevumi.resources.sources_youtube_save_to_server
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
                    is YouTubeTrack -> YouTubeTrackRow(result, owned, state, controller) { controller.playYouTube(result) }
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
}

/** The artist and title of every song in the library, which marks YouTube results the user already has. */
@Composable
internal fun rememberOwnedSongs(state: AppState): Set<String> =
    remember(state.tracks) { state.tracks.filter { it.inLibrary }.map { songKey(it.metadata.artist, it.metadata.title) }.toSet() }

/** What a swipe on a YouTube song can do without adding it to the library: queue it, or save it to this device. */
private fun MusicController.youTubeActionAvailable(action: TrackAction) = when (action) {
    TrackAction.PLAY_NEXT, TrackAction.PLAY_AFTER, TrackAction.APPEND -> true
    TrackAction.DOWNLOAD -> youTubeDeviceDownloads
    else -> false
}

private fun MusicController.performYouTubeAction(track: YouTubeTrack, action: TrackAction, state: AppState): Boolean {
    if (!youTubeActionAvailable(action)) return false
    when (action) {
        TrackAction.PLAY_NEXT -> playYouTube(track, QueueAction.PLAY_NEXT)
        TrackAction.PLAY_AFTER -> playYouTube(track, QueueAction.PLAY_AFTER)
        TrackAction.APPEND -> playYouTube(track, QueueAction.APPEND)
        TrackAction.DOWNLOAD -> downloadFromYouTube(track, YouTubeDestination.DEVICE, state.preferences.youTubeReviewTags)
        else -> Unit
    }
    return true
}

/** A song or video; [play] runs on a tap. It swipes like a library song, and a long press, right click or its options button opens the song menu. */
@Composable
internal fun YouTubeTrackRow(track: YouTubeTrack, owned: Set<String>, state: AppState, controller: MusicController, play: () -> Unit) {
    val inLibrary = songKey(track.artist, track.title) in owned
    val description = stringResource(Res.string.sources_youtube_song_description, track.title, track.artist)
    var menu by remember { mutableStateOf(false) }
    var artworkPlaced by remember { mutableStateOf(false) }
    var artworkBounds by remember { mutableStateOf<Rect?>(null) }
    var menuOrigin by remember { mutableStateOf<Rect?>(null) }
    val openMenu = { menuOrigin = artworkBounds; artworkPlaced = false; menu = true }
    val labels = listOf(TrackAction.PLAY_NEXT, TrackAction.PLAY_AFTER, TrackAction.APPEND, TrackAction.DOWNLOAD).associateWith { it.displayLabel() }
    SwipeActionRow(track.videoId, state.preferences.swipeRight, state.preferences.swipeLeft, true, controller::youTubeActionAvailable,
        { controller.performYouTubeAction(track, it, state) }, { it.icon() }, { labels[it].orEmpty() }, Modifier.onSecondaryClick(openMenu)) {
        ListItem(modifier = Modifier.combinedClickable(onClick = play, onLongClick = openMenu).semantics { contentDescription = description },
            headlineContent = { Text(track.title, maxLines = 2) },
            supportingContent = {
                Text(listOfNotNull(track.artist.ifBlank { null }, track.durationMs.takeIf { it > 0 }?.let(::playbackTime),
                    if (track.song) null else stringResource(Res.string.sources_youtube_video)).joinToString(" · "), maxLines = 1)
            },
            leadingContent = {
                YouTubeImage(smallArtwork(track), controller, Modifier.size(48.dp).onGloballyPositioned {
                    artworkBounds = Rect(it.positionOnScreen(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                }.graphicsLayer { alpha = if (menu && artworkPlaced) 0f else 1f })
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (inLibrary) Text(stringResource(Res.string.sources_in_library), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppIconButton(AppIcon.MORE, stringResource(Res.string.sources_youtube_options_for, track.title), onClick = openMenu)
                }
            })
    }
    if (menu) YouTubeSongMenu(track, state, controller, menuOrigin, { artworkPlaced = true }) { menu = false }
}

// Recently shown YouTube images, so scrolling back or returning to a page does not fetch them again.
private val youTubeImages = mutableMapOf<String, ImageBitmap>()

/** An image from YouTube's image servers, at most [maxDimension] pixels wide or high once decoded. */
@Composable
internal fun rememberYouTubeImage(url: String?, maxDimension: Int, controller: MusicController): ImageBitmap? {
    val key = url?.let { "$maxDimension:$it" }
    val image by produceState(key?.let(youTubeImages::get), key) {
        // A reused row or card keeps the previous image until it learns this one's.
        value = key?.let(youTubeImages::get)
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

/** The song menu of a YouTube song: it queues the song, saves it to this device or the server, or opens it on YouTube. */
@Composable
internal fun YouTubeSongMenu(track: YouTubeTrack, state: AppState, controller: MusicController, artworkOrigin: Rect? = null,
    onArtworkPlaced: () -> Unit = {}, dismiss: () -> Unit) {
    val browser = LocalUriHandler.current
    val reviewTags = state.preferences.youTubeReviewTags
    val options = buildList {
        // The menu closes before an option runs.
        fun option(label: String, icon: AppIcon, detail: String? = null, click: () -> Unit) = add(TrackOption(label, icon, detail) { dismiss(); click() })
        listOf(TrackAction.PLAY_NEXT, TrackAction.PLAY_AFTER, TrackAction.APPEND).forEach { action ->
            option(action.displayLabel(), action.icon()) { controller.performYouTubeAction(track, action, state) }
        }
        if (controller.youTubeDeviceDownloads) option(stringResource(Res.string.sources_save_to_this_device), AppIcon.DEVICE) {
            controller.downloadFromYouTube(track, YouTubeDestination.DEVICE, reviewTags)
        }
        val sftp = state.sftp
        if (controller.youTubeDeviceDownloads && controller.youTubeServerUploads && sftp != null)
            option(stringResource(Res.string.sources_youtube_save_to_server), AppIcon.SERVER, sftp.libraryName) {
                controller.downloadFromYouTube(track, YouTubeDestination.SERVER, reviewTags)
            }
        option(stringResource(Res.string.sources_open_on_youtube), AppIcon.OPEN_EXTERNAL) { runCatching { browser.openUri(track.url) } }
    }
    val artworkDescription = stringResource(Res.string.player_song_menu_artwork, track.title)
    TrackActionDialog(track.title, track.artist, { _ ->
        val image = rememberYouTubeImage(smallArtwork(track), 160, controller)
        image?.let { Image(it, artworkDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(AppIcon.YOUTUBE.vector, artworkDescription, Modifier.fillMaxSize(.45f), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
    }, options, dismiss, artworkOrigin, 12.dp, onArtworkPlaced)
}
