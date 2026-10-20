package me.d3sox.nevumi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.materialkolor.ktx.themeColor
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.queue.QueueAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CollectionHero(collection: TrackCollection, tab: LibraryTab, state: AppState, controller: MusicController, back: () -> Unit) {
    val artwork = collection.tracks.firstNotNullOfOrNull { state.artwork[it.id] }
    val origin = LocalLibraryOrigin.current
    val bitmap = rememberArtwork(artwork, 128)
    val fallback = MaterialTheme.colorScheme.primary
    val color by produceState(fallback, bitmap) {
        value = if (bitmap == null) fallback else withContext(Dispatchers.Default) { bitmap.themeColor(fallback) }
    }
    var shuffle by rememberSaveable(collection.id) { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(false) }
    val ids = remember(collection.tracks) { collection.tracks.map { it.id } }
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color.copy(alpha = .4f), MaterialTheme.colorScheme.surface)))
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            CollectionArtwork(artwork, collection.title, tab.icon(), Modifier.size(200.dp).align(Alignment.Center)
                .semantics { contentDescription = "Collection artwork: ${collection.title}" })
            AppIconButton(AppIcon.BACK, "Back", Modifier.align(Alignment.TopStart), onClick = back)
        }
        Text(collection.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (collection.subtitle.isNotBlank()) Text(collection.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${stringResource(tab.label).removeSuffix("s")} · ${countLabel(ids.size, "song")} · ${playbackTime(collection.tracks.sumOf { it.metadata.durationMs })}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AppIconButton(AppIcon.MORE, "Collection options") { options = true }
                DropdownMenu(options, { options = false }) {
                    QueueActionMenuItems(ids, controller) { options = false }
                    AppMenuItem({ Text("Manage playlists") }, { options = false; playlists = true }, leadingIcon = {
                        Icon((if (ids.isNotEmpty() && ids.all { id -> state.playlists.any { id in it.trackIds } }) AppIcon.PLAYLIST_CHECK else AppIcon.PLAYLIST_PLUS).vector, null)
                    })
                    if (collection.tracks.any { song -> song.sources.any { it.kind == SourceKind.SERVER } })
                        AppMenuItem({ Text("Download") }, { options = false; controller.downloadTracks(ids) }, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) })
                }
            }
            Spacer(Modifier.weight(1f))
            IconToggleButton(shuffle, { shuffle = it }, Modifier.semantics { contentDescription = "Shuffle collection"; selected = shuffle },
                colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Icon(AppIcon.SHUFFLE.vector, null) }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                val index = if (shuffle) Random.nextInt(ids.size) else 0
                controller.playTrack(ids[index], ids, index, collection.title, shuffleContext = shuffle, origin = origin)
            }, enabled = ids.isNotEmpty(), modifier = Modifier.size(56.dp)) { Icon(AppIcon.PLAY.vector, "Play collection", Modifier.size(32.dp)) }
        }
    }
    if (playlists) PlaylistPicker(controller, ids) { playlists = false }
}

@Composable
internal fun QueueActionMenuItems(trackIds: List<String>, controller: MusicController, dismiss: () -> Unit) {
    QueueAction.entries.forEach { action ->
        val label = when (action) {
            QueueAction.PLAY_NEXT -> "Play next"
            QueueAction.PLAY_AFTER -> "Play after"
            QueueAction.APPEND -> "Add to queue"
        }
        AppMenuItem({ Text(label) }, { dismiss(); controller.enqueue(trackIds, action) },
            enabled = trackIds.isNotEmpty(), leadingIcon = { Icon(action.icon().vector, null) })
    }
}
