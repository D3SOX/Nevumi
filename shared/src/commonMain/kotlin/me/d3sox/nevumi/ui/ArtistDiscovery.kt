package me.d3sox.nevumi.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.discovery_about
import me.d3sox.nevumi.resources.discovery_not_in_library
import me.d3sox.nevumi.resources.discovery_open_last_fm
import me.d3sox.nevumi.resources.discovery_play_similar
import me.d3sox.nevumi.resources.discovery_play_similar_songs
import me.d3sox.nevumi.resources.discovery_show_less
import me.d3sox.nevumi.resources.discovery_show_more
import me.d3sox.nevumi.resources.discovery_similar_artists
import me.d3sox.nevumi.resources.discovery_top_songs
import org.jetbrains.compose.resources.stringResource

/** The server's knowledge of an artist below the artist page's hero; shows nothing outside artist pages or without a server. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistDiscovery(artist: String, tab: LibraryTab, state: AppState, controller: MusicController) {
    if (tab != LibraryTab.ARTISTS && tab != LibraryTab.ALBUM_ARTISTS || artist.isBlank() || state.server == null) return
    // The library is a key so a refresh or scan maps the server's songs and artists to the library again; details stay cached.
    val discovery by produceState<me.d3sox.nevumi.app.ArtistDiscovery?>(null, artist, state.server, state.libraryReady, state.tracks) {
        value = controller.discoveryController.artistInfo(artist)
    }
    val navigate = LocalLibraryNavigate.current
    val browser = LocalUriHandler.current
    var expanded by rememberSaveable(artist) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton({ controller.discoveryController.playSimilarToArtist(artist) }) {
            Icon(AppIcon.RECOMMENDATIONS.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.discovery_play_similar))
        }
        discovery?.let { info ->
            if (info.biography.isNotBlank()) {
                Text(stringResource(Res.string.discovery_about), style = MaterialTheme.typography.titleMedium)
                Text(info.biography, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ expanded = !expanded }) {
                        Text(stringResource(if (expanded) Res.string.discovery_show_less else Res.string.discovery_show_more))
                    }
                    info.lastFmUrl?.let { url ->
                        TextButton({ runCatching { browser.openUri(url) } }) {
                            Icon(AppIcon.OPEN_EXTERNAL.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.discovery_open_last_fm))
                        }
                    }
                }
            }
            if (info.similarInLibrary.isNotEmpty() || info.similarElsewhere.isNotEmpty()) {
                Text(stringResource(Res.string.discovery_similar_artists), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.similarInLibrary.forEach { name ->
                        SuggestionChip({ navigate(LibraryTab.ARTISTS, name) }, { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            icon = { Icon(AppIcon.ARTIST.vector, null) })
                    }
                    info.similarElsewhere.forEach { name ->
                        val missing = stringResource(Res.string.discovery_not_in_library, name)
                        SuggestionChip({}, { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, enabled = false,
                            modifier = Modifier.semantics { contentDescription = missing })
                    }
                }
            }
            if (info.topSongs.isNotEmpty()) {
                val title = stringResource(Res.string.discovery_top_songs)
                Text(title, style = MaterialTheme.typography.titleMedium)
                val ids = remember(info.topSongs) { info.topSongs.map { it.id } }
                info.topSongs.forEachIndexed { index, track -> TrackRow(track, controller, ids, index, contextName = "$artist · $title") }
            }
        }
    }
}

/** Adds "Play similar songs" to a song's menu when the song is on the connected server. */
@Composable
internal fun MutableList<TrackOption>.playSimilarOption(track: Track, state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val serverId = state.server?.serverId ?: return
    if (track.sources.none { it.kind == SourceKind.SERVER && it.id.startsWith("server:$serverId:") }) return
    add(TrackOption(stringResource(Res.string.discovery_play_similar_songs), AppIcon.RECOMMENDATIONS) {
        dismiss(); controller.discoveryController.playSimilarToTrack(track.id)
    })
}
