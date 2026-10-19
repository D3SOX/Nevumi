package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import me.d3sox.nevumi.playback.AudioEffects
import me.d3sox.nevumi.playback.EqualizerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun frequencyLabel(frequency: Double): String = if (frequency >= 1_000) {
    "${(frequency / 1_000).toString().removeSuffix(".0")} kHz"
} else "${frequency.toString().removeSuffix(".0")} Hz"

@Composable
internal fun AudioEffectsScreen(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    var profiles by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var saved by remember { mutableStateOf(emptyList<EqualizerProfile>()) }
    var revision by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val catalog = controller.equalizerProfiles
    val effects = state.preferences.audioEffects
    LaunchedEffect(profiles, revision) { saved = catalog?.saved().orEmpty() }
    fun update(change: (AudioEffects) -> AudioEffects) {
        controller.updatePreferences { it.copy(audioEffects = change(it.audioEffects)) }
    }
    AnimatedNavigation(profiles, false, canGoBack = profiles, onBack = { profiles = false }, depth = { if (it) 1 else 0 }) { showProfiles ->
    if (showProfiles && controller.equalizerProfiles != null) AutoEqScreen(controller.equalizerProfiles, controller) { profiles = false }
    else {
    SettingsScreen(onDismissRequest = dismiss, title = { Text("Audio effects") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EffectSwitch("Enable equalizer", effects.enabled) { enabled -> update { it.copy(enabled = enabled) } }
            EffectSwitch("Mono audio", effects.mono) { mono -> update { it.copy(mono = mono) } }
            if (catalog != null) {
                val selected = saved.firstOrNull { it.effects.copy(mono = effects.mono) == effects }?.id
                SettingChoice("Equalizer profile", selected,
                    listOf(null to "Custom settings") + saved.map { it.id as String? to it.name }) { id ->
                    if (id == null) update { it.copy(profileName = null) }
                    saved.firstOrNull { it.id == id }?.let { profile -> update {
                        if (profile.id.startsWith("equalizer-custom:")) profile.effects else profile.effects.copy(mono = it.mono)
                    } }
                }
                SettingsLink("Headphone profiles", AppIcon.HEADPHONES, "Browse AutoEQ, import and manage profiles") { profiles = true }
                OutlinedButton(onClick = { name = ""; error = null; saving = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcon.PLUS.vector, null); Spacer(Modifier.width(8.dp)); Text("Save as profile")
                }
            }
            EffectSlider("Preamp", effects.preampDb, -30f..12f, 41, effects.enabled) { gain -> update { it.copy(preampDb = gain) } }
            EffectSwitch("Reduce gain for boosts", effects.reduceGainForBoosts) { enabled -> update { it.copy(reduceGainForBoosts = enabled) } }
            Text("Effective preamp: ${effects.effectivePreampDb.toString().removeSuffix(".0")} dB",
                style = MaterialTheme.typography.bodySmall)
            effects.bands.forEachIndexed { index, band ->
                val label = frequencyLabel(band.frequencyHz)
                EffectSlider("Equalizer gain at $label", band.gainDb, -24f..24f, 47, effects.enabled,
                    visibleLabel = label) { gain -> update { current ->
                    current.copy(profileName = null, bands = current.bands.mapIndexed { i, value -> if (i == index) value.copy(gainDb = gain) else value })
                } }
            }
            ActionButton("Reset equalizer", AppIcon.RESET, style = ActionStyle.OUTLINED) { update { AudioEffects(enabled = it.enabled, mono = it.mono) } }
        }
    }, confirmButton = { PageAction("Done", onClick = dismiss) })
    }
    }
    if (saving && catalog != null) AlertDialog(onDismissRequest = { if (!working) saving = false },
        title = { Text("Save equalizer profile") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Profile name") },
                    singleLine = true, enabled = !working)
                if (saved.any { it.id.startsWith("equalizer-custom:") && it.name == name.trim() })
                    Text("This replaces your saved profile with the same name.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = name.trim().length in 1..200 && !working, onClick = {
            working = true
            scope.launch {
                try {
                    val profile = catalog.save(name, controller.state.value.preferences.audioEffects)
                    controller.updatePreferences { it.copy(audioEffects = profile.effects) }.join()
                    revision++; saving = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Could not save this profile" }
                finally { working = false }
            }
        }) { Text("Save profile") } }, dismissButton = {
            TextButton(enabled = !working, onClick = { saving = false }) { Text("Cancel") }
        })
}

@Composable
private fun EffectSwitch(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, change, Modifier.semantics { contentDescription = label })
    }
}

@Composable
private fun EffectSlider(label: String, value: Double, range: ClosedFloatingPointRange<Float>, steps: Int,
    enabled: Boolean, visibleLabel: String = label, change: (Double) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(visibleLabel, Modifier.weight(1f))
            Text("${draft.roundToInt()} dB", style = MaterialTheme.typography.labelLarge)
        }
        PreciseSlider(label, draft, range, steps, enabled, change = { draft = it }, finish = { change(draft.toDouble()) })
    }
}
