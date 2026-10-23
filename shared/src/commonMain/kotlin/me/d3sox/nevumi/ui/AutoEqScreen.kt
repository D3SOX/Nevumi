package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.playback.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.done
import me.d3sox.nevumi.resources.remove
import me.d3sox.nevumi.resources.settings_autoeq_catalog
import me.d3sox.nevumi.resources.settings_autoeq_download_catalog
import me.d3sox.nevumi.resources.settings_autoeq_license
import me.d3sox.nevumi.resources.settings_autoeq_no_matches
import me.d3sox.nevumi.resources.settings_autoeq_privacy
import me.d3sox.nevumi.resources.settings_catalog_profile_action
import me.d3sox.nevumi.resources.settings_could_not_load_equalizer_profile
import me.d3sox.nevumi.resources.settings_download_profile
import me.d3sox.nevumi.resources.settings_headphone_profiles
import me.d3sox.nevumi.resources.settings_import_profile
import me.d3sox.nevumi.resources.settings_refresh_catalog
import me.d3sox.nevumi.resources.settings_remove_saved_profile_named
import me.d3sox.nevumi.resources.settings_saved_profiles
import me.d3sox.nevumi.resources.settings_search_headphones
import me.d3sox.nevumi.resources.settings_use_profile
import me.d3sox.nevumi.resources.settings_use_saved_profile
import me.d3sox.nevumi.resources.settings_use_saved_profile_named
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutoEqScreen(profiles: AutoEqProfiles, controller: MusicController, dismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val files = LocalEqualizerFiles.current
    var query by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf(emptyList<AutoEqCatalogEntry>()) }
    var saved by remember { mutableStateOf(emptyList<EqualizerProfile>()) }
    var working by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var license by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(query, revision) {
        catalog = profiles.search(query)
        saved = profiles.saved()
    }
    fun action(catalogRefresh: Boolean = false, operation: suspend () -> Unit) {
        if (if (catalogRefresh) refreshing else working) return
        if (catalogRefresh) refreshing = true else working = true
        scope.launch {
            error = null
            try { operation(); revision++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: getString(Res.string.settings_could_not_load_equalizer_profile) }
            finally { if (catalogRefresh) refreshing = false else working = false }
        }
    }
    suspend fun select(profile: EqualizerProfile) {
        controller.updatePreferences { it.copy(audioEffects = if (profile.id.startsWith("equalizer-custom:")) profile.effects
            else profile.effects.copy(mono = it.audioEffects.mono)) }.join()
    }
    LaunchedEffect(Unit) {
        if (profiles.search("", limit = 1).isEmpty()) action(catalogRefresh = true) { profiles.refreshCatalog() }
    }
    AnimatedNavigation(license, false, canGoBack = license, onBack = { license = false }, depth = { if (it) 1 else 0 }) { showLicense ->
    if (showLicense) {
        SettingsScreen(onDismissRequest = { license = false }, title = { Text(stringResource(Res.string.settings_autoeq_license)) },
            text = { Text(autoEqLicense, Modifier.verticalScroll(rememberScrollState())) })
    } else {
    SettingsScreen(onDismissRequest = dismiss, title = { Text(stringResource(Res.string.settings_headphone_profiles)) }, text = {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(stringResource(Res.string.settings_autoeq_privacy),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.settings_search_headphones)) }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(Res.string.settings_refresh_catalog), AppIcon.REFRESH, enabled = !refreshing, style = ActionStyle.OUTLINED) {
                        action(catalogRefresh = true) { profiles.refreshCatalog() }
                    }
                    ActionButton(stringResource(Res.string.settings_import_profile), AppIcon.IMPORT, enabled = !working, style = ActionStyle.OUTLINED) { action {
                        files.open()?.let { document -> select(profiles.import(document.name, document.text)) }
                    } }
                }
                if (working || refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (saved.isNotEmpty()) item { Text(stringResource(Res.string.settings_saved_profiles), style = MaterialTheme.typography.titleMedium) }
            items(saved, key = { "saved:${it.id}" }) { profile ->
                val useDescription = stringResource(Res.string.settings_use_saved_profile_named, profile.name)
                val removeDescription = stringResource(Res.string.settings_remove_saved_profile_named, profile.name)
                Column {
                    Text(profile.name)
                    Text(profile.attribution, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(Res.string.settings_use_profile), AppIcon.CHECK, Modifier.semantics { contentDescription = useDescription },
                            enabled = !working) { action { select(profile) } }
                        ActionButton(stringResource(Res.string.remove), AppIcon.REMOVE, Modifier.semantics { contentDescription = removeDescription },
                            enabled = !working, style = ActionStyle.DESTRUCTIVE) { action { profiles.remove(profile.id) } }
                    }
                }
            }
            if (catalog.isEmpty()) item {
                Text(stringResource(if (query.isBlank()) Res.string.settings_autoeq_download_catalog else Res.string.settings_autoeq_no_matches))
            } else item { Text(stringResource(Res.string.settings_autoeq_catalog), style = MaterialTheme.typography.titleMedium) }
            items(catalog, key = { "catalog:${it.id}" }) { profile ->
                Column {
                    Text(profile.name)
                    Text(profile.attribution, style = MaterialTheme.typography.bodySmall)
                    val isSaved = saved.any { it.id == profile.id }
                    val label = stringResource(if (isSaved) Res.string.settings_use_saved_profile else Res.string.settings_download_profile)
                    val description = stringResource(Res.string.settings_catalog_profile_action, label, profile.name, profile.attribution)
                    ActionButton(label, if (isSaved) AppIcon.CHECK else AppIcon.DOWNLOAD,
                        Modifier.semantics { contentDescription = description },
                        enabled = !working) { action { select(profiles.download(profile)) } }
                }
            }
            item { ActionButton(stringResource(Res.string.settings_autoeq_license), AppIcon.LICENSE, style = ActionStyle.OUTLINED) { license = true } }
        }
    }, confirmButton = { PageAction(stringResource(Res.string.done), onClick = dismiss) })
    }
    }
}
