package me.d3sox.nevumi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.soundcloud.ScGateDestination
import me.d3sox.nevumi.soundcloud.SoundCloudDestination
import me.d3sox.nevumi.soundcloud.SoundCloudSource
import me.d3sox.nevumi.soundcloud.SoundCloudTrack

private fun songKey(artist: String, title: String) = "${artist.trim().lowercase()}\u0000${title.trim().lowercase()}"

/** Searches SoundCloud through sc-gate-dl once [query] stops changing, so typing does not send a request per letter. */
@Composable
internal fun SoundCloudSearchResults(query: String, state: AppState, controller: MusicController) {
    val results = remember(query) { mutableStateListOf<SoundCloudTrack>() }
    var nextOffset by remember(query) { mutableStateOf<Int?>(null) }
    var loading by remember(query) { mutableStateOf(query.isNotBlank()) }
    var error by remember(query) { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<SoundCloudTrack?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load(offset: Int) {
        loading = true; error = null
        try {
            val page = controller.searchSoundCloud(query, offset)
            if (offset == 0) results.clear()
            results += page.results.filter { result -> results.none { it.id == result.id } }
            nextOffset = page.nextOffset
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "SoundCloud search failed" }
        finally { loading = false }
    }
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(500)
        load(0)
    }
    val owned = remember(state.tracks) { state.tracks.map { songKey(it.metadata.artist, it.metadata.title) }.toSet() }
    val jobs = remember(state.soundCloudJobs) { state.soundCloudJobs.groupBy { it.track.url } }
    val profile = state.soundCloud
    fun pick(track: SoundCloudTrack) {
        val server = profile?.libraryDestination == true && state.server != null
        when {
            profile?.destination == SoundCloudDestination.SERVER && server -> controller.downloadFromSoundCloud(track, ScGateDestination.LIBRARY)
            profile?.destination == SoundCloudDestination.DEVICE && controller.soundCloudDeviceDownloads ->
                controller.downloadFromSoundCloud(track, ScGateDestination.CLIENT)
            else -> picked = track
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("soundcloud-results")) {
        if (query.isBlank()) item { Text("Search SoundCloud for songs to download.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(results, key = { "soundcloud:${it.id}" }) { track ->
            val job = jobs[track.url]?.firstOrNull()
            val inLibrary = songKey(track.artist, track.title) in owned
            ListItem(modifier = Modifier.combinedClickable(onClick = { pick(track) }, onLongClick = { picked = track })
                .semantics { contentDescription = "SoundCloud song: ${track.title} by ${track.artist}" },
                headlineContent = { Text(track.title, maxLines = 2) },
                supportingContent = {
                    Column {
                        Text(listOfNotNull(track.artist, track.durationMs.takeIf { it > 0 }?.let(::playbackTime), when (track.source) {
                            SoundCloudSource.DOWNLOAD -> "Free download"
                            SoundCloudSource.GATE -> "Download gate"
                            SoundCloudSource.NONE -> null
                        }).joinToString(" · "), maxLines = 1)
                        if (job != null && !job.finished) Text(job.message, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                },
                leadingContent = { SoundCloudCover(track, controller) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (inLibrary) Text("In library", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AppIconButton(AppIcon.DOWNLOAD, "Download options: ${track.title}") { picked = track }
                    }
                })
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        error?.let { message ->
            item { Text(message, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
        }
        if (!loading && error == null && query.isNotBlank() && results.isEmpty() && nextOffset == null)
            item { Text("No SoundCloud results for “${query.trim()}”", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        nextOffset?.let { offset ->
            if (!loading) item { TextButton(onClick = { scope.launch { load(offset) } }, Modifier.padding(horizontal = 8.dp)) { Text("Show more") } }
        }
    }
    picked?.let { track -> SoundCloudDownloadSheet(track, state, controller) { picked = null } }
}

// Covers of recent results, so scrolling back does not fetch them again.
private val soundCloudCovers = mutableMapOf<String, ImageBitmap>()

/** Loads the cover through sc-gate-dl, so this device never contacts SoundCloud. */
@Composable
private fun SoundCloudCover(track: SoundCloudTrack, controller: MusicController) {
    // A 48 dp row needs no more than SoundCloud's 300 px variant.
    val url = track.artworkUrl?.replace("-t500x500.", "-t300x300.")
    val image by produceState(url?.let(soundCloudCovers::get), url) {
        if (url == null || value != null) return@produceState
        value = try {
            val bytes = controller.soundCloudArtwork(url)
            withContext(Dispatchers.Default) { decodeArtwork(bytes, 160) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        value?.let { cover ->
            soundCloudCovers[url] = cover
            if (soundCloudCovers.size > 120) soundCloudCovers.remove(soundCloudCovers.keys.first())
        }
    }
    Surface(Modifier.size(48.dp), color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        image?.let { Image(it, null, Modifier.fillMaxSize().testTag("soundcloud-cover"), contentScale = ContentScale.Crop) }
            ?: Box(contentAlignment = Alignment.Center) {
                Icon(AppIcon.CLOUD.vector, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
    }
}

/** Offers a SoundCloud search for [query] at the end of the library results. */
@Composable
internal fun SearchSoundCloudRow(query: String, open: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = open).testTag("search-soundcloud"),
        headlineContent = { Text("Search SoundCloud for “${query.trim()}”") },
        supportingContent = { Text("Find songs to download through sc-gate-dl") },
        leadingContent = { Icon(AppIcon.CLOUD.vector, null) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SoundCloudDownloadSheet(track: SoundCloudTrack, state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val browser = LocalUriHandler.current
    val server = state.soundCloud?.libraryDestination == true && state.server != null
    var reviewTags by remember { mutableStateOf(state.soundCloud?.reviewTags == true) }
    fun download(destination: ScGateDestination, keepOffline: Boolean = false) {
        controller.downloadFromSoundCloud(track, destination, keepOffline, reviewTags)
        dismiss()
    }
    AppBottomSheet(onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIconButton(AppIcon.CLOSE, "Close download options", onClick = dismiss)
        }
        ListItem(headlineContent = { Text("Check tags before saving") }, supportingContent = { Text("Edit the tags once the file is downloaded") },
            leadingContent = { Icon(AppIcon.TAGS.vector, null) }, trailingContent = { Switch(reviewTags, null) },
            modifier = Modifier.toggleable(reviewTags, role = Role.Switch) { reviewTags = it })
        if (server) {
            ListItem(headlineContent = { Text("Save to music server") }, supportingContent = { Text("Add it to your library on ${state.server.baseUrl.substringAfter("://")}") },
                leadingContent = { Icon(AppIcon.SERVER.vector, null) }, modifier = Modifier.clickable { download(ScGateDestination.LIBRARY) })
            ListItem(headlineContent = { Text("Save to music server and keep offline") }, supportingContent = { Text("Also download it to this device") },
                leadingContent = { Icon(AppIcon.AVAILABLE_OFFLINE.vector, null) }, modifier = Modifier.clickable { download(ScGateDestination.LIBRARY, keepOffline = true) })
        }
        if (controller.soundCloudDeviceDownloads) ListItem(headlineContent = { Text("Save to this device") },
            supportingContent = { Text("Add the file to your local music") },
            leadingContent = { Icon(AppIcon.DEVICE.vector, null) }, modifier = Modifier.clickable { download(ScGateDestination.CLIENT) })
        ListItem(headlineContent = { Text("Open on SoundCloud") }, leadingContent = { Icon(AppIcon.OPEN_EXTERNAL.vector, null) },
            modifier = Modifier.clickable { dismiss(); browser.openUri(track.url) })
        Spacer(Modifier.height(16.dp))
    }
}
