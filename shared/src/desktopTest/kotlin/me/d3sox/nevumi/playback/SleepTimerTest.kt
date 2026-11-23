package me.d3sox.nevumi.playback

import kotlinx.coroutines.delay
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
    /** Plays through the crossfade engine around mpv, as the Linux app does; [decoder] wraps each mpv decoder it creates. */
    private fun withPlayer(decoder: (PlaybackEngine) -> PlaybackEngine = { it },
        test: suspend (MusicController, Map<String, Track>) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("sleep-timer")
        val store: MusicStore = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music").toString())
            .associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), CrossfadePlaybackEngine { decoder(MpvPlaybackEngine("null")) })
        try {
            withTimeout(3_000) { controller.state.first { !it.loading } }
            test(controller, tracks)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun endOfSongPausesARepeatingSongAtItsEndAndWaitsAtTheNextSong() {
        val handed = CopyOnWriteArrayList<List<String>>()
        val repeats = CopyOnWriteArrayList<RepeatMode>()
        withPlayer({ decoder -> object : PlaybackEngine by decoder {
            override fun loadQueue(tracks: List<PlayableTrack>, startIndex: Int, positionMs: Long, play: Boolean) {
                handed += tracks.map { it.id }; decoder.loadQueue(tracks, startIndex, positionMs, play)
            }
            override fun updateQueue(tracks: List<PlayableTrack>) { handed += tracks.map { it.id }; decoder.updateQueue(tracks) }
            override fun setRepeat(mode: RepeatMode, playCount: Int?) { repeats += mode; decoder.setRepeat(mode, playCount) }
        } }) { controller, tracks ->
            val first = tracks.getValue("Northern Light").id
            val next = tracks.getValue("Night Walk").id
            controller.playTrack(first, listOf(first, next)).join()
            controller.setQueueRepeat(RepeatMode.ONE).join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            controller.setSleepAfterSong().join()
            withTimeout(3_000) { while (handed.last() != listOf(first) || repeats.last() != RepeatMode.OFF) delay(10) }
            assertEquals(RepeatMode.ONE, controller.engine.status.value.repeatMode, "The engine keeps reporting the listener's repeat")
            assertEquals(listOf(first, next), controller.queue.snapshot().entries.map { it.trackId })
            controller.seek(2_700).join()
            val waiting = withTimeout(4_000) { controller.state.first { !it.sleepAfterSong && it.playback.trackId == next && it.playback.durationMs > 0 } }
            assertEquals(false, waiting.playback.playWhenReady)
            assertEquals(next, waiting.queue.currentTrackId)
            assertTrue(waiting.playback.positionMs < 500, "Waits at the start: ${waiting.playback.positionMs}")
            assertEquals(RepeatMode.ONE, waiting.queue.repeatMode)
            withTimeout(3_000) { while (repeats.last() != RepeatMode.ONE) delay(10) }
            controller.setQueueRepeat(RepeatMode.OFF).join()
            controller.play().join()
            withTimeout(3_000) { controller.state.first { it.playback.playing } }
            assertEquals(listOf(first, next), handed.last(), "The decoder holds the whole queue again")
        }
    }

    @Test fun endOfSongDoesNotCrossfadeIntoTheNextSong() = withPlayer { controller, tracks ->
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

    @Test fun skippingWhileWaitingForTheSongToEndStopsAfterTheNewSong() = withPlayer { controller, tracks ->
        val first = tracks.getValue("Northern Light").id
        val second = tracks.getValue("Long Way Home").id
        val third = tracks.getValue("Night Walk").id
        controller.playTrack(first, listOf(first, second, third)).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing } }
        controller.setSleepAfterSong().join()
        controller.skip(1).join()
        withTimeout(3_000) { controller.state.first { it.playback.trackId == second && it.playback.playing && it.playback.durationMs > 0 } }
        controller.seek(controller.state.value.playback.durationMs - 300).join()
        val waiting = withTimeout(4_000) { controller.state.first { !it.sleepAfterSong && it.playback.trackId == third && it.playback.durationMs > 0 } }
        assertEquals(false, waiting.playback.playWhenReady)
    }

    @Test fun cancelingEndOfSongLetsTheQueueContinue() = withPlayer { controller, tracks ->
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

    @Test fun theTimerFadesTheMusicOutPausesAndRestoresTheVolumeOnTheNextPlay() = withPlayer { controller, tracks ->
        val first = tracks.getValue("Northern Light").id
        controller.setQueueRepeat(RepeatMode.ONE).join()
        controller.playTrack(first, listOf(first)).join()
        controller.setVolume(.8).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing && abs(it.playback.volume - .8) < .01 } }
        controller.playbackController.setSleepTimerMs(2_000).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing && it.playback.volume in .05..0.5 } }
        val paused = withTimeout(4_000) { controller.state.first { it.sleepTimerEndsAt == null && !it.playback.playWhenReady } }
        assertEquals(first, paused.playback.trackId)
        delay(500)
        assertTrue(controller.state.value.playback.volume < .05, "The volume stays low while the pause fades out")
        controller.play().join()
        withTimeout(3_000) { controller.state.first { it.playback.playing && abs(it.playback.volume - .8) < .01 } }
    }

    @Test fun aVolumeChosenDuringTheFadeIsKeptAndRestored() = withPlayer { controller, tracks ->
        val first = tracks.getValue("Northern Light").id
        controller.setQueueRepeat(RepeatMode.ONE).join()
        controller.playTrack(first, listOf(first)).join()
        withTimeout(3_000) { controller.state.first { it.playback.playing } }
        controller.playbackController.setSleepTimerMs(3_000).join()
        withTimeout(3_000) { controller.state.first { it.playback.volume < .7 } }
        controller.setVolume(.3).join()
        val volumes = CopyOnWriteArrayList<Double>()
        withTimeout(4_000) { controller.state.first { volumes += it.playback.volume; it.sleepTimerEndsAt == null && !it.playback.playWhenReady } }
        assertTrue(volumes.drop(2).all { it <= .3 }, "The fade must not raise the chosen volume: $volumes")
        controller.play().join()
        withTimeout(3_000) { controller.state.first { it.playback.playing && abs(it.playback.volume - .3) < .01 } }
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
