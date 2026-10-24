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
import me.d3sox.nevumi.playback.ReplayGainMode
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.settings.ColorMode
import me.d3sox.nevumi.settings.TrackAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_gate_browser
import me.d3sox.nevumi.resources.ssh_authentication
import me.d3sox.nevumi.resources.statistics_grouping
import me.d3sox.nevumi.resources.music_sources
import me.d3sox.nevumi.resources.music_library
import me.d3sox.nevumi.resources.about_nevumi
import me.d3sox.nevumi.resources.add_to_queue
import me.d3sox.nevumi.resources.audio_effects
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.clear
import me.d3sox.nevumi.resources.colors
import me.d3sox.nevumi.resources.connect_last_fm
import me.d3sox.nevumi.resources.connect_listenbrainz
import me.d3sox.nevumi.resources.crossfade
import me.d3sox.nevumi.resources.customize_home
import me.d3sox.nevumi.resources.download_network
import me.d3sox.nevumi.resources.downloads
import me.d3sox.nevumi.resources.keep_undo_backups
import me.d3sox.nevumi.resources.library_tabs
import me.d3sox.nevumi.resources.music_server
import me.d3sox.nevumi.resources.off
import me.d3sox.nevumi.resources.on
import me.d3sox.nevumi.resources.on_launch
import me.d3sox.nevumi.resources.paired_devices
import me.d3sox.nevumi.resources.play_after
import me.d3sox.nevumi.resources.play_next
import me.d3sox.nevumi.resources.playback
import me.d3sox.nevumi.resources.playback_cache
import me.d3sox.nevumi.resources.quick_action
import me.d3sox.nevumi.resources.record_listening_history
import me.d3sox.nevumi.resources.saved_queues
import me.d3sox.nevumi.resources.server
import me.d3sox.nevumi.resources.server_files_sftp
import me.d3sox.nevumi.resources.settings
import me.d3sox.nevumi.resources.settings_about_nevumi_description
import me.d3sox.nevumi.resources.settings_add_excluded_folder
import me.d3sox.nevumi.resources.settings_add_folder
import me.d3sox.nevumi.resources.settings_add_included_folder
import me.d3sox.nevumi.resources.settings_amoled_black
import me.d3sox.nevumi.resources.settings_amoled_black_description
import me.d3sox.nevumi.resources.settings_any_network
import me.d3sox.nevumi.resources.settings_appearance
import me.d3sox.nevumi.resources.settings_appearance_description
import me.d3sox.nevumi.resources.settings_audio_effects_description
import me.d3sox.nevumi.resources.settings_backup_restore
import me.d3sox.nevumi.resources.settings_backup_restore_description
import me.d3sox.nevumi.resources.settings_clear_all_favorites
import me.d3sox.nevumi.resources.settings_clear_all_favorites_message
import me.d3sox.nevumi.resources.settings_clear_all_ratings
import me.d3sox.nevumi.resources.settings_clear_all_ratings_message
import me.d3sox.nevumi.resources.settings_clear_favorites
import me.d3sox.nevumi.resources.settings_clear_history
import me.d3sox.nevumi.resources.settings_clear_listening_history
import me.d3sox.nevumi.resources.settings_clear_listening_history_message
import me.d3sox.nevumi.resources.settings_clear_listening_history_question
import me.d3sox.nevumi.resources.settings_clear_ratings
import me.d3sox.nevumi.resources.settings_colors_artwork
import me.d3sox.nevumi.resources.settings_colors_custom
import me.d3sox.nevumi.resources.settings_colors_system
import me.d3sox.nevumi.resources.settings_confirm_queue_jumps
import me.d3sox.nevumi.resources.settings_confirm_queue_jumps_description
import me.d3sox.nevumi.resources.settings_confirm_up_next_interruption
import me.d3sox.nevumi.resources.settings_confirm_up_next_interruption_description
import me.d3sox.nevumi.resources.settings_could_not_select_folder
import me.d3sox.nevumi.resources.settings_customize_home_description
import me.d3sox.nevumi.resources.settings_days
import me.d3sox.nevumi.resources.settings_dismiss_folder_error
import me.d3sox.nevumi.resources.settings_downloads_cache
import me.d3sox.nevumi.resources.settings_downloads_cache_description
import me.d3sox.nevumi.resources.settings_exclude_folders
import me.d3sox.nevumi.resources.settings_exclude_folders_empty
import me.d3sox.nevumi.resources.settings_files_recovery
import me.d3sox.nevumi.resources.settings_files_recovery_description
import me.d3sox.nevumi.resources.settings_folder_rules_note
import me.d3sox.nevumi.resources.settings_gapless_playback
import me.d3sox.nevumi.resources.settings_gapless_playback_description
import me.d3sox.nevumi.resources.settings_replay_gain
import me.d3sox.nevumi.resources.settings_replay_gain_album
import me.d3sox.nevumi.resources.settings_replay_gain_preamp
import me.d3sox.nevumi.resources.settings_replay_gain_track
import me.d3sox.nevumi.resources.settings_include_folders
import me.d3sox.nevumi.resources.settings_include_folders_empty
import me.d3sox.nevumi.resources.settings_last_opened_page
import me.d3sox.nevumi.resources.settings_library_gestures
import me.d3sox.nevumi.resources.settings_library_gestures_description
import me.d3sox.nevumi.resources.settings_library_tabs_description
import me.d3sox.nevumi.resources.settings_listening_scrobbling
import me.d3sox.nevumi.resources.settings_listening_scrobbling_description
import me.d3sox.nevumi.resources.settings_local_music
import me.d3sox.nevumi.resources.settings_local_music_description
import me.d3sox.nevumi.resources.settings_local_music_summary
import me.d3sox.nevumi.resources.settings_manage_downloads
import me.d3sox.nevumi.resources.settings_manage_downloads_description
import me.d3sox.nevumi.resources.settings_music_server_description
import me.d3sox.nevumi.resources.settings_original_pitch
import me.d3sox.nevumi.resources.settings_paired_devices_description
import me.d3sox.nevumi.resources.settings_playback_description
import me.d3sox.nevumi.resources.settings_playback_pitch
import me.d3sox.nevumi.resources.settings_playback_speed
import me.d3sox.nevumi.resources.settings_previous_track_limit
import me.d3sox.nevumi.resources.settings_remember_scroll_positions
import me.d3sox.nevumi.resources.settings_remember_scroll_positions_description
import me.d3sox.nevumi.resources.settings_remove_excluded_folder
import me.d3sox.nevumi.resources.settings_remove_included_folder
import me.d3sox.nevumi.resources.settings_review_interrupted_edit
import me.d3sox.nevumi.resources.settings_review_interrupted_edit_description
import me.d3sox.nevumi.resources.settings_saved_queues_description
import me.d3sox.nevumi.resources.settings_scan_complete
import me.d3sox.nevumi.resources.settings_scan_music
import me.d3sox.nevumi.resources.settings_scanning_music
import me.d3sox.nevumi.resources.settings_search_settings
import me.d3sox.nevumi.resources.settings_seconds
import me.d3sox.nevumi.resources.settings_semitones_down
import me.d3sox.nevumi.resources.settings_semitones_up
import me.d3sox.nevumi.resources.settings_server_files_description
import me.d3sox.nevumi.resources.settings_show_audio_info
import me.d3sox.nevumi.resources.settings_show_audio_info_description
import me.d3sox.nevumi.resources.settings_show_options_buttons_description
import me.d3sox.nevumi.resources.settings_show_previous_tracks
import me.d3sox.nevumi.resources.settings_show_previous_tracks_description
import me.d3sox.nevumi.resources.settings_soundcloud
import me.d3sox.nevumi.resources.settings_soundcloud_description
import me.d3sox.nevumi.resources.settings_source_local
import me.d3sox.nevumi.resources.settings_theme_dark
import me.d3sox.nevumi.resources.settings_theme_follow_system
import me.d3sox.nevumi.resources.settings_theme_light
import me.d3sox.nevumi.resources.settings_title_scroll_speed
import me.d3sox.nevumi.resources.settings_tunnel_description
import me.d3sox.nevumi.resources.settings_undo_backups_note
import me.d3sox.nevumi.resources.settings_unmetered_only
import me.d3sox.nevumi.resources.settings_waveforms
import me.d3sox.nevumi.resources.settings_waveforms_description
import me.d3sox.nevumi.resources.show_options_buttons
import me.d3sox.nevumi.resources.songs
import me.d3sox.nevumi.resources.source_priority
import me.d3sox.nevumi.resources.swipe_left
import me.d3sox.nevumi.resources.swipe_right
import me.d3sox.nevumi.resources.theme
import me.d3sox.nevumi.resources.tunnel
import me.d3sox.nevumi.resources.undo_backup_storage
import me.d3sox.nevumi.history.ScrobblingService
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource

@Composable
internal fun QueueAction.label(): String = stringResource(when (this) {
    QueueAction.PLAY_NEXT -> Res.string.play_next
    QueueAction.PLAY_AFTER -> Res.string.play_after
    QueueAction.APPEND -> Res.string.add_to_queue
})

/** Settings navigation uses these page ids; this resolves the localized title shown for a page. */
@Composable
internal fun settingsPageTitle(page: String): String = stringResource(when (page) {
    "Appearance" -> Res.string.settings_appearance
    "Library & gestures" -> Res.string.settings_library_gestures
    "Local music" -> Res.string.settings_local_music
    "Playback" -> Res.string.playback
    "Waveforms" -> Res.string.settings_waveforms
    "Downloads & cache" -> Res.string.settings_downloads_cache
    "Listening & scrobbling" -> Res.string.settings_listening_scrobbling
    "Files & recovery" -> Res.string.settings_files_recovery
    "Backup & restore" -> Res.string.settings_backup_restore
    "Music server" -> Res.string.music_server
    "Manage downloads" -> Res.string.settings_manage_downloads
    "Audio effects" -> Res.string.audio_effects
    "Server files (SFTP)" -> Res.string.server_files_sftp
    "Tunnel" -> Res.string.tunnel
    "SoundCloud" -> Res.string.settings_soundcloud
    "Paired devices" -> Res.string.paired_devices
    "Saved queues" -> Res.string.saved_queues
    "About Nevumi" -> Res.string.about_nevumi
    "Customize Home" -> Res.string.customize_home
    "Library tabs" -> Res.string.library_tabs
    "Connect Last.fm" -> Res.string.connect_last_fm
    "Connect ListenBrainz" -> Res.string.connect_listenbrainz
    "Search settings" -> Res.string.settings_search_settings
    else -> return page
})

@Composable
internal fun AppSettings(state: AppState, controller: MusicController, server: () -> Unit, downloads: () -> Unit,
    savedQueues: () -> Unit, audioEffects: () -> Unit, serverFiles: () -> Unit, tunnel: () -> Unit, pairedDevices: () -> Unit,
    fileRecovery: () -> Unit, soundCloud: () -> Unit = {}, categoryState: MutableState<String?>, onAddMusic: (() -> Unit)? = null, folderPicker: FolderPicker, dismiss: () -> Unit) {
    val settings = state.preferences
    val trackActions = TrackAction.entries.filter { it != TrackAction.PLAY }.map { it to it.displayLabel() }
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
            catch (error: Exception) { folderError = error.message ?: getString(Res.string.settings_could_not_select_folder) }
            finally { pickingFolder = false }
        }
    }
    var customizeHome by rememberSaveable { mutableStateOf(false) }
    var libraryTabs by rememberSaveable { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var clearingRatings by remember { mutableStateOf(false) }
    val ratingCount = remember(state.tracks) { state.tracks.count { it.rating != 0 } }
    var clearingFavorites by remember { mutableStateOf(false) }
    val favoriteIds = remember(state.tracks) { state.tracks.filter { it.favorite }.map { it.id } }
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
                "SoundCloud" -> soundCloud()
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
    else if (shownCategory == "Library tabs") { LibraryTabsDialog(settings.libraryTabs, controller, settings.bottomNavigationTabs) { libraryTabs = false } }
    else if (shownCategory == "About Nevumi") { AboutNevumi { about = false } }
    else if (shownCategory == "Connect ListenBrainz") { ConnectListenBrainz(controller, back) }
    else if (shownCategory == "Connect Last.fm") { ConnectLastFm(controller, back) }
    else {
    val category = shownCategory
    SettingsScreen(onDismissRequest = back, title = { Text(category?.let { settingsPageTitle(it) } ?: stringResource(Res.string.settings)) },
        actions = { AppIconButton(AppIcon.SEARCH, stringResource(Res.string.settings_search_settings)) { search = ""; searching = true } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (category == "Appearance") {
            SettingsGroup {
            SettingChoice(Res.string.theme, settings.themeMode, listOf(ThemeMode.SYSTEM to stringResource(Res.string.settings_theme_follow_system), ThemeMode.LIGHT to stringResource(Res.string.settings_theme_light),
                ThemeMode.DARK to stringResource(Res.string.settings_theme_dark))) { mode ->
                controller.updatePreferences { it.copy(themeMode = mode) }
            }
            SettingsToggle(stringResource(Res.string.settings_amoled_black), AppIcon.DARK_THEME, stringResource(Res.string.settings_amoled_black_description), settings.amoledBlack) {
                enabled -> controller.updatePreferences { it.copy(amoledBlack = enabled) }
            }
            SettingChoice(Res.string.colors, settings.colors, listOf(ColorMode.SYSTEM to stringResource(Res.string.settings_colors_system), ColorMode.ARTWORK to stringResource(Res.string.settings_colors_artwork),
                ColorMode.CUSTOM to stringResource(Res.string.settings_colors_custom))) { colors ->
                controller.updatePreferences { it.copy(colors = colors) }
            }
            if (settings.colors == ColorMode.CUSTOM) CustomColorPicker(settings.customColor) { color ->
                controller.updatePreferences { it.copy(customColor = color) }
            }
            SettingsSlider(stringResource(Res.string.settings_title_scroll_speed), AppIcon.TEXT_SCROLL, settings.marqueeSpeed.toFloat(), 0.5f..3f, 9,
                { value -> "${(value * 100).roundToInt() / 100.0}×" }) { value ->
                controller.updatePreferences { it.copy(marqueeSpeed = (value * 4).roundToInt() / 4.0) }
            }
                        }
}
            if (category == "Library & gestures") {
            SettingsGroup {
            SettingChoice(Res.string.on_launch, settings.startupTab,
                listOf(null to stringResource(Res.string.settings_last_opened_page)) + settings.libraryTabs.map { it to stringResource(it.label) }) { tab ->
                controller.updatePreferences { it.copy(startupTab = tab) }
            }
            SettingChoice(Res.string.swipe_right, settings.swipeRight, trackActions) { action ->
                controller.updatePreferences { it.copy(swipeRight = action) }
            }
            SettingChoice(Res.string.swipe_left, settings.swipeLeft, trackActions) { action ->
                controller.updatePreferences { it.copy(swipeLeft = action) }
            }
            SettingChoice(Res.string.quick_action, settings.quickAction, trackActions) { action ->
                controller.updatePreferences { it.copy(quickAction = action) }
            }
            SettingsToggle(stringResource(Res.string.show_options_buttons), AppIcon.MORE, stringResource(Res.string.settings_show_options_buttons_description), settings.showMoreButtons) {
                enabled -> controller.updatePreferences { it.copy(showMoreButtons = enabled) }
            }
            SettingsToggle(stringResource(Res.string.settings_remember_scroll_positions), AppIcon.LIBRARY, stringResource(Res.string.settings_remember_scroll_positions_description), settings.rememberScrollPositions) {
                enabled -> controller.updatePreferences { it.copy(rememberScrollPositions = enabled) }
            }
                SettingsLink(stringResource(Res.string.customize_home), AppIcon.HOME, stringResource(Res.string.settings_customize_home_description)) { customizeHome = true }
                SettingsLink(stringResource(Res.string.library_tabs), AppIcon.TABS, stringResource(Res.string.settings_library_tabs_description)) { libraryTabs = true }
                        }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(Res.string.settings_clear_favorites), AppIcon.FAVORITE, enabled = favoriteIds.isNotEmpty(),
                    style = ActionStyle.DESTRUCTIVE) { clearingFavorites = true }
                ActionButton(stringResource(Res.string.settings_clear_ratings), AppIcon.RATING, enabled = ratingCount > 0,
                    style = ActionStyle.DESTRUCTIVE) { clearingRatings = true }
            }
}
            if (category == "Playback") {
            SettingsGroup {
            SettingsSlider(stringResource(Res.string.settings_playback_speed), AppIcon.SPEED, settings.speed.toFloat(), 0.5f..2f, 29,
                { value -> "${(value * 100).roundToInt() / 100.0}×" }) { controller.setSpeed(it.toDouble()) }
            SettingsSlider(stringResource(Res.string.settings_playback_pitch), AppIcon.PITCH, (12 * log2(settings.pitch)).toFloat(), -12f..12f, 23,
                { value -> when (val semitones = value.roundToInt()) {
                    0 -> stringResource(Res.string.settings_original_pitch)
                    else -> if (semitones > 0) pluralStringResource(Res.plurals.settings_semitones_up, semitones, semitones)
                        else pluralStringResource(Res.plurals.settings_semitones_down, -semitones, -semitones)
                } }) { controller.setPitch(2.0.pow(it.roundToInt() / 12.0)) }
            SettingsLink(stringResource(Res.string.settings_waveforms), AppIcon.WAVEFORM, stringResource(Res.string.settings_waveforms_description)) { categoryState.value = "Waveforms" }
            SettingsToggle(stringResource(Res.string.settings_show_audio_info), AppIcon.INFO, stringResource(Res.string.settings_show_audio_info_description), settings.showAudioInfo) {
                enabled -> controller.updatePreferences { it.copy(showAudioInfo = enabled) }
            }
            LyricsSettings(settings, controller)
            SettingsLink(stringResource(Res.string.audio_effects), AppIcon.EQUALIZER, stringResource(Res.string.settings_audio_effects_description), audioEffects)
            SettingsSlider(stringResource(Res.string.crossfade), AppIcon.CROSSFADE, settings.crossfadeDurationMs / 1_000f, 0f..12f, 11,
                { value -> when (val seconds = value.roundToInt()) { 0 -> stringResource(Res.string.off); else -> pluralStringResource(Res.plurals.settings_seconds, seconds, seconds) } }) {
                controller.updatePreferences { preferences -> preferences.copy(crossfadeDurationMs = it.roundToInt() * 1_000L) }
            }
            SettingsToggle(stringResource(Res.string.settings_gapless_playback), AppIcon.MUSIC, stringResource(Res.string.settings_gapless_playback_description), settings.gaplessPlayback) {
                enabled -> controller.updatePreferences { it.copy(gaplessPlayback = enabled) }
            }
            SettingChoice(Res.string.settings_replay_gain, settings.replayGain.mode, listOf(ReplayGainMode.OFF to stringResource(Res.string.off),
                ReplayGainMode.TRACK to stringResource(Res.string.settings_replay_gain_track),
                ReplayGainMode.ALBUM to stringResource(Res.string.settings_replay_gain_album))) { mode ->
                controller.updatePreferences { it.copy(replayGain = it.replayGain.copy(mode = mode)) }
            }
            if (settings.replayGain.mode != ReplayGainMode.OFF) {
                SettingsSlider(stringResource(Res.string.settings_replay_gain_preamp), AppIcon.VOLUME, settings.replayGain.preampDb.toFloat(), -15f..15f, 29,
                    { value -> "${value.roundToInt()} dB" }) { value ->
                    controller.updatePreferences { it.copy(replayGain = it.replayGain.copy(preampDb = value.roundToInt().toDouble())) }
                }
            }
            SettingsToggle(stringResource(Res.string.settings_show_previous_tracks), AppIcon.HISTORY, stringResource(Res.string.settings_show_previous_tracks_description), settings.showPreviousQueueTracks) {
                enabled -> controller.updatePreferences { it.copy(showPreviousQueueTracks = enabled) }
            }
            SettingsToggle(stringResource(Res.string.settings_confirm_queue_jumps), AppIcon.CONFIRM, stringResource(Res.string.settings_confirm_queue_jumps_description), settings.confirmQueueJumps) {
                enabled -> controller.updatePreferences { it.copy(confirmQueueJumps = enabled) }
            }
            SettingsToggle(stringResource(Res.string.settings_confirm_up_next_interruption), AppIcon.CONFIRM, stringResource(Res.string.settings_confirm_up_next_interruption_description), settings.confirmNextUpInterruption) {
                enabled -> controller.updatePreferences { it.copy(confirmNextUpInterruption = enabled) }
            }
            if (settings.showPreviousQueueTracks) {
                SettingsSlider(stringResource(Res.string.settings_previous_track_limit), AppIcon.REPEAT_COUNT, settings.previousQueueTrackLimit.toFloat(), 1f..100f, 98,
                    format = { it.roundToInt().toString() }) { value ->
                    controller.updatePreferences { it.copy(previousQueueTrackLimit = value.roundToInt()) }
                }
            }
                        }
}
            if (category == "Waveforms") { WaveformSettings(state, controller) }
            if (category == "Downloads & cache") {
            SettingsGroup {
            val sourceNames = mapOf(SourceKind.LOCAL to stringResource(Res.string.settings_source_local), SourceKind.DOWNLOAD to stringResource(Res.string.downloads),
                SourceKind.SERVER to stringResource(Res.string.server))
            val sourceOrders = SourceKind.entries.flatMap { first ->
                SourceKind.entries.filter { it != first }.map { second ->
                    listOf(first, second, SourceKind.entries.first { it != first && it != second })
                }
            }
            SettingChoice(Res.string.source_priority, settings.sourcePriority, sourceOrders.map { order -> order to order.joinToString(" → ") { source ->
                sourceNames.getValue(source)
            } }) { order -> controller.updatePreferences { it.copy(sourcePriority = order) } }
            SettingChoice(Res.string.playback_cache, settings.cacheBytes, listOf(0L to stringResource(Res.string.off), 268_435_456L to "256 MiB",
                1_073_741_824L to "1 GiB", 2_147_483_648L to "2 GiB", 5_368_709_120L to "5 GiB")) { bytes ->
                controller.updatePreferences { it.copy(cacheBytes = bytes) }
            }
            SettingChoice(Res.string.download_network, settings.downloadOnMetered,
                listOf(false to stringResource(Res.string.settings_unmetered_only), true to stringResource(Res.string.settings_any_network))) { allowed ->
                controller.updatePreferences { it.copy(downloadOnMetered = allowed) }
            }
                SettingsLink(stringResource(Res.string.settings_manage_downloads), AppIcon.DOWNLOAD, stringResource(Res.string.settings_manage_downloads_description), downloads)
                        }
}
            if (category == "Listening & scrobbling") {
            SettingsGroup {
            SettingChoice(Res.string.record_listening_history, settings.recordListeningHistory,
                listOf(true to stringResource(Res.string.on), false to stringResource(Res.string.off))) { enabled ->
                controller.updatePreferences { it.copy(recordListeningHistory = enabled) }
            }
                        }
            ActionButton(stringResource(Res.string.settings_clear_listening_history), AppIcon.REMOVE, style = ActionStyle.DESTRUCTIVE) { clearHistory = true }
                if (controller.scrobblingAvailable) ScrobblingSettingsContent(state, controller) {
                    categoryState.value = when (it) {
                        ScrobblingService.LISTENBRAINZ -> "Connect ListenBrainz"
                        ScrobblingService.LASTFM -> "Connect Last.fm"
                        ScrobblingService.NAVIDROME -> "Connect Music server"
                    }
                }
}
            if (category == "Files & recovery") {
            SettingsGroup {
                if (state.fileEdits.any { it.needsRecovery || (!it.applied && !it.undone && !it.writing) })
                    SettingsLink(stringResource(Res.string.settings_review_interrupted_edit), AppIcon.UNDO, stringResource(Res.string.settings_review_interrupted_edit_description), fileRecovery)
            if (controller.fileEditingAvailable) {
                SettingChoice(Res.string.keep_undo_backups, settings.undoRetentionDays, listOf(7, 30, 90).map { it to pluralStringResource(Res.plurals.settings_days, it, it) }) { days ->
                    controller.updatePreferences { it.copy(undoRetentionDays = days) }
                }
                SettingChoice(Res.string.undo_backup_storage, settings.undoBackupBytes, listOf(268_435_456L to "256 MiB",
                    1_073_741_824L to "1 GiB", 5_368_709_120L to "5 GiB")) { bytes ->
                    controller.updatePreferences { it.copy(undoBackupBytes = bytes) }
                }
            }
                if (controller.serverFilesAvailable) SettingsLink(stringResource(Res.string.server_files_sftp), AppIcon.SERVER_FILES, stringResource(Res.string.settings_server_files_description), serverFiles)
                        }
            if (controller.fileEditingAvailable)
                Text(stringResource(Res.string.settings_undo_backups_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
            if (category == "Local music") {
                Text(stringResource(Res.string.settings_local_music_description),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !state.loading && state.localScan == null,
                    onClick = { if (onAddMusic != null) onAddMusic() else controller.rescanLocalMusic() }) {
                    Icon(AppIcon.SEARCH.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.settings_scan_music))
                }
                state.localScan?.let { scan ->
                    LinearProgressIndicator(progress = { if (scan.total == 0) 0f else scan.processed.toFloat() / scan.total }, Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.settings_scanning_music, scan.processed, scan.total), style = MaterialTheme.typography.bodySmall)
                } ?: state.lastLocalScanCount?.let { count ->
                    Text(stringResource(Res.string.settings_scan_complete, countLabel(count, Res.plurals.songs)), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(Res.string.settings_folder_rules_note),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FolderRuleList(stringResource(Res.string.settings_include_folders), settings.includeFolders, stringResource(Res.string.settings_add_included_folder),
                    Res.string.settings_remove_included_folder, pickingFolder, stringResource(Res.string.settings_include_folders_empty), { pickFolder(true) }) { folder ->
                    controller.updateLocalFolders(settings.includeFolders - folder, settings.excludeFolders)
                }
                HorizontalDivider()
                FolderRuleList(stringResource(Res.string.settings_exclude_folders), settings.excludeFolders, stringResource(Res.string.settings_add_excluded_folder),
                    Res.string.settings_remove_excluded_folder, pickingFolder, stringResource(Res.string.settings_exclude_folders_empty), { pickFolder(false) }) { folder ->
                    controller.updateLocalFolders(settings.includeFolders, settings.excludeFolders - folder)
                }
                if (pickingFolder) LinearProgressIndicator(Modifier.fillMaxWidth())
                folderError?.let { error ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        AppIconButton(AppIcon.CLOSE, stringResource(Res.string.settings_dismiss_folder_error)) { folderError = null }
                    }
                }
            }
            if (category == "Backup & restore") BackupSettings(controller)
            if (category == null) {
                SettingsGroup {
                SettingsLink(stringResource(Res.string.settings_appearance), AppIcon.THEME, stringResource(Res.string.settings_appearance_description)) { categoryState.value = "Appearance" }
                SettingsLink(stringResource(Res.string.settings_local_music), AppIcon.FOLDER, stringResource(Res.string.settings_local_music_summary)) { categoryState.value = "Local music" }
                SettingsLink(stringResource(Res.string.settings_library_gestures), AppIcon.GESTURES, stringResource(Res.string.settings_library_gestures_description)) { categoryState.value = "Library & gestures" }
                SettingsLink(stringResource(Res.string.playback), AppIcon.PLAY, stringResource(Res.string.settings_playback_description)) { categoryState.value = "Playback" }
                }
                SettingsGroup {
                SettingsLink(stringResource(Res.string.settings_downloads_cache), AppIcon.DOWNLOAD, stringResource(Res.string.settings_downloads_cache_description)) { categoryState.value = "Downloads & cache" }
                SettingsLink(stringResource(Res.string.music_server), AppIcon.SERVER, stringResource(Res.string.settings_music_server_description), server)
                if (controller.tunnel != null) SettingsLink(stringResource(Res.string.tunnel), AppIcon.TAILSCALE, stringResource(Res.string.settings_tunnel_description), tunnel)
                if (controller.soundCloudAvailable) SettingsLink(stringResource(Res.string.settings_soundcloud), AppIcon.CLOUD, stringResource(Res.string.settings_soundcloud_description), soundCloud)
                SettingsLink(stringResource(Res.string.settings_listening_scrobbling), AppIcon.HISTORY, stringResource(Res.string.settings_listening_scrobbling_description)) { categoryState.value = "Listening & scrobbling" }
                }
                SettingsGroup {
                if (controller.pairedDevices != null) SettingsLink(stringResource(Res.string.paired_devices), AppIcon.DEVICES, stringResource(Res.string.settings_paired_devices_description), pairedDevices)
                if (controller.fileEditingAvailable || controller.serverFilesAvailable) SettingsLink(stringResource(Res.string.settings_files_recovery), AppIcon.UNDO, stringResource(Res.string.settings_files_recovery_description)) { categoryState.value = "Files & recovery" }
                SettingsLink(stringResource(Res.string.saved_queues), AppIcon.QUEUE, stringResource(Res.string.settings_saved_queues_description), savedQueues)
                if (controller.backupController.backupAvailable) SettingsLink(stringResource(Res.string.settings_backup_restore), AppIcon.BACKUP_FILES,
                    stringResource(Res.string.settings_backup_restore_description)) { categoryState.value = "Backup & restore" }
                SettingsLink(stringResource(Res.string.about_nevumi), AppIcon.INFO, stringResource(Res.string.settings_about_nevumi_description)) { about = true }
                }
            }
        }
    })
    }
    }
    if (clearingRatings) AlertDialog(onDismissRequest = { clearingRatings = false },
        title = { Text(stringResource(Res.string.settings_clear_all_ratings)) },
        text = { Text(stringResource(Res.string.settings_clear_all_ratings_message, countLabel(ratingCount, Res.plurals.songs))) },
        confirmButton = { TextButton(onClick = { controller.clearRatings(); clearingRatings = false },
            enabled = ratingCount > 0) { Text(stringResource(Res.string.clear)) } },
        dismissButton = { TextButton(onClick = { clearingRatings = false }) { Text(stringResource(Res.string.cancel)) } })
    if (clearingFavorites) AlertDialog(onDismissRequest = { clearingFavorites = false },
        title = { Text(stringResource(Res.string.settings_clear_all_favorites)) },
        text = { Text(stringResource(Res.string.settings_clear_all_favorites_message, countLabel(favoriteIds.size, Res.plurals.songs))) },
        confirmButton = {
            TextButton(onClick = { controller.setFavorite(favoriteIds, false); clearingFavorites = false },
                enabled = favoriteIds.isNotEmpty()) { Text(stringResource(Res.string.clear)) }
        },
        dismissButton = { TextButton(onClick = { clearingFavorites = false }) { Text(stringResource(Res.string.cancel)) } })
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text(stringResource(Res.string.settings_clear_listening_history_question)) },
        text = { Text(stringResource(Res.string.settings_clear_listening_history_message)) },
        confirmButton = { TextButton(onClick = { controller.clearListeningHistory(); clearHistory = false }) { Text(stringResource(Res.string.settings_clear_history)) } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text(stringResource(Res.string.cancel)) } })
}

@Composable
internal fun <T> SettingChoice(label: StringResource, selected: T, choices: List<Pair<T, String>>, card: Boolean = true, change: (T) -> Unit) {
    SettingChoice(stringResource(label), label, settingsIcon(label), selected, choices, card, change)
}

@Composable
internal fun <T> SettingChoice(label: String, selected: T, choices: List<Pair<T, String>>, card: Boolean = true, change: (T) -> Unit) {
    val key = settingKey(label)
    SettingChoice(label, key, key?.let(::settingsIcon) ?: AppIcon.SETTINGS, selected, choices, card, change)
}

@Composable
private fun <T> SettingChoice(label: String, key: StringResource?, icon: AppIcon, selected: T, choices: List<Pair<T, String>>,
    card: Boolean, change: (T) -> Unit) {
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
        is me.d3sox.nevumi.soundcloud.ScGateFormat -> AppIcon.MUSIC
        is me.d3sox.nevumi.soundcloud.ScGateBrowser -> when (value) {
            me.d3sox.nevumi.soundcloud.ScGateBrowser.HEADLESS -> AppIcon.CODE
            me.d3sox.nevumi.soundcloud.ScGateBrowser.XVFB -> AppIcon.HIDDEN
            me.d3sox.nevumi.soundcloud.ScGateBrowser.HEADED -> AppIcon.VISIBLE
        }
        is me.d3sox.nevumi.soundcloud.SoundCloudDestination -> when (value) {
            me.d3sox.nevumi.soundcloud.SoundCloudDestination.ASK -> AppIcon.CONFIRM
            me.d3sox.nevumi.soundcloud.SoundCloudDestination.SERVER -> AppIcon.SERVER
            me.d3sox.nevumi.soundcloud.SoundCloudDestination.DEVICE -> AppIcon.DEVICE
        }
        is SourceKind -> when (value) { SourceKind.LOCAL -> AppIcon.DEVICE; SourceKind.DOWNLOAD -> AppIcon.DOWNLOAD; SourceKind.SERVER -> AppIcon.CLOUD }
        is List<*> -> when (value.firstOrNull()) { SourceKind.LOCAL -> AppIcon.DEVICE; SourceKind.DOWNLOAD -> AppIcon.DOWNLOAD; SourceKind.SERVER -> AppIcon.CLOUD; else -> null }
        is Boolean -> if (key == Res.string.download_network) { if (value) AppIcon.NETWORK else AppIcon.WIFI }
            else if (value) AppIcon.CHECK else AppIcon.CLOSE
        is Long -> when (key) { Res.string.playback_cache, Res.string.undo_backup_storage -> AppIcon.STORAGE; Res.string.music_library -> AppIcon.FOLDER; else -> null }
        is Int -> if (key == Res.string.keep_undo_backups) AppIcon.YEAR else null
        is me.d3sox.nevumi.history.ListeningPeriod -> if (value.days == null) AppIcon.HISTORY else AppIcon.YEAR
        null -> when (key) { Res.string.on_launch -> AppIcon.HISTORY; Res.string.music_sources -> AppIcon.LIBRARY
            Res.string.sources_gate_browser -> AppIcon.SERVER; else -> null }
        is String -> if (key == Res.string.statistics_grouping) when (value) {
            "Songs" -> AppIcon.MUSIC; "Artists" -> AppIcon.ARTIST; "Albums" -> AppIcon.ALBUM; "Genres" -> AppIcon.GENRE; else -> null
        } else if (key == Res.string.ssh_authentication) { if (value == "SSH private key") AppIcon.KEY else AppIcon.LOCK } else null
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
        if (card && LocalSettingsCards.current) SettingsCard(icon, onClick = { expanded = true }) {
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
private fun FolderRuleList(title: String, folders: List<String>, addDescription: String, removeDescription: StringResource, busy: Boolean,
    emptyMessage: String, add: () -> Unit, remove: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (folders.isEmpty()) Text(emptyMessage, style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    folders.forEach { folder ->
        ListItem(headlineContent = { Text(folder.trimEnd('/').substringAfterLast('/')) },
            supportingContent = { Text(folder) }, leadingContent = { Icon(AppIcon.FOLDER.vector, null) },
            trailingContent = { AppIconButton(AppIcon.REMOVE, stringResource(removeDescription, folder), enabled = !busy) { remove(folder) } })
    }
    TextButton(onClick = add, enabled = !busy, modifier = Modifier.semantics { contentDescription = addDescription }) {
        Icon(AppIcon.PLUS.vector, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.settings_add_folder))
    }
}

@Composable
internal fun SettingsSlider(label: String, icon: AppIcon, selected: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int = 0, format: @Composable (Float) -> String, change: (Float) -> Unit) {
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
