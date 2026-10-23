package me.d3sox.nevumi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
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
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
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

private val compact = DpSize(110.dp, 40.dp)
private val wide = DpSize(240.dp, 40.dp)
private val tall = DpSize(240.dp, 100.dp)
private const val ARTWORK_PIXELS = 256

class NowPlayingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(compact, wide, tall))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val controller = (context.applicationContext as MusicApplication).controller
        // A cold process is still restoring its queue; wait for it so the widget does not flash "Nothing playing".
        val state = withTimeoutOrNull(5_000) { controller.state.first { it.libraryReady } } ?: controller.state.value
        val playing = state.nowPlaying()
        val artwork = playing.artwork?.let { path -> withContext(Dispatchers.IO) { runCatching { decodeArtwork(path) }.getOrNull() } }
        provideContent { GlanceTheme { Content(playing, artwork) } }
    }
}

private fun decodeArtwork(path: String): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    val scale = minOf(1f, ARTWORK_PIXELS / maxOf(info.size.width, info.size.height).toFloat())
    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
}

@Composable
private fun Content(playing: NowPlaying, artwork: Bitmap?) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(android.R.dimen.system_app_widget_background_radius).padding(horizontal = 12.dp, vertical = 4.dp)
        .clickable(actionStartActivity<MainActivity>()), Alignment.Center) {
        // Three buttons do not fit the smallest size, so it only plays and pauses.
        // A one-row widget can be as short as 40 dp, so artwork and buttons shrink to the height left inside the padding.
        val fit = minOf(48.dp, size.height - 8.dp)
        val title = @Composable {
            Text(playing.title ?: context.getString(R.string.nothing_playing), maxLines = 1,
                style = TextStyle(GlanceTheme.colors.onSurface, 15.sp, FontWeight.Medium))
            if (playing.artist.isNotEmpty()) Text(playing.artist, maxLines = 1, style = TextStyle(GlanceTheme.colors.onSurfaceVariant, 13.sp))
        }
        if (size.width < wide.width) Controls(playing.playing, fit, skips = false)
        else if (size.height >= tall.height) Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // The buttons sit under the title, and the artwork takes the width the buttons and padding leave, up to 80 dp.
            val button = 40.dp
            Artwork(artwork, GlanceModifier.size(minOf(80.dp, size.height - 8.dp, size.width - 48.dp - button * 3)).cornerRadius(8.dp))
            Column(GlanceModifier.defaultWeight().padding(start = 12.dp)) {
                title()
                Controls(playing.playing, button)
            }
        }
        else Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork, GlanceModifier.size(fit).cornerRadius(8.dp))
            Column(GlanceModifier.defaultWeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { title() }
            Controls(playing.playing, fit)
        }
    }
}

@Composable
private fun Artwork(bitmap: Bitmap?, modifier: GlanceModifier) {
    if (bitmap != null) Image(ImageProvider(bitmap), null, modifier, ContentScale.Crop)
    else Box(modifier.background(GlanceTheme.colors.surfaceVariant), Alignment.Center) {
        Image(ImageProvider(R.drawable.ic_music_note), null, GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
    }
}

@Composable
private fun Controls(playing: Boolean, buttonSize: androidx.compose.ui.unit.Dp, skips: Boolean = true) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (skips) CircleIconButton(ImageProvider(R.drawable.ic_skip_previous), context.getString(R.string.playback_previous), PlaybackAction.action("previous"), GlanceModifier.size(buttonSize),
            backgroundColor = null, contentColor = GlanceTheme.colors.onSurface)
        CircleIconButton(ImageProvider(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            context.getString(if (playing) R.string.playback_pause else R.string.playback_play), PlaybackAction.action("toggle"), GlanceModifier.size(buttonSize),
            backgroundColor = GlanceTheme.colors.primary, contentColor = GlanceTheme.colors.onPrimary)
        if (skips) CircleIconButton(ImageProvider(R.drawable.ic_skip_next), context.getString(R.string.playback_next), PlaybackAction.action("next"), GlanceModifier.size(buttonSize),
            backgroundColor = null, contentColor = GlanceTheme.colors.onSurface)
    }
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
