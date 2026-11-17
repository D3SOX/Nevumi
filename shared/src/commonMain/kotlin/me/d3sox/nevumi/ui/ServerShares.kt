package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.navidrome.ServerShare
import me.d3sox.nevumi.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Shares server music by link: Android offers the share sheet and Linux copies the link. Null while the server cannot share, or
 * when [ids] holds nothing from the connected server.
 */
@Composable
internal fun rememberShareLink(controller: MusicController, ids: List<String>, title: String): (() -> Unit)? {
    val media by controller.serverMediaController.media.collectAsState()
    // shareIds took [ids] from this server, so the link goes there or nowhere.
    val serverId = controller.state.collectAsState().value.server?.serverId
    val sharer = rememberLinkSharer()
    if (!media.sharingAvailable || ids.isEmpty()) return null
    return { controller.serverMediaController.share(serverId, ids, title) { share -> sharer.share(title, share.url); sharer.copies } }
}

/**
 * The server's song IDs a link to [tracks] shares. Playlists share their songs too, as Subsonic's `createShare` reads song, album
 * and video IDs, and a server other than Navidrome could take a playlist ID for an unrelated song.
 */
@Composable
internal fun shareIds(controller: MusicController, tracks: List<Track>): List<String> {
    val server = controller.state.collectAsState().value.server
    return remember(tracks, server) {
        // A link named after a collection must hold all of it, so songs only on this device rule out a link.
        controller.serverMediaController.serverSongIds(tracks).takeIf { it.size == tracks.size }.orEmpty()
    }
}

/** The account's share links, which can be copied, opened or deleted. */
@Composable
internal fun SharesPage(controller: MusicController, back: () -> Unit) {
    val media by controller.serverMediaController.media.collectAsState()
    var deleting by remember(media.serverId) { mutableStateOf<ServerShare?>(null) }
    val sharer = rememberLinkSharer()
    val browser = LocalUriHandler.current
    val untitled = stringResource(Res.string.shares_untitled)
    fun ServerShare.title() = description.ifBlank { entryTitles.firstOrNull() ?: untitled }
    LaunchedEffect(Unit) { controller.serverMediaController.refresh() }
    SettingsScreen(back, title = { Text(stringResource(Res.string.shares_title)) }, actions = {
        AppIconButton(AppIcon.REFRESH, stringResource(Res.string.shares_refresh), enabled = !media.loading) { controller.serverMediaController.refresh() }
    }, text = {
        LazyColumn(Modifier.fillMaxSize().testTag("shares-page"), contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "progress") { if (media.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            media.error?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
            val shares = media.shares
            if (shares == null) item(key = "unsupported") { Text(stringResource(Res.string.shares_unsupported), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else if (shares.isEmpty()) item(key = "none") { Text(stringResource(Res.string.shares_none), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(shares.orEmpty(), key = { it.id }) { share ->
                var menu by remember { mutableStateOf(false) }
                val details = listOfNotNull(share.url, stringResource(Res.string.shares_visits, share.visitCount),
                    share.expires?.take(10)?.takeIf { it.length == 10 }?.let { stringResource(Res.string.shares_expires, formatReleaseDate(it)) })
                ListItem(modifier = Modifier.testTag("share:${share.id}"),
                    headlineContent = { Text(share.title(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(details.joinToString("\n"), maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(AppIcon.LINK.vector, null) },
                    trailingContent = {
                        Box {
                            AppIconButton(AppIcon.MORE, stringResource(Res.string.library_collection_options_for, share.title())) { menu = true }
                            DropdownMenu(menu, { menu = false }) {
                                AppMenuItem(text = { Text(stringResource(if (sharer.copies) Res.string.shares_copy else Res.string.shares_share)) },
                                    leadingIcon = { Icon((if (sharer.copies) AppIcon.COPY else AppIcon.SHARE).vector, null) },
                                    onClick = { menu = false; sharer.share(share.title(), share.url); if (sharer.copies) controller.serverMediaController.announceCopied() })
                                AppMenuItem(text = { Text(stringResource(Res.string.shares_open)) }, leadingIcon = { Icon(AppIcon.OPEN_EXTERNAL.vector, null) },
                                    onClick = { menu = false; runCatching { browser.openUri(share.url) } })
                                AppMenuItem(text = { Text(stringResource(Res.string.shares_delete)) }, leadingIcon = { Icon(AppIcon.REMOVE.vector, null) },
                                    onClick = { menu = false; deleting = share })
                            }
                        }
                    })
            }
        }
    })
    deleting?.let { share ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(Res.string.shares_delete_named, share.title())) },
            text = { Text(stringResource(Res.string.shares_delete_description)) },
            confirmButton = { TextButton({ controller.serverMediaController.deleteShare(media.serverId, share.id); deleting = null }) { Text(stringResource(Res.string.delete)) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(Res.string.cancel)) } })
    }
}
