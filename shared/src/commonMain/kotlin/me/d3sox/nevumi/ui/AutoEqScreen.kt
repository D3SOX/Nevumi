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
            catch (failure: Exception) { error = failure.message ?: "Could not load this equalizer profile" }
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
        SettingsScreen(onDismissRequest = { license = false }, title = { Text("AutoEQ license") },
            text = { Text(autoEqLicense, Modifier.verticalScroll(rememberScrollState())) })
    } else {
    SettingsScreen(onDismissRequest = dismiss, title = { Text("Headphone profiles") }, text = {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("AutoEQ profiles stay on this device. Catalog refreshes and profile downloads contact GitHub.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search headphones") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Refresh catalog", AppIcon.REFRESH, enabled = !refreshing, style = ActionStyle.OUTLINED) {
                        action(catalogRefresh = true) { profiles.refreshCatalog() }
                    }
                    ActionButton("Import profile", AppIcon.IMPORT, enabled = !working, style = ActionStyle.OUTLINED) { action {
                        files.open()?.let { document -> select(profiles.import(document.name, document.text)) }
                    } }
                }
                if (working || refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            if (saved.isNotEmpty()) item { Text("Saved profiles", style = MaterialTheme.typography.titleMedium) }
            items(saved, key = { "saved:${it.id}" }) { profile ->
                Column {
                    Text(profile.name)
                    Text(profile.attribution, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton("Use profile", AppIcon.CHECK, Modifier.semantics { contentDescription = "Use saved profile: ${profile.name}" },
                            enabled = !working) { action { select(profile) } }
                        ActionButton("Remove", AppIcon.REMOVE, Modifier.semantics { contentDescription = "Remove saved profile: ${profile.name}" },
                            enabled = !working, style = ActionStyle.DESTRUCTIVE) { action { profiles.remove(profile.id) } }
                    }
                }
            }
            if (catalog.isEmpty()) item {
                Text(if (query.isBlank()) "Download the AutoEQ catalog to search headphone profiles." else "No matching headphone profiles. Try another search or refresh the catalog.")
            } else item { Text("AutoEQ catalog", style = MaterialTheme.typography.titleMedium) }
            items(catalog, key = { "catalog:${it.id}" }) { profile ->
                Column {
                    Text(profile.name)
                    Text(profile.attribution, style = MaterialTheme.typography.bodySmall)
                    val isSaved = saved.any { it.id == profile.id }
                    ActionButton(if (isSaved) "Use saved profile" else "Download profile", if (isSaved) AppIcon.CHECK else AppIcon.DOWNLOAD,
                        Modifier.semantics { contentDescription = "Download profile: ${profile.name}, ${profile.attribution}" },
                        enabled = !working) { action { select(profiles.download(profile)) } }
                }
            }
            item { ActionButton("AutoEQ license", AppIcon.LICENSE, style = ActionStyle.OUTLINED) { license = true } }
        }
    }, confirmButton = { PageAction("Done", onClick = dismiss) })
    }
    }
}
