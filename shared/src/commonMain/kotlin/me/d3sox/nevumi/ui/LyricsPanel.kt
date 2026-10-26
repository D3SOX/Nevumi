package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.LyricsState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.lyrics.Lyrics
import me.d3sox.nevumi.lyrics.LyricsSource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.lyrics_loading
import me.d3sox.nevumi.resources.lyrics_none
import me.d3sox.nevumi.resources.lyrics_none_online_hint
import me.d3sox.nevumi.resources.lyrics_seek_line
import me.d3sox.nevumi.resources.lyrics_source_embedded
import me.d3sox.nevumi.resources.lyrics_source_lrclib
import me.d3sox.nevumi.resources.lyrics_source_netease
import me.d3sox.nevumi.resources.lyrics_source_server
import me.d3sox.nevumi.resources.lyrics_source_sidecar
import me.d3sox.nevumi.resources.lyrics_title
import me.d3sox.nevumi.resources.retry
import org.jetbrains.compose.resources.stringResource

/** Whether Now Playing shows lyrics in place of the artwork, kept while the app runs. */
internal object PlayerLyrics {
    var visible by mutableStateOf(false)
    fun shown(controller: MusicController) = visible && controller.lyricsController.available
}

@Composable
internal fun LyricsButton(controller: MusicController) {
    if (!controller.lyricsController.available) return
    val label = stringResource(Res.string.lyrics_title)
    IconToggleButton(PlayerLyrics.visible, { PlayerLyrics.visible = it }, Modifier.semantics { selected = PlayerLyrics.visible }) {
        Icon(Icons.Rounded.Lyrics, label)
    }
}

/** The lyrics in the artwork's place, keeping an empty [artworkAnchor] so the moving artwork shrinks into it instead of covering them. */
@Composable
internal fun LyricsCover(state: AppState, controller: MusicController, artworkAnchor: Modifier) {
    Box(Modifier.fillMaxSize()) {
        LyricsPanel(state, controller, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.Center).size(0.dp).then(artworkAnchor))
    }
}

@Composable
internal fun LyricsPanel(state: AppState, controller: MusicController, modifier: Modifier = Modifier) {
    val lyrics by controller.lyricsController.lyrics.collectAsState()
    Surface(modifier.testTag("lyrics-panel"), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        when (val current = lyrics) {
            LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val loading = stringResource(Res.string.lyrics_loading)
                CircularProgressIndicator(Modifier.semantics { contentDescription = loading },
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            LyricsState.None -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.lyrics_none), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (!state.preferences.fetchLyricsOnline) Text(stringResource(Res.string.lyrics_none_online_hint),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center)
                TextButton({ controller.lyricsController.refresh() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) { Text(stringResource(Res.string.retry)) }
            }
            is LyricsState.Available -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (current.lyrics.synced) SyncedLyrics(current.lyrics, controller)
                    else Text(current.lyrics.plain, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(current.lyrics.source.label()), Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, controller: MusicController) {
    val positionFlow = remember(controller) { controller.state.map { it.playback.positionMs }.distinctUntilChanged() }
    val position by positionFlow.collectAsState(controller.state.value.playback.positionMs)
    val lines = lyrics.lines
    val active by remember(lines) { derivedStateOf { lines.indexOfLast { it.startMs <= position } } }
    val list = rememberLazyListState()
    var autoScrolling by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    // A scroll the panel didn't start is the listener reading ahead, so following the song waits until they've stopped for a moment.
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress && !autoScrolling }.collectLatest { reading ->
            if (reading) paused = true else if (paused) { delay(3_000); paused = false }
        }
    }
    LaunchedEffect(active, paused) {
        if (paused || active < 0) return@LaunchedEffect
        autoScrolling = true
        try { list.animateScrollToItem(active, -list.layoutInfo.viewportSize.height / 3) } finally { autoScrolling = false }
    }
    val seekLabel = stringResource(Res.string.lyrics_seek_line)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = maxHeight / 3)) {
            itemsIndexed(lines) { index, line ->
                val current = index == active
                Text(line.text.ifBlank { "♪" }, Modifier.fillMaxWidth().clickable(onClickLabel = seekLabel) { controller.seek(line.startMs) }
                    .padding(vertical = 8.dp).testTag("lyrics-line-$index").semantics { selected = current },
                    style = MaterialTheme.typography.titleLarge, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

private fun LyricsSource.label() = when (this) {
    LyricsSource.EMBEDDED -> Res.string.lyrics_source_embedded
    LyricsSource.SIDECAR -> Res.string.lyrics_source_sidecar
    LyricsSource.SERVER -> Res.string.lyrics_source_server
    LyricsSource.LRCLIB -> Res.string.lyrics_source_lrclib
    LyricsSource.NETEASE -> Res.string.lyrics_source_netease
}
