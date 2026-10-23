package me.d3sox.nevumi.ui

import androidx.compose.runtime.Composable
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.lyrics_setting_online
import me.d3sox.nevumi.resources.lyrics_setting_online_description
import me.d3sox.nevumi.settings.MusicSettings
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LyricsSettings(settings: MusicSettings, controller: MusicController) {
    if (!controller.lyricsController.available) return
    SettingsToggle(stringResource(Res.string.lyrics_setting_online), AppIcon.NETWORK, stringResource(Res.string.lyrics_setting_online_description),
        settings.fetchLyricsOnline) { enabled -> controller.updatePreferences { it.copy(fetchLyricsOnline = enabled) } }
}
