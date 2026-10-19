package me.d3sox.nevumi.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLSurfaceView
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.d3sox.nevumi.visualizer.*
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Composable
internal actual fun MilkDropVisualizer(state: AppState, controller: MusicController, close: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
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
    LaunchedEffect(Unit) {
        try { library = withContext(Dispatchers.IO) { milkDropLibrary(File(context.filesDir, "milkdrop")) } }
        catch (failure: Exception) { error = failure.message ?: "Could not open the presets" }
    }
    val surface = remember(context, library) {
        library?.let { MilkDropSurface(context, it, selected, settings.automatic, eligible, { index ->
            choices.getOrNull(index)?.let { preset ->
                if (controller.state.value.preferences.milkDrop.selectedPresetId != preset.id)
                    change { it.copy(selectedPresetId = preset.id) }
            }
        }, { error = it }) }
    }
    LaunchedEffect(surface, selected, settings.automatic, eligible) {
        surface?.renderer?.selected?.set(selected)
        surface?.renderer?.automatic?.set(settings.automatic)
        surface?.renderer?.eligible?.set(eligible)
        surface?.requestRender()
    }
    LaunchedEffect(surface, state.playback.playing, eligible.isNotEmpty()) {
        surface?.setPlaying(state.playback.playing && eligible.isNotEmpty())
    }
    DisposableEffect(surface, lifecycle) {
        var disposed = false
        fun resume() {
            if (!disposed) surface?.resumeRendering()
        }
        fun pause() { surface?.pauseRendering() }
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> resume(); Lifecycle.Event.ON_PAUSE -> pause(); else -> Unit }
        }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        else surface?.pauseRendering()
        onDispose {
            disposed = true
            lifecycle.lifecycle.removeObserver(observer)
            surface?.release()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ActionButton("Close visualizer", AppIcon.CLOSE, style = ActionStyle.OUTLINED, onClick = close)
                    ActionButton(if (settings.automatic) "Lock preset" else "Unlock preset", if (settings.automatic) AppIcon.LOCK else AppIcon.UNLOCK) {
                        change { it.copy(automatic = !it.automatic) }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    surface?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()
                        .semantics { contentDescription = "MilkDrop visualization" }) }
                    if (presets.isEmpty() && error == null) Column(Modifier.align(androidx.compose.ui.Alignment.Center),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text("Preparing the MilkDrop library…")
                    }
                    error?.let { message -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    } }
                }
                MilkDropControls(choices, selected, { index ->
                    surface?.renderer?.selected?.set(index); surface?.requestRender()
                    change { it.copy(selectedPresetId = choices[index].id) }
                }, settings, ::change)
            }
        }
    }
}

class MilkDropSurface(context: Context, library: MilkDropLibrary, initialSelected: Int, initialAutomatic: Boolean,
    eligible: List<Int>, selected: (Int) -> Unit, error: (String?) -> Unit) : GLSurfaceView(context) {
    private var subscription: AutoCloseable? = null
    private var resumed = false
    private var attached = false
    private var playing = false
    val renderer = MilkDropRenderer(library, initialSelected, initialAutomatic, eligible,
        { ContextCompat.getMainExecutor(context).execute { selected(it) } },
        { ContextCompat.getMainExecutor(context).execute { error(it) } })
    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = false
        setRenderer(renderer)
    }
    fun release() { renderer.stopRendering(); pauseRendering() }
    fun resumeRendering() {
        resumed = true
        if (!attached) return
        updateAudio()
        renderer.resumeRendering(); onResume()
        requestRender()
    }
    fun setPlaying(value: Boolean) { playing = value; renderer.playing.set(value); updateAudio() }
    private fun updateAudio() {
        if (resumed && attached && playing && subscription == null) subscription = VisualizerPcm.subscribe()
        else if (!resumed || !attached || !playing) { subscription?.close(); subscription = null }
        renderMode = if (resumed && attached && playing) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
    }
    fun pauseRendering() {
        resumed = false
        suspendRendering()
    }
    private fun suspendRendering() {
        // Stop tapping audio before waiting for GL, including temporary view detachment.
        updateAudio()
        renderer.pauseRendering(); queueEvent { renderer.release() }; onPause()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        if (resumed) resumeRendering()
    }
    override fun onDetachedFromWindow() {
        attached = false
        suspendRendering()
        super.onDetachedFromWindow()
    }
}

class MilkDropRenderer(private val library: MilkDropLibrary, initialSelected: Int, initialAutomatic: Boolean,
    initialEligible: List<Int>, private val changed: (Int) -> Unit, private val error: (String?) -> Unit) : GLSurfaceView.Renderer {
    private val presets = library.presets
    val selected = AtomicInteger(initialSelected)
    val automatic = AtomicBoolean(initialAutomatic)
    val eligible = AtomicReference(initialEligible)
    val playing = AtomicBoolean(false)
    private var handle = 0L
    private var width = 1
    private var height = 1
    private var loaded = -1
    var failed = false
        private set
    private val closed = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private var changedAt = 0L
    private var drawnAt = 0L
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        release()
        failed = false
        if (closed.get()) return
        try { ProjectMNative.initialize() }
        catch (_: LinkageError) { failed = true; error("Could not load MilkDrop's visualizer library") }
    }
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { this.width = width; this.height = height }
    override fun onDrawFrame(gl: GL10?) {
        if (failed || closed.get() || !active.get()) return
        if (selected.get() !in eligible.get()) return
        try {
            val wait = 33_333_333L - (System.nanoTime() - drawnAt)
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
            val previousFrame = drawnAt
            drawnAt = System.nanoTime()
            if (handle == 0L) handle = ProjectMNative.create(width, height, false, library.textures.path)
            // Pausing stops continuous rendering, so a long gap between frames also restarts the preset timer.
            if (!playing.get() || drawnAt - previousFrame > 1_000_000_000L) changedAt = drawnAt
            if (loaded >= 0 && playing.get() && automatic.get() && System.nanoTime() - changedAt >= 20_000_000_000L)
                selected.updateAndGet { milkDropNextPreset(eligible.get(), it) ?: -1 }
            val next = selected.get()
            if (loaded != next) {
                try {
                    ProjectMNative.loadPreset(handle, presets[next].file, loaded >= 0)
                    error(null)
                } catch (failure: IllegalStateException) {
                    error("${presets[next].name} could not be loaded. Choose another preset.")
                }
                loaded = next; changedAt = System.nanoTime(); changed(next)
            }
            ProjectMNative.render(handle, VisualizerPcm.consume(), width, height)
        } catch (failure: Exception) { failed = true; error(failure.message ?: "Could not render MilkDrop") }
    }
    fun release() { if (handle != 0L) ProjectMNative.destroy(handle); handle = 0L; loaded = -1 }
    fun stopRendering() { closed.set(true) }
    fun resumeRendering() { active.set(true) }
    fun pauseRendering() { active.set(false) }
}

internal actual val milkDropBackgroundSupported = true

@Composable
internal actual fun MilkDropBackground(state: AppState, controller: MusicController, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var library by remember { mutableStateOf<MilkDropLibrary?>(null) }
    LaunchedEffect(Unit) {
        library = runCatching { withContext(Dispatchers.IO) { milkDropLibrary(File(context.filesDir, "milkdrop")) } }.getOrNull()
    }
    val settings = state.preferences.milkDrop
    val choices = remember(library) { library?.presets.orEmpty().map { it.info } }
    val eligible = remember(choices, settings.favorites, settings.favoritesOnly) { milkDropPlaybackIndices(choices, settings) }
    val selected = choices.indexOfFirst { it.id == settings.selectedPresetId }.takeIf { it in eligible }
        ?: eligible.firstOrNull() ?: -1
    val view = remember(context, library) {
        library?.let { MilkDropTextureView(context, it, selected, settings.automatic, eligible) { index ->
            choices.getOrNull(index)?.let { preset ->
                if (controller.state.value.preferences.milkDrop.selectedPresetId != preset.id)
                    controller.updatePreferences { it.copy(milkDrop = it.milkDrop.copy(selectedPresetId = preset.id)) }
            }
        } }
    }
    LaunchedEffect(view, selected, settings.automatic, eligible) {
        view?.renderer?.selected?.set(selected)
        view?.renderer?.automatic?.set(settings.automatic)
        view?.renderer?.eligible?.set(eligible)
        view?.redraw()
    }
    LaunchedEffect(view, state.playback.playing) { view?.setPlaying(state.playback.playing) }
    DisposableEffect(view, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> view?.setResumed(true); Lifecycle.Event.ON_PAUSE -> view?.setResumed(false); else -> Unit }
        }
        lifecycle.lifecycle.addObserver(observer)
        view?.setResumed(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.lifecycle.removeObserver(observer); view?.setResumed(false) }
    }
    if (view == null || eligible.isEmpty()) return
    Box(modifier.clearAndSetSemantics {}) {
        AndroidView({ view }, Modifier.fillMaxSize().then(if (settings.backgroundBlur > 0f)
            Modifier.blur((settings.backgroundBlur * 32).dp, BlurredEdgeTreatment.Rectangle) else Modifier))
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = settings.backgroundDim)))
    }
}

/**
 * Draws MilkDrop into the view hierarchy, unlike [GLSurfaceView]'s separate surface, so Compose can fade, move and blur
 * it with the now playing screen. It renders only while resumed and playing, plus one frame after a preset change.
 */
class MilkDropTextureView(context: Context, library: MilkDropLibrary, selected: Int, automatic: Boolean, eligible: List<Int>,
    changed: (Int) -> Unit) : TextureView(context), TextureView.SurfaceTextureListener {
    val renderer = MilkDropRenderer(library, selected, automatic, eligible,
        { ContextCompat.getMainExecutor(context).execute { changed(it) } }, {})
    private val lock = Object()
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    private var running = false
    private var resumed = false
    private var playing = false
    private var redraw = true
    private var thread: Thread? = null
    private var subscription: AutoCloseable? = null
    init { surfaceTextureListener = this }

    fun setResumed(value: Boolean) { update { resumed = value } }
    fun setPlaying(value: Boolean) { renderer.playing.set(value); update { playing = value } }
    fun redraw() { update { redraw = true } }

    private fun update(change: () -> Unit) {
        val listening = synchronized(lock) {
            change()
            if (resumed) renderer.resumeRendering() else renderer.pauseRendering()
            lock.notifyAll()
            running && resumed && playing
        }
        if (listening && subscription == null) subscription = VisualizerPcm.subscribe()
        else if (!listening) { subscription?.close(); subscription = null }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        update { running = true; surfaceWidth = width; surfaceHeight = height; redraw = true }
        thread = Thread({ render(texture) }, "MilkDrop background").apply { start() }
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        update { surfaceWidth = width; surfaceHeight = height; redraw = true }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        update { running = false }
        thread?.join(); thread = null
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    /** However the render thread ends, nothing draws on this surface anymore, so stop reading audio. */
    private fun stopped() {
        val ended = Thread.currentThread()
        // A replaced surface already has a newer thread, which this one must not stop.
        post { if (thread === ended) update { running = false } }
    }

    private fun render(texture: SurfaceTexture) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (!EGL14.eglInitialize(display, null, 0, null, 0)) { stopped(); return }
        var glContext = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
            if (count[0] == 0) return
            glContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (glContext == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE ||
                !EGL14.eglMakeCurrent(display, surface, surface, glContext)) return
            renderer.onSurfaceCreated(null, null)
            var size = 0 to 0
            while (true) {
                val next = synchronized(lock) {
                    while (running && !(resumed && (playing || redraw))) lock.wait()
                    redraw = false
                    if (running) surfaceWidth to surfaceHeight else null
                } ?: break
                if (next != size) { size = next; renderer.onSurfaceChanged(null, next.first, next.second) }
                renderer.onDrawFrame(null)
                // A failed renderer draws nothing, so stop instead of swapping empty frames.
                if (renderer.failed) break
                EGL14.eglSwapBuffers(display, surface)
            }
        } finally {
            stopped()
            renderer.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (glContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, glContext)
            // Android counts initializations, so this only releases this thread's reference to the display.
            EGL14.eglTerminate(display)
        }
    }
}
