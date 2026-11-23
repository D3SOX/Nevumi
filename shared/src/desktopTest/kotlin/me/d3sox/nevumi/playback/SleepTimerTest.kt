package me.d3sox.nevumi.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.LocalFiles
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.Track
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SleepTimerTest {
    private fun withPlayer(engine: (PlaybackEngine) -> PlaybackEngine = { it },
        test: suspend (MusicController, Map<String, Track>) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("sleep-timer")
        val store: MusicStore = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString())
            .associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), engine(MpvPlaybackEngine("null")))
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            test(controller, tracks)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun endOfSongPausesARepeatingSongAtItsEndAndWaitsAtTheNextSong() {
        val handed = CopyOnWriteArrayList<List<String>>()
        val repeats = CopyOnWriteArrayList<RepeatMode>()
        withPlayer({ decoder -> object : PlaybackEngine by decoder {
            override fun updateQueue(tracks: List<PlayableTrack>) { handed += tracks.map { it.id }; decoder.updateQueue(tracks) }
            override fun setRepeat(mode: RepeatMode, playCount: Int?) { repeats += mode; decoder.setRepeat(mode, playCount) }
        } }) { controller, tracks ->
            val first = tracks.getValue("Northern Light").id
            val next = tracks.getValue("Night Walk").id
            controller.playTrack(first, listOf(first, next)).join()
            controller.setQueueRepeat(RepeatMode.ONE).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            controller.setSleepAfterSong().join()
            assertTrue(controller.state.value.sleepAfterSong)
            assertEquals(listOf(first), handed.last(), "The decoder holds no song to move on to")
            assertEquals(RepeatMode.OFF, repeats.last(), "The decoder does not repeat the song")
            assertEquals(RepeatMode.ONE, controller.state.value.queue.repeatMode, "The listener's repeat stays")
            controller.seek(2_700).join()
            val waiting = withTimeout(4_000) { controller.state.first { !it.sleepAfterSong && it.playback.trackId == next && it.playback.durationMs > 0 } }
            assertEquals(false, waiting.playback.playWhenReady)
            assertEquals(next, waiting.queue.currentTrackId)
            assertTrue(waiting.playback.positionMs < 500, "Waits at the start: ${waiting.playback.positionMs}")
            assertEquals(RepeatMode.ONE, waiting.queue.repeatMode)
            assertEquals(RepeatMode.ONE, repeats.last(), "The decoder repeats again")
            assertEquals(listOf(first, next), controller.playbackController.playableQueue(controller.queue.snapshot()).map { it.id })
        }
    }

    @Test fun endOfSongDoesNotCrossfadeIntoTheNextSong() = withPlayer({ CrossfadePlaybackEngine { MpvPlaybackEngine("null") } }) { controller, tracks ->
        val first = tracks.getValue("Northern Light").id
        val next = tracks.getValue("Night Walk").id
        controller.updatePreferences { it.copy(crossfadeDurationMs = 2_000) }.join()
        controller.playTrack(first, listOf(first, next)).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing } }
        controller.setSleepAfterSong().join()
        controller.seek(2_000).join()
        val waiting = withTimeout(5_000) { controller.state.first { !it.sleepAfterSong && it.playback.trackId == next && it.playback.durationMs > 0 } }
        assertEquals(false, waiting.playback.playWhenReady)
        assertTrue(waiting.playback.positionMs < 500, "Waits at the start: ${waiting.playback.positionMs}")
    }

    @Test fun cancelingEndOfSongGivesTheDecoderTheRestOfTheQueueAgain() = withPlayer { controller, tracks ->
        val first = tracks.getValue("Northern Light").id
        val next = tracks.getValue("Night Walk").id
        controller.playTrack(first, listOf(first, next)).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing } }
        controller.setSleepAfterSong().join()
        controller.setSleepTimer(null).join()
        assertEquals(false, controller.state.value.sleepAfterSong)
        controller.seek(2_700).join()
        withTimeout(4_000) { controller.state.first { it.playback.trackId == next && it.playback.playing } }
    }

    @Test fun theTimerFadesTheMusicOutPausesAndRestoresTheVolume() = withPlayer { controller, tracks ->
        val first = tracks.getValue("Northern Light").id
        controller.setQueueRepeat(RepeatMode.ONE).join()
        controller.playTrack(first, listOf(first)).join()
        controller.setVolume(.8).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing && abs(it.playback.volume - .8) < .01 } }
        controller.playbackController.setSleepTimerMs(2_000).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.volume in .05..0.5 } }
        val paused = withTimeout(4_000) { controller.state.first { it.sleepTimerEndsAt == null && !it.playback.playWhenReady } }
        assertEquals(first, paused.playback.trackId)
        withTimeout(3_000) { controller.state.first { abs(it.playback.volume - .8) < .01 } }
    }

    @Test fun aVolumeChosenDuringTheFadeIsKeptAndRestored() = withPlayer { controller, tracks ->
        val first = tracks.getValue("Northern Light").id
        controller.setQueueRepeat(RepeatMode.ONE).join()
        controller.playTrack(first, listOf(first)).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing } }
        controller.playbackController.setSleepTimerMs(3_000).join()
        withTimeout(3_000) { controller.state.first { it.playback.volume < .7 } }
        controller.setVolume(0.0).join()
        val volumes = CopyOnWriteArrayList<Double>()
        withTimeout(4_000) { controller.state.first { volumes += it.playback.volume; it.sleepTimerEndsAt == null && !it.playback.playWhenReady } }
        kotlinx.coroutines.delay(500)
        assertEquals(0.0, controller.state.value.playback.volume, "The muted volume stays: $volumes")
        assertTrue(volumes.drop(2).all { it < .01 }, "The fade must not raise a muted volume: $volumes")
    }

    @Test fun changingTheTimerDuringTheFadeRestoresTheVolume() = withPlayer { controller, tracks ->
        val first = tracks.getValue("Northern Light").id
        controller.setQueueRepeat(RepeatMode.ONE).join()
        controller.playTrack(first, listOf(first)).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing } }
        controller.playbackController.setSleepTimerMs(3_000).join()
        withTimeout(3_000) { controller.state.first { it.playback.volume < .7 } }
        val deadline = requireNotNull(controller.state.value.sleepTimerEndsAt)
        controller.extendSleepTimer(5).join()
        assertTrue(abs(requireNotNull(controller.state.value.sleepTimerEndsAt) - deadline - 5 * 60_000) < 100)
        withTimeout(3_000) { controller.state.first { it.playback.volume > .99 } }
        controller.setSleepTimer(null).join()
        assertEquals(null, controller.state.value.sleepTimerEndsAt)
        assertTrue(controller.state.value.playback.playWhenReady)
    }
}
