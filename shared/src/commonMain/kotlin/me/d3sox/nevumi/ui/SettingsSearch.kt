package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController

internal data class SettingResult(val title: String, val category: String, val icon: AppIcon,
    val keywords: String = "", val destination: String = category)

@Composable
internal fun SettingsSearch(query: String, onQuery: (String) -> Unit, back: () -> Unit,
    controller: MusicController, open: (SettingResult) -> Unit) {
    val index = remember(controller) {
        buildList {
            fun category(name: String, icon: AppIcon, vararg titles: Pair<String, String>) {
                titles.forEach { (title, keywords) -> add(SettingResult(title, name, icon, keywords)) }
            }
            category("Appearance", AppIcon.THEME, "Theme" to "light dark follow system", "AMOLED black" to "dark background oled",
                "Colors" to "system artwork material you dynamic")
            listOf("On launch" to "startup start page last opened",
                "Swipe right" to "gesture play next after favorite rate none", "Swipe left" to "gesture add queue none",
                "Quick action" to "song list button favorite rate playlist play next after none",
                "Show options buttons" to "three dots more menu overflow long press hold hide").forEach { (title, keywords) ->
                add(SettingResult(title, "Library & gestures", settingsIcon(title), keywords))
            }
            add(SettingResult("Customize Home", "Library & gestures", AppIcon.HOME, "reorder home sections", "Customize Home"))
            add(SettingResult("Library tabs", "Library & gestures", AppIcon.TABS, "bottom navigation reorder pages search header", "Library tabs"))
            add(SettingResult("Clear favorites", "Library & gestures", AppIcon.FAVORITE, "remove reset all favorites favourites starred songs"))
            add(SettingResult("Clear ratings", "Library & gestures", AppIcon.RATING, "remove reset all stars ratings"))
            add(SettingResult("Remember scroll positions", "Library & gestures", AppIcon.LIBRARY, "save restore list grid position start top"))
            category("Local music", AppIcon.FOLDER, "Scan music" to "index refresh local device files", "Include folders" to "picker local music",
                "Exclude folders" to "picker exclusions local music")
            category("Playback", AppIcon.PLAY, "Playback speed" to "tempo", "Playback pitch" to "semitones", "Crossfade" to "transition seconds",
                "Show audio info" to "format bitrate sample rate kb/s kHz quality",
                "Show previous tracks in queue" to "history previously played", "Previous track limit" to "queue history count", "Confirm queue jumps" to "confirmation skip play now",
                "Confirm interrupting Up next" to "confirmation replace queue starting music album playlist")
            category("Waveforms", AppIcon.WAVEFORM, "Prepare all waveforms" to "audio waveform background cache precompute progress",
                "Prepare automatically" to "audio waveform background cache precompute")
            add(SettingResult("Audio effects", "Playback", AppIcon.EQUALIZER, "equalizer headphone profiles mono balance limiter preamp", "Audio effects"))
            category("Downloads & cache", AppIcon.DOWNLOAD, "Source priority" to "local downloads server mixed library",
                "Playback cache" to "storage offline", "Download network" to "metered wifi unmetered")
            add(SettingResult("Manage downloads", "Downloads & cache", AppIcon.DOWNLOAD, "offline progress auto playlist", "Manage downloads"))
            add(SettingResult("Music server", "Music server", AppIcon.SERVER, "navidrome subsonic address url username password connect refresh disconnect"))
            category("Listening & scrobbling", AppIcon.HISTORY, "Record listening history" to "statistics recommendations",
                "Clear listening history" to "delete statistics")
            if (controller.scrobblingAvailable) {
                category("Listening & scrobbling", AppIcon.SCROBBLING,
                    "Scrobble after" to "threshold percentage percent lastfm listenbrainz server",
                    "Or after" to "scrobble scrobbling time limit threshold seconds minutes duration lastfm listenbrainz server")
                add(SettingResult("Connect Last.fm", "Listening & scrobbling", AppIcon.SCROBBLING,
                    "lastfm authorization sign in account", "Connect Last.fm"))
                add(SettingResult("Connect ListenBrainz", "Listening & scrobbling", AppIcon.SCROBBLING,
                    "token service address account", "Connect ListenBrainz"))
            }
            if (controller.fileEditingAvailable) category("Files & recovery", AppIcon.UNDO, "Keep undo backups" to "retention days edit tags",
                "Undo backup storage" to "space recovery limit")
            if (controller.serverFilesAvailable) add(SettingResult("Server files (SFTP)", "Files & recovery", AppIcon.SERVER_FILES,
                "ssh host port username directory fingerprint key password upload editing", "Server files (SFTP)"))
            if (controller.tunnel != null) add(SettingResult("Tunnel", "Tunnel", AppIcon.TAILSCALE,
                "tailscale headscale tailnet vpn control server auth key sign in navidrome sftp"))
            if (controller.soundCloudAvailable) add(SettingResult("SoundCloud", "SoundCloud", AppIcon.CLOUD,
                "sc-gate-dl search download gate hypeddit server address format mp3 flac"))
            if (controller.pairedDevices != null) add(SettingResult("Paired devices", "Paired devices", AppIcon.DEVICES, "sync phone desktop pairing"))
            add(SettingResult("Saved queues", "Saved queues", AppIcon.QUEUE, "restore delete playback"))
            add(SettingResult("About Nevumi", "About Nevumi", AppIcon.INFO, "license agpl source code version github"))
        }
    }
    val terms = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
    val results = remember(query, index) { if (terms.isEmpty()) emptyList() else index.filter { result ->
        val text = "${result.title} ${result.category} ${result.keywords}".lowercase()
        terms.all(text::contains)
    } }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SettingsScreen(onDismissRequest = back, title = {
        TextField(query, onQuery, placeholder = { Text("Search settings") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search settings query" },
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface))
    }, text = {
        if (results.isEmpty()) Text(if (terms.isEmpty()) "Find a setting in any category" else "No matching settings",
            Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { "${it.category}:${it.title}" }) { result ->
                SettingsLink(result.title, result.icon, result.category) { focusManager.clearFocus(); keyboard?.hide(); open(result) }
            }
        }
    })
}
