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

private enum class TagField(val label: String) {
    TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), GENRE("Genre");

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
    fun finish(keepFileTags: Boolean) = run("sc-gate-dl could not write the tags") {
        controller.finishSoundCloudTags(job.id, ScGateTagChoice(written, keepFileTags, artistTitle, cover.takeUnless { keepFileTags }))
        dismiss()
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SettingsScreen(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Check the tags") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("sc-gate-dl downloaded “${job.track.title}”. It saves the song " +
                (if (job.destination == ScGateDestination.LIBRARY) "to your music server" else "for this device") + " with these tags.")
            if (job.format == ScGateFormat.FLAC && review.lossySource)
                Text("The downloaded source is lossy. Saving it as FLAC cannot bring back the lost quality.", color = muted)
            val fileRows = review.fileTags?.let { fileTags -> TagField.entries.map { it to it.of(fileTags).trim() }.filter { (_, value) -> value.isNotEmpty() } }.orEmpty()
            if (fileRows.isNotEmpty() || review.fileCover) Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The file already has tags", style = MaterialTheme.typography.titleMedium)
                    Text("Copy a tag or the cover into the form, or keep the file's own tags and finish.", color = muted)
                    fileRows.forEach { (field, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(field.label, style = MaterialTheme.typography.labelMedium, color = muted)
                                Text(value)
                            }
                            AppIconButton(AppIcon.COPY, "Copy ${field.label.lowercase()} into the form", enabled = !busy) { tags = field.set(tags, value) }
                        }
                    }
                    if (review.fileCover) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CoverThumbnail(fileCover, 56, "Cover in the file")
                        Text("Cover", Modifier.weight(1f))
                        AppIconButton(AppIcon.COPY, "Copy the cover into the form", enabled = !busy) {
                            run("The file's cover is unavailable") { cover = controller.soundCloudCover(job.id, fromFile = true) }
                        }
                    }
                    ActionButton("Keep the file's tags and finish", AppIcon.CHECK, enabled = !busy, style = ActionStyle.OUTLINED) { finish(keepFileTags = true) }
                }
            }
            val cleaned = tags.withoutPromo()
            if (cleaned != tags) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The tags contain promo text like [FREE DL] or a repeated artist name.", color = muted)
                ActionButton("Remove promo text", AppIcon.CLEAN_UP, enabled = !busy, style = ActionStyle.OUTLINED) { tags = cleaned }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CoverThumbnail(if (cover != null) chosenCover else serverCover, 96, if (cover != null) "Chosen cover" else "Song cover")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Change cover", AppIcon.ARTWORK_COLORS, enabled = !busy, style = ActionStyle.OUTLINED) {
                        scope.launch {
                            try { picker.pick()?.let { cover = it } }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "The selected image is unavailable" }
                        }
                    }
                    if (cover != null) ActionButton("Reset cover", AppIcon.RESET, enabled = !busy, style = ActionStyle.OUTLINED) { cover = null }
                }
            }
            TagField.entries.forEach { field ->
                val value = field.of(tags)
                val default = field.of(original)
                OutlinedTextField(value, { tags = field.set(tags, it) }, Modifier.fillMaxWidth(), label = { Text(field.label) }, singleLine = true,
                    enabled = !busy, trailingIcon = if (value == default) null else ({
                        AppIconButton(AppIcon.RESET, "Reset ${field.label.lowercase()} to the original", enabled = !busy) { tags = field.set(tags, default) }
                    }))
            }
            Row(Modifier.fillMaxWidth().toggleable(artistTitle, enabled = !busy, role = Role.Checkbox) { artistTitle = it },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(artistTitle, null, enabled = !busy)
                Text("Name the file “Artist - Title”")
            }
            taggedFileName(review.fileName, job.format, artistTitle, written).takeIf { it.isNotEmpty() }?.let {
                Text("File name: $it", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { PageAction("Write tags", enabled = !busy) { finish(keepFileTags = false) } })
}

@Composable
private fun CoverThumbnail(image: ImageBitmap?, size: Int, description: String) {
    Surface(Modifier.size(size.dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        image?.let { Image(it, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Box(contentAlignment = Alignment.Center) { Icon(AppIcon.ALBUM.vector, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
    }
}
