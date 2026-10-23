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
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.audio_effects
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.settings_could_not_save_profile
import me.d3sox.nevumi.resources.settings_custom_settings
import me.d3sox.nevumi.resources.settings_effective_preamp
import me.d3sox.nevumi.resources.settings_enable_equalizer
import me.d3sox.nevumi.resources.settings_equalizer_gain_at
import me.d3sox.nevumi.resources.settings_equalizer_profile
import me.d3sox.nevumi.resources.settings_headphone_profiles
import me.d3sox.nevumi.resources.settings_headphone_profiles_description
import me.d3sox.nevumi.resources.settings_mono_audio
import me.d3sox.nevumi.resources.settings_preamp
import me.d3sox.nevumi.resources.settings_profile_name
import me.d3sox.nevumi.resources.settings_profile_replaces_saved
import me.d3sox.nevumi.resources.settings_reduce_gain_for_boosts
import me.d3sox.nevumi.resources.settings_reset_equalizer
import me.d3sox.nevumi.resources.settings_save_as_profile
import me.d3sox.nevumi.resources.settings_save_equalizer_profile
import me.d3sox.nevumi.resources.settings_save_profile
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

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
    SettingsScreen(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.audio_effects)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            EffectSwitch(stringResource(Res.string.settings_enable_equalizer), effects.enabled) { enabled -> update { it.copy(enabled = enabled) } }
            EffectSwitch(stringResource(Res.string.settings_mono_audio), effects.mono) { mono -> update { it.copy(mono = mono) } }
            if (catalog != null) {
                val selected = saved.firstOrNull { it.effects.copy(mono = effects.mono) == effects }?.id
                SettingChoice(Res.string.settings_equalizer_profile, selected,
                    listOf(null to stringResource(Res.string.settings_custom_settings)) + saved.map { it.id as String? to it.name }) { id ->
                    if (id == null) update { it.copy(profileName = null) }
                    saved.firstOrNull { it.id == id }?.let { profile -> update {
                        if (profile.id.startsWith("equalizer-custom:")) profile.effects else profile.effects.copy(mono = it.mono)
                    } }
                }
                SettingsLink(stringResource(Res.string.settings_headphone_profiles), AppIcon.HEADPHONES, stringResource(Res.string.settings_headphone_profiles_description)) { profiles = true }
                OutlinedButton(onClick = { name = ""; error = null; saving = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(AppIcon.PLUS.vector, null); Spacer(Modifier.width(8.dp)); Text(stringResource(Res.string.settings_save_as_profile))
                }
            }
            EffectSlider(stringResource(Res.string.settings_preamp), effects.preampDb, -30f..12f, 41, effects.enabled) { gain -> update { it.copy(preampDb = gain) } }
            EffectSwitch(stringResource(Res.string.settings_reduce_gain_for_boosts), effects.reduceGainForBoosts) { enabled -> update { it.copy(reduceGainForBoosts = enabled) } }
            Text(stringResource(Res.string.settings_effective_preamp, effects.effectivePreampDb.toString().removeSuffix(".0")),
                style = MaterialTheme.typography.bodySmall)
            effects.bands.forEachIndexed { index, band ->
                val label = frequencyLabel(band.frequencyHz)
                EffectSlider(stringResource(Res.string.settings_equalizer_gain_at, label), band.gainDb, -24f..24f, 47, effects.enabled,
                    visibleLabel = label) { gain -> update { current ->
                    current.copy(profileName = null, bands = current.bands.mapIndexed { i, value -> if (i == index) value.copy(gainDb = gain) else value })
                } }
            }
            ActionButton(stringResource(Res.string.settings_reset_equalizer), AppIcon.RESET, style = ActionStyle.OUTLINED) { update { AudioEffects(enabled = it.enabled, mono = it.mono) } }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
    }
    }
    if (saving && catalog != null) KeyboardAlertDialog(onDismissRequest = { if (!working) saving = false },
        title = { Text(stringResource(Res.string.settings_save_equalizer_profile)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.settings_profile_name)) },
                    singleLine = true, enabled = !working)
                if (saved.any { it.id.startsWith("equalizer-custom:") && it.name == name.trim() })
                    Text(stringResource(Res.string.settings_profile_replaces_saved), style = MaterialTheme.typography.bodySmall)
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
                catch (failure: Exception) { error = failure.message ?: getString(Res.string.settings_could_not_save_profile) }
                finally { working = false }
            }
        }) { Text(stringResource(Res.string.settings_save_profile)) } }, dismissButton = {
            TextButton(enabled = !working, onClick = { saving = false }) { Text(stringResource(Res.string.cancel)) }
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
