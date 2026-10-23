package me.d3sox.nevumi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.d3sox.nevumi.app.AppState
import me.d3sox.nevumi.app.MusicController
import java.io.File

/** What the home screen widget and the quick settings tile show for the current queue entry. */
data class NowPlaying(val title: String? = null, val artist: String = "", val artwork: String? = null,
    val playing: Boolean = false, val queued: Boolean = false)

fun AppState.nowPlaying(): NowPlaying {
    val trackId = playback.trackId ?: queue.entries.getOrNull(queue.currentIndex)?.trackId
    val track = tracks.firstOrNull { it.id == trackId }
    return NowPlaying(track?.metadata?.title, track?.metadata?.artist.orEmpty(), trackId?.let(artwork::get),
        playback.playWhenReady && !playback.ended && playback.error == null, queue.entries.isNotEmpty())
}

private const val ARTWORK_PIXELS = 384

class NowPlayingWidget : GlanceAppWidget() {
    // The layout follows the placed size, which launchers with tall cells make much larger than any fixed breakpoint.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val controller = (context.applicationContext as MusicApplication).controller
        val ready = controller.state.filter { it.libraryReady }
        // A cold process is still restoring its queue; wait for it so the widget does not flash "Nothing playing".
        val initial = (withTimeoutOrNull(5_000) { ready.first() } ?: controller.state.value).nowPlaying()
        val initialArtwork = initial.artwork?.let { loadArtwork(it) }
        provideContent {
            // Covers load after the library is ready, so the running session follows the state instead of keeping its first snapshot.
            val playing by remember { ready.map { it.nowPlaying() }.distinctUntilChanged() }.collectAsState(initial)
            val artwork by produceState(initialArtwork, playing.artwork) { value = playing.artwork?.let { loadArtwork(it) } }
            GlanceTheme { Content(playing, artwork) }
        }
    }
}

private suspend fun loadArtwork(path: String): Bitmap? = withContext(Dispatchers.IO) { runCatching { decodeArtwork(path) }.getOrNull() }

private fun decodeArtwork(path: String): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    val scale = minOf(1f, ARTWORK_PIXELS / maxOf(info.size.width, info.size.height).toFloat())
    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
}

@Composable
private fun Content(playing: NowPlaying, artwork: Bitmap?) {
    val context = LocalContext.current
    val size = LocalSize.current
    // A one-row widget can be as short as 40 dp, so it only keeps a thin vertical margin.
    val padding = if (size.height >= 72.dp) 12.dp else 4.dp
    val inner = DpSize(size.width - 24.dp, size.height - padding * 2)
    Box(GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(android.R.dimen.system_app_widget_background_radius).padding(horizontal = 12.dp, vertical = padding)
        .clickable(actionStartActivity<MainActivity>()), Alignment.Center) {
        val title = @Composable {
            Text(playing.title ?: context.getString(R.string.nothing_playing), maxLines = 1,
                style = TextStyle(GlanceTheme.colors.onSurface, 15.sp, FontWeight.Medium))
            if (playing.artist.isNotEmpty()) Text(playing.artist, maxLines = 1, style = TextStyle(GlanceTheme.colors.onSurfaceVariant, 13.sp))
        }
        when {
            // Three buttons and a title do not fit a narrow widget, so it shows the cover above play and pause when there is room.
            size.width < 200.dp -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                val button = minOf(48.dp, inner.height)
                val cover = minOf(inner.width, inner.height - button - 8.dp)
                if (cover >= 48.dp) {
                    Artwork(artwork, GlanceModifier.size(cover))
                    Box(GlanceModifier.padding(top = 8.dp)) { PlayButton(playing.playing, button) }
                } else PlayButton(playing.playing, button)
            }
            // A tall row puts the title above the buttons, and the cover fills the height up to 40% of the width.
            inner.height >= 96.dp -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Artwork(artwork, GlanceModifier.size(minOf(inner.height, inner.width * 0.4f)))
                Column(GlanceModifier.defaultWeight().fillMaxHeight().padding(start = 16.dp)) {
                    title()
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(GlanceModifier.defaultWeight(), Alignment.Center) { SkipButton(-1, 44.dp) }
                        Box(GlanceModifier.defaultWeight(), Alignment.Center) { PlayButton(playing.playing, 52.dp) }
                        Box(GlanceModifier.defaultWeight(), Alignment.Center) { SkipButton(1, 44.dp) }
                    }
                }
            }
            else -> Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                val fit = minOf(48.dp, inner.height)
                Artwork(artwork, GlanceModifier.size(fit))
                Column(GlanceModifier.defaultWeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { title() }
                SkipButton(-1, fit)
                PlayButton(playing.playing, fit)
                SkipButton(1, fit)
            }
        }
    }
}

@Composable
private fun Artwork(bitmap: Bitmap?, modifier: GlanceModifier) {
    val shape = modifier.cornerRadius(12.dp)
    if (bitmap != null) Image(ImageProvider(bitmap), null, shape, ContentScale.Crop)
    else Box(shape.background(GlanceTheme.colors.secondaryContainer), Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_music_note), null, GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer))
    }
}

@Composable
private fun PlayButton(playing: Boolean, size: Dp) {
    val context = LocalContext.current
    CircleIconButton(ImageProvider(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
        context.getString(if (playing) R.string.playback_pause else R.string.playback_play), PlaybackAction.action("toggle"), GlanceModifier.size(size),
        backgroundColor = GlanceTheme.colors.primary, contentColor = GlanceTheme.colors.onPrimary)
}

@Composable
private fun SkipButton(direction: Int, size: Dp) {
    val context = LocalContext.current
    CircleIconButton(ImageProvider(if (direction < 0) R.drawable.ic_skip_previous else R.drawable.ic_skip_next),
        context.getString(if (direction < 0) R.string.playback_previous else R.string.playback_next),
        PlaybackAction.action(if (direction < 0) "previous" else "next"), GlanceModifier.size(size),
        backgroundColor = null, contentColor = GlanceTheme.colors.onSurface)
}

/** Runs a transport command in the app process; the playback engine queues it until the session is connected. */
class PlaybackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val controller = (context.applicationContext as MusicApplication).controller
        when (parameters[command]) {
            "toggle" -> controller.togglePlayback()
            "next" -> controller.skip(1)
            "previous" -> controller.skip(-1)
            else -> null
        }?.join()
    }

    companion object {
        private val command = ActionParameters.Key<String>("command")
        fun action(name: String) = actionRunCallback<PlaybackAction>(actionParametersOf(command to name))
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); (context.applicationContext as MusicApplication).widgetUpdates.start() }
    override fun onDisabled(context: Context) { super.onDisabled(context); (context.applicationContext as MusicApplication).widgetUpdates.stop() }
}

/** Refreshes placed widgets when the shown song or play state changes, never on position ticks. */
class NowPlayingWidgetUpdates(private val context: Context, private val controller: MusicController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            controller.state.filter { it.libraryReady }.map { it.nowPlaying() }.distinctUntilChanged().collect { NowPlayingWidget().updateAll(context) }
        }
    }

    fun stop() { job?.cancel(); job = null }

    /** Picks updates back up after a process restart while widgets are still placed. */
    fun resume() { scope.launch { if (GlanceAppWidgetManager(context).getGlanceIds(NowPlayingWidget::class.java).isNotEmpty()) start() } }
}
