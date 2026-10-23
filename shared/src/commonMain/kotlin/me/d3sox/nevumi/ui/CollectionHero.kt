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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.add_to_queue
import me.d3sox.nevumi.resources.back
import me.d3sox.nevumi.resources.download
import me.d3sox.nevumi.resources.library_collection_artwork
import me.d3sox.nevumi.resources.library_collection_options
import me.d3sox.nevumi.resources.library_play_collection
import me.d3sox.nevumi.resources.library_shuffle_collection
import me.d3sox.nevumi.resources.manage_playlists
import me.d3sox.nevumi.resources.play_after
import me.d3sox.nevumi.resources.play_next
import me.d3sox.nevumi.resources.songs

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
    val artworkDescription = stringResource(Res.string.library_collection_artwork, collection.title)
    val shuffleDescription = stringResource(Res.string.library_shuffle_collection)
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(color.copy(alpha = .4f), MaterialTheme.colorScheme.surface)))
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            CollectionArtwork(artwork, collection.title, tab.icon(), Modifier.size(200.dp).align(Alignment.Center)
                .semantics { contentDescription = artworkDescription })
            AppIconButton(AppIcon.BACK, stringResource(Res.string.back), Modifier.align(Alignment.TopStart), onClick = back)
        }
        Text(collection.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (collection.subtitle.isNotBlank()) Text(collection.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${stringResource(tab.collectionNouns().kind)} · ${countLabel(ids.size, Res.plurals.songs)} · ${playbackTime(collection.tracks.sumOf { it.metadata.durationMs })}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AppIconButton(AppIcon.MORE, stringResource(Res.string.library_collection_options)) { options = true }
                DropdownMenu(options, { options = false }) {
                    QueueActionMenuItems(ids, controller) { options = false }
                    AppMenuItem({ Text(stringResource(Res.string.manage_playlists)) }, { options = false; playlists = true }, leadingIcon = {
                        Icon((if (ids.isNotEmpty() && ids.all { id -> state.playlists.any { id in it.trackIds } }) AppIcon.PLAYLIST_CHECK else AppIcon.PLAYLIST_PLUS).vector, null)
                    })
                    if (collection.tracks.any { song -> song.sources.any { it.kind == SourceKind.SERVER } })
                        AppMenuItem({ Text(stringResource(Res.string.download)) }, { options = false; controller.downloadTracks(ids) }, leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) })
                }
            }
            Spacer(Modifier.weight(1f))
            IconToggleButton(shuffle, { shuffle = it }, Modifier.semantics { contentDescription = shuffleDescription; selected = shuffle },
                colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Icon(AppIcon.SHUFFLE.vector, null) }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                val index = if (shuffle) Random.nextInt(ids.size) else 0
                controller.playTrack(ids[index], ids, index, collection.title, shuffleContext = shuffle, origin = origin)
            }, enabled = ids.isNotEmpty(), modifier = Modifier.size(56.dp)) { Icon(AppIcon.PLAY.vector, stringResource(Res.string.library_play_collection), Modifier.size(32.dp)) }
        }
        ArtistDiscovery(collection.id, tab, state, controller)
    }
    if (playlists) PlaylistPicker(controller, ids) { playlists = false }
}

@Composable
internal fun QueueActionMenuItems(trackIds: List<String>, controller: MusicController, dismiss: () -> Unit) {
    QueueAction.entries.forEach { action ->
        val label = stringResource(when (action) {
            QueueAction.PLAY_NEXT -> Res.string.play_next
            QueueAction.PLAY_AFTER -> Res.string.play_after
            QueueAction.APPEND -> Res.string.add_to_queue
        })
        AppMenuItem({ Text(label) }, { dismiss(); controller.enqueue(trackIds, action) },
            enabled = trackIds.isNotEmpty(), leadingIcon = { Icon(action.icon().vector, null) })
    }
}
