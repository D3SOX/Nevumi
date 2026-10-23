package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.soundcloud.ScGateDestination
import me.d3sox.nevumi.soundcloud.ScGateFormat
import me.d3sox.nevumi.soundcloud.ScGateTagChoice
import me.d3sox.nevumi.soundcloud.ScGateTags
import me.d3sox.nevumi.soundcloud.SoundCloudJob
import me.d3sox.nevumi.soundcloud.SoundCloudTagReview
import me.d3sox.nevumi.soundcloud.taggedFileName
import me.d3sox.nevumi.soundcloud.withoutPromo
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.album
import me.d3sox.nevumi.resources.artist
import me.d3sox.nevumi.resources.sources_change_cover
import me.d3sox.nevumi.resources.sources_check_the_tags
import me.d3sox.nevumi.resources.sources_chosen_cover
import me.d3sox.nevumi.resources.sources_copy_album_into_form
import me.d3sox.nevumi.resources.sources_copy_artist_into_form
import me.d3sox.nevumi.resources.sources_copy_cover_into_form
import me.d3sox.nevumi.resources.sources_copy_genre_into_form
import me.d3sox.nevumi.resources.sources_copy_title_into_form
import me.d3sox.nevumi.resources.sources_cover
import me.d3sox.nevumi.resources.sources_cover_in_file
import me.d3sox.nevumi.resources.sources_file_cover_unavailable
import me.d3sox.nevumi.resources.sources_file_has_tags
import me.d3sox.nevumi.resources.sources_file_has_tags_description
import me.d3sox.nevumi.resources.sources_file_name
import me.d3sox.nevumi.resources.sources_genre
import me.d3sox.nevumi.resources.sources_keep_file_tags
import me.d3sox.nevumi.resources.sources_lossy_flac_hint
import me.d3sox.nevumi.resources.sources_name_file_artist_title
import me.d3sox.nevumi.resources.sources_promo_text_hint
import me.d3sox.nevumi.resources.sources_remove_promo_text
import me.d3sox.nevumi.resources.sources_reset_album_to_original
import me.d3sox.nevumi.resources.sources_reset_artist_to_original
import me.d3sox.nevumi.resources.sources_reset_cover
import me.d3sox.nevumi.resources.sources_reset_genre_to_original
import me.d3sox.nevumi.resources.sources_reset_title_to_original
import me.d3sox.nevumi.resources.sources_selected_image_unavailable
import me.d3sox.nevumi.resources.sources_song_cover
import me.d3sox.nevumi.resources.sources_tags_intro_device
import me.d3sox.nevumi.resources.sources_tags_intro_server
import me.d3sox.nevumi.resources.sources_write_tags
import me.d3sox.nevumi.resources.sources_write_tags_failed
import me.d3sox.nevumi.resources.title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class TagField(val label: StringResource, val copy: StringResource, val reset: StringResource) {
    TITLE(Res.string.title, Res.string.sources_copy_title_into_form, Res.string.sources_reset_title_to_original),
    ARTIST(Res.string.artist, Res.string.sources_copy_artist_into_form, Res.string.sources_reset_artist_to_original),
    ALBUM(Res.string.album, Res.string.sources_copy_album_into_form, Res.string.sources_reset_album_to_original),
    GENRE(Res.string.sources_genre, Res.string.sources_copy_genre_into_form, Res.string.sources_reset_genre_to_original);

    fun of(tags: ScGateTags) = when (this) { TITLE -> tags.title; ARTIST -> tags.artist; ALBUM -> tags.album; GENRE -> tags.genre }
    fun set(tags: ScGateTags, value: String) = when (this) {
        TITLE -> tags.copy(title = value); ARTIST -> tags.copy(artist = value); ALBUM -> tags.copy(album = value); GENRE -> tags.copy(genre = value)
    }
}

@Composable
private fun rememberCover(job: SoundCloudJob, available: Boolean, fromFile: Boolean, controller: MusicController): ImageBitmap? {
    val cover by produceState<ImageBitmap?>(null, job.id, available) {
        if (!available) return@produceState
        value = try {
            val bytes = controller.soundCloudCover(job.id, fromFile)
            withContext(Dispatchers.Default) { decodeArtwork(bytes, 512) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    return cover
}

/** Checks the tags of a downloaded SoundCloud song before sc-gate-dl saves it, with the same choices as sc-gate-dl's web interface. */
@Composable
internal fun SoundCloudTagEditor(job: SoundCloudJob, controller: MusicController, dismiss: () -> Unit) {
    val review = job.review ?: SoundCloudTagReview(ScGateTags(job.track.title, job.track.artist))
    val original = review.tags
    var tags by remember(job.id) { mutableStateOf(original) }
    var artistTitle by remember(job.id) { mutableStateOf(true) }
    var cover by remember(job.id) { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = LocalArtworkPicker.current
    val scope = rememberCoroutineScope()
    val serverCover = rememberCover(job, review.cover, fromFile = false, controller)
    val fileCover = rememberCover(job, review.fileCover, fromFile = true, controller)
    val chosenCover by produceState<ImageBitmap?>(null, cover) { value = cover?.let { withContext(Dispatchers.Default) { decodeArtwork(it, 512) } } }
    val writeFailed = stringResource(Res.string.sources_write_tags_failed)
    val fileCoverUnavailable = stringResource(Res.string.sources_file_cover_unavailable)
    val imageUnavailable = stringResource(Res.string.sources_selected_image_unavailable)
    val written = ScGateTags(tags.title.trim(), tags.artist.trim(), tags.album.trim(), tags.genre.trim())
    fun run(fallback: String, operation: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: fallback }
            finally { busy = false }
        }
    }
    fun finish(keepFileTags: Boolean) = run(writeFailed) {
        controller.finishSoundCloudTags(job.id, ScGateTagChoice(written, keepFileTags, artistTitle, cover.takeUnless { keepFileTags }))
        dismiss()
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SettingsScreen(onDismissRequest = { if (!busy) dismiss() }, title = { Text(stringResource(Res.string.sources_check_the_tags)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (job.destination == ScGateDestination.LIBRARY) Res.string.sources_tags_intro_server
                else Res.string.sources_tags_intro_device, job.track.title))
            if (job.format == ScGateFormat.FLAC && review.lossySource)
                Text(stringResource(Res.string.sources_lossy_flac_hint), color = muted)
            val fileRows = review.fileTags?.let { fileTags -> TagField.entries.map { it to it.of(fileTags).trim() }.filter { (_, value) -> value.isNotEmpty() } }.orEmpty()
            if (fileRows.isNotEmpty() || review.fileCover) Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.sources_file_has_tags), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(Res.string.sources_file_has_tags_description), color = muted)
                    fileRows.forEach { (field, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(field.label), style = MaterialTheme.typography.labelMedium, color = muted)
                                Text(value)
                            }
                            AppIconButton(AppIcon.COPY, stringResource(field.copy), enabled = !busy) { tags = field.set(tags, value) }
                        }
                    }
                    if (review.fileCover) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CoverThumbnail(fileCover, 56, stringResource(Res.string.sources_cover_in_file))
                        Text(stringResource(Res.string.sources_cover), Modifier.weight(1f))
                        AppIconButton(AppIcon.COPY, stringResource(Res.string.sources_copy_cover_into_form), enabled = !busy) {
                            run(fileCoverUnavailable) { cover = controller.soundCloudCover(job.id, fromFile = true) }
                        }
                    }
                    ActionButton(stringResource(Res.string.sources_keep_file_tags), AppIcon.CHECK, enabled = !busy, style = ActionStyle.OUTLINED) { finish(keepFileTags = true) }
                }
            }
            val cleaned = tags.withoutPromo()
            if (cleaned != tags) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.sources_promo_text_hint), color = muted)
                ActionButton(stringResource(Res.string.sources_remove_promo_text), AppIcon.CLEAN_UP, enabled = !busy, style = ActionStyle.OUTLINED) { tags = cleaned }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CoverThumbnail(if (cover != null) chosenCover else serverCover, 96, if (cover != null) stringResource(Res.string.sources_chosen_cover) else stringResource(Res.string.sources_song_cover))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(Res.string.sources_change_cover), AppIcon.ARTWORK_COLORS, enabled = !busy, style = ActionStyle.OUTLINED) {
                        scope.launch {
                            try { picker.pick()?.let { cover = it } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: imageUnavailable }
                        }
                    }
                    if (cover != null) ActionButton(stringResource(Res.string.sources_reset_cover), AppIcon.RESET, enabled = !busy, style = ActionStyle.OUTLINED) { cover = null }
                }
            }
            TagField.entries.forEach { field ->
                val value = field.of(tags)
                val default = field.of(original)
                val resetDescription = stringResource(field.reset)
                OutlinedTextField(value, { tags = field.set(tags, it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(field.label)) }, singleLine = true,
                    enabled = !busy, trailingIcon = if (value == default) null else ({
                        AppIconButton(AppIcon.RESET, resetDescription, enabled = !busy) { tags = field.set(tags, default) }
                    }))
            }
            Row(Modifier.fillMaxWidth().toggleable(artistTitle, enabled = !busy, role = Role.Checkbox) { artistTitle = it },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(artistTitle, null, enabled = !busy)
                Text(stringResource(Res.string.sources_name_file_artist_title))
            }
            taggedFileName(review.fileName, job.format, artistTitle, written).takeIf { it.isNotEmpty() }?.let {
                Text(stringResource(Res.string.sources_file_name, it), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.sources_write_tags), enabled = !busy) { finish(keepFileTags = false) } })
}

@Composable
private fun CoverThumbnail(image: ImageBitmap?, size: Int, description: String) {
    Surface(Modifier.size(size.dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        image?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Box(contentAlignment = Alignment.Center) { Icon(AppIcon.ALBUM.vector, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}
