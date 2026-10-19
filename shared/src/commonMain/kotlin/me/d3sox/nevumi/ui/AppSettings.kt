package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.settings.ColorMode
import me.d3sox.nevumi.settings.TrackAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun QueueAction.label(): String = when (this) {
    QueueAction.PLAY_NEXT -> "Play next"
    QueueAction.PLAY_AFTER -> "Play after"
    QueueAction.APPEND -> "Add to queue"
}

@Composable
internal fun AppSettings(state: AppState, controller: MusicController, server: () -> Unit, downloads: () -> Unit,
    savedQueues: () -> Unit, audioEffects: () -> Unit, serverFiles: () -> Unit, tunnel: () -> Unit, pairedDevices: () -> Unit,
    fileRecovery: () -> Unit, categoryState: MutableState<String?>, onAddMusic: (() -> Unit)? = null, folderPicker: FolderPicker, dismiss: () -> Unit) {
    val settings = state.preferences
    val trackActions = remember { TrackAction.entries.filter { it != TrackAction.PLAY }.map { it to it.label() } }
    var category by categoryState
    val scope = rememberCoroutineScope()
    var pickingFolder by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    fun pickFolder(include: Boolean) {
        folderError = null
        pickingFolder = true
        scope.launch {
            try {
                folderPicker.pick()?.let { folder ->
                    val current = controller.state.value.preferences
                    controller.updateLocalFolders(
                        if (include) current.includeFolders + folder else current.includeFolders,
                        if (include) current.excludeFolders else current.excludeFolders + folder).join()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { folderError = error.message ?: "Could not select this folder" }
            finally { pickingFolder = false }
        }
    }
    var customizeHome by rememberSaveable { mutableStateOf(false) }
    var libraryTabs by rememberSaveable { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var about by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    val destination = when { searching -> "Search settings"; customizeHome -> "Customize Home"; libraryTabs -> "Library tabs"; about -> "About Nevumi"; else -> category }
    val back = { when { searching -> searching = false; customizeHome -> customizeHome = false; libraryTabs -> libraryTabs = false; about -> about = false;
        category == "Connect ListenBrainz" || category == "Connect Last.fm" -> category = "Listening & scrobbling";
        category == "Waveforms" -> category = "Playback"; category != null -> category = null; else -> dismiss() } }
    val parent = if (searching || customizeHome || libraryTabs || about) category else when (category) {
        "Waveforms" -> "Playback"
        "Connect ListenBrainz", "Connect Last.fm" -> "Listening & scrobbling"
        else -> null
    }
    AnimatedNavigation(destination, parent, canGoBack = destination != null,
        onBack = back, depth = { when {
            it == null -> 0
            it == "Search settings" && category == "Waveforms" -> 3
            it == "Waveforms" || it == "Customize Home" || it == "Library tabs" || it == "Connect ListenBrainz" || it == "Connect Last.fm" || it == "Search settings" && category != null -> 2
            else -> 1
        } }) { shownCategory ->
    if (shownCategory == "Search settings") {
        SettingsSearch(search, { search = it }, back, controller) { result ->
            searching = false
            when (result.destination) {
                "Music server" -> server()
                "Manage downloads" -> downloads()
                "Audio effects" -> audioEffects()
                "Server files (SFTP)" -> serverFiles()
                "Tunnel" -> tunnel()
                "Paired devices" -> pairedDevices()
                "Saved queues" -> savedQueues()
                "About Nevumi" -> about = true
                "Customize Home" -> { categoryState.value = "Library & gestures"; customizeHome = true }
                "Library tabs" -> { categoryState.value = "Library & gestures"; libraryTabs = true }
                else -> categoryState.value = result.destination
            }
        }
    }
    else if (shownCategory == "Customize Home") { HomeCustomization(settings.homeSections, controller) { customizeHome = false } }
    else if (shownCategory == "Library tabs") { LibraryTabsDialog(settings.libraryTabs, controller) { libraryTabs = false } }
    else if (shownCategory == "About Nevumi") { AboutNevumi { about = false } }
    else if (shownCategory == "Connect ListenBrainz") { ConnectListenBrainz(controller, back) }
    else if (shownCategory == "Connect Last.fm") { ConnectLastFm(controller, back) }
    else {
    val category = shownCategory
    SettingsScreen(onDismissRequest = back, title = { Text(category ?: "Settings") },
        actions = { AppIconButton(AppIcon.SEARCH, "Search settings") { search = ""; searching = true } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (category == "Appearance") {
            SettingsGroup {
            SettingChoice("Theme", settings.themeMode, listOf(ThemeMode.SYSTEM to "Follow system", ThemeMode.LIGHT to "Light",
                ThemeMode.DARK to "Dark")) { mode ->
                controller.updatePreferences { it.copy(themeMode = mode) }
            }
            SettingsToggle("AMOLED black", AppIcon.DARK_THEME, "Use a black background in dark mode", settings.amoledBlack) {
                enabled -> controller.updatePreferences { it.copy(amoledBlack = enabled) }
            }
            SettingChoice("Colors", settings.colors, listOf(ColorMode.SYSTEM to "System colors", ColorMode.ARTWORK to "Artwork colors",
                ColorMode.CUSTOM to "Custom")) { colors ->
                controller.updatePreferences { it.copy(colors = colors) }
            }
            if (settings.colors == ColorMode.CUSTOM) CustomColorPicker(settings.customColor) { color ->
                controller.updatePreferences { it.copy(customColor = color) }
            }
                        }
}
            if (category == "Library & gestures") {
            SettingsGroup {
            SettingChoice("On launch", settings.startupTab,
                listOf(null to "Last opened page") + settings.libraryTabs.map { it to it.label }) { tab ->
                controller.updatePreferences { it.copy(startupTab = tab) }
            }
            SettingChoice("Swipe right", settings.swipeRight, trackActions) { action ->
                controller.updatePreferences { it.copy(swipeRight = action) }
            }
            SettingChoice("Swipe left", settings.swipeLeft, trackActions) { action ->
                controller.updatePreferences { it.copy(swipeLeft = action) }
            }
            SettingChoice("Quick action", settings.quickAction, trackActions) { action ->
                controller.updatePreferences { it.copy(quickAction = action) }
            }
            SettingsToggle("Show options buttons", AppIcon.MORE, "Show ⋮ on songs and collections. Long press opens the same menu.", settings.showMoreButtons) {
                enabled -> controller.updatePreferences { it.copy(showMoreButtons = enabled) }
            }
                SettingsLink("Customize Home", AppIcon.HOME, "Reorder sections on your Home page") { customizeHome = true }
                SettingsLink("Library tabs", AppIcon.TABS, "Choose and reorder library pages") { libraryTabs = true }
                        }
}
            if (category == "Playback") {
            SettingsGroup {
            SettingsSlider("Playback speed", AppIcon.SPEED, settings.speed.toFloat(), 0.5f..2f, 29,
                { value -> "${(value * 100).roundToInt() / 100.0}×" }) { controller.setSpeed(it.toDouble()) }
            SettingsSlider("Playback pitch", AppIcon.PITCH, (12 * log2(settings.pitch)).toFloat(), -12f..12f, 23,
                { value -> when (val semitones = value.roundToInt()) {
                    0 -> "Original pitch"
                    1 -> "+1 semitone"
                    -1 -> "−1 semitone"
                    else -> if (semitones > 0) "+$semitones semitones" else "$semitones semitones"
                } }) { controller.setPitch(2.0.pow(it.roundToInt() / 12.0)) }
            SettingsLink("Waveforms", AppIcon.WAVEFORM, "Prepare and cache waveforms for your music") { categoryState.value = "Waveforms" }
            SettingsToggle("Show audio info", AppIcon.INFO, "Format, bitrate and sample rate in Now Playing", settings.showAudioInfo) {
                enabled -> controller.updatePreferences { it.copy(showAudioInfo = enabled) }
            }
            SettingsLink("Audio effects", AppIcon.EQUALIZER, "Equalizer, headphone profiles and mono audio", audioEffects)
            SettingsSlider("Crossfade", AppIcon.CROSSFADE, settings.crossfadeDurationMs / 1_000f, 0f..12f, 11,
                { value -> when (val seconds = value.roundToInt()) { 0 -> "Off"; 1 -> "1 second"; else -> "$seconds seconds" } }) {
                controller.updatePreferences { preferences -> preferences.copy(crossfadeDurationMs = it.roundToInt() * 1_000L) }
            }
            SettingsToggle("Gapless playback", AppIcon.MUSIC, "Play adjacent tracks without a break when crossfade is off.", settings.gaplessPlayback) {
                enabled -> controller.updatePreferences { it.copy(gaplessPlayback = enabled) }
            }
            SettingsToggle("Show previous tracks in queue", AppIcon.HISTORY, "Display songs before the current track", settings.showPreviousQueueTracks) {
                enabled -> controller.updatePreferences { it.copy(showPreviousQueueTracks = enabled) }
            }
            SettingsToggle("Confirm queue jumps", AppIcon.CONFIRM, "Ask before playing a song other than the next one", settings.confirmQueueJumps) {
                enabled -> controller.updatePreferences { it.copy(confirmQueueJumps = enabled) }
            }
            SettingsToggle("Confirm interrupting Up next", AppIcon.CONFIRM, "Ask before starting other music while Up next is active", settings.confirmNextUpInterruption) {
                enabled -> controller.updatePreferences { it.copy(confirmNextUpInterruption = enabled) }
            }
            if (settings.showPreviousQueueTracks) {
                SettingsSlider("Previous track limit", AppIcon.REPEAT_COUNT, settings.previousQueueTrackLimit.toFloat(), 1f..100f, 98,
                    format = { it.roundToInt().toString() }) { value ->
                    controller.updatePreferences { it.copy(previousQueueTrackLimit = value.roundToInt()) }
                }
            }
                        }
}
            if (category == "Waveforms") { WaveformSettings(state, controller) }
            if (category == "Downloads & cache") {
            SettingsGroup {
            val sourceOrders = SourceKind.entries.flatMap { first ->
                SourceKind.entries.filter { it != first }.map { second ->
                    listOf(first, second, SourceKind.entries.first { it != first && it != second })
                }
            }
            SettingChoice("Source priority", settings.sourcePriority, sourceOrders.map { order -> order to order.joinToString(" → ") { source ->
                when (source) { SourceKind.LOCAL -> "Local"; SourceKind.DOWNLOAD -> "Downloads"; SourceKind.SERVER -> "Server" }
            } }) { order -> controller.updatePreferences { it.copy(sourcePriority = order) } }
            SettingChoice("Playback cache", settings.cacheBytes, listOf(0L to "Off", 268_435_456L to "256 MiB",
                1_073_741_824L to "1 GiB", 2_147_483_648L to "2 GiB", 5_368_709_120L to "5 GiB")) { bytes ->
                controller.updatePreferences { it.copy(cacheBytes = bytes) }
            }
            SettingChoice("Download network", settings.downloadOnMetered,
                listOf(false to "Unmetered only", true to "Any network")) { allowed ->
                controller.updatePreferences { it.copy(downloadOnMetered = allowed) }
            }
                SettingsLink("Manage downloads", AppIcon.DOWNLOAD, "Downloads, progress and offline music", downloads)
                        }
}
            if (category == "Listening & scrobbling") {
            SettingsGroup {
            SettingChoice("Record listening history", settings.recordListeningHistory,
                listOf(true to "On", false to "Off")) { enabled ->
                controller.updatePreferences { it.copy(recordListeningHistory = enabled) }
            }
            TextButton(onClick = { clearHistory = true }) { Text("Clear listening history") }
                        }
                if (controller.scrobblingAvailable) ScrobblingSettingsContent(state, controller) {
                    categoryState.value = "Connect ${it.label()}"
                }
}
            if (category == "Files & recovery") {
            SettingsGroup {
                if (state.fileEdits.any { it.needsRecovery || (!it.applied && !it.undone && !it.writing) })
                    SettingsLink("Review interrupted edit", AppIcon.UNDO, "Restore or finish a pending music file edit", fileRecovery)
            if (controller.fileEditingAvailable) {
                SettingChoice("Keep undo backups", settings.undoRetentionDays, listOf(7 to "7 days", 30 to "30 days", 90 to "90 days")) { days ->
                    controller.updatePreferences { it.copy(undoRetentionDays = days) }
                }
                SettingChoice("Undo backup storage", settings.undoBackupBytes, listOf(268_435_456L to "256 MiB",
                    1_073_741_824L to "1 GiB", 5_368_709_120L to "5 GiB")) { bytes ->
                    controller.updatePreferences { it.copy(undoBackupBytes = bytes) }
                }
                Text("Older backups expire. The newest edit and unfinished recovery can exceed the storage target.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
                if (controller.serverFilesAvailable) SettingsLink("Server files (SFTP)", AppIcon.SERVER_FILES, "Optional file editing and uploads", serverFiles)
                        }
}
            if (category == "Local music") {
                Text("Scan the music already stored on your device. Files stay in their current folders.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !state.loading && state.localScan == null,
                    onClick = { if (onAddMusic != null) onAddMusic() else controller.rescanLocalMusic() }) {
                    Icon(AppIcon.SEARCH.vector, null); Spacer(Modifier.width(8.dp)); Text("Scan music")
                }
                state.localScan?.let { scan ->
                    LinearProgressIndicator(progress = { if (scan.total == 0) 0f else scan.processed.toFloat() / scan.total }, Modifier.fillMaxWidth())
                    Text("Scanning music · ${scan.processed} / ${scan.total}", style = MaterialTheme.typography.bodySmall)
                } ?: state.lastLocalScanCount?.let { count ->
                    Text("Scan complete · ${countLabel(count, "song")}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Selected folders include their subfolders. Exclusions take priority.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FolderRuleList("Include folders", settings.includeFolders, "included", pickingFolder,
                    "All scanned folders are included", { pickFolder(true) }) { folder ->
                    controller.updateLocalFolders(settings.includeFolders - folder, settings.excludeFolders)
                }
                HorizontalDivider()
                FolderRuleList("Exclude folders", settings.excludeFolders, "excluded", pickingFolder,
                    "No folders excluded", { pickFolder(false) }) { folder ->
                    controller.updateLocalFolders(settings.includeFolders, settings.excludeFolders - folder)
                }
                if (pickingFolder) LinearProgressIndicator(Modifier.fillMaxWidth())
                folderError?.let { error ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        AppIconButton(AppIcon.CLOSE, "Dismiss folder error") { folderError = null }
                    }
                }
            }
            if (category == null) {
                SettingsGroup {
                SettingsLink("Appearance", AppIcon.THEME, "Theme, system colors and artwork colors") { categoryState.value = "Appearance" }
                SettingsLink("Local music", AppIcon.FOLDER, "Scan music, include folders and exclusions") { categoryState.value = "Local music" }
                SettingsLink("Library & gestures", AppIcon.GESTURES, "Tabs and swipe actions") { categoryState.value = "Library & gestures" }
                SettingsLink("Playback", AppIcon.PLAY, "Speed, pitch, crossfade and audio effects") { categoryState.value = "Playback" }
                }
                SettingsGroup {
                SettingsLink("Downloads & cache", AppIcon.DOWNLOAD, "Offline music, network and source priority") { categoryState.value = "Downloads & cache" }
                SettingsLink("Music server", AppIcon.SERVER, "Navidrome and Subsonic", server)
                if (controller.tunnel != null) SettingsLink("Tunnel", AppIcon.TAILSCALE, "Reach your servers through Tailscale", tunnel)
                SettingsLink("Listening & scrobbling", AppIcon.HISTORY, "History, statistics and connected services") { categoryState.value = "Listening & scrobbling" }
                }
                SettingsGroup {
                if (controller.pairedDevices != null) SettingsLink("Paired devices", AppIcon.DEVICES, "Sync with your phone or computer", pairedDevices)
                if (controller.fileEditingAvailable || controller.serverFilesAvailable) SettingsLink("Files & recovery", AppIcon.UNDO, "Undo backups and server file editing") { categoryState.value = "Files & recovery" }
                SettingsLink("Saved queues", AppIcon.QUEUE, "Manage saved playback queues", savedQueues)
                SettingsLink("About Nevumi", AppIcon.INFO, "Source code and license") { about = true }
                }
            }
        }
    })
    }
    }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("Clear listening history?") },
        text = { Text("This removes this device’s listening history and statistics.") },
        confirmButton = { TextButton(onClick = { controller.clearListeningHistory(); clearHistory = false }) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("Cancel") } })
}

@Composable
internal fun <T> SettingChoice(label: String, selected: T, choices: List<Pair<T, String>>, card: Boolean = true, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun choiceIcon(value: T): AppIcon? = when (value) {
        is QueueAction -> value.icon()
        is me.d3sox.nevumi.playlists.PlaylistSort -> value.icon()
        is me.d3sox.nevumi.settings.PlaylistPickerSort -> value.icon()
        is me.d3sox.nevumi.settings.TrackAction -> value.icon()
        is me.d3sox.nevumi.library.LibrarySort -> value.icon()
        is me.d3sox.nevumi.library.LibraryTab -> value.icon()
        is ThemeMode -> when (value) { ThemeMode.SYSTEM -> AppIcon.SYSTEM_THEME; ThemeMode.LIGHT -> AppIcon.LIGHT_THEME; ThemeMode.DARK -> AppIcon.DARK_THEME }
        is ColorMode -> when (value) { ColorMode.SYSTEM -> AppIcon.SYSTEM_COLORS; ColorMode.ARTWORK -> AppIcon.ARTWORK_COLORS; ColorMode.CUSTOM -> AppIcon.THEME }
        is SourceKind -> when (value) { SourceKind.LOCAL -> AppIcon.DEVICE; SourceKind.DOWNLOAD -> AppIcon.DOWNLOAD; SourceKind.SERVER -> AppIcon.CLOUD }
        is List<*> -> when (value.firstOrNull()) { SourceKind.LOCAL -> AppIcon.DEVICE; SourceKind.DOWNLOAD -> AppIcon.DOWNLOAD; SourceKind.SERVER -> AppIcon.CLOUD; else -> null }
        is Boolean -> if (label == "Download network") { if (value) AppIcon.NETWORK else AppIcon.WIFI }
            else if (value) AppIcon.CHECK else AppIcon.CLOSE
        is Double -> if (label == "Playback speed") AppIcon.SPEED else if (label == "Playback pitch") AppIcon.PITCH else null
        is Long -> when (label) { "Playback cache", "Undo backup storage" -> AppIcon.STORAGE; "Music library" -> AppIcon.FOLDER; else -> null }
        is Int -> if (label == "Keep undo backups") AppIcon.YEAR else null
        is me.d3sox.nevumi.history.ListeningPeriod -> if (value.days == null) AppIcon.HISTORY else AppIcon.YEAR
        null -> when (label) { "On launch" -> AppIcon.HISTORY; "Music sources" -> AppIcon.LIBRARY; else -> null }
        is String -> if (label == "Statistics grouping") when (value) {
            "Songs" -> AppIcon.MUSIC; "Artists" -> AppIcon.ARTIST; "Albums" -> AppIcon.ALBUM; "Genres" -> AppIcon.GENRE; else -> null
        } else if (label == "SSH authentication") { if (value == "SSH private key") AppIcon.KEY else AppIcon.LOCK } else null
        else -> null
    }
    val distinctIcons = choices.map { choiceIcon(it.first) }.distinct().size > 1
    val row: @Composable () -> Unit = {
    Row(Modifier.fillMaxWidth().then(if (card && LocalSettingsCards.current) Modifier else Modifier.clickable { expanded = true })
        .semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            Row(Modifier.widthIn(max = 240.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                choiceIcon(selected)?.takeIf { distinctIcons }?.let { icon ->
                    Icon(icon.vector, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                }
                Text(choices.first { it.first == selected }.second, Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                Icon(AppIcon.DOWN.vector, null, Modifier.size(16.dp))
            }
        }
    }
    }
    Box(Modifier.fillMaxWidth()) {
        if (card && LocalSettingsCards.current) SettingsCard(settingsIcon(label), onClick = { expanded = true }) {
            Box(Modifier.weight(1f)) { row() }
        } else row()
        // Keep the popup outside the clickable card so choosing an option cannot reopen it.
        Box(Modifier.align(Alignment.CenterEnd)) {
            DropdownMenu(expanded, { expanded = false }) {
                choices.forEach { (value, name) ->
                    AppMenuItem(text = { Text(name) }, leadingIcon = choiceIcon(value)?.takeIf { distinctIcons }?.let { icon -> { Icon(icon.vector, null) } },
                        onClick = { change(value); expanded = false })
                }
            }
        }
    }
}

@Composable
internal fun SettingsLink(title: String, icon: AppIcon, description: String, click: () -> Unit) {
    SettingsCard(icon, onClick = click) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(AppIcon.FORWARD.vector, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderRuleList(title: String, folders: List<String>, kind: String, busy: Boolean,
    emptyMessage: String, add: () -> Unit, remove: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (folders.isEmpty()) Text(emptyMessage, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    folders.forEach { folder ->
        ListItem(headlineContent = { Text(folder.trimEnd('/').substringAfterLast('/')) },
            supportingContent = { Text(folder) }, leadingContent = { Icon(AppIcon.FOLDER.vector, null) },
            trailingContent = { AppIconButton(AppIcon.REMOVE, "Remove $kind folder: $folder", enabled = !busy) { remove(folder) } })
    }
    TextButton(onClick = add, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Add $kind folder" }) {
        Icon(AppIcon.PLUS.vector, null)
        Spacer(Modifier.width(8.dp))
        Text("Add folder")
    }
}

@Composable
internal fun SettingsSlider(label: String, icon: AppIcon, selected: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int = 0, format: (Float) -> String, change: (Float) -> Unit) {
    var value by remember(selected) { mutableFloatStateOf(selected) }
    SettingsCard(icon) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f)); Text(format(value), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PreciseSlider(label, value, range, steps, change = { value = it }, finish = { change(value) })
        }
    }
}
