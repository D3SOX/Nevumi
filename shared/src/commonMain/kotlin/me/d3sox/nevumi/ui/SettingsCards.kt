package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_gate_browser
import me.d3sox.nevumi.resources.sources_when_you_pick_a_song
import me.d3sox.nevumi.resources.sources_download_format
import me.d3sox.nevumi.resources.colors
import me.d3sox.nevumi.resources.download_network
import me.d3sox.nevumi.resources.keep_undo_backups
import me.d3sox.nevumi.resources.library_tabs
import me.d3sox.nevumi.resources.music_library
import me.d3sox.nevumi.resources.music_sources
import me.d3sox.nevumi.resources.on_launch
import me.d3sox.nevumi.resources.playback_cache
import me.d3sox.nevumi.resources.quick_action
import me.d3sox.nevumi.resources.record_listening_history
import me.d3sox.nevumi.resources.settings_equalizer_profile
import me.d3sox.nevumi.resources.show_options_buttons
import me.d3sox.nevumi.resources.source_priority
import me.d3sox.nevumi.resources.ssh_authentication
import me.d3sox.nevumi.resources.statistics_grouping
import me.d3sox.nevumi.resources.swipe_left
import me.d3sox.nevumi.resources.swipe_right
import me.d3sox.nevumi.resources.theme
import me.d3sox.nevumi.resources.undo_backup_storage
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val LocalSettingsCards = staticCompositionLocalOf { false }
private val LocalSettingsGroup = staticCompositionLocalOf { false }

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
        CompositionLocalProvider(LocalSettingsGroup provides true) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
}

@Composable
internal fun SettingsCard(icon: AppIcon, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(if (LocalSettingsGroup.current) 4.dp else 24.dp)
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsIcon(icon)
            content()
        }
    }
    if (onClick != null) Surface(onClick, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, content = row)
    else Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, content = row)
}

@Composable
internal fun SettingsIcon(icon: AppIcon) {
    // Paired pastel/ink colors remain readable in both light and dark themes.
    val colors = when (icon) {
        AppIcon.THEME, AppIcon.DARK_THEME, AppIcon.PITCH, AppIcon.UNDO, AppIcon.TABS, AppIcon.BACKUP_FILES, AppIcon.TAGS -> Color(0xFFD5CCFA) to Color(0xFF55468C)
        AppIcon.FOLDER, AppIcon.STORAGE, AppIcon.DOWNLOAD, AppIcon.SWIPE_LEFT, AppIcon.CACHE, AppIcon.SERVER_FILES -> Color(0xFFBCE0FC) to Color(0xFF2D638B)
        AppIcon.PLAY, AppIcon.SPEED, AppIcon.CROSSFADE, AppIcon.SWIPE_RIGHT, AppIcon.WAVEFORM -> Color(0xFFB4EDF0) to Color(0xFF256A71)
        AppIcon.HISTORY, AppIcon.CLOUD, AppIcon.DEVICES, AppIcon.STARTUP, AppIcon.SOURCE_PRIORITY, AppIcon.SERVER, AppIcon.SCROBBLING,
        AppIcon.BROWSER -> Color(0xFFFFD1BF) to Color(0xFF945138)
        else -> Color(0xFFF3ECA9) to Color(0xFF716419)
    }
    Surface(Modifier.size(48.dp), shape = CircleShape, color = colors.first, contentColor = colors.second) {
        Box(contentAlignment = Alignment.Center) { Icon(icon.vector, null, Modifier.size(24.dp)) }
    }
}

@Composable
internal fun SettingsToggle(label: String, icon: AppIcon, description: String, selected: Boolean, change: (Boolean) -> Unit) {
    SettingsCard(icon, onClick = { change(!selected) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VerticalDivider(Modifier.height(32.dp))
        Switch(selected, change, modifier = Modifier.semantics { contentDescription = label })
    }
}

internal fun settingsIcon(label: StringResource): AppIcon = when (label) {
    Res.string.theme -> AppIcon.THEME
    Res.string.colors -> AppIcon.ARTWORK_COLORS
    Res.string.settings_equalizer_profile -> AppIcon.HEADPHONES
    Res.string.on_launch -> AppIcon.STARTUP
    Res.string.swipe_right -> AppIcon.SWIPE_RIGHT
    Res.string.swipe_left -> AppIcon.SWIPE_LEFT
    Res.string.quick_action -> AppIcon.QUICK_ACTION
    Res.string.show_options_buttons -> AppIcon.MORE
    Res.string.library_tabs -> AppIcon.TABS
    Res.string.source_priority -> AppIcon.SOURCE_PRIORITY
    Res.string.music_sources -> AppIcon.LIBRARY
    Res.string.playback_cache -> AppIcon.CACHE
    Res.string.undo_backup_storage -> AppIcon.BACKUP_FILES
    Res.string.download_network -> AppIcon.NETWORK
    Res.string.record_listening_history -> AppIcon.HISTORY
    Res.string.keep_undo_backups -> AppIcon.UNDO
    Res.string.statistics_grouping -> AppIcon.STATISTICS
    Res.string.ssh_authentication -> AppIcon.KEY
    Res.string.music_library -> AppIcon.FOLDER
    Res.string.sources_download_format -> AppIcon.MUSIC
    Res.string.sources_when_you_pick_a_song -> AppIcon.DOWNLOAD
    Res.string.sources_gate_browser -> AppIcon.BROWSER
    else -> AppIcon.SETTINGS
}

/** Settings whose icons depend on the setting, for callers that pass an already resolved label. */
private val iconSettings = listOf(Res.string.theme, Res.string.colors, Res.string.settings_equalizer_profile, Res.string.on_launch,
    Res.string.swipe_right, Res.string.swipe_left, Res.string.quick_action, Res.string.show_options_buttons, Res.string.library_tabs,
    Res.string.source_priority, Res.string.music_sources, Res.string.playback_cache, Res.string.undo_backup_storage,
    Res.string.download_network, Res.string.record_listening_history, Res.string.keep_undo_backups, Res.string.statistics_grouping,
    Res.string.sources_download_format, Res.string.sources_when_you_pick_a_song, Res.string.sources_gate_browser,
    Res.string.ssh_authentication, Res.string.music_library)

/** Finds the setting a resolved label belongs to by comparing it with the displayed, localized setting names. */
@Composable
internal fun settingKey(label: String): StringResource? {
    var key: StringResource? = null
    iconSettings.forEach { if (stringResource(it) == label) key = it }
    return key
}

