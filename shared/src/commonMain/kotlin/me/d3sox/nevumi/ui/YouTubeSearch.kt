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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
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
import me.d3sox.nevumi.resources.sources_go_to_youtube_music_album
import me.d3sox.nevumi.resources.sources_go_to_youtube_music_artist
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
import me.d3sox.nevumi.youtube.YouTubeCollectionKind
import me.d3sox.nevumi.youtube.YouTubeContinuation
import me.d3sox.nevumi.youtube.YouTubeLink
import me.d3sox.nevumi.youtube.YouTubeDestination
import me.d3sox.nevumi.youtube.YouTubeException
import me.d3sox.nevumi.youtube.suggestedTags
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
    val selection = remember(query, kind) { YouTubeSelection() }
    val tracks by remember(results) { derivedStateOf { results.filterIsInstance<YouTubeTrack>() } }
    CompositionLocalProvider(LocalYouTubeSelection provides selection) { Column(Modifier.fillMaxSize()) {
        val labels = mapOf(YouTubeSearchKind.SONGS to Res.string.search_type_songs, YouTubeSearchKind.ARTISTS to Res.string.search_type_artists,
            YouTubeSearchKind.ALBUMS to Res.string.search_type_albums, YouTubeSearchKind.VIDEOS to Res.string.sources_youtube_videos)
        // Picking songs replaces the kinds, so another kind cannot drop the picked songs from view.
        if (selection.active) YouTubeSelectionHeader(selection, state, controller)
        else OverflowChipRow(YouTubeSearchKind.entries, kind, { stringResource(labels.getValue(it)) }, { "youtube-kind:${it.name}" },
            Modifier.padding(horizontal = 16.dp)) { kind = it }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("youtube-results")) {
            items(results, key = { "youtube:${it.key}" }) { result ->
                when (result) {
                    is YouTubeTrack -> YouTubeTrackRow(result, owned, state, controller, YouTubeTrackList("results") { tracks }) { controller.playYouTube(result) }
                    is YouTubeCollection -> YouTubeCollectionRow(result, state, controller)
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
    } }
}

/** The artist and title of every song in the library, which marks YouTube results the user already has. */
@Composable
internal fun rememberOwnedSongs(state: AppState): Set<String> =
    remember(state.tracks) { state.tracks.filter { it.inLibrary }.map { songKey(it.metadata.artist, it.metadata.title) }.toSet() }

/** The YouTube Music page that is open, whose own album a song's menu does not offer to open again. */
internal val LocalYouTubePageLink = compositionLocalOf<YouTubeLink?> { null }

/**
 * What a swipe or the menu of a YouTube song can do without adding it to the library: queue it, save it to this device, go to its
 * artist in the library with [navigate] when the library has songs by them, or start a selection with [select].
 */
private class YouTubeTrackActions(val track: YouTubeTrack, val controller: MusicController, val state: AppState, val libraryArtist: String?,
    val navigate: (LibraryTab, String?) -> Unit, val select: (() -> Unit)?) {
    fun available(action: TrackAction) = when (action) {
        TrackAction.PLAY_NEXT, TrackAction.PLAY_AFTER, TrackAction.APPEND -> true
        TrackAction.DOWNLOAD -> controller.youTubeDeviceDownloads
        TrackAction.GO_ARTIST -> libraryArtist != null
        TrackAction.SELECT -> select != null
        else -> false
    }
    fun perform(action: TrackAction): Boolean {
        if (!available(action)) return false
        when (action) {
            TrackAction.PLAY_NEXT -> controller.playYouTube(track, QueueAction.PLAY_NEXT)
            TrackAction.PLAY_AFTER -> controller.playYouTube(track, QueueAction.PLAY_AFTER)
            TrackAction.APPEND -> controller.playYouTube(track, QueueAction.APPEND)
            TrackAction.DOWNLOAD -> controller.downloadFromYouTube(track, YouTubeDestination.DEVICE, state.preferences.youTubeReviewTags)
            TrackAction.GO_ARTIST -> navigate(LibraryTab.ARTISTS, libraryArtist)
            TrackAction.SELECT -> select?.invoke()
            else -> Unit
        }
        return true
    }
}

@Composable
private fun rememberYouTubeTrackActions(track: YouTubeTrack, controller: MusicController, state: AppState, select: (() -> Unit)?) =
    YouTubeTrackActions(track, controller, state, libraryArtist(track.suggestedTags().second, state), LocalLibraryNavigate.current, select)

/**
 * A song or video of [list]; [play] runs on a tap. It swipes like a library song, and a long press, right click or its options button
 * opens the song menu. While songs of the list are selected, a tap selects it instead.
 */
@Composable
internal fun YouTubeTrackRow(track: YouTubeTrack, owned: Set<String>, state: AppState, controller: MusicController,
    list: YouTubeTrackList = YouTubeTrackList(track.videoId) { listOf(track) }, play: () -> Unit) {
    val selection = LocalYouTubeSelection.current
    val select = selection?.let { { it.toggle(track, list) } }
    val selecting = selection?.active == true
    val selected = selection?.contains(track) == true
    val actions = rememberYouTubeTrackActions(track, controller, state, select)
    val inLibrary = songKey(track.artist, track.title) in owned
    val description = stringResource(Res.string.sources_youtube_song_description, track.title, track.artist)
    var menu by remember { mutableStateOf(false) }
    var artworkPlaced by remember { mutableStateOf(false) }
    var artworkBounds by remember { mutableStateOf<Rect?>(null) }
    var menuOrigin by remember { mutableStateOf<Rect?>(null) }
    val openMenu = { menuOrigin = artworkBounds; artworkPlaced = false; menu = true }
    val labels = listOf(TrackAction.PLAY_NEXT, TrackAction.PLAY_AFTER, TrackAction.APPEND, TrackAction.DOWNLOAD, TrackAction.GO_ARTIST,
        TrackAction.SELECT).associateWith { it.displayLabel() }
    SwipeActionRow(track.videoId, state.preferences.swipeRight, state.preferences.swipeLeft, !selecting, actions::available,
        actions::perform, { it.icon() }, { labels[it].orEmpty() }, Modifier.onSecondaryClick(openMenu).semantics { this.selected = selected }) {
        ListItem(modifier = Modifier.combinedClickable(onClick = { if (selecting) select?.invoke() else play() }, onLongClick = openMenu)
            .semantics { contentDescription = description },
            headlineContent = { Text(track.title, maxLines = 2) },
            supportingContent = {
                Text(listOfNotNull(track.artist.ifBlank { null }, track.durationMs.takeIf { it > 0 }?.let(::playbackTime),
                    if (track.song) null else stringResource(Res.string.sources_youtube_video)).joinToString(" · "), maxLines = 1)
            },
            leadingContent = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selecting) Checkbox(selected, onCheckedChange = { select?.invoke() })
                    YouTubeImage(smallArtwork(track), controller, Modifier.size(48.dp).onGloballyPositioned {
                        artworkBounds = Rect(it.positionOnScreen(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }.graphicsLayer { alpha = if (menu && artworkPlaced) 0f else 1f })
                }
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (inLibrary) Text(stringResource(Res.string.sources_in_library), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppIconButton(AppIcon.MORE, stringResource(Res.string.sources_youtube_options_for, track.title), onClick = openMenu)
                }
            })
    }
    if (menu) YouTubeSongMenu(track, state, controller, menuOrigin, { artworkPlaced = true }, select) { menu = false }
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

/** [artist] when the library has songs by them, so going to the artist opens their library page. */
@Composable
internal fun libraryArtist(artist: String, state: AppState): String? = remember(artist, state.tracks) {
    artist.takeIf { name -> name.isNotBlank() && state.tracks.any { it.inLibrary && it.metadata.artist == name } }
}

/**
 * The song menu of a YouTube song: it queues the song, goes to its artist in the library or on YouTube Music or to its album on
 * YouTube Music, selects it with [select], saves it to this device or the server, or opens it on YouTube.
 */
@Composable
internal fun YouTubeSongMenu(track: YouTubeTrack, state: AppState, controller: MusicController, artworkOrigin: Rect? = null,
    onArtworkPlaced: () -> Unit = {}, select: (() -> Unit)? = null, dismiss: () -> Unit) {
    val browser = LocalUriHandler.current
    val navigate = LocalLibraryNavigate.current
    val openPage = LocalOpenYouTubeLinkedPage.current
    val reviewTags = state.preferences.youTubeReviewTags
    val actions = rememberYouTubeTrackActions(track, controller, state, select)
    val currentPage = LocalYouTubePageLink.current
    val options = buildList {
        // The menu closes before an option runs.
        fun option(label: String, icon: AppIcon, detail: String? = null, click: () -> Unit) = add(TrackOption(label, icon, detail) { dismiss(); click() })
        listOf(TrackAction.PLAY_NEXT, TrackAction.PLAY_AFTER, TrackAction.APPEND, TrackAction.GO_ARTIST)
            .filter(actions::available).forEach { action -> option(action.displayLabel(), action.icon()) { actions.perform(action) } }
        option(stringResource(Res.string.sources_go_to_youtube_music_artist), AppIcon.YOUTUBE) { openPage(track.videoId, YouTubeCollectionKind.ARTIST) }
        // Only songs belong to an album; on an album's own page, its songs would only open it again.
        if (track.song && currentPage?.browseId?.startsWith("MPRE") != true) option(stringResource(Res.string.sources_go_to_youtube_music_album),
            AppIcon.ALBUM) { openPage(track.videoId, YouTubeCollectionKind.ALBUM) }
        if (actions.available(TrackAction.SELECT)) option(TrackAction.SELECT.displayLabel(), TrackAction.SELECT.icon()) { actions.perform(TrackAction.SELECT) }
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
