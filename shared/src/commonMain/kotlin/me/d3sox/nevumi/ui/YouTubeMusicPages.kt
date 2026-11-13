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
import androidx.compose.ui.draw.alpha
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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.back
import me.d3sox.nevumi.resources.discovery_show_less
import me.d3sox.nevumi.resources.discovery_show_more
import me.d3sox.nevumi.resources.library_play_collection
import me.d3sox.nevumi.resources.library_shuffle_collection
import me.d3sox.nevumi.resources.retry
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
 * so going back returns to its search results without searching again.
 */
@Composable
internal fun YouTubePageHost(state: AppState, controller: MusicController, bottomSpace: Dp = 0.dp, content: @Composable () -> Unit) {
    var pages by remember { mutableStateOf(emptyList<OpenedYouTubePage>()) }
    val focus = LocalFocusManager.current
    // The search field below would otherwise keep taking typed keys.
    val open: (YouTubeCollection) -> Unit = { collection -> focus.clearFocus(); pages = pages + OpenedYouTubePage(collection, pages.size) }
    val active = LocalNavigationActive.current
    Box(Modifier.fillMaxSize().onKeyEvent {
        // Escape leaves only the top page, not the search the pages were opened from.
        if (active && pages.isNotEmpty() && it.key == Key.Escape && it.type == KeyEventType.KeyUp) { pages = pages.dropLast(1); true } else false
    }) {
        Box(Modifier.fillMaxSize().then(if (pages.isEmpty()) Modifier else Modifier.clearAndSetSemantics {})) {
            CompositionLocalProvider(LocalOpenYouTubePage provides open, LocalNavigationActive provides (active && pages.isEmpty()), content = content)
        }
        AnimatedContent(pages.lastOrNull(), transitionSpec = {
            val forward = (targetState?.depth ?: -1) > (initialState?.depth ?: -1)
            val enter = slideInHorizontally(tween(250)) { if (forward) it else -it / 4 } + fadeIn(tween(180))
            val exit = slideOutHorizontally(tween(250)) { if (forward) -it / 4 else it } + fadeOut(tween(180))
            (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else -1f }
        }, label = "YouTube pages") { opened ->
            if (opened != null) Surface(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalOpenYouTubePage provides open, LocalNavigationActive provides (active && opened == pages.lastOrNull())) {
                    YouTubeBrowseScreen(opened, state, controller, bottomSpace) { pages = pages.filter { it.depth < opened.depth } }
                }
            }
        }
    }
}

@Composable
private fun YouTubeBrowseScreen(opened: OpenedYouTubePage, state: AppState, controller: MusicController, bottomSpace: Dp, back: () -> Unit) {
    PlatformBackHandler(back)
    val browser = LocalUriHandler.current
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
     * Plays the list shelf at [index] from [track], or from its start. A list that continues, such as a long playlist, first loads
     * its remaining songs, up to 1,000, so playback goes on past the songs shown.
     */
    fun playShelf(content: YouTubePageContent, index: Int, track: YouTubeTrack?, shuffle: Boolean = false) {
        // A later tap wins over one still waiting for the list to load.
        playing.value?.cancel()
        playing.value = scope.launch {
            if (index == content.continued) load {
                while (content.items(index).size < 1_000) appendNext(content, content.next ?: break)
            }
            // A page that failed to load stops the list where it got to.
            val tracks = content.items(index).filterIsInstance<YouTubeTrack>()
            if (tracks.isNotEmpty()) controller.playYouTube(tracks, track?.let(tracks::indexOf)?.coerceAtLeast(0) ?: 0, content.page.title, shuffle)
        }
    }
    LaunchedEffect(opened, attempt) {
        if (opened.page == null) load { opened.page = YouTubePageContent(controller.browseYouTube(opened.collection.link)) }
    }
    val owned = rememberOwnedSongs(state)
    val content = opened.page
    // Holding focus lets keys such as Escape reach the pages instead of the search field below.
    val focus = remember { FocusRequester() }
    LaunchedEffect(opened) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().testTag("youtube-page").focusRequester(focus).focusable()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcon.BACK, stringResource(Res.string.back), onClick = back)
            // The header names the page until it scrolls away.
            val scrolled by remember(opened) { derivedStateOf { opened.page == null || opened.list.firstVisibleItemIndex > 0 } }
            Text(opened.collection.title, Modifier.weight(1f).alpha(if (scrolled) 1f else 0f), style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            AppIconButton(AppIcon.OPEN_EXTERNAL, stringResource(Res.string.sources_open_on_youtube_music)) {
                runCatching { browser.openUri(opened.collection.link.url) }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = ((maxWidth - 24.dp) / 172.dp).toInt().coerceAtLeast(2)
            LazyColumn(Modifier.fillMaxSize().testTag("youtube-page-shelves"), opened.list, contentPadding = PaddingValues(bottom = 16.dp + bottomSpace)) {
                if (content != null) {
                    val page = content.page
                    val playable = page.shelves.indices.firstOrNull { index ->
                        page.shelves[index].layout == YouTubeShelfLayout.ROWS && content.items(index).any { it is YouTubeTrack }
                    }
                    item(key = "header") {
                        YouTubePageHeader(opened.collection, page, maxWidth, controller, playable?.let { index -> { shuffle -> playShelf(content, index, null, shuffle) } })
                    }
                    page.shelves.forEachIndexed { index, shelf ->
                        val items = content.items(index)
                        if (shelf.title.isNotEmpty() || shelf.more != null) item(key = "heading:$index") { ShelfHeading(shelf) }
                        when (shelf.layout) {
                            YouTubeShelfLayout.ROWS -> {
                                items(items, key = { "$index:${it.key}" }) { item ->
                                    when (item) {
                                        is YouTubeTrack -> YouTubeTrackRow(item, owned, controller, onOptions = { picked = item }) {
                                            playShelf(content, index, item)
                                        }
                                        is YouTubeCollection -> YouTubeCollectionRow(item, controller)
                                    }
                                }
                            }
                            YouTubeShelfLayout.CAROUSEL -> item(key = "carousel:$index") {
                                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(items, key = { it.key }) { item -> YouTubeCard(item, controller) { picked = it } }
                                }
                            }
                            YouTubeShelfLayout.GRID -> itemsIndexed(items.chunked(columns), key = { row, _ -> "grid:$index:$row" }) { _, row ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { item -> YouTubeCard(item, controller, Modifier.weight(1f)) { picked = it } }
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
    picked?.let { track -> YouTubeOptionsSheet(track, state, controller) { picked = null } }
}

@Composable
private fun YouTubePageHeader(collection: YouTubeCollection, page: YouTubeBrowsePage, width: Dp, controller: MusicController,
    play: ((shuffle: Boolean) -> Unit)?) {
    val open = LocalOpenYouTubePage.current
    val image = page.artworkUrl?.let { url -> rememberYouTubeImage(sizedArtwork(url, if (page.banner) 1440 else 544), if (page.banner) 1440 else 400, controller) }
    val fallback = MaterialTheme.colorScheme.primary
    val color by produceState(fallback, image) {
        value = if (image == null) fallback else withContext(Dispatchers.Default) { image.themeColor(fallback) }
    }
    val surface = MaterialTheme.colorScheme.surface
    // A list such as an artist's singles is named by the shelf it was opened from; its page names the artist.
    val list = collection.kind == YouTubeCollectionKind.LIST
    val title = if (list) collection.title else page.title.ifEmpty { collection.title }
    val subtitle = if (list) page.title else page.subtitle
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
            if (play != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                var shuffle by remember { mutableStateOf(false) }
                val shuffleDescription = stringResource(Res.string.library_shuffle_collection)
                Spacer(Modifier.weight(1f))
                IconToggleButton(shuffle, { shuffle = it }, Modifier.semantics { contentDescription = shuffleDescription; selected = shuffle },
                    colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Icon(AppIcon.SHUFFLE.vector, null) }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { play(shuffle) }, Modifier.size(56.dp)) {
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

/** A card of a shelf: tapping a song or video plays it, anything else opens its page. Artists are round. */
@Composable
private fun YouTubeCard(item: YouTubeItem, controller: MusicController, modifier: Modifier? = null, options: (YouTubeTrack) -> Unit) {
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
            onLongClick = { if (item is YouTubeTrack) options(item) })
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

/** A list row of an artist, album or playlist, such as an artist search result; tapping it opens its page. */
@Composable
internal fun YouTubeCollectionRow(collection: YouTubeCollection, controller: MusicController) {
    val open = LocalOpenYouTubePage.current
    val description = collection.description()
    ListItem(modifier = Modifier.clickable { open(collection) }.semantics { contentDescription = description },
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
