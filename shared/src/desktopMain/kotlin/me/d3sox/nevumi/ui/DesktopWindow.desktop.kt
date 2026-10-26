package me.d3sox.nevumi.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.localMusicExtensions
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.drop_music
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.net.URI

internal actual val desktopWindowSettings = true

/**
 * Whether closing the window hides it instead of quitting: always with the tray icon, which brings it back,
 * and while music plays when it should keep playing.
 */
fun AppState.closeHidesWindow() = preferences.trayIcon || preferences.keepPlayingInBackground && playback.playing

/** Sorts dropped file URIs into music folders and songs, as canonical paths; other files are left out. */
internal fun droppedMusic(uris: List<String>): Pair<List<String>, List<String>> {
    val files = uris.mapNotNull { uri -> runCatching { File(URI(uri)) }.getOrNull()?.canonicalFile }
    return files.filter { it.isDirectory }.map { it.path } to
        files.filter { it.isFile && it.extension.lowercase() in localMusicExtensions }.map { it.path }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun MusicDropTarget(controller: MusicController, content: @Composable () -> Unit) {
    var hovering by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val target = remember(controller) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val uris = (event.dragData() as? DragData.FilesList)?.readFiles() ?: return false
                scope.launch(Dispatchers.IO) {
                    val (folders, songs) = droppedMusic(uris)
                    controller.addMusic(folders, songs)
                }
                return true
            }
        }
    }
    Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)) {
        content()
        if (hovering) MusicDropHint()
    }
}

/** Covers the window while music is dragged over it and says what dropping does. */
@Composable
internal fun MusicDropHint() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)).testTag("music-drop"),
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).widthIn(max = 420.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(28.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp)).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(AppIcon.IMPORT.vector, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(Res.string.drop_music), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center)
        }
    }
}
