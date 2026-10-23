package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Clock
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.audio_effects
import me.d3sox.nevumi.resources.crossfade
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.off
import me.d3sox.nevumi.resources.player_blur_background
import me.d3sox.nevumi.resources.player_cancel_timer
import me.d3sox.nevumi.resources.player_dim_background
import me.d3sox.nevumi.resources.player_gapless_playback
import me.d3sox.nevumi.resources.player_gapless_playback_text
import me.d3sox.nevumi.resources.player_minutes_short
import me.d3sox.nevumi.resources.player_playback_settings
import me.d3sox.nevumi.resources.player_playback_stops_in
import me.d3sox.nevumi.resources.player_seconds
import me.d3sox.nevumi.resources.player_sleep_timer
import me.d3sox.nevumi.resources.player_visualizer_background
import me.d3sox.nevumi.resources.player_visualizer_background_text
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaybackQuickSettings(state: AppState, controller: MusicController, audioEffects: () -> Unit, close: () -> Unit) {
    val deadline = state.sleepTimerEndsAt
    val remaining by produceState(0L, deadline) {
        while (deadline != null) {
            value = (deadline - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)
            delay(1_000)
        }
    }
    val off = stringResource(Res.string.off)
    val crossfadeSeconds = (0..12).map { stringResource(Res.string.player_seconds, it) }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(Res.string.player_playback_settings)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = audioEffects, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcon.EQUALIZER.vector, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Res.string.audio_effects))
            }
            SettingsSlider(stringResource(Res.string.crossfade), AppIcon.CROSSFADE, state.preferences.crossfadeDurationMs / 1_000f, 0f..12f, 11,
                { if (it == 0f) off else crossfadeSeconds[it.roundToInt()] }) { seconds ->
                controller.updatePreferences { it.copy(crossfadeDurationMs = seconds.roundToInt() * 1_000L) }
            }
            SettingsToggle(stringResource(Res.string.player_gapless_playback), AppIcon.MUSIC, stringResource(Res.string.player_gapless_playback_text),
                state.preferences.gaplessPlayback) { enabled -> controller.updatePreferences { it.copy(gaplessPlayback = enabled) } }
            if (milkDropBackgroundSupported) {
                val milkDrop = state.preferences.milkDrop
                fun visualizer(update: (me.d3sox.nevumi.visualizer.MilkDropSettings) -> me.d3sox.nevumi.visualizer.MilkDropSettings) =
                    controller.updatePreferences { it.copy(milkDrop = update(it.milkDrop)) }
                SettingsToggle(stringResource(Res.string.player_visualizer_background), AppIcon.WAVEFORM, stringResource(Res.string.player_visualizer_background_text),
                    milkDrop.background) { enabled -> visualizer { it.copy(background = enabled) } }
                if (milkDrop.background) {
                    SettingsSlider(stringResource(Res.string.player_dim_background), AppIcon.DARK_THEME, milkDrop.backgroundDim, 0f..1f, 19,
                        { "${(it * 100).roundToInt()}%" }) { dim -> visualizer { it.copy(backgroundDim = dim) } }
                    SettingsSlider(stringResource(Res.string.player_blur_background), AppIcon.THEME, milkDrop.backgroundBlur, 0f..1f, 19,
                        { "${(it * 100).roundToInt()}%" }) { blur -> visualizer { it.copy(backgroundBlur = blur) } }
                }
            }
            Text(stringResource(Res.string.player_sleep_timer), style = MaterialTheme.typography.titleSmall)
            if (deadline != null) Text(stringResource(Res.string.player_playback_stops_in, playbackTime(remaining)), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 90).forEach { minutes ->
                    OutlinedButton(onClick = { controller.setSleepTimer(minutes) }) { Text(stringResource(Res.string.player_minutes_short, minutes)) }
                }
                if (deadline != null) ActionButton(stringResource(Res.string.player_cancel_timer), AppIcon.CLOSE, style = ActionStyle.OUTLINED) { controller.setSleepTimer(null) }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(Res.string.done)) } })
}
