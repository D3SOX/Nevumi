package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import me.d3sox.nevumi.visualizer.MilkDropPresetInfo
import me.d3sox.nevumi.visualizer.MilkDropSettings
import me.d3sox.nevumi.visualizer.milkDropPlaybackIndices
import me.d3sox.nevumi.visualizer.milkDropNextPreset
import me.d3sox.nevumi.visualizer.milkDropRandomPreset
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.resources.Res
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.ExperimentalResourceApi
import me.d3sox.nevumi.resources.all
import me.d3sox.nevumi.resources.close
import me.d3sox.nevumi.resources.library_tab_favorites
import me.d3sox.nevumi.resources.next
import me.d3sox.nevumi.resources.previous
import me.d3sox.nevumi.resources.sources_all_categories
import me.d3sox.nevumi.resources.sources_category
import me.d3sox.nevumi.resources.sources_change_preset_every_20_seconds
import me.d3sox.nevumi.resources.sources_change_presets_automatically
import me.d3sox.nevumi.resources.sources_choose_a_preset
import me.d3sox.nevumi.resources.sources_choose_milkdrop_preset
import me.d3sox.nevumi.resources.sources_favorite_current_preset
import me.d3sox.nevumi.resources.sources_favorite_preset
import me.d3sox.nevumi.resources.sources_favorite_preset_to_start
import me.d3sox.nevumi.resources.sources_milkdrop_presets
import me.d3sox.nevumi.resources.sources_milkdrop_visualizer
import me.d3sox.nevumi.resources.sources_next_preset
import me.d3sox.nevumi.resources.sources_no_presets_match
import me.d3sox.nevumi.resources.sources_only_play_favorite_presets
import me.d3sox.nevumi.resources.sources_preset_credits
import me.d3sox.nevumi.resources.sources_presets
import me.d3sox.nevumi.resources.sources_previous_preset
import me.d3sox.nevumi.resources.sources_random
import me.d3sox.nevumi.resources.sources_random_preset
import me.d3sox.nevumi.resources.sources_scroll_milkdrop_presets
import me.d3sox.nevumi.resources.sources_search_presets
import me.d3sox.nevumi.resources.sources_selected_preset
import me.d3sox.nevumi.resources.sources_unfavorite_current_preset
import me.d3sox.nevumi.resources.sources_unfavorite_preset
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MilkDropButton(open: () -> Unit) = AppIconButton(AppIcon.WAVEFORM, stringResource(Res.string.sources_milkdrop_visualizer), onClick = open)

@Composable
internal expect fun MilkDropVisualizer(state: AppState, controller: MusicController, close: () -> Unit)

/** Whether this platform can draw the visualizer behind the now playing screen. */
internal expect val milkDropBackgroundSupported: Boolean

@Composable
internal expect fun MilkDropBackground(state: AppState, controller: MusicController, modifier: Modifier)

@Composable
internal fun MilkDropControls(presets: List<MilkDropPresetInfo>, selected: Int, select: (Int) -> Unit,
    settings: MilkDropSettings, change: ((MilkDropSettings) -> MilkDropSettings) -> Unit) {
    var browse by remember { mutableStateOf(false) }
    val eligible = remember(presets, settings.favoritesOnly, settings.favorites) { milkDropPlaybackIndices(presets, settings) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (presets.isNotEmpty()) {
            val chooseDescription = stringResource(Res.string.sources_choose_milkdrop_preset)
            OutlinedCard(onClick = { browse = true }, modifier = Modifier.fillMaxWidth()
                .semantics { contentDescription = chooseDescription }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(presets.getOrNull(selected)?.name ?: stringResource(Res.string.sources_choose_a_preset), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${presets.getOrNull(selected)?.collection.orEmpty()} · ${countLabel(presets.size, Res.plurals.sources_presets)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    presets.getOrNull(selected)?.let { preset ->
                        val favorite = preset.id in settings.favorites
                        AppIconButton(if (favorite) AppIcon.FAVORITE_FILLED else AppIcon.FAVORITE,
                            if (favorite) stringResource(Res.string.sources_unfavorite_current_preset, preset.name) else stringResource(Res.string.sources_favorite_current_preset, preset.name)) {
                            change { it.copy(favorites = if (preset.id in it.favorites) it.favorites - preset.id else it.favorites + preset.id) }
                        }
                    }
                    Icon(AppIcon.SEARCH.vector, null, Modifier.padding(start = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf<Triple<Pair<String, String>, AppIcon, () -> Int?>>(
                    Triple(stringResource(Res.string.previous) to stringResource(Res.string.sources_previous_preset), AppIcon.PREVIOUS) { milkDropNextPreset(eligible, selected, -1) },
                    Triple(stringResource(Res.string.sources_random) to stringResource(Res.string.sources_random_preset), AppIcon.SHUFFLE) { milkDropRandomPreset(eligible, selected) },
                    Triple(stringResource(Res.string.next) to stringResource(Res.string.sources_next_preset), AppIcon.NEXT) { milkDropNextPreset(eligible, selected) },
                ).forEach { (labels, icon, target) ->
                    val (label, description) = labels
                    OutlinedButton(onClick = { target()?.let(select) }, enabled = eligible.isNotEmpty(),
                        modifier = Modifier.weight(1f).semantics { contentDescription = description },
                        contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(icon.vector, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(label, maxLines = 1)
                    }
                }
            }
        }
        @Composable fun SwitchRow(label: String, description: String, checked: Boolean, toggle: (Boolean) -> Unit) =
            // The whole row toggles, not just the switch.
            Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = toggle)
                .semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Switch(checked, null)
            }
        SwitchRow(stringResource(Res.string.sources_change_preset_every_20_seconds), stringResource(Res.string.sources_change_presets_automatically), settings.automatic) { enabled ->
            change { it.copy(automatic = enabled) }
        }
        SwitchRow(stringResource(Res.string.sources_only_play_favorite_presets), stringResource(Res.string.sources_only_play_favorite_presets), settings.favoritesOnly) { enabled ->
            change { it.copy(favoritesOnly = enabled) }
        }
        if (settings.favoritesOnly && eligible.isEmpty()) Text(stringResource(Res.string.sources_favorite_preset_to_start), style = MaterialTheme.typography.bodySmall)
    }
    if (browse) MilkDropPresetPicker(presets, selected, settings, change, { select(it); browse = false }, { browse = false })
}

@Composable
@OptIn(ExperimentalResourceApi::class)
private fun MilkDropPresetPicker(presets: List<MilkDropPresetInfo>, selected: Int, settings: MilkDropSettings,
    change: ((MilkDropSettings) -> MilkDropSettings) -> Unit, select: (Int) -> Unit, close: () -> Unit) {
    var credits by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf("") }
    LaunchedEffect(credits) { if (credits) notices = Res.readBytes("files/visualizer/milkdrop-notices.txt").decodeToString() }
    var query by remember { mutableStateOf("") }
    // Custom was the collection for user-supplied files before they joined Nevumi.
    val collection = if (settings.collection == "Custom") "Nevumi" else settings.collection
    val category = settings.category
    val collections = remember(presets) { presets.map { it.collection }.distinct().sorted() }
    val categories = remember(presets, collection, settings.favorites) {
        presets.filter { collection == null || it.collection == collection || collection == "@favorites" && it.id in settings.favorites }.map { it.category.substringBefore('/') }
            .filter { it.isNotEmpty() }.distinct().sorted()
    }
    val matches = remember(presets, query, collection, category, settings.favorites) {
        presets.indices.filter { index -> presets[index].let {
            (collection == null || it.collection == collection || collection == "@favorites" && it.id in settings.favorites) &&
                (category == null || it.category.substringBefore('/') == category) &&
                (query.isBlank() || "${it.name} ${it.collection} ${it.category}".contains(query.trim(), ignoreCase = true))
        } }
    }
    val list = rememberLazyListState()
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(query, collection, category) {
        val position = matches.indexOf(selected)
        if (opened || position < 0) { list.scrollToItem(0); opened = true; return@LaunchedEffect }
        opened = true
        // Center the current preset when the picker opens; later filter changes start from the top. Android's dialog
        // window can resize just after its first layout, so briefly center again whenever the list's height changes.
        withTimeoutOrNull(1_000) {
            snapshotFlow { list.layoutInfo.viewportSize.height }.filter { it > 0 }.distinctUntilChanged().collectLatest { height ->
                list.scrollToItem(position)
                val item = snapshotFlow { list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == position } }.filterNotNull().first()
                // Near the end the list can't put the item at the top, so center it from where it actually is.
                list.scrollBy(item.offset + item.size / 2f - list.layoutInfo.viewportStartOffset - height / 2f)
            }
        }
    }
    KeyboardAlertDialog(onDismissRequest = close, title = { Text(stringResource(Res.string.sources_milkdrop_presets)) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.sources_search_presets)) }, singleLine = true)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(collection == null, { change { it.copy(collection = null, category = null) } }, label = { Text(stringResource(Res.string.all)) })
                FilterChip(collection == "@favorites", { change { it.copy(collection = "@favorites", category = null) } }, label = { Text(stringResource(Res.string.library_tab_favorites)) })
                collections.forEach { name -> FilterChip(collection == name, { change { it.copy(collection = name, category = null) } }, label = { Text(name) }) }
            }
            if (categories.size > 1) SettingChoice(stringResource(Res.string.sources_category), category,
                listOf(null to stringResource(Res.string.sources_all_categories)) + categories.map { it to it }, card = false) { selectedCategory -> change { it.copy(category = selectedCategory) } }
            Text(countLabel(matches.size, Res.plurals.sources_presets), style = MaterialTheme.typography.bodySmall)
            if (matches.isEmpty()) Text(stringResource(Res.string.sources_no_presets_match))
            Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize().padding(end = 20.dp), state = list) {
                items(matches, key = { it }) { index ->
                    val preset = presets[index]
                    ListItem(headlineContent = { Text(preset.name) }, supportingContent = {
                        Text(listOf(preset.collection, preset.category).filter { it.isNotEmpty() }.joinToString(" · "))
                    }, trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (index == selected) Icon(AppIcon.CHECK.vector, stringResource(Res.string.sources_selected_preset))
                            val favorite = preset.id in settings.favorites
                            AppIconButton(if (favorite) AppIcon.FAVORITE_FILLED else AppIcon.FAVORITE,
                                if (favorite) stringResource(Res.string.sources_unfavorite_preset, preset.name) else stringResource(Res.string.sources_favorite_preset, preset.name)) {
                                change { it.copy(favorites = if (preset.id in it.favorites) it.favorites - preset.id else it.favorites + preset.id) }
                            }
                        }
                    },
                        colors = ListItemDefaults.colors(containerColor = if (index == selected)
                            MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                            headlineColor = if (index == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                            supportingColor = if (index == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            trailingIconColor = if (index == selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.selectable(index == selected, enabled = !settings.favoritesOnly || preset.id in settings.favorites,
                            role = Role.RadioButton) { select(index) })
                }
            }
            LibraryScrollbar(list, matches.size, stringResource(Res.string.sources_scroll_milkdrop_presets)) { presets[matches[it]].name }
            }
        }
    }, dismissButton = { TextButton(onClick = { credits = true }) { Text(stringResource(Res.string.sources_preset_credits)) } },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(Res.string.close)) } })
    if (credits) AlertDialog(onDismissRequest = { credits = false }, title = { Text(stringResource(Res.string.sources_preset_credits)) },
        text = { Text(notices, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { credits = false }) { Text(stringResource(Res.string.close)) } })
}
