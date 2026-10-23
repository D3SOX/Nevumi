package me.d3sox.nevumi

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Quick settings tile that toggles playback and names the current song. */
class NowPlayingTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    override fun onStartListening() {
        listening = scope.launch {
            (application as MusicApplication).controller.state.filter { it.libraryReady }.map { it.nowPlaying() }.distinctUntilChanged().collect { playing ->
                qsTile?.apply {
                    state = when { !playing.queued -> Tile.STATE_UNAVAILABLE; playing.playing -> Tile.STATE_ACTIVE; else -> Tile.STATE_INACTIVE }
                    subtitle = playing.title ?: getString(R.string.nothing_playing)
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() { listening?.cancel(); listening = null }

    override fun onClick() { (application as MusicApplication).controller.togglePlayback() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
