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
    AlertDialog(onDismissRequest = close, title = { Text("Playback settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = audioEffects, modifier = Modifier.fillMaxWidth()) {
                Icon(AppIcon.EQUALIZER.vector, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Audio effects")
            }
            SettingsSlider("Crossfade", AppIcon.CROSSFADE, state.preferences.crossfadeDurationMs / 1_000f, 0f..12f, 11,
                { if (it == 0f) "Off" else "${it.roundToInt()} seconds" }) { seconds ->
                controller.updatePreferences { it.copy(crossfadeDurationMs = seconds.roundToInt() * 1_000L) }
            }
            SettingsToggle("Gapless playback", AppIcon.MUSIC, "Play adjacent tracks without a break when crossfade is off.",
                state.preferences.gaplessPlayback) { enabled -> controller.updatePreferences { it.copy(gaplessPlayback = enabled) } }
            if (milkDropBackgroundSupported) {
                val milkDrop = state.preferences.milkDrop
                fun visualizer(update: (me.d3sox.nevumi.visualizer.MilkDropSettings) -> me.d3sox.nevumi.visualizer.MilkDropSettings) =
                    controller.updatePreferences { it.copy(milkDrop = update(it.milkDrop)) }
                SettingsToggle("Visualizer background", AppIcon.WAVEFORM, "Show the MilkDrop visualizer behind the now playing screen.",
                    milkDrop.background) { enabled -> visualizer { it.copy(background = enabled) } }
                if (milkDrop.background) {
                    SettingsSlider("Dim background", AppIcon.DARK_THEME, milkDrop.backgroundDim, 0f..1f, 19,
                        { "${(it * 100).roundToInt()}%" }) { dim -> visualizer { it.copy(backgroundDim = dim) } }
                    SettingsSlider("Blur background", AppIcon.THEME, milkDrop.backgroundBlur, 0f..1f, 19,
                        { "${(it * 100).roundToInt()}%" }) { blur -> visualizer { it.copy(backgroundBlur = blur) } }
                }
            }
            Text("Sleep timer", style = MaterialTheme.typography.titleSmall)
            if (deadline != null) Text("Playback stops in ${playbackTime(remaining)}", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 90).forEach { minutes ->
                    OutlinedButton(onClick = { controller.setSleepTimer(minutes) }) { Text("$minutes min") }
                }
                if (deadline != null) ActionButton("Cancel timer", AppIcon.CLOSE, style = ActionStyle.OUTLINED) { controller.setSleepTimer(null) }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Done") } })
}
