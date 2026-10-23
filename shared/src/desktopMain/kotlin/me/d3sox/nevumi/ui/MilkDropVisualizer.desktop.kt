package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.visualizer.*
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import me.d3sox.nevumi.resources.Res
import me.d3sox.nevumi.resources.sources_close_visualizer
import me.d3sox.nevumi.resources.sources_milkdrop_load_failed
import me.d3sox.nevumi.resources.sources_milkdrop_open_failed
import me.d3sox.nevumi.resources.sources_milkdrop_render_failed
import me.d3sox.nevumi.resources.sources_milkdrop_window_hint
import me.d3sox.nevumi.resources.sources_preparing_milkdrop
import me.d3sox.nevumi.resources.sources_preset_load_failed
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun MilkDropVisualizer(state: AppState, controller: MusicController, close: () -> Unit) {
    var library by remember { mutableStateOf<MilkDropLibrary?>(null) }
    val presets = library?.presets.orEmpty()
    val choices = remember(library) { presets.map { it.info } }
    val settings = state.preferences.milkDrop
    val eligible = remember(choices, settings.favorites, settings.favoritesOnly) { milkDropPlaybackIndices(choices, settings) }
    val selected = choices.indexOfFirst { it.id == settings.selectedPresetId }.takeIf { it in eligible }
        ?: eligible.firstOrNull() ?: -1
    fun change(update: (MilkDropSettings) -> MilkDropSettings) {
        controller.updatePreferences { it.copy(milkDrop = update(it.milkDrop)) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dismissed by rememberUpdatedState(close)
    val wanted = remember { AtomicInteger(0) }
    val advance = remember { AtomicBoolean(settings.automatic) }
    val pool = remember { AtomicReference(eligible) }
    val playing = remember { AtomicBoolean(state.playback.playing) }
    var audio by remember { mutableStateOf<DesktopVisualizerAudio?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(library, selected, eligible, settings.automatic) {
        wanted.set(selected); pool.set(eligible); advance.set(settings.automatic)
    }
    SideEffect { playing.set(state.playback.playing) }
    DisposableEffect(state.playback.playing, library, eligible.isNotEmpty()) {
        val monitor = if (state.playback.playing && eligible.isNotEmpty()) DesktopVisualizerAudio() else null
        val subscription = monitor?.let { VisualizerPcm.subscribe() }
        audio = monitor
        onDispose { monitor?.close(); subscription?.close() }
    }
    LaunchedEffect(audio) { notice = null; audio?.notice?.collect { notice = it } }
    LaunchedEffect(Unit) {
        try {
            library = withContext(Dispatchers.IO) {
                ProjectMNative.initialize()
                val base = System.getenv("XDG_DATA_HOME") ?: "${System.getProperty("user.home")}/.local/share"
                milkDropLibrary(File(base, "me.d3sox.nevumi/milkdrop"))
            }
        } catch (_: LinkageError) { error = getString(Res.string.sources_milkdrop_load_failed) }
        catch (failure: Exception) { error = failure.message ?: getString(Res.string.sources_milkdrop_open_failed) }
    }
    DisposableEffect(library) {
        val running = AtomicBoolean(true)
        wanted.set(selected); pool.set(eligible); advance.set(settings.automatic)
        val thread = if (presets.isNotEmpty()) Thread({
            var handle = 0L
            try {
                handle = ProjectMNative.create(960, 640, true, requireNotNull(library).textures.path)
                var current = -1
                var changedAt = System.nanoTime()
                while (running.get()) {
                    val frameStarted = System.nanoTime()
                    if (!playing.get()) changedAt = System.nanoTime()
                    if (playing.get() && advance.get() && System.nanoTime() - changedAt >= 20_000_000_000L)
                        wanted.updateAndGet { milkDropNextPreset(pool.get(), it) ?: -1 }
                    val next = wanted.get()
                    val preview = next != current && next in pool.get()
                    if (preview) {
                        try {
                            ProjectMNative.loadPreset(handle, presets[next].file, current >= 0)
                            scope.launch { error = null }
                        } catch (failure: IllegalStateException) {
                            scope.launch { error = getString(Res.string.sources_preset_load_failed, presets[next].name) }
                        }
                        current = next; changedAt = System.nanoTime()
                        scope.launch {
                            if (controller.state.value.preferences.milkDrop.selectedPresetId != choices[next].id)
                                change { it.copy(selectedPresetId = choices[next].id) }
                        }
                    }
                    val animate = next in pool.get() && (playing.get() || preview)
                    val commands = ProjectMNative.render(handle, if (playing.get()) VisualizerPcm.consume() else floatArrayOf(), 960, 640, animate)
                    if (commands and 4 != 0) { scope.launch { dismissed() }; break }
                    if (commands and 1 != 0) wanted.updateAndGet { milkDropNextPreset(pool.get(), it) ?: -1 }
                    if (commands and 2 != 0) wanted.updateAndGet { milkDropNextPreset(pool.get(), it, -1) ?: -1 }
                    val wait = 33_333_333L - (System.nanoTime() - frameStarted)
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            } catch (_: InterruptedException) { /* Closing the visualizer wakes its render loop. */ }
            catch (failure: Exception) { scope.launch { error = failure.message ?: getString(Res.string.sources_milkdrop_render_failed) } }
            finally { if (handle != 0L) ProjectMNative.destroy(handle) }
        }, "Nevumi MilkDrop").apply { isDaemon = true; start() } else null
        onDispose {
            running.set(false); thread?.interrupt()
            // Finish GL destruction while the application and its native libraries are still alive.
            thread?.join(2_000)
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("MilkDrop") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.sources_milkdrop_window_hint))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (presets.isEmpty() && error == null) {
                Text(stringResource(Res.string.sources_preparing_milkdrop))
                CircularProgressIndicator()
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            MilkDropControls(choices, selected, { index -> wanted.set(index); change { it.copy(selectedPresetId = choices[index].id) } }, settings, ::change)
        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(Res.string.sources_close_visualizer)) } })
}

// The desktop visualizer draws in its own native window, so it can't sit behind Compose content.
internal actual val milkDropBackgroundSupported = false

@Composable
internal actual fun MilkDropBackground(state: AppState, controller: MusicController, modifier: Modifier) = Unit
