package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.FileEditTarget
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.TrackMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TagEditor(track: Track, controller: MusicController, target: FileEditTarget = FileEditTarget.LOCAL, dismiss: () -> Unit) {
    val state by controller.state.collectAsState()
    var title by remember(track.id) { mutableStateOf(track.metadata.title) }
    var artist by remember(track.id) { mutableStateOf(track.metadata.artist) }
    var album by remember(track.id) { mutableStateOf(track.metadata.album) }
    var albumArtist by remember(track.id) { mutableStateOf(track.metadata.albumArtist) }
    var genres by remember(track.id) { mutableStateOf(track.metadata.genres.joinToString("; ")) }
    var number by remember(track.id) { mutableStateOf(track.metadata.trackNumber.takeIf { it > 0 }?.toString().orEmpty()) }
    var disc by remember(track.id) { mutableStateOf(track.metadata.discNumber.toString()) }
    var year by remember(track.id) { mutableStateOf(track.metadata.year.takeIf { it > 0 }?.toString().orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var original by remember(track.id) { mutableStateOf<TrackMetadata?>(null) }
    var loadError by remember(track.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(track.id) {
        try {
            val metadata = controller.fileTags(track.id, target)
            title = metadata.title
            artist = metadata.artist
            album = metadata.album
            albumArtist = metadata.albumArtist
            genres = metadata.genres.joinToString("; ")
            number = metadata.trackNumber.takeIf { it > 0 }?.toString().orEmpty()
            disc = metadata.discNumber.toString()
            year = metadata.year.takeIf { it > 0 }?.toString().orEmpty()
            original = metadata
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { loadError = error.message ?: "The music file could not be read" }
    }
    val scope = rememberCoroutineScope()
    val yearValid = year.isBlank() || year.toIntOrNull() in 1..9999
    val editable = original != null && !saving
    val valid = original != null && title.isNotBlank() && (number.isBlank() || (number.toIntOrNull() ?: -1) >= 0) && (disc.toIntOrNull() ?: 0) > 0 && yearValid
    KeyboardAlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(if (target == FileEditTarget.SERVER) "Edit server tags" else "Edit tags") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (target == FileEditTarget.SERVER) "Save tags to the server music file. Nevumi keeps the original so you can undo this edit."
                else "Save tags to the music file. Nevumi keeps the original so you can undo this edit.")
            if (original == null && loadError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true,
                enabled = editable, isError = title.isBlank(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text("Album artist") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(year, { year = it }, label = { Text("Year") }, singleLine = true, enabled = editable,
                isError = !yearValid, supportingText = { Text("1–9999, or leave empty to remove the year") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(genres, { genres = it }, label = { Text("Genres") },
                supportingText = { Text("Separate genres with a semicolon") }, singleLine = true, enabled = editable, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(number, { number = it }, label = { Text("Track number") }, singleLine = true, enabled = editable,
                isError = number.isNotBlank() && (number.toIntOrNull() ?: -1) < 0, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(disc, { disc = it }, label = { Text("Disc number") }, singleLine = true, enabled = editable,
                isError = (disc.toIntOrNull() ?: 0) <= 0, modifier = Modifier.fillMaxWidth())
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = valid && !saving, onClick = {
            saving = true
            scope.launch {
                controller.editTags(track.id, requireNotNull(original).copy(title = title.trim(), artist = artist.trim(), album = album.trim(),
                    albumArtist = albumArtist.trim(), genres = genres.split(';').map { it.trim() }.filter { it.isNotEmpty() },
                    trackNumber = number.toIntOrNull() ?: 0, discNumber = disc.toInt(), year = year.toIntOrNull() ?: 0), target).join()
                saving = false
                if (controller.state.value.error == null) dismiss()
            }
        }) { Text("Save changes") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("Cancel") } })
}
