package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playback.PreparedAudioWaveforms
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.settings_pause_preparation
import me.d3sox.nevumi.resources.settings_prepare_all_waveforms
import me.d3sox.nevumi.resources.settings_prepare_automatically
import me.d3sox.nevumi.resources.settings_prepare_automatically_description
import me.d3sox.nevumi.resources.settings_waveforms_cache_note
import me.d3sox.nevumi.resources.settings_waveforms_failed
import me.d3sox.nevumi.resources.settings_waveforms_prepared
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WaveformSettings(state: AppState, controller: MusicController) {
    val waveforms = LocalAudioWaveforms.current as? PreparedAudioWaveforms ?: return
    val progress by waveforms.preparation.collectAsState()
    SettingsGroup {
        SettingsToggle(stringResource(Res.string.settings_prepare_automatically), AppIcon.EQUALIZER,
            stringResource(Res.string.settings_prepare_automatically_description),
            state.preferences.precomputeWaveforms) { enabled ->
            controller.updatePreferences { it.copy(precomputeWaveforms = enabled) }
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.settings_waveforms_prepared, progress.prepared, progress.total), style = MaterialTheme.typography.titleMedium)
            if (progress.running) {
                LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.prepared.toFloat() / progress.total }, Modifier.fillMaxWidth())
                state.tracks.firstOrNull { track -> track.sources.any { it.id == progress.currentSourceId } }?.let {
                    Text(it.metadata.title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (progress.failed > 0) Text(stringResource(Res.string.settings_waveforms_failed, progress.failed), color = MaterialTheme.colorScheme.error)
            Text(stringResource(Res.string.settings_waveforms_cache_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = { if (progress.running) waveforms.pause() else waveforms.prepareAll() }, Modifier.fillMaxWidth()) {
                Icon((if (progress.running) AppIcon.PAUSE else AppIcon.EQUALIZER).vector, null)
                Spacer(Modifier.width(8.dp)); Text(stringResource(if (progress.running) Res.string.settings_pause_preparation else Res.string.settings_prepare_all_waveforms))
            }
        }
    }
}
