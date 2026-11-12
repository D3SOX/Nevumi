package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.settings_youtube_about
import me.d3sox.nevumi.resources.settings_youtube_enable
import me.d3sox.nevumi.resources.settings_youtube_enable_description
import me.d3sox.nevumi.resources.settings_youtube_review_tags_description
import me.d3sox.nevumi.resources.sources_check_tags_before_saving
import me.d3sox.nevumi.settings.MusicSettings
import org.jetbrains.compose.resources.stringResource

/** Turns YouTube on, which is off until chosen because this device then contacts YouTube itself. */
@Composable
internal fun YouTubeSettings(settings: MusicSettings, controller: MusicController) {
    SettingsGroup {
        SettingsToggle(stringResource(Res.string.settings_youtube_enable), AppIcon.YOUTUBE, stringResource(Res.string.settings_youtube_enable_description),
            settings.youTube) { enabled -> controller.updatePreferences { it.copy(youTube = enabled) } }
        if (settings.youTube && controller.youTubeDeviceDownloads) SettingsToggle(stringResource(Res.string.sources_check_tags_before_saving), AppIcon.TAGS,
            stringResource(Res.string.settings_youtube_review_tags_description), settings.youTubeReviewTags) { enabled ->
            controller.updatePreferences { it.copy(youTubeReviewTags = enabled) }
        }
    }
    Text(stringResource(Res.string.settings_youtube_about), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
