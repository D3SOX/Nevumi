package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.*
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class TrackMetadataLayoutUiTest {
    @Test
    fun favoriteAndRatingShareTheStatisticsLineWhenThereIsRoomAndWrapWhenNeeded() {
        val directory = Files.createTempDirectory("track-metadata-layout")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        var elapsed = 0L
        val history = ListeningHistory(store, library, wallClock = { elapsed }, elapsedClock = { elapsed })
        runBlocking {
            val track = library.importTrack(TrackMetadata("Song", "Artist", durationMs = 2_000),
                TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            library.setFavorite(track.id, true)
            library.setRating(track.id, 4)
            val playing = PlaybackStatus(track.id, playing = true, durationMs = 2_000, playbackId = "listen")
            history.record(playing)
            elapsed = 1_000
            history.record(playing.copy(playing = false, ended = true))
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), listeningHistory = history)
        try {
            runComposeUiTest {
                val width = mutableStateOf(640.dp)
                setContent { Box(Modifier.width(width.value)) { MusicPlayerApp(player) } }
                waitUntil(timeoutMillis = 3_000) { player.state.value.started }
                val statistics = onNodeWithText("1 play · 1s listened", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val heart = onNodeWithContentDescription("Favorited", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val rating = onNodeWithContentDescription("Rating: 4 stars", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(abs(statistics.center.y - heart.center.y) < 2, "Favorite should share the statistics row: statistics=$statistics, heart=$heart")
                assertTrue(abs(statistics.center.y - rating.center.y) < 2, "Rating should share the statistics row: statistics=$statistics, rating=$rating")
                assertTrue(heart.left < rating.left && rating.left < statistics.left, "Favorite and rating must precede the listening statistics")
                runOnIdle { width.value = 360.dp }
                onNodeWithText("1 play · 1s listened").assertIsDisplayed()
                onNodeWithContentDescription("Favorited").assertIsDisplayed()
                onNodeWithContentDescription("Rating: 4 stars").assertIsDisplayed()
                val narrowStatistics = onNodeWithText("1 play · 1s listened", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val narrowHeart = onNodeWithContentDescription("Favorited", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val narrowRating = onNodeWithContentDescription("Rating: 4 stars", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(abs(narrowHeart.center.y - narrowRating.center.y) < 2 && narrowHeart.left < narrowRating.left,
                    "Keep favorite and rating together before the statistics when space is limited")
                assertTrue(narrowStatistics.top >= narrowHeart.top)
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
