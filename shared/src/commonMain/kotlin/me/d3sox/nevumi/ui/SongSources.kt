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
        AlertDialog(onDismissRequest = { if (!saving) candidate = null }, title = { Text("Link these songs?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${track.metadata.title} · ${track.metadata.artist}")
                Text("${selected.metadata.title} · ${selected.metadata.artist}")
                Text("Combine their sources into one library song. Favorites, playlists, queues, and file undo are kept.")
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
            }) { Text("Link songs") }
        }, dismissButton = { TextButton(enabled = !saving, onClick = { candidate = null }) { Text("Cancel") } })
        return
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (choosing) "Link another copy" else "Song sources") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choosing) {
                Text("Choose another library song that represents the same recording and edition.")
                OutlinedTextField(search, { search = it }, label = { Text("Search songs") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val candidates = filterTracks(state.tracks.filterNot { it.id == track.id }, LibraryQuery(text = search))
                LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                    items(candidates, key = { it.id }) { copy ->
                        TextButton(onClick = { candidate = copy }, modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Link copy: ${copy.metadata.title}"
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(copy.metadata.title, style = MaterialTheme.typography.titleMedium)
                                Text(copy.metadata.artist.ifBlank { "Unknown artist" })
                                Text(copy.metadata.album.ifBlank { "Unknown album" }, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                track.sources.forEach { source ->
                    Text(when (source.kind) { SourceKind.LOCAL -> "Local file"; SourceKind.DOWNLOAD -> "Downloaded audio"; SourceKind.SERVER -> "Music server" },
                        style = MaterialTheme.typography.titleSmall)
                    Text(source.location, style = MaterialTheme.typography.bodySmall)
                    if (!source.available) Text("Unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActionButton("Link another copy", AppIcon.LINK, style = ActionStyle.OUTLINED) { choosing = true }
            }
        }
    }, confirmButton = { PageAction("Done", onClick = dismiss) })
}
