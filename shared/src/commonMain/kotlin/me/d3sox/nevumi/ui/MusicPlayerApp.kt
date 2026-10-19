package me.d3sox.nevumi.ui

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import com.materialkolor.ktx.themeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import me.d3sox.nevumi.app.ActionDestination
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import me.d3sox.nevumi.settings.ColorMode
import me.d3sox.nevumi.playback.PlaybackStatus
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.app.AppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The app state without playback progress, shared so song rows do not each wake on every position update. */
internal val LocalSteadyState = staticCompositionLocalOf<StateFlow<AppState>?> { null }

private fun AppState.withoutProgress() = copy(queue = queue.copy(positionMs = 0), playback = playback.copy(positionMs = 0))

private enum class AppPage { LIBRARY, SETTINGS, SERVER, DOWNLOADS, SAVED_QUEUES, FILE_RECOVERY, AUDIO_EFFECTS, SFTP, TUNNEL, PAIRED_DEVICES, SEARCH }

@Composable
fun MusicPlayerApp(controller: MusicController, onAddMusic: (() -> Unit)? = null, artworkPicker: ArtworkPicker? = null,
    playlistFiles: PlaylistFiles? = null, equalizerFiles: EqualizerFiles? = null, folderPicker: FolderPicker? = null,
    audioWaveforms: me.d3sox.nevumi.playback.AudioWaveforms? = null) {
    val picker = artworkPicker ?: rememberArtworkPicker()
    val files = playlistFiles ?: rememberPlaylistFiles()
    val equalizerDocuments = equalizerFiles ?: rememberEqualizerFiles()
    val folders = folderPicker ?: rememberFolderPicker()
    // The position changes many times per second; the seek bar observes it on its own so the app does not recompose.
    val steadyState = remember(controller) { MutableStateFlow(controller.state.value.withoutProgress()) }
    LaunchedEffect(steadyState) { controller.state.collect { steadyState.value = it.withoutProgress() } }
    val state by steadyState.collectAsState()
    val waveformReader = audioWaveforms ?: rememberAudioWaveforms()
    val waveforms = remember(waveformReader) { me.d3sox.nevumi.playback.PreparedAudioWaveforms(waveformReader) }
    DisposableEffect(waveforms) { onDispose { waveforms.close() } }
    val waveformSources = remember(state.tracks, state.preferences.sourcePriority, state.queue.entries, state.queue.currentEntryId) {
        val upcoming = state.queue.entries.drop(state.queue.currentIndex.coerceAtLeast(0)).map { it.trackId }.distinct()
            .mapIndexed { index, id -> id to index }.toMap()
        state.tracks.sortedWith(compareBy<me.d3sox.nevumi.library.Track> {
            upcoming[it.id] ?: Int.MAX_VALUE
        }.thenByDescending { track -> track.sources.maxOfOrNull { it.modifiedAt } ?: 0 }).mapNotNull { track ->
        track.sources.sortedBy { state.preferences.sourcePriority.indexOf(it.kind) }
            .firstOrNull { it.available && it.kind != me.d3sox.nevumi.library.SourceKind.SERVER }
            ?.takeIf { track.metadata.durationMs > 0 }?.let { it to track.metadata.durationMs }
    } }
    val prepareWaveforms = state.preferences.precomputeWaveforms && state.libraryReady && !state.loading &&
        state.localScan == null &&
        state.downloads.values.none { it.status == me.d3sox.nevumi.downloads.DownloadStatus.DOWNLOADING }
    LaunchedEffect(waveforms, waveformSources, prepareWaveforms) { waveforms.prepare(waveformSources, prepareWaveforms) }

    val hasPlayer = state.playback.trackId != null || state.queue.currentTrackId != null
    val playerSheet = rememberSaveable(saver = AnchoredDraggableState.Saver<PlayerPanel>()) { AnchoredDraggableState(PlayerPanel.MINI) }
    val libraryState by remember(controller) { derivedStateOf(structuralEqualityPolicy()) {
        state.copy(playback = PlaybackStatus(), queue = QueueSnapshot())
    } }
    val density = LocalDensity.current
    var navigationHeight by remember { mutableStateOf(80.dp) }
    val settingsCategory = rememberSaveable { mutableStateOf<String?>(null) }
    var sessionLibraryTab by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var libraryDestination by remember { mutableStateOf<LibraryDestination?>(null) }
    var page by rememberSaveable { mutableStateOf(AppPage.LIBRARY) }
    fun openLibrary(tab: me.d3sox.nevumi.library.LibraryTab, collectionId: String?, trackId: String? = null) {
        libraryDestination = LibraryDestination(tab, collectionId, (libraryDestination?.revision ?: 0) + 1, trackId)
        page = AppPage.LIBRARY
        scope.launch { playerSheet.animateTo(PlayerPanel.MINI, spring()) }
    }
    val cover = if (state.preferences.colors == ColorMode.ARTWORK) rememberArtwork(state.artwork[state.playback.trackId]) else null
    val artworkColor by produceState<Color?>(null, cover) {
        value = if (cover == null) null else withContext(Dispatchers.Default) { cover.themeColor(Color(0xFF6750A4)) }
    }
    CompositionLocalProvider(LocalSteadyState provides steadyState, LocalAudioWaveforms provides waveforms, LocalArtworkPicker provides picker, LocalPlaylistFiles provides files, LocalEqualizerFiles provides equalizerDocuments,
        LocalLibraryNavigate provides { tab, id -> openLibrary(tab, id) },
        LocalLibraryFocusHandled provides { libraryDestination = libraryDestination?.copy(trackId = null) },
        LocalPlayingContextNavigate provides { origin, track -> openLibrary(origin.tab, origin.collectionId, track) }) {
    MusicTheme(state.preferences, artworkColor) {
        val messages = remember { SnackbarHostState() }
        fun openNotice(destination: ActionDestination) {
            when (destination) {
                ActionDestination.QUEUE -> {
                    if (page != AppPage.SEARCH) page = AppPage.LIBRARY
                    scope.launch { playerSheet.animateTo(PlayerPanel.QUEUE, spring()) }
                }
                ActionDestination.FAVORITES -> openLibrary(me.d3sox.nevumi.library.LibraryTab.FAVORITES, null)
                ActionDestination.PLAYLISTS -> openLibrary(me.d3sox.nevumi.library.LibraryTab.PLAYLISTS, null)
                ActionDestination.DOWNLOADS -> page = AppPage.DOWNLOADS
            }
        }
        LaunchedEffect(state.error) {
            state.error?.let { error ->
                messages.showSnackbar(error, withDismissAction = true, duration = SnackbarDuration.Short)
                controller.dismissError(error)
            }
        }
        LaunchedEffect(controller) {
            controller.notices.collectLatest { notice ->
                messages.currentSnackbarData?.dismiss()
                if (messages.showSnackbar(ActionSnackbarVisuals(notice)) == SnackbarResult.ActionPerformed) {
                    if (notice.undo != null) notice.undo.invoke()
                    else notice.destination?.let(::openNotice)
                }
            }
        }
        var returnToSettings by rememberSaveable { mutableStateOf(false) }
        fun closePage() { page = if (returnToSettings) AppPage.SETTINGS else AppPage.LIBRARY }
        fun openPage(next: AppPage) { returnToSettings = true; page = next }
        Surface(Modifier.fillMaxSize().testTag("app-window")) {
            TrackMenuHost {
            Box(Modifier.safeDrawingPadding().fillMaxSize()) {
                AnimatedNavigation(page, if (page == AppPage.SETTINGS || !returnToSettings) AppPage.LIBRARY else AppPage.SETTINGS,
                    canGoBack = page != AppPage.LIBRARY, onBack = { if (page == AppPage.SETTINGS) page = AppPage.LIBRARY else closePage() },
                    depth = { when (it) { AppPage.LIBRARY -> 0; AppPage.SETTINGS -> 1; else -> 2 } }) { destination ->
                when (destination) {
                    AppPage.SEARCH -> Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().then(if (hasPlayer && playerSheet.targetValue != PlayerPanel.MINI) Modifier.clearAndSetSemantics {} else Modifier)) {
                            LibrarySearchScreen(libraryState, controller, if (hasPlayer) 96.dp else 0.dp, ::closePage)
                        }
                    }
                    AppPage.SERVER -> ServerSettings(state, controller, ::closePage)
                    AppPage.DOWNLOADS -> DownloadsScreen(state, controller, ::closePage)
                    AppPage.SAVED_QUEUES -> SavedQueuesScreen(state, controller, ::closePage)
                    AppPage.FILE_RECOVERY -> FileRecovery(state, controller, ::closePage)
                    AppPage.AUDIO_EFFECTS -> AudioEffectsScreen(state, controller, ::closePage)
                    AppPage.SFTP -> SftpSettings(state, controller, ::closePage)
                    AppPage.TUNNEL -> TunnelSettings(controller, ::closePage)
                    AppPage.PAIRED_DEVICES -> controller.pairedDevices?.let { PairingSettings(it, state.tracks, ::closePage, state.artwork) }
                    AppPage.SETTINGS -> AppSettings(state, controller,
                        server = { openPage(AppPage.SERVER) }, downloads = { openPage(AppPage.DOWNLOADS) },
                        audioEffects = { openPage(AppPage.AUDIO_EFFECTS) },
                        serverFiles = { openPage(AppPage.SFTP) }, tunnel = { openPage(AppPage.TUNNEL) }, pairedDevices = { openPage(AppPage.PAIRED_DEVICES) },
                        savedQueues = { openPage(AppPage.SAVED_QUEUES) }, fileRecovery = { openPage(AppPage.FILE_RECOVERY) },
                        categoryState = settingsCategory, onAddMusic = onAddMusic, folderPicker = folders) { page = AppPage.LIBRARY }
                    AppPage.LIBRARY -> {
                        Column(Modifier.fillMaxSize().then(if (hasPlayer && playerSheet.targetValue != PlayerPanel.MINI) Modifier.clearAndSetSemantics {} else Modifier)) {
                            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (state.libraryReady) LibraryScreens(libraryState, controller, Modifier.weight(1f), destination = libraryDestination,
                                backEnabled = LocalNavigationActive.current && (!hasPlayer || playerSheet.targetValue == PlayerPanel.MINI),
                                miniPlayerSpace = if (hasPlayer) 96.dp else 0.dp,
                                onNavigationHeight = { navigationHeight = with(density) { it.toDp() } },
                                initialTab = sessionLibraryTab?.let { me.d3sox.nevumi.library.LibraryTab.valueOf(it) },
                                onTabSelected = { sessionLibraryTab = it.name },
                                onSettings = { page = AppPage.SETTINGS }, onSearch = { returnToSettings = false; page = AppPage.SEARCH })
                            else Spacer(Modifier.weight(1f))
                        }
                    }
                }
                }
                if (page == AppPage.LIBRARY || page == AppPage.SEARCH) {
                    PlayerPanels(state, controller, playerSheet, if (page == AppPage.LIBRARY) navigationHeight else 0.dp,
                        onPairedDevices = { returnToSettings = false; page = AppPage.PAIRED_DEVICES },
                        onAudioEffects = { returnToSettings = false; page = AppPage.AUDIO_EFFECTS }) { returnToSettings = false; page = AppPage.SAVED_QUEUES }
                }
                val noticeInset by animateDpAsState(when (page) {
                    AppPage.LIBRARY -> navigationHeight + if (hasPlayer) 96.dp else 0.dp
                    AppPage.SEARCH -> if (hasPlayer) 96.dp else 0.dp
                    else -> 0.dp
                }, tween(250))
                val pageNoticeTop by animateFloatAsState(if (page != AppPage.LIBRARY && page != AppPage.SEARCH) 1f else 0f, tween(250))
                SnackbarHost(messages, Modifier.layout { measurable, constraints ->
                    val toast = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // Read drag progress during placement, so the toast tracks the finger in the same frame.
                        val offset = playerSheet.offset
                        val mini = playerSheet.anchors.positionOf(PlayerPanel.MINI)
                        val queue = playerSheet.anchors.positionOf(PlayerPanel.QUEUE)
                        val playingProgress = if (!hasPlayer || !offset.isFinite()) 0f else if (offset >= 0f)
                            (1f - offset / mini).coerceIn(0f, 1f) else (1f - offset / queue).coerceIn(0f, 1f)
                        val queueProgress = if (hasPlayer && offset.isFinite() && offset < 0f) (offset / queue).coerceIn(0f, 1f) else 0f
                        val topProgress = pageNoticeTop + (1f - pageNoticeTop) * playingProgress
                        val bottom = constraints.maxHeight - toast.height - 8.dp.roundToPx() - noticeInset.toPx() * (1f - queueProgress)
                        toast.placeRelative((constraints.maxWidth - toast.width) / 2, (bottom + (64.dp.toPx() - bottom) * topProgress).roundToInt())
                    }
                }.testTag("action-snackbar").padding(horizontal = 16.dp)) { data -> ActionSnackbar(data, ::openNotice) }
            }
        }
        }
    }
    }
    state.playbackConfirmation?.let { request ->
        MusicTheme(state.preferences, artworkColor) {
            AlertDialog(onDismissRequest = controller::cancelPlaybackConfirmation,
                title = { Text("Interrupt Up next?") },
                text = { Text("Play ${request.title} instead of continuing your Up next queue?") },
                confirmButton = { TextButton(onClick = controller::acceptPlaybackConfirmation) { Text("Play instead") } },
                dismissButton = { TextButton(onClick = controller::cancelPlaybackConfirmation) { Text("Keep queue") } })
        }
    }
}
