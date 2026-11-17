package me.d3sox.nevumi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.app.ServerMedia
import me.d3sox.nevumi.app.channelArtworkKey
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.library.isRadio
import me.d3sox.nevumi.navidrome.PodcastChannel
import me.d3sox.nevumi.navidrome.PodcastEpisode
import me.d3sox.nevumi.navidrome.PodcastStatus
import me.d3sox.nevumi.navidrome.RadioStation
import me.d3sox.nevumi.resources.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource

/** The host of [address], which names a station better than its whole stream address. */
private fun host(address: String) = address.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')

/**
 * The source ID of the station or episode that plays now, if one does. Library pages get a state without playback, which
 * changes many times a second, so this follows only the playing song.
 */
@Composable
private fun playingSource(controller: MusicController, kind: SourceKind): String? {
    val source = remember(controller, kind) {
        controller.state.map { it.playback.trackId to it.tracks }.distinctUntilChanged { old, new -> old.first == new.first && old.second === new.second }
            .map { (id, tracks) -> id?.let { tracks.firstOrNull { track -> track.id == it } }?.sources?.firstOrNull { it.kind == kind }?.id }
            .distinctUntilChanged()
    }
    return source.collectAsState(null).value
}

@Composable
private fun MediaHint(text: String, icon: AppIcon) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon.vector, null, tint = MaterialTheme.colorScheme.primary)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaCard(title: String, subtitle: String, artwork: String?, icon: AppIcon, description: String, playing: Boolean = false,
    modifier: Modifier = Modifier, open: () -> Unit) {
    Card(modifier.width(152.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = open).semantics { contentDescription = description }) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // What plays shows the equalizer, in place of the icon or on top of a cover.
            CollectionArtwork(artwork, title, if (playing) AppIcon.EQUALIZER else icon, Modifier.fillMaxWidth().aspectRatio(1f),
                badge = if (playing) AppIcon.EQUALIZER else null)
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The Home section of the server's radio stations; a station plays at a tap. */
@Composable
internal fun HomeRadio(title: String, state: AppState, media: ServerMedia, controller: MusicController, all: () -> Unit) {
    val stations = media.stations.orEmpty()
    val playing = playingSource(controller, SourceKind.RADIO)
    HomeSection(title, all) {
        if (stations.isEmpty()) MediaHint(stringResource(Res.string.radio_none_admin), AppIcon.RADIO)
        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(stations, key = { it.id }) { station ->
                MediaCard(station.name, host(station.homepageUrl.ifBlank { station.streamUrl }), null, AppIcon.RADIO,
                    stringResource(Res.string.radio_play_named, station.name), playing == "radio:${media.serverId}:${station.id}",
                    Modifier.testTag("home-station:${station.id}")) { controller.serverMediaController.playStation(station) }
            }
        }
    }
}

/** The Home section of the server's podcasts; a podcast opens its episodes. */
@Composable
internal fun HomePodcasts(title: String, state: AppState, media: ServerMedia, all: () -> Unit, open: (String) -> Unit) {
    val channels = media.podcasts.orEmpty()
    HomeSection(title, all) {
        if (channels.isEmpty()) MediaHint(stringResource(if (media.roles.podcast) Res.string.podcasts_none_manage else Res.string.podcasts_none), AppIcon.PODCAST)
        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(channels, key = { it.id }) { channel ->
                MediaCard(channel.title, countLabel(channel.episodes.size, Res.plurals.podcasts_episodes),
                    state.artwork[channelArtworkKey(media.serverId.orEmpty(), channel.id)], AppIcon.PODCAST,
                    stringResource(Res.string.podcasts_open_named, channel.title), modifier = Modifier.testTag("home-podcast:${channel.id}")) { open(channel.id) }
            }
        }
    }
}

@Composable
private fun PageHeading(text: String) {
    Text(text, Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun MediaProgress(media: ServerMedia) {
    if (media.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("server-media-loading"))
    media.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
}

/** Every radio station on the server; an account that may manage them can add, edit and delete stations. */
@Composable
internal fun RadioPage(state: AppState, media: ServerMedia, controller: MusicController, back: () -> Unit) {
    val radio = controller.serverMediaController
    // Dialogs belong to the server that listed their station, so another server closes them.
    var editing by remember(media.serverId) { mutableStateOf<RadioStation?>(null) }
    var adding by remember(media.serverId) { mutableStateOf(false) }
    var deleting by remember(media.serverId) { mutableStateOf<RadioStation?>(null) }
    val playing = playingSource(controller, SourceKind.RADIO)
    val browser = LocalUriHandler.current
    SettingsScreen(back, title = { Text(stringResource(Res.string.radio_title)) }, actions = {
        if (media.roles.admin) AppIconButton(AppIcon.PLUS, stringResource(Res.string.radio_add_station)) { adding = true }
        AppIconButton(AppIcon.REFRESH, stringResource(Res.string.radio_refresh), enabled = !media.loading) { radio.refresh() }
    }, text = {
        LazyColumn(Modifier.fillMaxSize().testTag("radio-page"), contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "progress") { MediaProgress(media) }
            if (media.stations.orEmpty().isEmpty()) item(key = "none") {
                Text(stringResource(if (media.roles.admin) Res.string.radio_none_admin else Res.string.radio_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(media.stations.orEmpty(), key = { it.id }) { station ->
                var menu by remember { mutableStateOf(false) }
                val current = playing == "radio:${media.serverId}:${station.id}"
                val description = stringResource(Res.string.radio_play_named, station.name)
                ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { radio.playStation(station) }
                    .semantics { contentDescription = description }.testTag("station:${station.id}"),
                    colors = if (current) ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.primary) else ListItemDefaults.colors(),
                    headlineContent = { Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(host(station.homepageUrl.ifBlank { station.streamUrl }), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon((if (current) AppIcon.EQUALIZER else AppIcon.RADIO).vector, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = if (media.roles.admin || station.homepageUrl.isNotBlank()) { {
                        Box {
                            AppIconButton(AppIcon.MORE, stringResource(Res.string.radio_station_options, station.name)) { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                if (station.homepageUrl.isNotBlank()) AppMenuItem(text = { Text(stringResource(Res.string.radio_open_homepage)) },
                                    leadingIcon = { Icon(AppIcon.OPEN_EXTERNAL.vector, null) },
                                    onClick = { menu = false; runCatching { browser.openUri(station.homepageUrl) } })
                                if (media.roles.admin) {
                                    AppMenuItem(text = { Text(stringResource(Res.string.radio_edit_station)) }, leadingIcon = { Icon(AppIcon.EDIT.vector, null) },
                                        onClick = { menu = false; editing = station })
                                    AppMenuItem(text = { Text(stringResource(Res.string.radio_delete_station)) }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) },
                                        onClick = { menu = false; deleting = station })
                                }
                            }
                        }
                    } } else null)
            }
        }
    })
    if (adding || editing != null) StationDialog(editing, media.serverId, controller) { adding = false; editing = null }
    deleting?.let { station ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(Res.string.radio_delete_named, station.name)) },
            text = { Text(stringResource(Res.string.radio_delete_description)) },
            confirmButton = { TextButton({ radio.deleteStation(media.serverId, station.id); deleting = null }) { Text(stringResource(Res.string.delete)) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(Res.string.cancel)) } })
    }
}

@Composable
private fun StationDialog(station: RadioStation?, serverId: String?, controller: MusicController, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(station?.name.orEmpty()) }
    var stream by remember { mutableStateOf(station?.streamUrl.orEmpty()) }
    var homepage by remember { mutableStateOf(station?.homepageUrl.orEmpty()) }
    fun web(address: String) = address.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
    val valid = name.isNotBlank() && web(stream) && (homepage.isBlank() || web(homepage))
    KeyboardAlertDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("station-dialog"),
        title = { Text(stringResource(if (station == null) Res.string.radio_add_station else Res.string.radio_edit_station)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("station-name"), label = { Text(stringResource(Res.string.radio_station_name)) }, singleLine = true)
                OutlinedTextField(stream, { stream = it }, Modifier.fillMaxWidth().testTag("station-stream"), label = { Text(stringResource(Res.string.radio_stream_address)) }, singleLine = true)
                OutlinedTextField(homepage, { homepage = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(Res.string.radio_homepage)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = valid && serverId != null, onClick = {
            val radio = controller.serverMediaController
            if (station == null) radio.addStation(serverId, name, stream, homepage) else radio.updateStation(serverId, station.id, name, stream, homepage)
            dismiss()
        }) { Text(stringResource(if (station == null) Res.string.library_create else Res.string.save)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.cancel)) } })
}

/** The newest episodes and every subscribed podcast; an account with the podcast role can subscribe and check the feeds. */
@Composable
internal fun PodcastsPage(state: AppState, media: ServerMedia, controller: MusicController, back: () -> Unit, open: (String) -> Unit) {
    val podcasts = controller.serverMediaController
    var subscribing by remember(media.serverId) { mutableStateOf(false) }
    val channels = media.podcasts.orEmpty()
    SettingsScreen(back, title = { Text(stringResource(Res.string.podcasts_title)) }, actions = {
        // Checking the feeds reads the podcasts again too, so an account that may check them needs no second refresh.
        if (media.roles.podcast) {
            AppIconButton(AppIcon.PLUS, stringResource(Res.string.podcasts_subscribe)) { subscribing = true }
            AppIconButton(AppIcon.REFRESH, stringResource(Res.string.podcasts_check_feeds), enabled = !media.loading) { podcasts.refreshPodcasts() }
        } else AppIconButton(AppIcon.REFRESH, stringResource(Res.string.podcasts_refresh), enabled = !media.loading) { podcasts.refresh() }
    }, text = {
        LazyColumn(Modifier.fillMaxSize().testTag("podcasts-page"), contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "progress") { MediaProgress(media) }
            if (channels.isEmpty()) item(key = "none") {
                Text(stringResource(if (media.roles.podcast) Res.string.podcasts_none_manage else Res.string.podcasts_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val newest = media.newestEpisodes.take(5)
            if (newest.isNotEmpty()) item(key = "newest") { PageHeading(stringResource(Res.string.podcasts_new_episodes)) }
            items(newest, key = { "newest:${it.id}" }) { episode -> EpisodeRow(episode, state, media, controller, showChannel = true) }
            if (channels.isNotEmpty()) item(key = "subscriptions") { PageHeading(stringResource(Res.string.podcasts_subscriptions)) }
            items(channels, key = { "channel:${it.id}" }) { channel ->
                val description = stringResource(Res.string.podcasts_open_named, channel.title)
                ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { open(channel.id) }
                    .semantics { contentDescription = description }.testTag("podcast:${channel.id}"),
                    headlineContent = { Text(channel.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(if (channel.status == PodcastStatus.ERROR) stringResource(Res.string.podcasts_channel_error, channel.error.ifBlank { "?" })
                            else countLabel(channel.episodes.size, Res.plurals.podcasts_episodes),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { CollectionArtwork(state.artwork[channelArtworkKey(media.serverId.orEmpty(), channel.id)], channel.title,
                        AppIcon.PODCAST, Modifier.size(48.dp)) },
                    trailingContent = { Icon(AppIcon.FORWARD.vector, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
    })
    if (subscribing) SubscribeDialog(media.serverId, controller) { subscribing = false }
}

@Composable
private fun SubscribeDialog(serverId: String?, controller: MusicController, dismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val valid = url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
    KeyboardAlertDialog(onDismissRequest = dismiss, modifier = Modifier.testTag("podcast-subscribe"),
        title = { Text(stringResource(Res.string.podcasts_subscribe_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.podcasts_subscribe_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().testTag("podcast-feed"), label = { Text(stringResource(Res.string.podcasts_feed_address)) }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = valid && serverId != null, onClick = { controller.serverMediaController.subscribe(serverId, url); dismiss() }) {
            Text(stringResource(Res.string.podcasts_subscribe))
        } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(Res.string.cancel)) } })
}

/** One podcast's description and episodes. */
@Composable
internal fun PodcastChannelPage(state: AppState, media: ServerMedia, channel: PodcastChannel, controller: MusicController, back: () -> Unit) {
    var unsubscribing by remember(media.serverId) { mutableStateOf(false) }
    SettingsScreen(back, title = { Text(channel.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, actions = {
        if (media.roles.podcast) AppIconButton(AppIcon.REMOVE, stringResource(Res.string.podcasts_unsubscribe)) { unsubscribing = true }
    }, text = {
        LazyColumn(Modifier.fillMaxSize().testTag("podcast-page"), contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CollectionArtwork(state.artwork[channelArtworkKey(media.serverId.orEmpty(), channel.id)], channel.title, AppIcon.PODCAST, Modifier.size(96.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(countLabel(channel.episodes.size, Res.plurals.podcasts_episodes),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (channel.description.isNotBlank()) Text(channel.description, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 6, overflow = TextOverflow.Ellipsis)
                        if (channel.status == PodcastStatus.ERROR) Text(stringResource(Res.string.podcasts_channel_error, channel.error.ifBlank { "?" }),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item(key = "progress") { MediaProgress(media) }
            if (channel.episodes.isEmpty()) item(key = "none") { Text(stringResource(Res.string.podcasts_no_episodes), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(channel.episodes, key = { it.id }) { episode -> EpisodeRow(episode, state, media, controller, showChannel = false) }
        }
    })
    if (unsubscribing) AlertDialog(onDismissRequest = { unsubscribing = false }, title = { Text(stringResource(Res.string.podcasts_unsubscribe_named, channel.title)) },
        text = { Text(stringResource(Res.string.podcasts_unsubscribe_description)) },
        confirmButton = { TextButton({ controller.serverMediaController.unsubscribe(media.serverId, channel.id); unsubscribing = false; back() }) {
            Text(stringResource(Res.string.podcasts_unsubscribe))
        } },
        dismissButton = { TextButton({ unsubscribing = false }) { Text(stringResource(Res.string.cancel)) } })
}

@Composable
private fun EpisodeRow(episode: PodcastEpisode, state: AppState, media: ServerMedia, controller: MusicController, showChannel: Boolean) {
    val podcasts = controller.serverMediaController
    var menu by remember { mutableStateOf(false) }
    val current = playingSource(controller, SourceKind.PODCAST) == "podcast:${media.serverId}:${episode.id}"
    val resume = episode.streamId?.let { media.bookmarks[it] }?.takeIf { it > 0 }
    val details = listOfNotNull(
        episode.channelTitle.takeIf { showChannel && it.isNotBlank() },
        episode.publishDate.take(10).takeIf { it.length == 10 }?.let(::formatReleaseDate),
        episode.durationMs.takeIf { it > 0 }?.let(::playbackTime),
        when (episode.status) {
            PodcastStatus.DOWNLOADING -> stringResource(Res.string.podcasts_status_downloading)
            PodcastStatus.ERROR -> stringResource(Res.string.podcasts_status_error)
            PodcastStatus.COMPLETED -> resume?.let { stringResource(Res.string.podcasts_resume_at, playbackTime(it)) }
            else -> stringResource(Res.string.podcasts_status_not_downloaded)
        }).joinToString(" · ")
    ListItem(modifier = Modifier.clip(MaterialTheme.shapes.medium)
        .then(if (episode.playable) Modifier.clickable { podcasts.playEpisode(episode) } else Modifier).testTag("episode:${episode.id}"),
        colors = if (current) ListItemDefaults.colors(headlineColor = MaterialTheme.colorScheme.primary) else ListItemDefaults.colors(),
        headlineContent = { Text(episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(details, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (resume != null && episode.durationMs > 0) LinearProgressIndicator({ (resume.toFloat() / episode.durationMs).coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth(.5f), drawStopIndicator = {})
            }
        },
        leadingContent = {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    episode.status == PodcastStatus.DOWNLOADING -> CircularProgressIndicator(Modifier.size(24.dp))
                    episode.playable -> FilledTonalIconButton({ podcasts.playEpisode(episode) }) {
                        Icon((if (current) AppIcon.EQUALIZER else AppIcon.PLAY).vector, stringResource(Res.string.podcasts_play_episode))
                    }
                    media.roles.podcast -> AppIconButton(AppIcon.DOWNLOAD, stringResource(Res.string.podcasts_download_episode)) { podcasts.downloadEpisode(media.serverId, episode.id) }
                    else -> Icon(AppIcon.PODCAST.vector, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = if (media.roles.podcast) { {
            Box {
                AppIconButton(AppIcon.MORE, stringResource(Res.string.podcasts_episode_options, episode.title)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (episode.playable) AppMenuItem(text = { Text(stringResource(Res.string.podcasts_play_episode)) }, leadingIcon = { Icon(AppIcon.PLAY.vector, null) },
                        onClick = { menu = false; podcasts.playEpisode(episode) })
                    if (!episode.playable && episode.status != PodcastStatus.DOWNLOADING) AppMenuItem(text = { Text(stringResource(Res.string.podcasts_download_episode)) },
                        leadingIcon = { Icon(AppIcon.DOWNLOAD.vector, null) }, onClick = { menu = false; podcasts.downloadEpisode(media.serverId, episode.id) })
                    if (episode.status == PodcastStatus.COMPLETED || episode.status == PodcastStatus.ERROR) AppMenuItem(text = { Text(stringResource(Res.string.podcasts_delete_episode)) },
                        leadingIcon = { Icon(AppIcon.REMOVE.vector, null) }, onClick = { menu = false; podcasts.deleteEpisode(media.serverId, episode.id) })
                }
            }
        } } else null)
}

/** The line under a song's title: its artist, or for a radio station the title it announces for what plays now. */
@Composable
internal fun artistLine(track: Track, state: AppState): String = when {
    track.isRadio -> state.playback.streamTitle?.takeIf { state.playback.trackId == track.id } ?: stringResource(Res.string.radio_live_stream)
    else -> track.metadata.artist.ifBlank { stringResource(Res.string.unknown_artist) }
}

/** Stands in for the seek bar of a live station, which has no length. */
@Composable
internal fun LiveIndicator(modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 72.dp).testTag("live-indicator"), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(AppIcon.LIVE.vector, null, Modifier.size(18.dp))
                Text(stringResource(Res.string.radio_live), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
