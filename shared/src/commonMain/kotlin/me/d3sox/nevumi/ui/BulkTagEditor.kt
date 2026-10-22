package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.FileEditTarget
import me.d3sox.nevumi.library.TagChanges
import me.d3sox.nevumi.library.Track
import kotlinx.coroutines.launch

@Composable
internal fun BulkTagEditor(tracks: List<Track>, controller: MusicController, target: FileEditTarget = FileEditTarget.LOCAL, dismiss: () -> Unit) {
    val fields = listOf("Title", "Artist", "Album", "Album artist", "Genres", "Track number", "Disc number", "Year")
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it, "") } } }
    var changed by remember { mutableStateOf(emptySet<String>()) }
    var saving by remember { mutableStateOf(false) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun value(field: String): String? = if (field in changed) values.getValue(field).trim() else null
    val valid = changed.isNotEmpty() && (value("Title")?.isNotBlank() != false) &&
        (value("Track number")?.let { it.isEmpty() || (it.toIntOrNull() ?: -1) >= 0 } != false) &&
        (value("Disc number")?.let { (it.toIntOrNull() ?: 0) > 0 } != false) &&
        (value("Year")?.let { it.isBlank() || it.toIntOrNull() in 1..9999 } != false)
    KeyboardAlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(if (target == FileEditTarget.SERVER) "Edit ${countLabel(tracks.size, "server song")}" else "Edit ${countLabel(tracks.size, "song")}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Only checked fields will change. Original files are kept for undo.")
            fields.forEach { field ->
                Row(Modifier.fillMaxWidth().toggleable(field in changed, enabled = !saving, role = Role.Checkbox) {
                    changed = if (it) changed + field else changed - field
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(field in changed, onCheckedChange = null, enabled = !saving)
                    Text("Change ${field.lowercase()}")
                }
                OutlinedTextField(values.getValue(field), { values[field] = it }, label = { Text(field) },
                    singleLine = true, enabled = !saving && field in changed, modifier = Modifier.fillMaxWidth())
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = valid && !saving, onClick = {
            saving = true
            scope.launch {
                controller.editTags(tracks.map { it.id }, TagChanges(title = value("Title"), artist = value("Artist"),
                    album = value("Album"), albumArtist = value("Album artist"),
                    genres = value("Genres")?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() },
                    trackNumber = value("Track number")?.let { it.toIntOrNull() ?: 0 }, discNumber = value("Disc number")?.toIntOrNull(),
                    year = value("Year")?.let { it.toIntOrNull() ?: 0 }), target).join()
                saving = false
                if (controller.state.value.error == null) dismiss()
            }
        }) { Text("Save changes") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("Cancel") } })
}
