package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.ReleasesController
import me.d3sox.nevumi.history.LastFmPeriod
import me.d3sox.nevumi.history.ScrobblingService
import me.d3sox.nevumi.releases.ArtistCollection
import me.d3sox.nevumi.releases.ImportableArtist
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.artists
import me.d3sox.nevumi.resources.back
import me.d3sox.nevumi.resources.cancel
import me.d3sox.nevumi.resources.releases_following
import me.d3sox.nevumi.resources.releases_import
import me.d3sox.nevumi.resources.releases_import_find
import me.d3sox.nevumi.resources.releases_import_follow
import me.d3sox.nevumi.resources.releases_import_lastfm
import me.d3sox.nevumi.resources.releases_import_lastfm_description
import me.d3sox.nevumi.resources.releases_import_lastfm_unavailable
import me.d3sox.nevumi.resources.releases_import_loading
import me.d3sox.nevumi.resources.releases_import_lookup
import me.d3sox.nevumi.resources.releases_import_musicbrainz
import me.d3sox.nevumi.resources.releases_import_musicbrainz_description
import me.d3sox.nevumi.resources.releases_import_no_collections
import me.d3sox.nevumi.resources.releases_import_none
import me.d3sox.nevumi.resources.releases_import_period_half_year
import me.d3sox.nevumi.resources.releases_import_period_month
import me.d3sox.nevumi.resources.releases_import_period_overall
import me.d3sox.nevumi.resources.releases_import_period_quarter
import me.d3sox.nevumi.resources.releases_import_period_year
import me.d3sox.nevumi.resources.releases_import_plays
import me.d3sox.nevumi.resources.releases_import_select_all
import me.d3sox.nevumi.resources.releases_import_too_large
import me.d3sox.nevumi.resources.releases_import_username
import me.d3sox.nevumi.resources.retry
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class ImportSource { LASTFM, MUSICBRAINZ }

/** What [load] returned, or null while it runs; a new [keys] or retry loads again. */
@Composable
private fun <T> rememberLoad(vararg keys: Any?, load: suspend () -> T): Result<T>? {
    val result by produceState<Result<T>?>(null, *keys) {
        value = null
        value = try { Result.success(load()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
    }
    return result
}

/** Picks artists to follow from the Last.fm account's top artists or from a MusicBrainz user's public artist collection. */
@Composable
internal fun ArtistImportDialog(state: AppState, controller: MusicController, dismiss: () -> Unit) {
    val releases = controller.releasesController
    val lastFm = state.scrobbling.profiles.filter { it.service == ScrobblingService.LASTFM }.maxByOrNull { it.enabled }
    var source by remember { mutableStateOf<ImportSource?>(null) }
    var period by remember { mutableStateOf(LastFmPeriod.YEAR) }
    var editor by remember { mutableStateOf("") }
    var searchedEditor by remember { mutableStateOf<String?>(null) }
    var collection by remember { mutableStateOf<ArtistCollection?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(emptySet<ImportableArtist>()) }
    fun isFollowed(artist: ImportableArtist) = state.followedArtists.any {
        it.id == artist.id || artist.id == null && it.libraryName.equals(artist.name, ignoreCase = true)
    }
    val chosen = collection
    val searched = searchedEditor
    val artists = when {
        source == ImportSource.LASTFM -> rememberLoad(period, attempt) { releases.lastFmTopArtists(period) }
        chosen != null -> rememberLoad(chosen, attempt) { releases.collectionArtists(chosen) }
        else -> null
    }
    // Everything not followed yet starts selected.
    LaunchedEffect(artists) { selected = artists?.getOrNull().orEmpty().filterNot(::isFollowed).toSet() }
    val collections = if (source == ImportSource.MUSICBRAINZ && chosen == null && searched != null)
        rememberLoad(searched, attempt) { releases.artistCollections(searched) } else null
    val failure = (artists ?: collections)?.exceptionOrNull()
    val back: (() -> Unit)? = when {
        collection != null -> { { collection = null } }
        source != null -> { { source = null; searchedEditor = null } }
        else -> null
    }

    AlertDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("artist-import"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                back?.let { AppIconButton(AppIcon.BACK, stringResource(Res.string.back), onClick = it) }
                Text(chosen?.name ?: stringResource(when (source) {
                    ImportSource.LASTFM -> Res.string.releases_import_lastfm
                    ImportSource.MUSICBRAINZ -> Res.string.releases_import_musicbrainz
                    null -> Res.string.releases_import
                }))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val checklist: @Composable () -> Unit = {
                    Loaded(artists) { found ->
                        if (found.isEmpty()) Text(stringResource(Res.string.releases_import_none))
                        else ArtistChecklist(found, selected, ::isFollowed) { selected = it }
                    }
                }
                when {
                    source == null -> ImportSources(lastFm?.username) { source = it }
                    source == ImportSource.LASTFM -> { PeriodChips(period) { period = it }; checklist() }
                    chosen == null -> {
                        OutlinedTextField(editor, { editor = it }, Modifier.fillMaxWidth().testTag("import-username"),
                            label = { Text(stringResource(Res.string.releases_import_username)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { if (editor.isNotBlank()) searchedEditor = editor.trim() }),
                            trailingIcon = { AppIconButton(AppIcon.SEARCH, stringResource(Res.string.releases_import_find), enabled = editor.isNotBlank()) {
                                searchedEditor = editor.trim()
                            } })
                        if (searched != null) Loaded(collections) { found ->
                            if (found.isEmpty()) Text(stringResource(Res.string.releases_import_no_collections, searched))
                            else CollectionList(found) { collection = it }
                        }
                    }
                    else -> checklist()
                }
            }
        },
        confirmButton = {
            when {
                failure != null -> TextButton({ attempt++ }) { Text(stringResource(Res.string.retry)) }
                artists?.isSuccess == true -> TextButton({ releases.import(artists.getOrThrow().filter { it in selected }); dismiss() },
                    enabled = selected.isNotEmpty(), modifier = Modifier.testTag("import-follow")) {
                    Text(stringResource(Res.string.releases_import_follow, selected.size))
                }
            }
        },
        dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.cancel)) } })
}

/** A spinner while [result] loads, its error, or its [content]. */
@Composable
private fun <T> Loaded(result: Result<T>?, content: @Composable (T) -> Unit) {
    val failure = result?.exceptionOrNull()
    when {
        result == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(Res.string.releases_import_loading))
        }
        failure != null -> Text(failure.message ?: failure.toString(), color = MaterialTheme.colorScheme.error)
        else -> content(result.getOrThrow())
    }
}

@Composable
private fun ImportSources(lastFmUser: String?, choose: (ImportSource) -> Unit) {
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    val disabled = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = lastFmUser != null) { choose(ImportSource.LASTFM) }
            .testTag("import-source:lastfm"),
            colors = if (lastFmUser != null) colors else ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                headlineColor = disabled, leadingIconColor = disabled),
            headlineContent = { Text(stringResource(Res.string.releases_import_lastfm)) },
            supportingContent = { Text(lastFmUser?.let { stringResource(Res.string.releases_import_lastfm_description, it) }
                ?: stringResource(Res.string.releases_import_lastfm_unavailable)) },
            leadingContent = { Icon(AppIcon.SCROBBLING.vector, null) })
        ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { choose(ImportSource.MUSICBRAINZ) }
            .testTag("import-source:musicbrainz"), colors = colors,
            headlineContent = { Text(stringResource(Res.string.releases_import_musicbrainz)) },
            supportingContent = { Text(stringResource(Res.string.releases_import_musicbrainz_description)) },
            leadingContent = { Icon(AppIcon.LIBRARY.vector, null) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeriodChips(period: LastFmPeriod, choose: (LastFmPeriod) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LastFmPeriod.entries.forEach { option ->
            FilterChip(option == period, { choose(option) }, label = { Text(stringResource(when (option) {
                LastFmPeriod.MONTH -> Res.string.releases_import_period_month
                LastFmPeriod.QUARTER -> Res.string.releases_import_period_quarter
                LastFmPeriod.HALF_YEAR -> Res.string.releases_import_period_half_year
                LastFmPeriod.YEAR -> Res.string.releases_import_period_year
                LastFmPeriod.OVERALL -> Res.string.releases_import_period_overall
            })) })
        }
    }
}

@Composable
private fun CollectionList(collections: List<ArtistCollection>, choose: (ArtistCollection) -> Unit) {
    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(collections, key = { it.id }) { collection ->
            val importable = collection.artistCount <= ReleasesController.IMPORT_LIMIT
            ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(enabled = importable) { choose(collection) }
                .testTag("import-collection:${collection.id}"),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                headlineContent = { Text(collection.name) },
                supportingContent = { Text(if (importable) pluralStringResource(Res.plurals.artists, collection.artistCount, collection.artistCount)
                    else stringResource(Res.string.releases_import_too_large, ReleasesController.IMPORT_LIMIT)) },
                leadingContent = { Icon(AppIcon.LIBRARY.vector, null) })
        }
    }
}

@Composable
private fun ArtistChecklist(artists: List<ImportableArtist>, selected: Set<ImportableArtist>, followed: (ImportableArtist) -> Boolean,
    select: (Set<ImportableArtist>) -> Unit) {
    val available = artists.filterNot(followed)
    val all = when {
        selected.isEmpty() -> ToggleableState.Off
        selected.size == available.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (available.isNotEmpty()) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).triStateToggleable(all, role = Role.Checkbox) {
            select(if (all == ToggleableState.On) emptySet() else available.toSet())
        }.padding(start = 16.dp).testTag("import-select-all"), verticalAlignment = Alignment.CenterVertically) {
            TriStateCheckbox(all, null)
            Text(stringResource(Res.string.releases_import_select_all), Modifier.padding(start = 16.dp))
        }
        HorizontalDivider()
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(artists) { artist ->
                val isFollowed = followed(artist)
                val details = listOfNotNull(
                    if (isFollowed) stringResource(Res.string.releases_following) else null,
                    artist.plays?.let { pluralStringResource(Res.plurals.releases_import_plays, it, it) },
                    artist.disambiguation.ifBlank { null })
                ListItem(modifier = Modifier.toggleable(isFollowed || artist in selected, enabled = !isFollowed, role = Role.Checkbox) { checked ->
                    select(if (checked) selected + artist else selected - artist)
                }.testTag("import-artist:${artist.name}"),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(artist.name) },
                    supportingContent = details.takeIf { it.isNotEmpty() }?.let { { Text(it.joinToString(" · ")) } },
                    leadingContent = { Checkbox(isFollowed || artist in selected, null, enabled = !isFollowed) })
            }
        }
        if (artists.any { it.id == null }) Text(stringResource(Res.string.releases_import_lookup), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
