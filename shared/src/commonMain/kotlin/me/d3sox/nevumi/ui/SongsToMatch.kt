package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.sync.PairedDevices
import me.d3sox.nevumi.sync.SyncUnmatched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.matches
import me.d3sox.nevumi.resources.player_all_songs_matched
import me.d3sox.nevumi.resources.player_all_songs_matched_text
import me.d3sox.nevumi.resources.player_another_device
import me.d3sox.nevumi.resources.player_choose_this_copy
import me.d3sox.nevumi.resources.player_choose_your_copy
import me.d3sox.nevumi.resources.player_could_not_match
import me.d3sox.nevumi.resources.player_find_your_song
import me.d3sox.nevumi.resources.player_match_shared_song
import me.d3sox.nevumi.resources.player_no_songs_found
import me.d3sox.nevumi.resources.player_songs_to_match
import me.d3sox.nevumi.resources.player_songs_to_match_text
import me.d3sox.nevumi.resources.player_your_library_count
import me.d3sox.nevumi.resources.unknown_artist
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SongsToMatch(devices: PairedDevices, songs: List<SyncUnmatched>, tracks: List<Track>,
    artwork: Map<String, String>, dismiss: () -> Unit) {
    val peers by devices.state.collectAsState()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun key(song: SyncUnmatched) = "${song.peerDeviceId}:${song.track.key}"
    AnimatedNavigation(selected, null, canGoBack = selected != null, onBack = { selected = null; error = null },
        depth = { if (it == null) 0 else 1 }) { selection ->
        val song = songs.firstOrNull { key(it) == selection }
        SettingsScreen(onDismissRequest = { if (selection == null) dismiss() else { selected = null; error = null } },
            title = { Text(stringResource(if (song == null) Res.string.player_songs_to_match else Res.string.player_choose_your_copy)) }, text = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (song == null) {
                        Text(stringResource(Res.string.player_songs_to_match_text),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (songs.isEmpty()) Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(AppIcon.CHECK.vector, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(Res.string.player_all_songs_matched), style = MaterialTheme.typography.titleLarge)
                                Text(stringResource(Res.string.player_all_songs_matched_text), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        else LazyColumn(Modifier.weight(1f).testTag("songs-to-match"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(songs, key = { key(it) }) { shared ->
                                val matchDescription = stringResource(Res.string.player_match_shared_song, shared.track.metadata.title)
                                Card(onClick = { selected = key(shared); error = null }, modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = matchDescription
                                }) {
                                    MatchingSong(shared.track.metadata, peer = peers.peers.firstOrNull { it.deviceId == shared.peerDeviceId }?.name ?: stringResource(Res.string.player_another_device),
                                        trailing = { Icon(AppIcon.FORWARD.vector, null) })
                                }
                            }
                        }
                    } else {
                        var query by rememberSaveable(selection) { mutableStateOf("") }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            MatchingSong(song.track.metadata, peer = peers.peers.firstOrNull { it.deviceId == song.peerDeviceId }?.name ?: stringResource(Res.string.player_another_device), emphasized = true)
                        }
                        val findLabel = stringResource(Res.string.player_find_your_song)
                        OutlinedTextField(query, { query = it }, label = { Text(findLabel) }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = findLabel })
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val candidates = remember(query, tracks, song) {
                            tracks.filter { track -> listOf(track.metadata.title, track.metadata.artist, track.metadata.album, track.metadata.folder)
                                .any { it.contains(query.trim(), ignoreCase = true) } }
                                .sortedByDescending { track ->
                                    (if (track.metadata.title.equals(song.track.metadata.title, true)) 2 else 0) +
                                        (if (song.track.metadata.artist.isNotBlank() && track.metadata.artist.equals(song.track.metadata.artist, true)) 1 else 0)
                                }
                        }
                        Text(if (query.isBlank()) stringResource(Res.string.player_your_library_count, candidates.size) else countLabel(candidates.size, Res.plurals.matches), style = MaterialTheme.typography.titleSmall)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (candidates.isEmpty()) item { Text(stringResource(Res.string.player_no_songs_found), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            items(candidates, key = { it.id }) { track ->
                                val chooseDescription = stringResource(Res.string.player_choose_this_copy, track.metadata.title)
                                val matchFailed = stringResource(Res.string.player_could_not_match)
                                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                                    Box(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                        busy = true; error = null
                                        scope.launch {
                                            try { devices.library.mapTrack(song.peerDeviceId, song.track.key, track.id); selected = null }
                                            catch (cancelled: CancellationException) { throw cancelled }
                                            catch (failure: Exception) { error = failure.message ?: matchFailed }
                                            finally { busy = false }
                                        }
                                    }.semantics { contentDescription = chooseDescription }) {
                                        MatchingSong(track.metadata, artwork = artwork[track.id], peer = track.metadata.folder,
                                            trailing = { Icon(AppIcon.LINK.vector, null, tint = MaterialTheme.colorScheme.primary) })
                                    }
                                }
                            }
                        }
                    }
                }
            })
    }
}

@Composable
private fun MatchingSong(metadata: TrackMetadata, artwork: String? = null, peer: String,
    trailing: (@Composable () -> Unit)? = null, emphasized: Boolean = false) {
    ListItem(colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
        headlineColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        supportingColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
        headlineContent = { Text(metadata.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }, metadata.album).filter { it.isNotBlank() }.joinToString(" · "),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (peer.isNotBlank()) Text(peer, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, leadingContent = { CollectionArtwork(artwork, metadata.title, AppIcon.MUSIC, Modifier.size(64.dp)) }, trailingContent = trailing)
}
