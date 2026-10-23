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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.album
import me.d3sox.nevumi.resources.album_artist
import me.d3sox.nevumi.resources.artist
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.genres
import me.d3sox.nevumi.resources.library_bulk_tag_hint
import me.d3sox.nevumi.resources.library_change_album
import me.d3sox.nevumi.resources.library_change_album_artist
import me.d3sox.nevumi.resources.library_change_artist
import me.d3sox.nevumi.resources.library_change_disc_number
import me.d3sox.nevumi.resources.library_change_genres
import me.d3sox.nevumi.resources.library_change_title
import me.d3sox.nevumi.resources.library_change_track_number
import me.d3sox.nevumi.resources.library_change_year
import me.d3sox.nevumi.resources.library_disc_number
import me.d3sox.nevumi.resources.library_edit_count
import me.d3sox.nevumi.resources.library_save_changes
import me.d3sox.nevumi.resources.library_track_number
import me.d3sox.nevumi.resources.server_songs
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.title
import me.d3sox.nevumi.resources.year
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource

/** A tag the bulk editor can change, with its field label and the label of its checkbox. */
private enum class BulkTagField(val label: StringResource, val change: StringResource) {
    TITLE(Res.string.title, Res.string.library_change_title), ARTIST(Res.string.artist, Res.string.library_change_artist),
    ALBUM(Res.string.album, Res.string.library_change_album), ALBUM_ARTIST(Res.string.album_artist, Res.string.library_change_album_artist),
    GENRES(Res.string.genres, Res.string.library_change_genres), TRACK_NUMBER(Res.string.library_track_number, Res.string.library_change_track_number),
    DISC_NUMBER(Res.string.library_disc_number, Res.string.library_change_disc_number), YEAR(Res.string.year, Res.string.library_change_year)
}

@Composable
internal fun BulkTagEditor(tracks: List<Track>, controller: MusicController, target: FileEditTarget = FileEditTarget.LOCAL, dismiss: () -> Unit) {
    val fields = BulkTagField.entries
    val values = remember { mutableStateMapOf<BulkTagField, String>().apply { fields.forEach { put(it, "") } } }
    var changed by remember { mutableStateOf(emptySet<BulkTagField>()) }
    var saving by remember { mutableStateOf(false) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    fun value(field: BulkTagField): String? = if (field in changed) values.getValue(field).trim() else null
    val valid = changed.isNotEmpty() && (value(BulkTagField.TITLE)?.isNotBlank() != false) &&
        (value(BulkTagField.TRACK_NUMBER)?.let { it.isEmpty() || (it.toIntOrNull() ?: -1) >= 0 } != false) &&
        (value(BulkTagField.DISC_NUMBER)?.let { (it.toIntOrNull() ?: 0) > 0 } != false) &&
        (value(BulkTagField.YEAR)?.let { it.isBlank() || it.toIntOrNull() in 1..9999 } != false)
    KeyboardAlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(stringResource(Res.string.library_edit_count,
        countLabel(tracks.size, if (target == FileEditTarget.SERVER) Res.plurals.server_songs else Res.plurals.songs))) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.library_bulk_tag_hint))
            fields.forEach { field ->
                Row(Modifier.fillMaxWidth().toggleable(field in changed, enabled = !saving, role = Role.Checkbox) {
                    changed = if (it) changed + field else changed - field
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(field in changed, onCheckedChange = null, enabled = !saving)
                    Text(stringResource(field.change))
                }
                OutlinedTextField(values.getValue(field), { values[field] = it }, label = { Text(stringResource(field.label)) },
                    singleLine = true, enabled = !saving && field in changed, modifier = Modifier.fillMaxWidth())
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = valid && !saving, onClick = {
            saving = true
            scope.launch {
                controller.editTags(tracks.map { it.id }, TagChanges(title = value(BulkTagField.TITLE), artist = value(BulkTagField.ARTIST),
                    album = value(BulkTagField.ALBUM), albumArtist = value(BulkTagField.ALBUM_ARTIST),
                    genres = value(BulkTagField.GENRES)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() },
                    trackNumber = value(BulkTagField.TRACK_NUMBER)?.let { it.toIntOrNull() ?: 0 }, discNumber = value(BulkTagField.DISC_NUMBER)?.toIntOrNull(),
                    year = value(BulkTagField.YEAR)?.let { it.toIntOrNull() ?: 0 }), target).join()
                saving = false
                if (controller.state.value.error == null) dismiss()
            }
        }) { Text(stringResource(Res.string.library_save_changes)) }
    }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text(stringResource(Res.string.cancel)) } })
}
