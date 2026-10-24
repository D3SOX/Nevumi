package me.d3sox.nevumi.ui

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.playback.RepeatMode
import me.d3sox.nevumi.queue.QueueEntry
import me.d3sox.nevumi.queue.QueueSection
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.queue.QueueClear
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.abs
import kotlin.math.roundToInt
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.all
import me.d3sox.nevumi.resources.audio_effects
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.clear
import me.d3sox.nevumi.resources.export_queue
import me.d3sox.nevumi.resources.move_down
import me.d3sox.nevumi.resources.move_up
import me.d3sox.nevumi.resources.next
import me.d3sox.nevumi.resources.next_track
import me.d3sox.nevumi.resources.pause
import me.d3sox.nevumi.resources.play
import me.d3sox.nevumi.resources.player_album_artwork
import me.d3sox.nevumi.resources.player_audio_output
import me.d3sox.nevumi.resources.player_clear_all_text
import me.d3sox.nevumi.resources.player_clear_all_title
import me.d3sox.nevumi.resources.player_clear_duplicates_text
import me.d3sox.nevumi.resources.player_clear_duplicates_title
import me.d3sox.nevumi.resources.player_clear_next_text
import me.d3sox.nevumi.resources.player_clear_next_title
import me.d3sox.nevumi.resources.player_clear_previous_text
import me.d3sox.nevumi.resources.player_clear_previous_title
import me.d3sox.nevumi.resources.player_clear_queue
import me.d3sox.nevumi.resources.player_clear_queue_text
import me.d3sox.nevumi.resources.player_collapse
import me.d3sox.nevumi.resources.player_duplicated_tracks
import me.d3sox.nevumi.resources.player_elapsed_time
import me.d3sox.nevumi.resources.player_enter_positive_number
import me.d3sox.nevumi.resources.player_jump_to_song
import me.d3sox.nevumi.resources.player_manage_saved_queues
import me.d3sox.nevumi.resources.player_manual_queue_time_remaining
import me.d3sox.nevumi.resources.player_mini_player_artwork
import me.d3sox.nevumi.resources.player_mini_player_position
import me.d3sox.nevumi.resources.player_new_empty_queue
import me.d3sox.nevumi.resources.player_now_playing
import me.d3sox.nevumi.resources.player_paused
import me.d3sox.nevumi.resources.player_play_now
import me.d3sox.nevumi.resources.player_play_song_now_title
import me.d3sox.nevumi.resources.player_playback_position
import me.d3sox.nevumi.resources.player_playback_settings
import me.d3sox.nevumi.resources.player_playing
import me.d3sox.nevumi.resources.player_playing_context
import me.d3sox.nevumi.resources.player_playing_from
import me.d3sox.nevumi.resources.player_playing_from_context
import me.d3sox.nevumi.resources.player_previously_played
import me.d3sox.nevumi.resources.player_queue_artwork
import me.d3sox.nevumi.resources.player_queue_mini_player_artwork
import me.d3sox.nevumi.resources.player_queue_play_pause
import me.d3sox.nevumi.resources.player_remaining_time
import me.d3sox.nevumi.resources.player_reorder
import me.d3sox.nevumi.resources.player_repeat_this_song
import me.d3sox.nevumi.resources.player_repeat_this_song_text
import me.d3sox.nevumi.resources.player_scroll_queue
import me.d3sox.nevumi.resources.player_set_repeat_count
import me.d3sox.nevumi.resources.player_show_all
import me.d3sox.nevumi.resources.player_show_less
import me.d3sox.nevumi.resources.player_shuffle
import me.d3sox.nevumi.resources.player_this_song
import me.d3sox.nevumi.resources.player_time_remaining
import me.d3sox.nevumi.resources.player_total_plays
import me.d3sox.nevumi.resources.player_track_options
import me.d3sox.nevumi.resources.player_track_preview
import me.d3sox.nevumi.resources.player_unavailable_track
import me.d3sox.nevumi.resources.queue_export_up_next
import me.d3sox.nevumi.resources.player_use_temporary_queue
import me.d3sox.nevumi.resources.previous
import me.d3sox.nevumi.resources.previous_track
import me.d3sox.nevumi.resources.queue
import me.d3sox.nevumi.resources.saved_queues
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.unknown_artist
import org.jetbrains.compose.resources.stringResource

internal enum class PlayerPanel { MINI, PLAYING, QUEUE }

private data class QueueRow(val key: String, val entry: QueueEntry? = null, val label: String = "", val section: QueueSection? = null)

/** Resolved queue section headings, so the rows can be built outside of composition. */
private data class QueueLabels(val previous: String, val current: String, val upNext: String, val context: String)

private fun queueRows(queue: QueueSnapshot, previousTrackLimit: Int, labels: QueueLabels): List<QueueRow> = buildList {
    fun songs(entries: List<QueueEntry>) { addAll(entries.map { QueueRow(it.id, it) }) }
    if (queue.currentIndex > 0 && previousTrackLimit > 0) {
        add(QueueRow("queue:previous", label = labels.previous))
        songs(queue.entries.take(queue.currentIndex).takeLast(previousTrackLimit))
    }
    queue.entries.getOrNull(queue.currentIndex)?.let {
        add(QueueRow("queue:current", label = labels.current))
        songs(listOf(it))
    }
    add(QueueRow("queue:manual", label = if (queue.upNext.isEmpty()) "" else labels.upNext, section = QueueSection.MANUAL))
    songs(queue.upNext)
    add(QueueRow("queue:context", label = labels.context, section = QueueSection.CONTEXT))
    songs(queue.context)
}

private fun queueEntries(rows: List<QueueRow>, queue: QueueSnapshot): List<QueueEntry> {
    val visibleIds = rows.mapNotNull { it.entry?.id }.toSet()
    val hiddenPrevious = queue.entries.take(queue.currentIndex.coerceAtLeast(0)).filterNot { it.id in visibleIds }
    val current = rows.indexOfFirst { it.entry?.id == queue.currentEntryId }
    var section = QueueSection.MANUAL
    return hiddenPrevious + rows.mapIndexedNotNull { index, row ->
        row.section?.let { section = it }
        row.entry?.let { if (index > current) it.copy(section = section) else it }
    }
}

@Composable
internal fun PlayerPanels(state: AppState, controller: MusicController, sheet: AnchoredDraggableState<PlayerPanel>, navigationHeight: androidx.compose.ui.unit.Dp = 80.dp,
    onPairedDevices: () -> Unit = {}, onAudioEffects: () -> Unit = {}, savedQueues: () -> Unit) {
    val current = state.tracks.firstOrNull { it.id == (state.playback.trackId ?: state.queue.currentTrackId) } ?: return
    val requested = state.playback.playWhenReady && !state.playback.ended && state.playback.error == null
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun dismissSearchInput() { focus.clearFocus(force = true); keyboard?.hide() }
    LaunchedEffect(sheet.targetValue) {
        if (sheet.targetValue != PlayerPanel.MINI) dismissSearchInput()
    }
    val navigateContext = LocalPlayingContextNavigate.current
    val motion = remember(sheet, scope) { PlayerMotion(sheet, scope) }
    var playingArtworkBounds by remember(current.id) { mutableStateOf<Rect?>(null) }
    var showRepeatCount by remember { mutableStateOf(false) }
    var repeatCount by remember { mutableStateOf("2") }
    var clearingQueue by remember { mutableStateOf<QueueClear?>(null) }
    var queueJump by remember { mutableStateOf<String?>(null) }
    var exportingQueue by remember { mutableStateOf<QueueSnapshot?>(null) }
    var clearMenu by remember { mutableStateOf(false) }
    var choosingOutput by remember { mutableStateOf(false) }
    // Opening the preset keyboard can move the player controls between portrait and landscape layouts.
    var showingMilkDrop by remember { mutableStateOf(false) }
    var showingPlaybackSettings by remember { mutableStateOf(false) }
    val queueScroll = rememberLazyListState()
    var queueTop by remember { mutableFloatStateOf(0f) }
    var draggingEntry by remember { mutableStateOf<String?>(null) }
    var committingDrag by remember { mutableStateOf(false) }
    var expandedHistory by remember { mutableStateOf(false) }
    val previousTrackLimit = if (!state.preferences.showPreviousQueueTracks) 0
        else if (expandedHistory) Int.MAX_VALUE else state.preferences.previousQueueTrackLimit
    val queueLabels = QueueLabels(stringResource(Res.string.player_previously_played), stringResource(Res.string.player_now_playing),
        stringResource(Res.string.queue_export_up_next), stringResource(Res.string.player_playing_from_context, state.queue.contextName))
    var rows by remember { mutableStateOf(queueRows(state.queue, previousTrackLimit, queueLabels)) }
    // A replaced queue gets a fresh list, because cross-fading every row can leave stale rows drawn over the new queue.
    // It changes together with the rows, so the fresh list never animates the old rows away.
    var rowsReplacement by remember { mutableIntStateOf(controller.queue.replacements) }
    val displayedEntries = remember(rows, state.queue) { queueEntries(rows, state.queue) }
    val catalog = rememberDerived(null, emptyList(), listOf(state.tracks)) { state.tracks.associateBy { it.id } }
    LaunchedEffect(state.queue.entries, state.queue.currentEntryId, state.queue.contextName, previousTrackLimit, draggingEntry, committingDrag, queueLabels) {
        if (draggingEntry == null && !committingDrag) {
            rows = queueRows(state.queue, previousTrackLimit, queueLabels)
            // The queue counts a replacement before the controller publishes it.
            rowsReplacement = controller.queue.replacements
        }
    }
    val reorderable = rememberReorderableLazyListState(queueScroll) { from, to ->
        val source = rows.indexOfFirst { it.key == from.key }
        val manualHeader = rows.indexOfFirst { it.key == "queue:manual" }
        val target = rows.indexOfFirst { it.key == to.key }
        val destination = target.coerceAtLeast(manualHeader + 1)
        if (source > manualHeader && target >= manualHeader && destination in (manualHeader + 1)..rows.lastIndex && rows[source].entry != null) {
            rows = rows.toMutableList().apply { add(destination, removeAt(source)) }
        }
    }
    fun finishDrag(entryId: String, atBoundary: Boolean) {
        if (atBoundary) {
            val source = rows.indexOfFirst { it.key == entryId }
            val firstManual = rows.indexOfFirst { it.key == "queue:manual" } + 1
            if (source > firstManual) rows = rows.toMutableList().apply { add(firstManual, removeAt(source)) }
        }
        val finalEntries = queueEntries(rows, controller.state.value.queue)
        val destination = finalEntries.indexOfFirst { it.id == entryId }
        committingDrag = true
        draggingEntry = null
        scope.launch {
            try {
                if (destination >= 0) {
                    controller.moveQueueEntry(entryId, destination, finalEntries[destination].section).join()
                }
            }
            finally { committingDrag = false }
        }
    }
    val panelThreshold = with(LocalDensity.current) { 120.dp.toPx() }
    var panelAnimation by remember { mutableStateOf<Job?>(null) }
    fun show(panel: PlayerPanel) {
        if (panel != PlayerPanel.MINI) dismissSearchInput()
        panelAnimation?.cancel()
        panelAnimation = scope.launch { sheet.animateTo(panel, spring()) }
    }
    var backPanel by remember { mutableStateOf<PlayerPanel?>(null) }
    var backStart by remember { mutableFloatStateOf(0f) }
    val trackSwipeThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    val touchSlop = androidx.compose.ui.platform.LocalViewConfiguration.current.touchSlop
    val artworkSwipe = remember(controller) { PlayingArtworkSwipe() }
    var dragAxis by remember { mutableStateOf<Orientation?>(null) }
    var dragOrigin by remember { mutableStateOf(PlayerPanel.MINI) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    var artworkDragAllowed by remember { mutableStateOf(false) }
    val panelDrag = rememberDraggable2DState { delta ->
        if (dragAxis == null) {
            dragAxis = if (abs(delta.x) > abs(delta.y)) Orientation.Horizontal else Orientation.Vertical
            if (dragAxis == Orientation.Vertical && delta.y < 0 && sheet.currentValue == PlayerPanel.MINI) dismissSearchInput()
            if (dragAxis == Orientation.Horizontal && artworkDragAllowed) {
                val latest = controller.state.value
                artworkSwipe.begin(latest, latest.tracks.firstOrNull { it.id == latest.playback.trackId } ?: current)
            }
        }
        if (dragAxis == Orientation.Vertical) { dragDistance += delta.y; sheet.dispatchRawDelta(delta.y) }
        else if (artworkDragAllowed) artworkSwipe.drag(delta.x)
    }
    SongActionHost(current, state, controller, artworkOrigin = { playingArtworkBounds }, artworkCornerRadius = 28.dp,
        rightClickMenu = false) { _, _, openPlayingMenu, playingMenuOpen ->
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { motion.origin = it.positionInRoot() }) {
        val height = maxHeight
        val heightPx = with(LocalDensity.current) { height.toPx() }
        val miniHeight = 96.dp
        val miniBottomInset = with(LocalDensity.current) { navigationHeight.toPx() }
        val miniPx = with(LocalDensity.current) { miniHeight.toPx() }
        val miniAnchor = (heightPx - miniPx - miniBottomInset).coerceAtLeast(1f)
        motion.miniAnchor = miniAnchor
        motion.height = heightPx
        val previousMiniAnchor = sheet.anchors.positionOf(PlayerPanel.MINI)
        val rawOffset = sheet.offset
        // Insets change before AnchoredDraggable updates its anchors. Keep a resting mini
        // player collapsed during that frame, while preserving actual drag progress.
        val offset = if (sheet.targetValue == PlayerPanel.MINI && previousMiniAnchor.isFinite() &&
            rawOffset.isFinite() && abs(rawOffset - previousMiniAnchor) < .5f) miniAnchor
            else rawOffset.takeIf { it.isFinite() } ?: miniAnchor
        motion.layoutOffset = offset
        val expansion = (1 - offset / miniAnchor).coerceIn(0f, 1f)
        val queueProgress = (-offset / heightPx).coerceIn(0f, 1f)
        PlatformPredictiveBackHandler(enabled = LocalNavigationActive.current && (backPanel != null || sheet.targetValue != PlayerPanel.MINI),
            onStarted = { backPanel = sheet.targetValue; backStart = sheet.requireOffset() },
            onProgress = { progress ->
                val destination = if (backPanel == PlayerPanel.QUEUE) 0f else heightPx - miniPx - miniBottomInset
                sheet.dispatchRawDelta(backStart + (destination - backStart) * progress - sheet.requireOffset())
            }, onCancelled = {
                val origin = backPanel ?: sheet.targetValue
                scope.launch { sheet.animateTo(origin, spring()); backPanel = null }
            }, onBack = {
                val origin = backPanel ?: sheet.targetValue
                backPanel = null
                show(if (origin == PlayerPanel.QUEUE) PlayerPanel.PLAYING else PlayerPanel.MINI)
            })
        val queueVisible by remember(sheet) { derivedStateOf { sheet.offset < 0f || sheet.targetValue == PlayerPanel.QUEUE } }
        // Removing earlier tracks shifts the current row; that must not pull a scrolled history back to it.
        LaunchedEffect(queueVisible, state.queue.currentEntryId, previousTrackLimit) {
            if (queueVisible && state.queue.entries.isNotEmpty()) {
                withFrameNanos { }
                queueScroll.scrollToItem(rows.indexOfFirst { it.key == "queue:current" }.coerceAtLeast(0))
            }
        }
        SideEffect {
            val anchors = DraggableAnchors {
                PlayerPanel.MINI at heightPx - miniPx - miniBottomInset
                PlayerPanel.PLAYING at 0f
                PlayerPanel.QUEUE at -heightPx
            }
            if (anchors != sheet.anchors) {
                // Resizing for the IME must not snap a held custom drag to a resting anchor.
                val heldOffset = sheet.offset.takeIf { dragAxis == Orientation.Vertical && it.isFinite() }
                sheet.updateAnchors(anchors, newTarget = sheet.targetValue)
                if (heldOffset != null) sheet.dispatchRawDelta(heldOffset - sheet.requireOffset())
            }
        }
        var queuePointerHeld by remember { mutableStateOf(false) }
        val queuePull = remember(sheet, heightPx, panelThreshold) {
            object : NestedScrollConnection {
                var pulling = false
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (!pulling || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                    return Offset(0f, sheet.dispatchRawDelta(available.y))
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (!queuePointerHeld || source != NestedScrollSource.UserInput || available.y <= 0f || sheet.offset >= 0f) return Offset.Zero
                    pulling = true
                    return Offset(0f, sheet.dispatchRawDelta(available.y.coerceAtMost(-sheet.requireOffset())))
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!pulling) return Velocity.Zero
                    pulling = false
                    val destination = if (heightPx + sheet.requireOffset() >= panelThreshold || available.y > 800f)
                        PlayerPanel.PLAYING else PlayerPanel.QUEUE
                    sheet.animateTo(destination, spring())
                    return Velocity(0f, available.y)
                }
            }
        }
        val fling = AnchoredDraggableDefaults.flingBehavior(sheet, positionalThreshold = { minOf(it * .3f, panelThreshold) })
        val drag = Modifier.draggable2D(panelDrag, startDragImmediately = sheet.isAnimationRunning,
            onDragStarted = {
                panelAnimation?.cancel()
                dragAxis = null
                dragOrigin = sheet.targetValue
                dragDistance = 0f
                artworkDragAllowed = sheet.targetValue == PlayerPanel.PLAYING && abs(sheet.requireOffset()) < 1f
            }, onDragStopped = { velocity ->
                if (dragAxis == Orientation.Vertical) panelAnimation = scope.launch {
                    // A retraced gesture returns to its origin even if IME dismissal moved that anchor.
                    if (abs(dragDistance) <= touchSlop + 1f && abs(velocity.y) < 800f) {
                        sheet.animateTo(dragOrigin, spring())
                        return@launch
                    }
                    sheet.anchoredDrag { anchors ->
                        with(fling) {
                            object : ScrollScope {
                                override fun scrollBy(pixels: Float): Float {
                                    val before = sheet.requireOffset()
                                    dragTo((before + pixels).coerceIn(anchors.minPosition(), anchors.maxPosition()))
                                    return sheet.requireOffset() - before
                                }
                            }.performFling(velocity.y)
                        }
                    }
                }
                else if (artworkDragAllowed) scope.launch { artworkSwipe.settle(controller, trackSwipeThreshold, true) }
                dragAxis = null
            })
        val collapsed = sheet.targetValue == PlayerPanel.MINI && expansion <= .001f
        Box(Modifier.fillMaxWidth().offset {
            IntOffset(0, offset.roundToInt())
        }.wrapContentHeight(unbounded = true, align = Alignment.Top).height(if (collapsed) miniHeight else height * 2).clipToBounds().then(drag)) {
        Column(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true, align = Alignment.Top)) {
            Surface(Modifier.height(height).fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Box {
                    // The cover moves above the controls, but its glow belongs behind all player content.
                    Box(Modifier.fillMaxSize().offset { IntOffset(0, -offset.roundToInt()) }) {
                        PlayerMotionGlow(motion, state.artwork[current.id], expansion, queueProgress)
                        // The full visualizer reads the same audio, so the background stops while it's open.
                        if (state.preferences.milkDrop.background && milkDropBackgroundSupported && !showingMilkDrop &&
                            expansion > 0f && queueProgress < 1f)
                            MilkDropBackground(state, controller, Modifier.fillMaxSize().graphicsLayer { alpha = expansion * (1 - queueProgress) })
                    }
                    // A restored queue starts collapsed; measuring the hidden controls delays the first window.
                    if (!collapsed) Column(Modifier.fillMaxSize().testTag("now-playing").graphicsLayer {
                        translationY = (-offset).coerceAtLeast(0f)
                        val visible = 1 - queueProgress
                        alpha = expansion * visible * visible * visible * visible
                    }
                        .then(if (collapsed || queueProgress >= .999f) Modifier.clearAndSetSemantics {} else Modifier)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIconButton(AppIcon.DOWN, stringResource(Res.string.player_collapse)) { show(PlayerPanel.MINI) }
                            val playingContext = stringResource(Res.string.player_playing_context)
                            val liveQueue = state.savedQueues.firstOrNull { it.id == state.queue.selectedSavedQueueId }
                            val manualTrack = state.queue.entries.getOrNull(state.queue.currentIndex)?.section == QueueSection.MANUAL
                            Column(Modifier.weight(1f).clickable {
                                if (liveQueue != null || manualTrack) show(PlayerPanel.QUEUE)
                                else navigateContext(state.queue.origin ?: me.d3sox.nevumi.queue.QueueOrigin(), current.id)
                            }.semantics { contentDescription = playingContext }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(Res.string.player_playing_from), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                PlayerMarquee(liveQueue?.name ?: if (manualTrack) stringResource(Res.string.queue_export_up_next) else state.queue.contextName,
                                    MaterialTheme.typography.titleSmall, centerProgress = 1f)
                            }
                            AppIconButton(AppIcon.MORE, stringResource(Res.string.player_track_options, current.metadata.title), onClick = openPlayingMenu)
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                            val controlsVisible = expansion >= .999f && queueProgress == 0f
                            val controlsMaxHeight = maxHeight * .65f
                            if (maxWidth > maxHeight) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                                PlayerCover(state, controller, current, Modifier.weight(1f).fillMaxHeight(), motion)
                                PlayerControls(state, controller, current, requested, Modifier.weight(1f), motion, controlsVisible, artworkSwipe, onOutput = { choosingOutput = true }, onVisualizer = { showingMilkDrop = true }, onAudioEffects = onAudioEffects, onPlaybackSettings = { showingPlaybackSettings = true }, onRepeatCount = { repeatCount = (state.queue.repeatPlays ?: 2).toString(); showRepeatCount = true }, onQueue = { show(PlayerPanel.QUEUE) })
                            } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                PlayerCover(state, controller, current, Modifier.weight(1f).fillMaxWidth(), motion)
                                PlayerControls(state, controller, current, requested,
                                    Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight), motion, controlsVisible, artworkSwipe, onOutput = { choosingOutput = true }, onVisualizer = { showingMilkDrop = true }, onAudioEffects = onAudioEffects, onPlaybackSettings = { showingPlaybackSettings = true }, onRepeatCount = { repeatCount = (state.queue.repeatPlays ?: 2).toString(); showRepeatCount = true }, onQueue = { show(PlayerPanel.QUEUE) })
                            }
                        }

                    }
                    if (expansion < .999f) Surface(
                        Modifier.height(miniHeight).fillMaxWidth().padding(8.dp).testTag("mini-player")
                            .graphicsLayer { alpha = (offset / miniAnchor).coerceIn(0f, 1f) },
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Box {
                            MiniPlayerCarousel(state, controller, current, motion, collapsed) { show(PlayerPanel.PLAYING) }
                            MiniPlayerSeekBar(controller, Modifier.align(Alignment.BottomCenter).offset(y = 6.dp))
                        }
                    }
                }
            }
            if (!collapsed) Surface(Modifier.height(height).fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().then(if (sheet.currentValue != PlayerPanel.QUEUE && sheet.targetValue != PlayerPanel.QUEUE) Modifier.clearAndSetSemantics {} else Modifier)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("queue-mini-player")
                        .clickable(onClickLabel = stringResource(Res.string.player_now_playing)) { show(PlayerPanel.PLAYING) }.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(Modifier.size(56.dp).then(motion.anchor(PlayerPanel.QUEUE, PlayerElement.ARTWORK, queueProgress >= .999f))
                            .then(if (queueProgress >= .999f) Modifier.testTag("player-artwork") else Modifier),
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            MusicArtwork(state.artwork[current.id], current.metadata.title, stringResource(Res.string.player_queue_mini_player_artwork, current.metadata.title))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PlayerMarquee(current.metadata.title, MaterialTheme.typography.titleSmall,
                                motion.anchor(PlayerPanel.QUEUE, PlayerElement.TITLE, queueProgress >= .999f),
                                marqueeState = motion.marquee(PlayerElement.TITLE, current.metadata.title), active = queueProgress >= .999f)
                            PlayerMarquee(current.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }, MaterialTheme.typography.bodySmall,
                                motion.anchor(PlayerPanel.QUEUE, PlayerElement.ARTIST, queueProgress >= .999f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                marqueeState = motion.marquee(PlayerElement.ARTIST, current.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }), active = queueProgress >= .999f)
                        }
                        AppVolume(controller, Modifier.width(240.dp))
                        val playState = stringResource(if (requested) Res.string.player_playing else Res.string.player_paused)
                        FilledIconButton(onClick = { controller.togglePlayback() },
                            modifier = Modifier.size(48.dp).then(motion.anchor(PlayerPanel.QUEUE, PlayerElement.PLAY, queueProgress >= .999f))
                                .semantics { stateDescription = playState },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon((if (requested) AppIcon.PAUSE else AppIcon.PLAY).vector, stringResource(Res.string.player_queue_play_pause))
                        }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("queue-header").padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(state.savedQueues.firstOrNull { it.id == state.queue.selectedSavedQueueId }?.name ?: stringResource(Res.string.queue),
                                style = MaterialTheme.typography.titleLarge)
                            Text(countLabel(state.queue.entries.size, Res.plurals.songs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            var selectingQueue by remember { mutableStateOf(false) }
                            AppIconButton(AppIcon.QUEUE, stringResource(Res.string.saved_queues), onClick = { selectingQueue = true })
                            DropdownMenu(selectingQueue, { selectingQueue = false }) {
                                state.savedQueues.filter { it.kind == me.d3sox.nevumi.queue.SavedQueueKind.LIVE }.forEach { saved ->
                                    AppMenuItem(text = { Text(saved.name) },
                                        leadingIcon = { Icon(if (saved.id == state.queue.selectedSavedQueueId) AppIcon.CHECK.vector else AppIcon.QUEUE.vector, null) },
                                        onClick = { selectingQueue = false; controller.loadSavedQueue(saved.id) })
                                }
                                if (state.queue.selectedSavedQueueId != null)
                                    AppMenuItem(text = { Text(stringResource(Res.string.player_use_temporary_queue)) }, onClick = { selectingQueue = false; controller.detachSavedQueue() })
                                AppMenuItem(text = { Text(stringResource(Res.string.player_new_empty_queue)) }, onClick ={ selectingQueue = false; controller.startEmptyQueue() })
                                AppMenuItem(text = { Text(stringResource(Res.string.player_manage_saved_queues)) }, onClick = { selectingQueue = false; savedQueues() })
                            }
                        }
                        AppIconButton(AppIcon.SAVE, stringResource(Res.string.export_queue), enabled = state.queue.entries.isNotEmpty()) {
                            exportingQueue = state.queue.copy(positionMs = controller.state.value.playback.positionMs)
                        }
                        Box {
                            AppIconButton(AppIcon.CLEAR_QUEUE, stringResource(Res.string.player_clear_queue), enabled = state.queue.entries.size > 1) { clearMenu = true }
                            DropdownMenu(clearMenu, { clearMenu = false }) {
                                QueueClear.entries.forEach { selection ->
                                    AppMenuItem(text = { Text(selection.label()) }, leadingIcon = { Icon(selection.icon().vector, null) },
                                        onClick = { clearMenu = false; clearingQueue = selection })
                                }
                            }
                        }
                        AppIconButton(AppIcon.DOWN, stringResource(Res.string.player_now_playing)) { show(PlayerPanel.PLAYING) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    key(rowsReplacement) {
                    LazyColumn(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { queueTop = it.positionInRoot().y }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    queuePointerHeld = event.type != PointerEventType.Scroll && event.changes.any { it.pressed }
                                }
                            }
                        }.nestedScroll(queuePull), state = queueScroll) {
                        itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                            val entry = row.entry
                            if (entry == null) {
                                ReorderableItem(reorderable, row.key, enabled = row.section != null) { _ ->
                                // A nonzero layout target keeps reordering stable while the heading is hidden.
                                if (row.label.isEmpty()) Spacer(Modifier.fillMaxWidth().height(1.dp).testTag(row.key))
                                else Row(Modifier.fillMaxWidth().testTag(row.key).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(row.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    if (row.key == "queue:previous" && (expandedHistory || state.queue.currentIndex > state.preferences.previousQueueTrackLimit)) {
                                        TextButton(onClick = { expandedHistory = !expandedHistory }) {
                                            Text(if (expandedHistory) stringResource(Res.string.player_show_less) else stringResource(Res.string.player_show_all, state.queue.currentIndex))
                                        }
                                    }
                                    if (row.section == QueueSection.MANUAL) ManualQueueTime(controller)

                                }
                                }
                                return@itemsIndexed
                            }
                            val index = displayedEntries.indexOfFirst { it.id == entry.id }
                            val track = catalog[entry.trackId]
                            val upcoming = index > state.queue.currentIndex
                            var rowTop by remember(entry.id) { mutableFloatStateOf(0f) }
                            var artworkBounds by remember(entry.id) { mutableStateOf<Rect?>(null) }
                            val boundary = remember(entry.id, queueScroll) { DragBoundary({ rowTop }, {
                                val layout = queueScroll.layoutInfo
                                val header = layout.visibleItemsInfo.firstOrNull { it.key == "queue:manual" }
                                val manualIndex = rows.indexOfFirst { it.key == "queue:manual" }
                                val bottom = header?.let { it.offset + it.size }
                                    ?: if (queueScroll.firstVisibleItemIndex > manualIndex) 0 else layout.viewportEndOffset
                                queueTop + bottom.coerceAtLeast(0)
                            }) }
                            val nowPlaying = stringResource(Res.string.player_now_playing)
                            val moveUp = stringResource(Res.string.move_up)
                            val moveDown = stringResource(Res.string.move_down)
                            val reorderDescription = stringResource(Res.string.player_reorder, track?.metadata?.title.toString())
                            ReorderableItem(reorderable, entry.id, enabled = upcoming) { isDragging ->
                            QueueSwipe(entry.id, { controller.removeQueueEntry(entry.id) }) {
                            val item: @Composable ((() -> Unit)?, Boolean) -> Unit = { openMenu, menuOpen ->
                            val highlighted = !isDragging && entry.id == state.queue.currentEntryId
                            val foreground = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            val supporting = if (highlighted) foreground else MaterialTheme.colorScheme.onSurfaceVariant
                            ListItem(modifier = Modifier.onGloballyPositioned { rowTop = it.positionInRoot().y }
                                .testTag("queue-entry:${entry.id}")
                                .semantics {
                                    selected = entry.id == state.queue.currentEntryId
                                    if (entry.id == state.queue.currentEntryId) stateDescription = nowPlaying
                                    customActions = if (!upcoming) emptyList() else listOf(CustomAccessibilityAction(moveUp) {
                                        val allowed = index > state.queue.currentIndex + 1
                                        if (allowed) controller.moveQueueEntry(entry.id, index - 1); allowed
                                    }, CustomAccessibilityAction(moveDown) {
                                        val allowed = upcoming && index < state.queue.entries.lastIndex
                                        if (allowed) controller.moveQueueEntry(entry.id, index + 1)
                                        allowed
                                    })
                                }.combinedClickable(onLongClick = openMenu, onClick = {
                                    if (entry.id != state.queue.currentEntryId) {
                                        if (state.preferences.confirmQueueJumps && index != state.queue.currentIndex + 1) queueJump = entry.id
                                        else controller.selectQueueEntry(entry.id)
                                    }
                                }),
                                headlineContent = { Text(track?.metadata?.title ?: stringResource(Res.string.player_unavailable_track), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(track?.metadata?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (upcoming) Box(Modifier.size(48.dp).semantics { contentDescription = reorderDescription }
                                            .pointerInput(Unit) {
                                                // Holding the grip belongs to reordering; the row owns its context menu.
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                    if (!currentEvent.buttons.isSecondaryPressed) down.consume()
                                                    waitForUpOrCancellation()
                                                }
                                            }
                                            .draggableHandle(enabled = !committingDrag,
                                                dragGestureDetector = boundary,
                                                onDragStarted = { draggingEntry = entry.id },
                                                onDragStopped = { finishDrag(entry.id, boundary.atBoundary) }),
                                            contentAlignment = Alignment.Center) { Icon(AppIcon.DRAG.vector, null) }
                                        Surface(Modifier.size(48.dp).onGloballyPositioned { artworkBounds = Rect(it.positionOnScreen(), it.size.toSize()) }
                                            .graphicsLayer { alpha = if (menuOpen) 0f else 1f },
                                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                            MusicArtwork(state.artwork[entry.trackId], track?.metadata?.title.orEmpty(), stringResource(Res.string.player_queue_artwork, entry.id))
                                        }
                                    }
                                }, trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (track != null && track.metadata.durationMs > 0) Text(playbackTime(track.metadata.durationMs),
                                        style = MaterialTheme.typography.labelMedium, color = supporting)
                                    if (openMenu != null && state.preferences.showMoreButtons) AppIconButton(AppIcon.MORE, stringResource(Res.string.player_track_options, track?.metadata?.title.toString()), onClick = openMenu)
                                }
                            },
                                colors = ListItemDefaults.colors(headlineColor = foreground, supportingColor = supporting,
                                    leadingIconColor = supporting, trailingIconColor = supporting, containerColor = when {
                                    isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    entry.id == state.queue.currentEntryId -> MaterialTheme.colorScheme.primaryContainer
                                    else -> MaterialTheme.colorScheme.surface
                                }))
                            }
                            if (track != null) SongActionHost(track, state, controller, queueEntryId = entry.id,
                                artworkOrigin = { artworkBounds }, artworkCornerRadius = 12.dp) { _, _, openMenu, menuOpen -> item(openMenu, menuOpen) }
                            else item(null, false)
                            }
                            }
                        }
                    }
                    }
                    val scrollRows = rows
                    LibraryScrollbar(queueScroll, scrollRows.size, stringResource(Res.string.player_scroll_queue)) { index ->
                        scrollRows.getOrNull(index)?.let { row -> row.entry?.let { catalog[it.trackId]?.metadata?.title } ?: row.label }.orEmpty()
                    }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().offset { IntOffset(0, -offset.roundToInt()) }) {
            if (!collapsed && queueProgress < .999f) {
                PlayerMotionElements(motion, state, controller, current, expansion, queueProgress, Modifier, artworkSwipe,
                    artworkModifier = Modifier.onGloballyPositioned { playingArtworkBounds = Rect(it.positionOnScreen(), it.size.toSize()) }
                        .graphicsLayer { alpha = if (playingMenuOpen) 0f else 1f })
            }
        }
        }
    }
    }
    queueJump?.let { entryId ->
        val target = state.queue.entries.firstOrNull { it.id == entryId }
        if (target == null) LaunchedEffect(entryId) { queueJump = null }
        else AlertDialog(onDismissRequest = { queueJump = null }, icon = { Icon(AppIcon.QUEUE.vector, null) },
            title = { Text(stringResource(Res.string.player_play_song_now_title)) },
            text = { Text(stringResource(Res.string.player_jump_to_song, catalog[target.trackId]?.metadata?.title ?: stringResource(Res.string.player_this_song))) },
            confirmButton = { Button(onClick = { controller.selectQueueEntry(entryId); queueJump = null }) {
                Icon(AppIcon.PLAY.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.player_play_now))
            } }, dismissButton = { TextButton(onClick = { queueJump = null }) { Text(stringResource(Res.string.cancel)) } })
    }
    if (choosingOutput) AudioOutputPicker(controller, { choosingOutput = false }, onPairedDevices)
    if (showingMilkDrop) MilkDropVisualizer(state, controller) { showingMilkDrop = false }
    if (showingPlaybackSettings) PlaybackQuickSettings(state, controller,
        audioEffects = { showingPlaybackSettings = false; onAudioEffects() }, close = { showingPlaybackSettings = false })
    clearingQueue?.let { selection -> AlertDialog(onDismissRequest = { clearingQueue = null },
        icon = { Icon(selection.icon().vector, null) }, title = { Text(stringResource(selection.clearTitle())) },
        text = { Text(stringResource(Res.string.player_clear_queue_text, stringResource(when (selection) {
            QueueClear.DUPLICATES -> Res.string.player_clear_duplicates_text
            QueueClear.PREVIOUS -> Res.string.player_clear_previous_text
            QueueClear.NEXT -> Res.string.player_clear_next_text
            QueueClear.ALL -> Res.string.player_clear_all_text
        }))) },
        confirmButton = { Button(onClick = { controller.clearQueue(selection); clearingQueue = null }) {
            Icon(AppIcon.CLEAR_QUEUE.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.clear))
        } }, dismissButton = { TextButton(onClick = { clearingQueue = null }) { Text(stringResource(Res.string.cancel)) } }) }
    exportingQueue?.let { QueueExportDialog(it, it.contextName, controller) { exportingQueue = null } }
    if (showRepeatCount) {
        val plays = repeatCount.toIntOrNull()?.takeIf { it > 0 }
        KeyboardAlertDialog(onDismissRequest = { showRepeatCount = false }, title = { Text(stringResource(Res.string.player_repeat_this_song)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.player_repeat_this_song_text))
                OutlinedTextField(repeatCount, { repeatCount = it }, label = { Text(stringResource(Res.string.player_total_plays)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = plays == null,
                    supportingText = { if (plays == null) Text(stringResource(Res.string.player_enter_positive_number)) }, modifier = Modifier.fillMaxWidth())
            }
        }, confirmButton = { TextButton(enabled = plays != null, onClick = {
            controller.setQueueRepeat(RepeatMode.ONE, requireNotNull(plays)); showRepeatCount = false
        }) { Text(stringResource(Res.string.player_set_repeat_count)) } }, dismissButton = { TextButton(onClick = { showRepeatCount = false }) { Text(stringResource(Res.string.cancel)) } })
    }
}

@Composable
internal fun MiniTrackInformation(state: AppState, track: Track, modifier: Modifier, preview: String? = null, motion: PlayerMotion? = null, visible: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(48.dp).then(motion?.anchor(PlayerPanel.MINI, PlayerElement.ARTWORK, visible) ?: Modifier)
            .then(if (preview == null && visible) Modifier.testTag("player-artwork") else Modifier), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            MusicArtwork(state.artwork[track.id], track.metadata.title,
                if (preview == null) stringResource(Res.string.player_mini_player_artwork, track.metadata.title) else stringResource(Res.string.player_track_preview, preview, track.metadata.title))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            PlayerMarquee(track.metadata.title, MaterialTheme.typography.titleSmall,
                if (preview == null) motion?.anchor(PlayerPanel.MINI, PlayerElement.TITLE, visible) ?: Modifier else Modifier.testTag("mini-preview-title"),
                marqueeState = if (preview == null) motion?.marquee(PlayerElement.TITLE, track.metadata.title) else null, active = visible)
            PlayerMarquee(track.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }, MaterialTheme.typography.bodySmall,
                motion?.anchor(PlayerPanel.MINI, PlayerElement.ARTIST, visible) ?: Modifier, color = MaterialTheme.colorScheme.onSurfaceVariant,
                marqueeState = if (preview == null) motion?.marquee(PlayerElement.ARTIST, track.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }) else null, active = visible)
        }
    }
}

@Composable
private fun PlayerCover(state: AppState, controller: MusicController, track: Track, modifier: Modifier, motion: PlayerMotion) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (PlayerLyrics.shown(controller)) LyricsCover(state, controller, motion.anchor(PlayerPanel.PLAYING, PlayerElement.ARTWORK)) else
        Surface(Modifier.size(minOf(maxWidth, maxHeight, 420.dp)).then(motion.anchor(PlayerPanel.PLAYING, PlayerElement.ARTWORK)), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            MusicArtwork(state.artwork[track.id], track.metadata.title, stringResource(Res.string.player_album_artwork, track.metadata.title), maxDimension = 1024)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerControls(state: AppState, controller: MusicController, current: Track, requested: Boolean,
    modifier: Modifier, motion: PlayerMotion, visible: Boolean, artworkSwipe: PlayingArtworkSwipe,
    onRepeatCount: () -> Unit, onQueue: () -> Unit, onOutput: () -> Unit, onVisualizer: () -> Unit, onAudioEffects: () -> Unit, onPlaybackSettings: () -> Unit) {
    var seeking by remember(state.playback.entryId) { mutableStateOf<Float?>(null) }
    var seekRequest by remember(state.playback.entryId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val positionFlow = remember(controller) { controller.state.map { it.playback.positionMs }.distinctUntilChanged() }
    val positionMs by positionFlow.collectAsState(controller.state.value.playback.positionMs)
    val durationMs = state.playback.durationMs.takeIf { it > 0 } ?: current.metadata.durationMs
    val duration = durationMs.coerceAtLeast(1).toFloat()
    val waveforms = LocalAudioWaveforms.current ?: rememberAudioWaveforms()
    val source = current.sources.firstOrNull { it.id == state.playback.sourceId && it.kind != me.d3sox.nevumi.library.SourceKind.SERVER }
        ?: current.sources.firstOrNull { it.available && it.kind != me.d3sox.nevumi.library.SourceKind.SERVER }
    val peaks by produceState<List<Float>>(emptyList(), source, durationMs > 0) {
        value = emptyList()
        if (source != null) value = waveforms.load(source, durationMs)
    }
    val sliderColors = appSliderColors()
    val activeWave = MaterialTheme.colorScheme.primary
    val inactiveWave = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .28f)
    val waveformReveal = animateFloatAsState(if (peaks.isEmpty()) 0f else 1f,
        tween(320, easing = FastOutSlowInEasing), label = "Waveform entrance")
    val waveformHeights = remember(peaks) {
        val highest = peaks.maxOrNull()?.coerceAtLeast(.001f) ?: 1f
        peaks.map { kotlin.math.sqrt((it / highest).coerceIn(0f, 1f)) }
    }
    val controlsScroll = rememberScrollState()
    Column(modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(controlsScroll, enabled = controlsScroll.maxValue > 0), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayingTrackDetails(state, controller, current, motion, visible, artworkSwipe)
        Column(Modifier.fillMaxWidth()) {
            val playbackPosition = stringResource(Res.string.player_playback_position)
            Slider(value = (seeking ?: positionMs.toFloat()).coerceIn(0f, duration), valueRange = 0f..duration, colors = sliderColors,
                enabled = durationMs > 0 && state.playback.entryId != null, onValueChange = { seekRequest++; seeking = it },
                onValueChangeFinished = {
                    val entry = state.playback.entryId
                    val request = seekRequest
                    seeking?.let { position -> scope.launch {
                        try { if (entry != null) controller.seekAndConfirm(entry, position.toLong()) }
                        finally { if (request == seekRequest) seeking = null }
                    } }
                }, modifier = Modifier.semantics { contentDescription = playbackPosition }
                    .then(if (peaks.isNotEmpty()) Modifier.testTag("audio-waveform") else Modifier),
                track = { slider ->
                    if (peaks.isEmpty()) SliderDefaults.Track(slider, colors = sliderColors)
                    else Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                        val fraction = slider.value / duration
                        val step = size.width / waveformHeights.size
                        waveformHeights.forEachIndexed { index, height ->
                            val x = (index + .5f) * step
                            val minimum = 1.5.dp.toPx()
                            val half = minimum + (height * (size.height / 2 - minimum)) * waveformReveal.value
                            drawLine(if (x / size.width <= fraction) activeWave else inactiveWave,
                                Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half),
                                (step * .6f).coerceAtLeast(1f), StrokeCap.Round)
                        }
                    }
                })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val position = (seeking ?: positionMs.toFloat()).toLong()
                val remaining = state.preferences.showRemainingTime
                val timeDescription = stringResource(if (remaining) Res.string.player_remaining_time else Res.string.player_elapsed_time)
                Surface(onClick = { controller.updatePreferences { it.copy(showRemainingTime = !it.showRemainingTime) } },
                    modifier = Modifier.semantics { contentDescription = timeDescription },
                    color = androidx.compose.ui.graphics.Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                    Box(Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(if (remaining) "−${playbackTime((durationMs - position).coerceAtLeast(0))}" else playbackTime(position),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(playbackTime(durationMs), style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.widthIn(max = 400.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilledIconToggleButton(state.queue.unshuffledEntryIds != null, { controller.setQueueShuffled(it) },
                modifier = Modifier.size(48.dp).semantics { selected = state.queue.unshuffledEntryIds != null },
                colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface, checkedContainerColor = MaterialTheme.colorScheme.primary,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimary)) { Icon(AppIcon.SHUFFLE.vector, stringResource(Res.string.player_shuffle)) }
            AppIconButton(AppIcon.PREVIOUS, stringResource(Res.string.previous_track), Modifier.size(48.dp).then(motion.anchor(PlayerPanel.PLAYING, PlayerElement.PREVIOUS, visible))) { controller.skip(-1) }
            FilledIconButton(onClick = { controller.togglePlayback() }, modifier = Modifier.size(72.dp).then(motion.anchor(PlayerPanel.PLAYING, PlayerElement.PLAY, visible)),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Icon(if (requested) AppIcon.PAUSE.vector else AppIcon.PLAY.vector, stringResource(if (requested) Res.string.pause else Res.string.play), Modifier.size(36.dp))
            }
            AppIconButton(AppIcon.NEXT, stringResource(Res.string.next_track), Modifier.size(48.dp).then(motion.anchor(PlayerPanel.PLAYING, PlayerElement.NEXT, visible))) { controller.skip(1) }
            PlayerRepeatControl(state.queue, controller, onRepeatCount, Modifier.size(48.dp))
        }
        PlayerToolbar(controller, state.playback.audioInfo?.takeIf { state.preferences.showAudioInfo }?.let(::audioInfoLabel),
            onOutput, onVisualizer, onAudioEffects, onPlaybackSettings, onQueue)
        AppVolume(controller)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerToolbar(controller: MusicController, audioInfo: String?, onOutput: () -> Unit, onVisualizer: () -> Unit,
    onAudioEffects: () -> Unit, onPlaybackSettings: () -> Unit, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val leftButtons: @Composable () -> Unit = {
        AppIconButton(AppIcon.DEVICES, stringResource(Res.string.player_audio_output), onClick = onOutput)
        MilkDropButton(onVisualizer)
        AppIconButton(AppIcon.EQUALIZER, stringResource(Res.string.audio_effects), onClick = onAudioEffects)
    }
    val rightButtons: @Composable () -> Unit = {
        AppIconButton(AppIcon.SETTINGS, stringResource(Res.string.player_playback_settings), onClick = onPlaybackSettings)
        LyricsButton(controller)
        AppIconButton(AppIcon.QUEUE, stringResource(Res.string.queue), onClick = onQueue)
    }
    val info: @Composable (Modifier) -> Unit = { textModifier ->
        if (audioInfo != null) Text(audioInfo, style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = textModifier.testTag("playing-audio-info"))
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val groupWidth = 48.dp * 3
        val infoWidth = with(density) { (maxWidth - groupWidth * 2 - 16.dp).roundToPx() }
        val inline = audioInfo != null && infoWidth > 0 && !measurer.measure(audioInfo, style,
            maxLines = 2, constraints = Constraints(maxWidth = infoWidth)).hasVisualOverflow
        val actionsPerRow = if (maxWidth < groupWidth * 2) 3 else 6
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (inline) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(groupWidth)) { leftButtons() }
                    info(Modifier.weight(1f).padding(horizontal = 8.dp))
                    Row(Modifier.width(groupWidth), horizontalArrangement = Arrangement.End) { rightButtons() }
                }
            } else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = actionsPerRow) {
                    leftButtons()
                    rightButtons()
                }
            }
            if (!inline) info(Modifier.fillMaxWidth())
        }
    }
}

internal fun playbackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun audioInfoLabel(info: me.d3sox.nevumi.playback.AudioInfo) = buildList {
    add(info.format)
    info.bitrateBitsPerSecond?.takeIf { it > 0 }?.let { add("${(it / 1_000.0).roundToInt()} kb/s") }
    info.sampleRateHz?.takeIf { it > 0 }?.let { rate ->
        val fraction = (rate % 1_000).toString().padStart(3, '0').trimEnd('0')
        add("${rate / 1_000}${if (fraction.isEmpty()) "" else ".$fraction"} kHz")
    }
}.joinToString(" · ")

@Composable
private fun ManualQueueTime(controller: MusicController) {
    val remainingFlow = remember(controller) {
        // Playback status arrives many times per second; index the library only when it changes.
        var indexed: Pair<List<Track>, Map<String, Track>>? = null
        controller.state.map { state ->
            val tracks = indexed?.takeIf { it.first === state.tracks }?.second
                ?: state.tracks.associateBy { it.id }.also { indexed = state.tracks to it }
            val queued = state.queue.upNext.sumOf { tracks[it.trackId]?.metadata?.durationMs ?: 0 }
            val current = state.queue.entries.getOrNull(state.queue.currentIndex)
            val playing = if (current?.section == QueueSection.MANUAL)
                ((tracks[current.trackId]?.metadata?.durationMs ?: 0) - state.playback.positionMs).coerceAtLeast(0) else 0
            (queued + playing) / 1_000
        }.distinctUntilChanged()
    }
    val seconds by remainingFlow.collectAsState(0)
    val description = stringResource(Res.string.player_manual_queue_time_remaining)
    Text(stringResource(Res.string.player_time_remaining, playbackTime(seconds * 1_000)), Modifier.semantics { contentDescription = description },
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MiniPlayerSeekBar(controller: MusicController, modifier: Modifier) {
    val playbackFlow = remember(controller) { controller.state.map { it.playback }.distinctUntilChanged() }
    val playback by playbackFlow.collectAsState(controller.state.value.playback)
    var seeking by remember(playback.entryId) { mutableStateOf<Float?>(null) }
    var seekRequest by remember(playback.entryId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val duration = playback.durationMs.coerceAtLeast(1).toFloat()
    val value = (seeking ?: playback.positionMs.toFloat()).coerceIn(0f, duration)
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f)
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val description = stringResource(Res.string.player_mini_player_position)
    Slider(value = value, onValueChange = { seekRequest++; seeking = it }, valueRange = 0f..duration,
        enabled = playback.durationMs > 0, interactionSource = interaction,
        onValueChangeFinished = {
            val entry = playback.entryId
            val request = seekRequest
            seeking?.let { position -> scope.launch {
                try { if (entry != null) controller.seekAndConfirm(entry, position.toLong()) }
                finally { if (request == seekRequest) seeking = null }
            } }
        }, modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).height(24.dp).semantics { contentDescription = description },
        thumb = { Spacer(Modifier.size(10.dp)) },
        track = { Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val center = Offset(size.width * value / duration, size.height / 2)
            drawLine(inactive, Offset(0f, center.y), Offset(size.width, center.y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(active, Offset(0f, center.y), center, 3.dp.toPx(), StrokeCap.Round)
            if (dragging) drawCircle(active, 5.dp.toPx(), center)
        } })
}

private suspend fun MusicController.seekAndConfirm(entryId: String, positionMs: Long) {
    seekCurrentEntry(entryId, positionMs).join()
    withTimeoutOrNull(3_000) {
        state.first { it.playback.entryId != entryId || it.playback.error != null || abs(it.playback.positionMs - positionMs) <= 250 }
    }
}

@Composable
private fun QueueClear.label() = stringResource(when (this) {
    QueueClear.DUPLICATES -> Res.string.player_duplicated_tracks
    QueueClear.PREVIOUS -> Res.string.previous
    QueueClear.NEXT -> Res.string.next
    QueueClear.ALL -> Res.string.all
})

private fun QueueClear.clearTitle() = when (this) {
    QueueClear.DUPLICATES -> Res.string.player_clear_duplicates_title
    QueueClear.PREVIOUS -> Res.string.player_clear_previous_title
    QueueClear.NEXT -> Res.string.player_clear_next_title
    QueueClear.ALL -> Res.string.player_clear_all_title
}

private fun QueueClear.icon() = when (this) {
    QueueClear.DUPLICATES -> AppIcon.DUPLICATES
    QueueClear.PREVIOUS -> AppIcon.PREVIOUS
    QueueClear.NEXT -> AppIcon.NEXT
    QueueClear.ALL -> AppIcon.CLEAR_QUEUE
}
