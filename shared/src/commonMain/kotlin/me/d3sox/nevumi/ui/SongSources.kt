package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.music_server
import me.d3sox.nevumi.resources.player_downloaded_audio
import me.d3sox.nevumi.resources.player_link_another_copy
import me.d3sox.nevumi.resources.player_link_choose_text
import me.d3sox.nevumi.resources.player_link_copy
import me.d3sox.nevumi.resources.player_link_songs
import me.d3sox.nevumi.resources.player_link_songs_text
import me.d3sox.nevumi.resources.player_link_songs_title
import me.d3sox.nevumi.resources.player_local_file
import me.d3sox.nevumi.resources.player_search_songs
import me.d3sox.nevumi.resources.player_song_sources
import me.d3sox.nevumi.resources.player_unavailable
import me.d3sox.nevumi.resources.player_unknown_album
import me.d3sox.nevumi.resources.unknown_artist
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SongSources(track: Track, controller: MusicController, dismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<Track?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selected = candidate
    if (selected != null) {
        AlertDialog(onDismissRequest = { if (!saving) candidate = null }, title = { Text(stringResource(Res.string.player_link_songs_title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${track.metadata.title} · ${track.metadata.artist}")
                Text("${selected.metadata.title} · ${selected.metadata.artist}")
                Text(stringResource(Res.string.player_link_songs_text))
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    controller.linkTracks(track.id, selected.id).join()
                    saving = false
                    if (controller.state.value.error == null) dismiss()
                }
            }) { Text(stringResource(Res.string.player_link_songs)) }
        }, dismissButton = { TextButton(enabled = !saving, onClick = { candidate = null }) { Text(stringResource(Res.string.cancel)) } })
        return
    }
    KeyboardAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(if (choosing) Res.string.player_link_another_copy else Res.string.player_song_sources)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choosing) {
                Text(stringResource(Res.string.player_link_choose_text))
                OutlinedTextField(search, { search = it }, label = { Text(stringResource(Res.string.player_search_songs)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val candidates = filterTracks(state.tracks.filterNot { it.id == track.id }, LibraryQuery(text = search))
                LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                    items(candidates, key = { it.id }) { copy ->
                        val linkDescription = stringResource(Res.string.player_link_copy, copy.metadata.title)
                        TextButton(onClick = { candidate = copy }, modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = linkDescription
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(copy.metadata.title, style = MaterialTheme.typography.titleMedium)
                                Text(copy.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) })
                                Text(copy.metadata.album.ifBlank { stringResource(Res.string.player_unknown_album) }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                track.sources.forEach { source ->
                    Text(stringResource(when (source.kind) { SourceKind.LOCAL -> Res.string.player_local_file; SourceKind.DOWNLOAD -> Res.string.player_downloaded_audio; SourceKind.SERVER -> Res.string.music_server }),
                        style = MaterialTheme.typography.titleSmall)
                    Text(source.location, style = MaterialTheme.typography.bodySmall)
                    if (!source.available) Text(stringResource(Res.string.player_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActionButton(stringResource(Res.string.player_link_another_copy), AppIcon.LINK, style = ActionStyle.OUTLINED) { choosing = true }
            }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
}
