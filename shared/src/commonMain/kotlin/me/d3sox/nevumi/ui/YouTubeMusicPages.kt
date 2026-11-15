package me.d3sox.nevumi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.materialkolor.ktx.themeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.TrackAction
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.back
import me.d3sox.nevumi.resources.library_collection_options
import me.d3sox.nevumi.resources.discovery_show_less
import me.d3sox.nevumi.resources.discovery_show_more
import me.d3sox.nevumi.resources.library_play_collection
import me.d3sox.nevumi.resources.library_shuffle_collection
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.retry
import me.d3sox.nevumi.resources.settings
import me.d3sox.nevumi.resources.sources_save_to_this_device
import me.d3sox.nevumi.resources.sources_youtube_save_to_server
import me.d3sox.nevumi.resources.sources_open_album
import me.d3sox.nevumi.resources.sources_open_artist
import me.d3sox.nevumi.resources.sources_open_on_youtube_music
import me.d3sox.nevumi.resources.sources_open_playlist
import me.d3sox.nevumi.resources.sources_open_youtube_list
import me.d3sox.nevumi.resources.sources_see_all
import me.d3sox.nevumi.resources.sources_show_more
import me.d3sox.nevumi.resources.sources_youtube_page_empty
import me.d3sox.nevumi.resources.sources_youtube_page_failed
import me.d3sox.nevumi.resources.sources_youtube_song_description
import me.d3sox.nevumi.youtube.*
import org.jetbrains.compose.resources.stringResource

/** Opens a YouTube Music page, such as an artist found by a search, above the screen it was found on. */
internal val LocalOpenYouTubePage = compositionLocalOf<(YouTubeCollection) -> Unit> { {} }

/** Opens the YouTube Music page of a video's artist or album, given the video's ID, once YouTube names it. */
internal val LocalOpenYouTubeLinkedPage = compositionLocalOf<(String, YouTubeCollectionKind) -> Unit> { { _, _ -> } }

/** Looks up a video's artist or album on a scope that outlives the menu it was picked in, and hands its page to [open]. */
@Composable
internal fun rememberYouTubeLinkedPageOpener(controller: MusicController, open: (YouTubeCollection) -> Unit): (String, YouTubeCollectionKind) -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(open)
    return remember(controller, scope) {
        // Only the last pick opens, even when an earlier lookup answers after it.
        var lookup: Job? = null
        { videoId, kind -> lookup?.cancel(); lookup = scope.launch { controller.youTubePage(videoId, kind)?.let { latest(it) } } }
    }
}

/** An open YouTube Music page; it keeps what it loaded and its scroll position while pages opened from it are on top. */
private class OpenedYouTubePage(val collection: YouTubeCollection, val depth: Int) {
    var page by mutableStateOf<YouTubePageContent?>(null)
    val list = LazyListState()
}

/** A loaded page with the entries that continuing its last list added. */
private class YouTubePageContent(val page: YouTubeBrowsePage) {
    val more = mutableStateListOf<YouTubeItem>()
    var next by mutableStateOf(page.next)
    // Only the last list or grid continues, such as a long playlist or discography.
    val continued = page.shelves.indexOfLast { it.layout != YouTubeShelfLayout.CAROUSEL }
    fun items(index: Int): List<YouTubeItem> = page.shelves[index].items.let { if (index == continued) it + more else it }
}

/**
 * Shows YouTube Music pages that [content] opens, one above the other. [content] stays composed below them,
 * so going back returns to its search results without searching again. A [request] from elsewhere in the app,
 * such as the player's song menu, opens on top too, and [onRequestShown] then lets it go.
 */
@Composable
internal fun YouTubePageHost(state: AppState, controller: MusicController, bottomSpace: Dp = 0.dp, request: YouTubeCollection? = null,
    onRequestShown: () -> Unit = {}, content: @Composable () -> Unit) {
    var pages by remember { mutableStateOf(emptyList<OpenedYouTubePage>()) }
    val focus = LocalFocusManager.current
    // The search field below would otherwise keep taking typed keys.
    val open: (YouTubeCollection) -> Unit = { collection -> focus.clearFocus(); pages = pages + OpenedYouTubePage(collection, pages.size) }
    val openLinked = rememberYouTubeLinkedPageOpener(controller, open)
    val active = LocalNavigationActive.current
    Box(Modifier.fillMaxSize().onKeyEvent {
        // Escape leaves only the top page, not the search the pages were opened from.
        if (active && pages.isNotEmpty() && it.key == Key.Escape && it.type == KeyEventType.KeyUp) { pages = pages.dropLast(1); true } else false
    }) {
        Box(Modifier.fillMaxSize().then(if (pages.isEmpty()) Modifier else Modifier.clearAndSetSemantics {})) {
            CompositionLocalProvider(LocalOpenYouTubePage provides open, LocalOpenYouTubeLinkedPage provides openLinked,
                LocalNavigationActive provides (active && pages.isEmpty()), content = content)
        }
        AnimatedContent(pages.lastOrNull(), transitionSpec = {
            val forward = (targetState?.depth ?: -1) > (initialState?.depth ?: -1)
            val enter = slideInHorizontally(tween(250)) { if (forward) it else -it / 4 } + fadeIn(tween(180))
            val exit = slideOutHorizontally(tween(250)) { if (forward) -it / 4 else it } + fadeOut(tween(180))
            (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else -1f }
        }, label = "YouTube pages") { opened ->
            if (opened != null) Surface(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalOpenYouTubePage provides open, LocalOpenYouTubeLinkedPage provides openLinked,
                    LocalNavigationActive provides (active && opened == pages.lastOrNull())) {
                    YouTubeBrowseScreen(opened, state, controller, bottomSpace) { pages = pages.filter { it.depth < opened.depth } }
                }
            }
        }
    }
    // After the content's own effects, so the page also takes the focus a newly shown search field asks for.
    LaunchedEffect(request) { request?.let { open(it); onRequestShown() } }
}

@Composable
private fun YouTubeBrowseScreen(opened: OpenedYouTubePage, state: AppState, controller: MusicController, bottomSpace: Dp, back: () -> Unit) {
    PlatformBackHandler(back)
    var loading by remember(opened) { mutableStateOf(false) }
    var error by remember(opened) { mutableStateOf<String?>(null) }
    var permanent by remember(opened) { mutableStateOf(false) }
    var attempt by remember(opened) { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<YouTubeTrack?>(null) }
    val scope = rememberCoroutineScope()
    val playing = remember { mutableStateOf<Job?>(null) }
    val failed = stringResource(Res.string.sources_youtube_page_failed)
    suspend fun load(block: suspend () -> Unit) {
        loading = true; error = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failed; permanent = (failure as? YouTubeException)?.retryable == false }
        finally { loading = false }
    }
    suspend fun appendNext(content: YouTubePageContent, continuation: YouTubeContinuation) {
        val page = controller.browseYouTubeMore(continuation)
        val shown = content.items(content.continued).map { it.key }.toSet()
        content.more += page.results.filter { it.key !in shown }
        content.next = page.next
    }
    fun continueList(content: YouTubePageContent, continuation: YouTubeContinuation) = scope.launch { load { appendNext(content, continuation) } }
    /**
     * The songs of the list shelf at [index]. A list that continues, such as a long playlist, first loads its remaining songs,
     * up to 1,000, so playing or queueing it goes on past the songs shown.
     */
    suspend fun shelfTracks(content: YouTubePageContent, index: Int): List<YouTubeTrack> {
        if (index == content.continued) load {
            while (content.items(index).size < 1_000) appendNext(content, content.next ?: break)
        }
        // A page that failed to load stops the list where it got to.
        return content.items(index).filterIsInstance<YouTubeTrack>()
    }
    /** Plays the list shelf at [index] from [track], or from its start. */
    fun playShelf(content: YouTubePageContent, index: Int, track: YouTubeTrack?, shuffle: Boolean = false) {
        // The tap counts from now, so anything chosen while the list loads wins; a later tap here also stops this one's loading.
        val request = controller.reservePlayRequest()
        playing.value?.cancel()
        playing.value = scope.launch {
            val tracks = shelfTracks(content, index)
            if (tracks.isNotEmpty()) controller.playYouTube(tracks, track?.let(tracks::indexOf)?.coerceAtLeast(0) ?: 0, content.page.title, shuffle, request)
        }
    }
    fun enqueueShelf(content: YouTubePageContent, index: Int, action: QueueAction) = scope.launch {
        // Playing keeps every song shown, so a tapped song past the first 1,000 still plays; adding stops at 1,000.
        val tracks = shelfTracks(content, index).take(1_000)
        if (tracks.isNotEmpty()) controller.enqueueYouTube(tracks, action)
    }
    fun downloadShelf(content: YouTubePageContent, index: Int, destination: YouTubeDestination) = scope.launch {
        val tracks = shelfTracks(content, index).take(1_000)
        if (tracks.isNotEmpty()) controller.downloadFromYouTube(tracks, content.page.title.ifEmpty { opened.collection.title }, destination,
            state.preferences.youTubeReviewTags)
    }
    LaunchedEffect(opened, attempt) {
        if (opened.page == null) load { opened.page = YouTubePageContent(controller.browseYouTube(opened.collection.link)) }
    }
    val owned = rememberOwnedSongs(state)
    val content = opened.page
    // Holding focus lets keys such as Escape reach the pages instead of the search field below.
    val focus = remember { FocusRequester() }
    LaunchedEffect(opened) { focus.requestFocus() }
    val selection = remember(opened) { YouTubeSelection() }
    val playable = content?.let { loaded ->
        loaded.page.shelves.indices.firstOrNull { index ->
            loaded.page.shelves[index].layout == YouTubeShelfLayout.ROWS && loaded.items(index).any { it is YouTubeTrack }
        }
    }
    val actions = if (content != null && playable != null) ShelfActions({ shuffle -> playShelf(content, playable, null, shuffle) },
        { action -> enqueueShelf(content, playable, action) }, { destination -> downloadShelf(content, playable, destination) }) else null
    CompositionLocalProvider(LocalYouTubeSelection provides selection, LocalYouTubePageLink provides opened.collection.link) {
    Column(Modifier.fillMaxSize().testTag("youtube-page").focusRequester(focus).focusable()) {
        // Like a library page, the header names the page and holds what it can do.
        if (selection.active) YouTubeSelectionHeader(selection, state, controller)
        else Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp).testTag("youtube-page-header"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIconButton(AppIcon.BACK, stringResource(Res.string.back), onClick = back)
            val (title, subtitle) = pageTitles(opened.collection, content?.page)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Like a library page's header, it leads to the settings; the page's own options sit beside its play button.
            LocalOpenSettings.current?.let { settings -> AppIconButton(AppIcon.SETTINGS, stringResource(Res.string.settings), onClick = settings) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = ((maxWidth - 24.dp) / 172.dp).toInt().coerceAtLeast(2)
            LazyColumn(Modifier.fillMaxSize().testTag("youtube-page-shelves"), opened.list, contentPadding = PaddingValues(bottom = 16.dp + bottomSpace)) {
                if (content != null) {
                    val page = content.page
                    item(key = "header") { YouTubePageHeader(opened.collection, page, maxWidth, state, controller, actions) }
                    page.shelves.forEachIndexed { index, shelf ->
                        val items = content.items(index)
                        if (shelf.title.isNotEmpty() || shelf.more != null) item(key = "heading:$index") { ShelfHeading(shelf) }
                        when (shelf.layout) {
                            YouTubeShelfLayout.ROWS -> {
                                // Read when picked, so selecting all also covers songs loaded since.
                                val tracks = YouTubeTrackList(index) { content.items(index).filterIsInstance<YouTubeTrack>() }
                                items(items, key = { "$index:${it.key}" }) { item ->
                                    when (item) {
                                        is YouTubeTrack -> YouTubeTrackRow(item, owned, state, controller, tracks) {
                                            playShelf(content, index, item)
                                        }
                                        is YouTubeCollection -> YouTubeCollectionRow(item, state, controller)
                                    }
                                }
                            }
                            YouTubeShelfLayout.CAROUSEL -> item(key = "carousel:$index") {
                                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(items, key = { it.key }) { item -> YouTubeCard(item, state, controller) { picked = it } }
                                }
                            }
                            YouTubeShelfLayout.GRID -> itemsIndexed(items.chunked(columns), key = { row, _ -> "grid:$index:$row" }) { _, row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { item -> YouTubeCard(item, state, controller, Modifier.weight(1f)) { picked = it } }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    if (page.shelves.isEmpty() && page.description.isEmpty()) item(key = "empty") {
                        Text(stringResource(Res.string.sources_youtube_page_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                error?.let { message ->
                    item(key = "error") {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.error)
                            if (!permanent) ActionButton(stringResource(Res.string.retry), AppIcon.REFRESH, style = ActionStyle.TONAL) {
                                val next = content?.next
                                if (content == null || next == null) attempt++ else continueList(content, next)
                            }
                        }
                    }
                }
                val next = content?.next
                if (next != null && !loading && error == null) item(key = "more") {
                    TextButton(onClick = { continueList(content, next) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(Res.string.sources_show_more)) }
                }
            }
        }
    }
    picked?.let { track -> YouTubeSongMenu(track, state, controller) { picked = null } }
    }
}

/** The title and subtitle of a page; a list such as an artist's singles is named by the shelf it was opened from, and its page names the artist. */
private fun pageTitles(collection: YouTubeCollection, page: YouTubeBrowsePage?): Pair<String, String> {
    if (page == null) return collection.title to ""
    val list = collection.kind == YouTubeCollectionKind.LIST
    return (if (list) collection.title else page.title.ifEmpty { collection.title }) to (if (list) page.title else page.subtitle)
}

/** What the page header does with the page's songs. */
private class ShelfActions(val play: (shuffle: Boolean) -> Unit, val enqueue: (QueueAction) -> Unit, val download: (YouTubeDestination) -> Unit)

/** The songs of [collection], which is not open; each choice loads its page first. */
private fun MusicController.collectionActions(collection: YouTubeCollection, state: AppState) = ShelfActions({ shuffle -> playYouTube(collection, shuffle) },
    { action -> enqueueYouTube(collection, action) }, { destination -> downloadFromYouTube(collection, destination, state.preferences.youTubeReviewTags) })

/** The options of a page: queue or save its songs when [actions] are given, and open it on YouTube Music. */
@Composable
private fun YouTubeCollectionMenuButton(link: YouTubeLink, state: AppState, controller: MusicController, actions: ShelfActions?) {
    var options by remember { mutableStateOf(false) }
    Box {
        AppIconButton(AppIcon.MORE, stringResource(Res.string.library_collection_options)) { options = true }
        DropdownMenu(options, { options = false }) {
            YouTubeCollectionMenuItems(link, state, controller, actions) { options = false }
        }
    }
}

@Composable
private fun YouTubeCollectionMenuItems(link: YouTubeLink, state: AppState, controller: MusicController, actions: ShelfActions?, dismiss: () -> Unit) {
    val browser = LocalUriHandler.current
    if (actions != null) {
        QueueActionMenuItems(enabled = true, dismiss = dismiss, enqueue = actions.enqueue)
        // The same choices as a YouTube song's menu, for all of the page's songs.
        YouTubeSaveMenuItems(state, controller) { destination -> dismiss(); actions.download(destination) }
    }
    AppMenuItem({ Text(stringResource(Res.string.sources_open_on_youtube_music)) },
        { dismiss(); runCatching { browser.openUri(link.url) } }, leadingIcon = { Icon(AppIcon.OPEN_EXTERNAL.vector, null) })
}

/**
 * Gives a card or row of [collection] the page's options in the same centered menu as a song's, which a long press or a right click opens.
 * An artist's, album's or playlist's songs can be played, queued or saved without opening its page.
 */
@Composable
private fun YouTubeCollectionMenuHost(collection: YouTubeCollection, state: AppState, controller: MusicController, modifier: Modifier = Modifier,
    content: @Composable (openMenu: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val openMenu = { menu = true }
    Box(modifier.onSecondaryClick(openMenu)) { content(openMenu) }
    if (menu) {
        val dismiss = { menu = false }
        val browser = LocalUriHandler.current
        val actions = if (collection.kind == YouTubeCollectionKind.LIST) null else controller.collectionActions(collection, state)
        val options = buildList {
            // The menu closes before an option runs.
            fun option(label: String, icon: AppIcon, detail: String? = null, click: () -> Unit) = add(TrackOption(label, icon, detail) { dismiss(); click() })
            if (actions != null) {
                option(stringResource(Res.string.play), AppIcon.PLAY) { actions.play(false) }
                option(stringResource(Res.string.library_shuffle_collection), AppIcon.SHUFFLE) { actions.play(true) }
                listOf(TrackAction.PLAY_NEXT to QueueAction.PLAY_NEXT, TrackAction.PLAY_AFTER to QueueAction.PLAY_AFTER, TrackAction.APPEND to QueueAction.APPEND)
                    .forEach { (track, queue) -> option(track.displayLabel(), track.icon()) { actions.enqueue(queue) } }
                if (controller.youTubeDeviceDownloads) {
                    option(stringResource(Res.string.sources_save_to_this_device), AppIcon.DEVICE) { actions.download(YouTubeDestination.DEVICE) }
                    val sftp = state.sftp
                    if (controller.youTubeServerUploads && sftp != null) option(stringResource(Res.string.sources_youtube_save_to_server), AppIcon.SERVER,
                        sftp.libraryName) { actions.download(YouTubeDestination.SERVER) }
                }
            }
            option(stringResource(Res.string.sources_open_on_youtube_music), AppIcon.OPEN_EXTERNAL) { runCatching { browser.openUri(collection.link.url) } }
        }
        val artist = collection.kind == YouTubeCollectionKind.ARTIST
        TrackActionDialog(collection.title, collection.subtitle, { maxDimension ->
            YouTubeImage(collection.artworkUrl?.let { sizedArtwork(it, 400) }, controller, Modifier.fillMaxSize(), collection.kind.shape(),
                collection.kind.icon(), maxDimension)
        }, options, dismiss, artworkCornerRadius = if (artist) 200.dp else 12.dp)
    }
}

@Composable
private fun YouTubePageHeader(collection: YouTubeCollection, page: YouTubeBrowsePage, width: Dp, state: AppState, controller: MusicController,
    actions: ShelfActions?) {
    val open = LocalOpenYouTubePage.current
    val image = page.artworkUrl?.let { url -> rememberYouTubeImage(sizedArtwork(url, if (page.banner) 1440 else 544), if (page.banner) 1440 else 400, controller).image }
    val fallback = MaterialTheme.colorScheme.primary
    val color by produceState(fallback, image) {
        value = if (image == null) fallback else withContext(Dispatchers.Default) { image.themeColor(fallback) }
    }
    val surface = MaterialTheme.colorScheme.surface
    val (title, subtitle) = pageTitles(collection, page)
    Column(Modifier.fillMaxWidth()) {
        if (page.banner) Box(Modifier.fillMaxWidth().height(min(width / 1.5f, 360.dp))) {
            image?.let { androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .35f to surface.copy(alpha = .1f), 1f to surface)))
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color.copy(alpha = .4f), surface)))
            .padding(start = 24.dp, end = 24.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page.artworkUrl != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                YouTubeImage(sizedArtwork(page.artworkUrl, 544), controller, Modifier.size(200.dp), collection.kind.shape(), collection.kind.icon(), 400)
            }
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            page.artist?.let { artist ->
                Text(artist.title, Modifier.clickable { open(artist) }.semantics { contentDescription = artist.title },
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (subtitle.isNotEmpty()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page.description.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Text(page.description, Modifier.clickable { expanded = !expanded }, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                Text(stringResource(if (expanded) Res.string.discovery_show_less else Res.string.discovery_show_more),
                    Modifier.clickable { expanded = !expanded }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            // Like a library album: its options on the left, shuffle and play on the right when it has songs.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                var shuffle by remember { mutableStateOf(false) }
                val shuffleDescription = stringResource(Res.string.library_shuffle_collection)
                YouTubeCollectionMenuButton(collection.link, state, controller, actions)
                Spacer(Modifier.weight(1f))
                if (actions != null) IconToggleButton(shuffle, { shuffle = it }, Modifier.semantics { contentDescription = shuffleDescription; selected = shuffle },
                    colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Icon(AppIcon.SHUFFLE.vector, null) }
                if (actions != null) Spacer(Modifier.width(8.dp))
                if (actions != null) FilledIconButton(onClick = { actions.play(shuffle) }, Modifier.size(56.dp)) {
                    Icon(AppIcon.PLAY.vector, stringResource(Res.string.library_play_collection), Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun ShelfHeading(shelf: YouTubeShelf) {
    val open = LocalOpenYouTubePage.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(shelf.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        shelf.more?.let { more -> TextButton(onClick = { open(more) }) { Text(stringResource(Res.string.sources_see_all)) } }
    }
}

/**
 * A card of a shelf: tapping a song or video plays it, anything else opens its page. A long press or right click opens a song's menu
 * with [options], or the menu of the page it opens. Artists are round.
 */
@Composable
private fun YouTubeCard(item: YouTubeItem, state: AppState, controller: MusicController, modifier: Modifier? = null, options: (YouTubeTrack) -> Unit) {
    // A grid sizes the host, the row's own child, and the card fills it.
    if (item is YouTubeCollection) YouTubeCollectionMenuHost(item, state, controller, modifier ?: Modifier) { openMenu ->
        YouTubeCardContent(item, controller, modifier?.let { Modifier.fillMaxWidth() }, openMenu)
    }
    else YouTubeCardContent(item, controller, modifier) { options(item as YouTubeTrack) }
}

@Composable
private fun YouTubeCardContent(item: YouTubeItem, controller: MusicController, modifier: Modifier?, openMenu: () -> Unit) {
    val open = LocalOpenYouTubePage.current
    val wide = item is YouTubeTrack && !item.song
    val (title, subtitle) = when (item) {
        is YouTubeTrack -> item.title to item.artist
        is YouTubeCollection -> item.title to item.subtitle
    }
    val description = item.description()
    val artist = item is YouTubeCollection && item.kind == YouTubeCollectionKind.ARTIST
    // A grid sizes its cards; in a carousel, videos are wider than the square covers.
    Column((modifier ?: Modifier.width(if (wide) 256.dp else 160.dp)).clip(RoundedCornerShape(12.dp))
        .combinedClickable(onClick = { if (item is YouTubeTrack) controller.playYouTube(item) else open(item as YouTubeCollection) },
            onLongClick = openMenu)
        .semantics(mergeDescendants = true) { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val kind = (item as? YouTubeCollection)?.kind
        YouTubeImage(item.artworkUrl()?.let { sizedArtwork(it, 400) }, controller, Modifier.fillMaxWidth().aspectRatio(if (wide) 16 / 9f else 1f),
            kind?.shape() ?: RoundedCornerShape(12.dp), kind?.icon() ?: AppIcon.YOUTUBE, 320)
        Spacer(Modifier.height(6.dp))
        Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = if (artist) TextAlign.Center else TextAlign.Start)
        if (subtitle.isNotEmpty()) Text(subtitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (artist) TextAlign.Center else TextAlign.Start)
    }
}

/**
 * A list row of an artist, album or playlist, such as an artist search result; tapping it opens its page, and a long press or right click
 * opens its menu.
 */
@Composable
internal fun YouTubeCollectionRow(collection: YouTubeCollection, state: AppState, controller: MusicController) =
    YouTubeCollectionMenuHost(collection, state, controller) { openMenu -> YouTubeCollectionRowContent(collection, controller, openMenu) }

@Composable
private fun YouTubeCollectionRowContent(collection: YouTubeCollection, controller: MusicController, openMenu: () -> Unit) {
    val open = LocalOpenYouTubePage.current
    val description = collection.description()
    ListItem(modifier = Modifier.combinedClickable(onClick = { open(collection) }, onLongClick = openMenu).semantics { contentDescription = description },
        headlineContent = { Text(collection.title, maxLines = 2) },
        supportingContent = { if (collection.subtitle.isNotEmpty()) Text(collection.subtitle, maxLines = 1) },
        leadingContent = {
            YouTubeImage(collection.artworkUrl?.let { sizedArtwork(it, 226) }, controller, Modifier.size(48.dp), collection.kind.shape(), collection.kind.icon(), 160)
        },
        trailingContent = { Icon(AppIcon.FORWARD.vector, null) })
}

private fun YouTubeItem.artworkUrl() = when (this) {
    is YouTubeTrack -> artworkUrl
    is YouTubeCollection -> artworkUrl
}

@Composable
private fun YouTubeItem.description() = when (this) {
    is YouTubeTrack -> stringResource(Res.string.sources_youtube_song_description, title, artist)
    is YouTubeCollection -> stringResource(when (kind) {
        YouTubeCollectionKind.ARTIST -> Res.string.sources_open_artist
        YouTubeCollectionKind.ALBUM -> Res.string.sources_open_album
        YouTubeCollectionKind.PLAYLIST -> Res.string.sources_open_playlist
        YouTubeCollectionKind.LIST -> Res.string.sources_open_youtube_list
    }, title)
}

private fun YouTubeCollectionKind.shape(): Shape = if (this == YouTubeCollectionKind.ARTIST) CircleShape else RoundedCornerShape(12.dp)

private fun YouTubeCollectionKind.icon() = when (this) {
    YouTubeCollectionKind.ARTIST -> AppIcon.ARTIST
    YouTubeCollectionKind.ALBUM -> AppIcon.ALBUM
    YouTubeCollectionKind.PLAYLIST, YouTubeCollectionKind.LIST -> AppIcon.QUEUE
}
