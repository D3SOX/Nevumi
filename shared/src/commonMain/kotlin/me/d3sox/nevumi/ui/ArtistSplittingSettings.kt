package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.*
import me.d3sox.nevumi.settings.MusicSettings
import org.jetbrains.compose.resources.stringResource

/** Opens [ArtistSplittingDialog], and shows which separators split artists. */
@Composable
internal fun ArtistSplittingSetting(settings: MusicSettings, controller: MusicController) {
    var editing by remember { mutableStateOf(false) }
    val separators = settings.artistSeparators
    SettingsLink(stringResource(Res.string.settings_split_artists), AppIcon.ALBUM_ARTIST,
        if (separators.isEmpty()) stringResource(Res.string.settings_split_artists_off)
        else stringResource(Res.string.settings_split_artists_description, separators.joinToString(" · "))) { editing = true }
    if (editing) ArtistSplittingDialog(settings, controller) { editing = false }
}

@Composable
private fun ArtistSplittingDialog(settings: MusicSettings, controller: MusicController, dismiss: () -> Unit) {
    var separators by remember { mutableStateOf(settings.artistSeparators.joinToString("\n")) }
    var exceptions by remember { mutableStateOf(settings.artistSeparatorExceptions.joinToString("\n")) }
    var featured by remember { mutableStateOf(settings.featuredArtistsFromTitle) }
    fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val featuredLabel = stringResource(Res.string.settings_featured_artists_from_titles)
    KeyboardAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.settings_split_artists)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(separators, { separators = it }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text(stringResource(Res.string.settings_artist_separators)) },
                supportingText = { Text(stringResource(Res.string.settings_artist_separators_hint)) })
            OutlinedTextField(exceptions, { exceptions = it }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text(stringResource(Res.string.settings_artist_exceptions)) },
                supportingText = { Text(stringResource(Res.string.settings_artist_exceptions_hint)) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(featuredLabel, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(Res.string.settings_featured_artists_from_titles_description),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(featured, { featured = it }, Modifier.semantics { contentDescription = featuredLabel })
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            controller.updatePreferences { it.copy(artistSeparators = lines(separators), artistSeparatorExceptions = lines(exceptions),
                featuredArtistsFromTitle = featured) }
            dismiss()
        }) { Text(stringResource(Res.string.save)) }
    }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(Res.string.cancel)) } })
}
