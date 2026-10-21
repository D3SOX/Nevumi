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

internal fun settingsIcon(label: String): AppIcon = when (label) {
    "Theme" -> AppIcon.THEME
    "Colors" -> AppIcon.ARTWORK_COLORS
    "Equalizer profile" -> AppIcon.HEADPHONES
    "On launch" -> AppIcon.STARTUP
    "Swipe right" -> AppIcon.SWIPE_RIGHT
    "Swipe left" -> AppIcon.SWIPE_LEFT
    "Quick action" -> AppIcon.QUICK_ACTION
    "Show options buttons" -> AppIcon.MORE
    "Library tabs" -> AppIcon.TABS
    "Source priority" -> AppIcon.SOURCE_PRIORITY
    "Music sources" -> AppIcon.LIBRARY
    "Playback cache" -> AppIcon.CACHE
    "Undo backup storage" -> AppIcon.BACKUP_FILES
    "Download network" -> AppIcon.NETWORK
    "Record listening history", "Listening period" -> AppIcon.HISTORY
    "Keep undo backups" -> AppIcon.UNDO
    "Statistics grouping" -> AppIcon.STATISTICS
    "SSH authentication" -> AppIcon.KEY
    "Music library" -> AppIcon.FOLDER
    "Download format" -> AppIcon.MUSIC
    "When you pick a song" -> AppIcon.DOWNLOAD
    "Gate browser" -> AppIcon.BROWSER
    else -> AppIcon.SETTINGS
}
