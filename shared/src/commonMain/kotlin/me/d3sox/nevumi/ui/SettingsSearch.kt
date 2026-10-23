package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.about_nevumi
import me.d3sox.nevumi.resources.audio_effects
import me.d3sox.nevumi.resources.colors
import me.d3sox.nevumi.resources.connect_last_fm
import me.d3sox.nevumi.resources.connect_listenbrainz
import me.d3sox.nevumi.resources.crossfade
import me.d3sox.nevumi.resources.customize_home
import me.d3sox.nevumi.resources.download_network
import me.d3sox.nevumi.resources.keep_undo_backups
import me.d3sox.nevumi.resources.library_tabs
import me.d3sox.nevumi.resources.music_server
import me.d3sox.nevumi.resources.on_launch
import me.d3sox.nevumi.resources.paired_devices
import me.d3sox.nevumi.resources.playback_cache
import me.d3sox.nevumi.resources.quick_action
import me.d3sox.nevumi.resources.record_listening_history
import me.d3sox.nevumi.resources.saved_queues
import me.d3sox.nevumi.resources.server_files_sftp
import me.d3sox.nevumi.resources.settings_amoled_black
import me.d3sox.nevumi.resources.settings_clear_favorites
import me.d3sox.nevumi.resources.settings_clear_listening_history
import me.d3sox.nevumi.resources.settings_clear_ratings
import me.d3sox.nevumi.resources.settings_confirm_queue_jumps
import me.d3sox.nevumi.resources.settings_confirm_up_next_interruption
import me.d3sox.nevumi.resources.settings_exclude_folders
import me.d3sox.nevumi.resources.settings_include_folders
import me.d3sox.nevumi.resources.settings_keywords_about_nevumi
import me.d3sox.nevumi.resources.settings_keywords_amoled_black
import me.d3sox.nevumi.resources.settings_keywords_audio_effects
import me.d3sox.nevumi.resources.settings_keywords_clear_favorites
import me.d3sox.nevumi.resources.settings_keywords_clear_listening_history
import me.d3sox.nevumi.resources.settings_keywords_clear_ratings
import me.d3sox.nevumi.resources.settings_keywords_colors
import me.d3sox.nevumi.resources.settings_keywords_confirm_queue_jumps
import me.d3sox.nevumi.resources.settings_keywords_confirm_up_next_interruption
import me.d3sox.nevumi.resources.settings_keywords_connect_last_fm
import me.d3sox.nevumi.resources.settings_keywords_connect_listenbrainz
import me.d3sox.nevumi.resources.settings_keywords_crossfade
import me.d3sox.nevumi.resources.settings_keywords_replay_gain
import me.d3sox.nevumi.resources.settings_replay_gain
import me.d3sox.nevumi.resources.settings_keywords_customize_home
import me.d3sox.nevumi.resources.settings_keywords_download_network
import me.d3sox.nevumi.resources.settings_keywords_exclude_folders
import me.d3sox.nevumi.resources.settings_keywords_include_folders
import me.d3sox.nevumi.resources.settings_keywords_keep_undo_backups
import me.d3sox.nevumi.resources.settings_keywords_library_tabs
import me.d3sox.nevumi.resources.settings_keywords_manage_downloads
import me.d3sox.nevumi.resources.settings_keywords_music_server
import me.d3sox.nevumi.resources.settings_keywords_on_launch
import me.d3sox.nevumi.resources.settings_keywords_or_after
import me.d3sox.nevumi.resources.settings_keywords_paired_devices
import me.d3sox.nevumi.resources.settings_keywords_playback_cache
import me.d3sox.nevumi.resources.settings_keywords_playback_pitch
import me.d3sox.nevumi.resources.settings_keywords_playback_speed
import me.d3sox.nevumi.resources.settings_keywords_prepare_all_waveforms
import me.d3sox.nevumi.resources.settings_keywords_prepare_automatically
import me.d3sox.nevumi.resources.settings_keywords_previous_track_limit
import me.d3sox.nevumi.resources.settings_keywords_quick_action
import me.d3sox.nevumi.resources.settings_keywords_record_listening_history
import me.d3sox.nevumi.resources.settings_keywords_remember_scroll_positions
import me.d3sox.nevumi.resources.settings_keywords_saved_queues
import me.d3sox.nevumi.resources.settings_keywords_scan_music
import me.d3sox.nevumi.resources.settings_keywords_scrobble_after
import me.d3sox.nevumi.resources.settings_keywords_server_files
import me.d3sox.nevumi.resources.settings_keywords_show_audio_info
import me.d3sox.nevumi.resources.settings_keywords_show_options_buttons
import me.d3sox.nevumi.resources.settings_keywords_show_previous_tracks
import me.d3sox.nevumi.resources.settings_keywords_soundcloud
import me.d3sox.nevumi.resources.settings_keywords_source_priority
import me.d3sox.nevumi.resources.settings_keywords_swipe_left
import me.d3sox.nevumi.resources.settings_keywords_swipe_right
import me.d3sox.nevumi.resources.settings_keywords_theme
import me.d3sox.nevumi.resources.settings_keywords_title_scroll_speed
import me.d3sox.nevumi.resources.settings_keywords_tunnel
import me.d3sox.nevumi.resources.settings_keywords_undo_backup_storage
import me.d3sox.nevumi.resources.settings_manage_downloads
import me.d3sox.nevumi.resources.settings_or_after
import me.d3sox.nevumi.resources.settings_playback_pitch
import me.d3sox.nevumi.resources.settings_playback_speed
import me.d3sox.nevumi.resources.settings_prepare_all_waveforms
import me.d3sox.nevumi.resources.settings_prepare_automatically
import me.d3sox.nevumi.resources.settings_previous_track_limit
import me.d3sox.nevumi.resources.settings_remember_scroll_positions
import me.d3sox.nevumi.resources.settings_scan_music
import me.d3sox.nevumi.resources.settings_scrobble_after
import me.d3sox.nevumi.resources.settings_search_hint
import me.d3sox.nevumi.resources.settings_search_no_results
import me.d3sox.nevumi.resources.settings_search_query
import me.d3sox.nevumi.resources.settings_search_settings
import me.d3sox.nevumi.resources.settings_show_audio_info
import me.d3sox.nevumi.resources.lyrics_keywords_online
import me.d3sox.nevumi.resources.lyrics_setting_online
import me.d3sox.nevumi.resources.settings_show_previous_tracks
import me.d3sox.nevumi.resources.settings_soundcloud
import me.d3sox.nevumi.resources.settings_title_scroll_speed
import me.d3sox.nevumi.resources.show_options_buttons
import me.d3sox.nevumi.resources.source_priority
import me.d3sox.nevumi.resources.swipe_left
import me.d3sox.nevumi.resources.swipe_right
import me.d3sox.nevumi.resources.theme
import me.d3sox.nevumi.resources.tunnel
import me.d3sox.nevumi.resources.undo_backup_storage
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal data class SettingResult(val title: String, val category: String, val icon: AppIcon,
    val keywords: String = "", val destination: String)

/** A searchable setting. [category] and [destination] are settings page ids, see [settingsPageTitle]. */
private class SettingEntry(val title: StringResource, val category: String, val icon: AppIcon,
    val keywords: StringResource, val destination: String = category)

@Composable
internal fun SettingsSearch(query: String, onQuery: (String) -> Unit, back: () -> Unit,
    controller: MusicController, open: (SettingResult) -> Unit) {
    val entries = remember(controller) {
        buildList {
            fun category(name: String, icon: AppIcon, vararg titles: Pair<StringResource, StringResource>) {
                titles.forEach { (title, keywords) -> add(SettingEntry(title, name, icon, keywords)) }
            }
            category("Appearance", AppIcon.THEME, Res.string.theme to Res.string.settings_keywords_theme,
                Res.string.settings_amoled_black to Res.string.settings_keywords_amoled_black,
                Res.string.colors to Res.string.settings_keywords_colors,
                Res.string.settings_title_scroll_speed to Res.string.settings_keywords_title_scroll_speed)
            listOf(Res.string.on_launch to Res.string.settings_keywords_on_launch,
                Res.string.swipe_right to Res.string.settings_keywords_swipe_right, Res.string.swipe_left to Res.string.settings_keywords_swipe_left,
                Res.string.quick_action to Res.string.settings_keywords_quick_action,
                Res.string.show_options_buttons to Res.string.settings_keywords_show_options_buttons).forEach { (title, keywords) ->
                add(SettingEntry(title, "Library & gestures", settingsIcon(title), keywords))
            }
            add(SettingEntry(Res.string.customize_home, "Library & gestures", AppIcon.HOME, Res.string.settings_keywords_customize_home, "Customize Home"))
            add(SettingEntry(Res.string.library_tabs, "Library & gestures", AppIcon.TABS, Res.string.settings_keywords_library_tabs, "Library tabs"))
            add(SettingEntry(Res.string.settings_clear_favorites, "Library & gestures", AppIcon.FAVORITE, Res.string.settings_keywords_clear_favorites))
            add(SettingEntry(Res.string.settings_clear_ratings, "Library & gestures", AppIcon.RATING, Res.string.settings_keywords_clear_ratings))
            add(SettingEntry(Res.string.settings_remember_scroll_positions, "Library & gestures", AppIcon.LIBRARY,
                Res.string.settings_keywords_remember_scroll_positions))
            category("Local music", AppIcon.FOLDER, Res.string.settings_scan_music to Res.string.settings_keywords_scan_music,
                Res.string.settings_include_folders to Res.string.settings_keywords_include_folders,
                Res.string.settings_exclude_folders to Res.string.settings_keywords_exclude_folders)
            category("Playback", AppIcon.PLAY, Res.string.settings_playback_speed to Res.string.settings_keywords_playback_speed,
                Res.string.settings_playback_pitch to Res.string.settings_keywords_playback_pitch,
                Res.string.crossfade to Res.string.settings_keywords_crossfade,
                Res.string.settings_replay_gain to Res.string.settings_keywords_replay_gain,
                Res.string.settings_show_audio_info to Res.string.settings_keywords_show_audio_info,
                Res.string.lyrics_setting_online to Res.string.lyrics_keywords_online,
                Res.string.settings_show_previous_tracks to Res.string.settings_keywords_show_previous_tracks,
                Res.string.settings_previous_track_limit to Res.string.settings_keywords_previous_track_limit,
                Res.string.settings_confirm_queue_jumps to Res.string.settings_keywords_confirm_queue_jumps,
                Res.string.settings_confirm_up_next_interruption to Res.string.settings_keywords_confirm_up_next_interruption)
            category("Waveforms", AppIcon.WAVEFORM, Res.string.settings_prepare_all_waveforms to Res.string.settings_keywords_prepare_all_waveforms,
                Res.string.settings_prepare_automatically to Res.string.settings_keywords_prepare_automatically)
            add(SettingEntry(Res.string.audio_effects, "Playback", AppIcon.EQUALIZER, Res.string.settings_keywords_audio_effects, "Audio effects"))
            category("Downloads & cache", AppIcon.DOWNLOAD, Res.string.source_priority to Res.string.settings_keywords_source_priority,
                Res.string.playback_cache to Res.string.settings_keywords_playback_cache,
                Res.string.download_network to Res.string.settings_keywords_download_network)
            add(SettingEntry(Res.string.settings_manage_downloads, "Downloads & cache", AppIcon.DOWNLOAD, Res.string.settings_keywords_manage_downloads,
                "Manage downloads"))
            add(SettingEntry(Res.string.music_server, "Music server", AppIcon.SERVER, Res.string.settings_keywords_music_server))
            category("Listening & scrobbling", AppIcon.HISTORY, Res.string.record_listening_history to Res.string.settings_keywords_record_listening_history,
                Res.string.settings_clear_listening_history to Res.string.settings_keywords_clear_listening_history)
            if (controller.scrobblingAvailable) {
                category("Listening & scrobbling", AppIcon.SCROBBLING,
                    Res.string.settings_scrobble_after to Res.string.settings_keywords_scrobble_after,
                    Res.string.settings_or_after to Res.string.settings_keywords_or_after)
                add(SettingEntry(Res.string.connect_last_fm, "Listening & scrobbling", AppIcon.SCROBBLING,
                    Res.string.settings_keywords_connect_last_fm, "Connect Last.fm"))
                add(SettingEntry(Res.string.connect_listenbrainz, "Listening & scrobbling", AppIcon.SCROBBLING,
                    Res.string.settings_keywords_connect_listenbrainz, "Connect ListenBrainz"))
            }
            if (controller.fileEditingAvailable) category("Files & recovery", AppIcon.UNDO,
                Res.string.keep_undo_backups to Res.string.settings_keywords_keep_undo_backups,
                Res.string.undo_backup_storage to Res.string.settings_keywords_undo_backup_storage)
            if (controller.serverFilesAvailable) add(SettingEntry(Res.string.server_files_sftp, "Files & recovery", AppIcon.SERVER_FILES,
                Res.string.settings_keywords_server_files, "Server files (SFTP)"))
            if (controller.tunnel != null) add(SettingEntry(Res.string.tunnel, "Tunnel", AppIcon.TAILSCALE, Res.string.settings_keywords_tunnel))
            if (controller.soundCloudAvailable) add(SettingEntry(Res.string.settings_soundcloud, "SoundCloud", AppIcon.CLOUD,
                Res.string.settings_keywords_soundcloud))
            if (controller.pairedDevices != null) add(SettingEntry(Res.string.paired_devices, "Paired devices", AppIcon.DEVICES,
                Res.string.settings_keywords_paired_devices))
            add(SettingEntry(Res.string.saved_queues, "Saved queues", AppIcon.QUEUE, Res.string.settings_keywords_saved_queues))
            add(SettingEntry(Res.string.about_nevumi, "About Nevumi", AppIcon.INFO, Res.string.settings_keywords_about_nevumi))
        }
    }
    // Search the displayed, localized names together with the localized keywords.
    val index = entries.map { entry ->
        SettingResult(stringResource(entry.title), settingsPageTitle(entry.category), entry.icon, stringResource(entry.keywords), entry.destination)
    }
    val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    val results = remember(query, index) { if (terms.isEmpty()) emptyList() else index.filter { result ->
        val text = "${result.title} ${result.category} ${result.keywords}".lowercase()
        terms.all(text::contains)
    } }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val queryDescription = stringResource(Res.string.settings_search_query)
    SettingsScreen(onDismissRequest = back, title = {
        TextField(query, onQuery, placeholder = { Text(stringResource(Res.string.settings_search_settings)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = queryDescription },
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface))
    }, text = {
        if (results.isEmpty()) Text(stringResource(if (terms.isEmpty()) Res.string.settings_search_hint else Res.string.settings_search_no_results),
            Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { "${it.category}:${it.title}" }) { result ->
                SettingsLink(result.title, result.icon, result.category) { focusManager.clearFocus(); keyboard?.hide(); open(result) }
            }
        }
    })
}
