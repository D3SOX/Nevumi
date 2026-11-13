package me.d3sox.nevumi.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.FileEditTarget
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.history.ListeningStatistics
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.TrackAction
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.FileEditOperation
import me.d3sox.nevumi.downloads.DownloadStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.plays

@Composable
internal fun TrackRow(track: Track, controller: MusicController, contextTrackIds: List<String>? = null, contextIndex: Int? = null,
    playlistId: String? = null, selectionMode: Boolean = false, selected: Boolean = false, onSelect: (() -> Unit)? = null,
    statistics: ListeningStatistics? = null, extraInfo: String? = null, contextName: String = "Library", playlistPosition: Int? = null,
    shuffleContext: Boolean = false) {
    val appState = LocalSteadyState.current ?: controller.state
    val rowState = remember(appState, track.id) {
        appState.distinctUntilChanged { old, next ->
            old.preferences == next.preferences && old.artwork[track.id] == next.artwork[track.id] &&
                old.downloads[track.id] == next.downloads[track.id] && old.fileEdits == next.fileEdits &&
                old.playlists == next.playlists && old.server == next.server && old.sftp == next.sftp
        }
    }
    val state by rowState.collectAsState(appState.value)
    val origin = LocalLibraryOrigin.current
    var artworkBounds by remember(track.id) { mutableStateOf<Rect?>(null) }
    val availableOffline = track.sources.any { it.kind == SourceKind.LOCAL && it.available }
    val lastEdit = state.fileEdits.lastOrNull { it.trackId == track.id && it.applied && !it.undone }
    SongActionHost(track, state, controller, contextTrackIds, contextIndex, playlistId, onSelect, contextName = contextName,
        playlistPosition = playlistPosition, shuffleContext = shuffleContext, artworkOrigin = { artworkBounds },
        hiddenAction = if (selectionMode) TrackAction.NONE else state.preferences.quickAction) { available, perform, openMenu, menuOpen ->
    val strings = LocalTrackRowStrings.current ?: trackRowStrings()
    fun label(action: TrackAction) = strings.actionLabel(action, track.favorite, availableOffline)
    SwipeActionRow(track.id, state.preferences.swipeRight, state.preferences.swipeLeft, !selectionMode, available, perform,
        icon = { it.icon(track.favorite, track.rating, track.id in state.playlistTrackIds, availableOffline) }, label = ::label,
        modifier = Modifier.testTag("track-row:${track.id}").semantics { this.selected = selected }) {
            Row(Modifier.combinedClickable(onClick = {
                if (selectionMode) onSelect?.invoke() else controller.playTrack(track.id, contextTrackIds, contextIndex, contextName,
                    shuffleContext = shuffleContext, origin = origin, preserveManualQueue = true)
            }, onLongClick = openMenu).padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (selectionMode) Checkbox(selected, onCheckedChange = { onSelect?.invoke() },
                    modifier = Modifier.semantics { contentDescription = strings.selectTemplate.withArgs(track.metadata.title) })
                Surface(Modifier.size(52.dp).onGloballyPositioned { artworkBounds = Rect(it.positionOnScreen(),
                    androidx.compose.ui.geometry.Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                    .graphicsLayer { alpha = if (menuOpen) 0f else 1f }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    MusicArtwork(state.artwork[track.id], track.metadata.title, strings.artworkTemplate.withArgs(track.metadata.title))
                }
                Column(Modifier.weight(1f)) {
                    Text(track.metadata.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.metadata.artist.ifBlank { strings.unknownArtist }, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (track.favorite && (selectionMode || state.preferences.quickAction != TrackAction.FAVORITE))
                            Icon(AppIcon.FAVORITE_FILLED.vector, strings.favorited, Modifier.size(16.dp).align(Alignment.CenterVertically),
                            tint = MaterialTheme.colorScheme.primary)
                        if (state.downloads[track.id]?.status == DownloadStatus.COMPLETE &&
                            (selectionMode || state.preferences.quickAction != TrackAction.DOWNLOAD || !availableOffline))
                            Icon(AppIcon.AVAILABLE_OFFLINE.vector, strings.downloaded, Modifier.size(16.dp).align(Alignment.CenterVertically),
                            tint = MaterialTheme.colorScheme.primary)
                        if (track.rating > 0) Row(Modifier.align(Alignment.CenterVertically).semantics(mergeDescendants = true) {
                            contentDescription = strings.rating(track.rating)
                        }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(AppIcon.RATING_FILLED.vector, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("${track.rating}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        statistics?.let { Text(strings.statisticsTemplate.withArgs(countLabel(it.playCount, Res.plurals.plays), formatListeningDuration(it.listenedMs)), Modifier.align(Alignment.CenterVertically),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        extraInfo?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically)) }
                    }
                    if (lastEdit?.operation == FileEditOperation.DELETE)
                        Text(if (lastEdit.providerId.startsWith("sftp:")) strings.serverFileDeleted else strings.localFileDeleted,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.downloads[track.id]?.let { record ->
                        when (record.status) {
                            DownloadStatus.COMPLETE -> null
                            DownloadStatus.DOWNLOADING -> strings.downloadingTemplate.withArgs(record.bytesDownloaded, record.sizeBytes)
                            DownloadStatus.PENDING -> strings.downloadQueued
                            DownloadStatus.PAUSED -> strings.downloadPaused
                            DownloadStatus.FAILED -> strings.downloadFailed
                        }
                    }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                val action = state.preferences.quickAction
                val quickAction = !selectionMode && available(action)
                Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (track.metadata.durationMs > 0) Text(playbackTime(track.metadata.durationMs),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = strings.durationTemplate.withArgs(playbackTime(track.metadata.durationMs)) })
                    if (quickAction) {
                        val member = track.id in state.playlistTrackIds
                        val active = action == TrackAction.FAVORITE && track.favorite || action == TrackAction.RATE && track.rating > 0 ||
                            action == TrackAction.ADD_TO_PLAYLIST && member || action == TrackAction.DOWNLOAD && availableOffline
                        IconButton(onClick = { perform(action) }, Modifier.size(48.dp).semantics {
                            contentDescription = strings.quickActionTemplate.withArgs(label(action), track.metadata.title)
                            if (action == TrackAction.FAVORITE || action == TrackAction.ADD_TO_PLAYLIST || action == TrackAction.DOWNLOAD) this.selected = active
                        }) {
                            BadgedBox(badge = { if (action == TrackAction.RATE && track.rating > 0) Badge { Text("${track.rating}") } }) {
                                Icon(action.icon(track.favorite, track.rating, member, availableOffline).vector, null,
                                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (!quickAction && track.metadata.durationMs > 0 && state.preferences.showMoreButtons) Spacer(Modifier.width(16.dp))
                if (state.preferences.showMoreButtons) Box {
                    IconButton(onClick = openMenu, Modifier.semantics { contentDescription = strings.optionsTemplate.withArgs(track.metadata.title) }) {
                        Icon(AppIcon.MORE.vector, null)
                    }
                }
                }
            }
    }
    }
}

/**
 * A song row that runs the user's swipe actions: [content] follows the finger over the action a swipe past 56 dp runs. A swipe
 * toward an action that is not [available] leaves the row in place, and [enabled] false, such as in selection mode, stops swipes.
 * Accessibility services offer every available action.
 */
@Composable
internal fun SwipeActionRow(key: Any, swipeRight: TrackAction, swipeLeft: TrackAction, enabled: Boolean, available: (TrackAction) -> Boolean,
    perform: (TrackAction) -> Boolean, icon: (TrackAction) -> AppIcon, label: (TrackAction) -> String, modifier: Modifier = Modifier,
    content: @Composable () -> Unit) {
    var offset by remember(key) { mutableFloatStateOf(0f) }
    var settling by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val performAction by rememberUpdatedState(perform)
    val actionAvailable by rememberUpdatedState(available)
    fun settle() {
        settling = scope.launch { animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value } }
    }
    // Turning swipes off restarts the gesture handler; a swipe in progress must not leave the row displaced.
    LaunchedEffect(enabled) { if (!enabled && offset != 0f) settle() }
    Box(modifier.fillMaxWidth().clipToBounds()) {
        Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.padding(horizontal = 24.dp), contentAlignment = if (offset >= 0) Alignment.CenterStart else Alignment.CenterEnd) {
                if (offset != 0f) {
                    val action = if (offset > 0) swipeRight else swipeLeft
                    Column(Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(icon(action).vector, null, Modifier.size(24.dp))
                        Text(label(action), style = MaterialTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        }
        Surface(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.fillMaxWidth()
            .horizontalSwipe(key, threshold, swipeRight, swipeLeft, enabled, onStart = { settling?.cancel() }, onDrag = { amount ->
                val next = offset + amount
                // A swipe can return to the middle, but not move on toward an action that is not available.
                if (enabled) offset = if (actionAvailable(if (next >= 0) swipeRight else swipeLeft)) next else 0f
            }) { released ->
                if (released && abs(offset) >= threshold) performAction(if (offset > 0) swipeRight else swipeLeft)
                settle()
            }.semantics {
                customActions = TrackAction.entries.filter { available(it) }.map { action ->
                    CustomAccessibilityAction(label(action)) { perform(action) }
                }
            }, color = MaterialTheme.colorScheme.surface, content = content)
    }
}
