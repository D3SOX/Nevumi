package me.d3sox.nevumi

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.d3sox.nevumi.playback.AndroidPlaybackEngine
import me.d3sox.nevumi.playback.PlayableTrack
import me.d3sox.nevumi.playback.RepeatMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import java.io.File
import java.util.UUID
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore

@RunWith(AndroidJUnit4::class)
class AndroidPlaybackTest {
    @Test
    fun playNextAfterTheQueueEndsRestoresThePlayingState() = verifyPlayNextAfterEnd(gapless = false)

    @Test
    fun playNextAfterAGaplessQueueEndsRestoresThePlayingState() = verifyPlayNextAfterEnd(gapless = true)

    @Test
    fun playNextAfterACrossfadeQueueEndsRestoresThePlayingState() = verifyPlayNextAfterEnd(gapless = true, crossfadeMs = 500)

    private fun verifyPlayNextAfterEnd(gapless: Boolean, crossfadeMs: Long = 0) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "queue-end-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
                try {
                    engine.setRepeat(RepeatMode.OFF)
                    engine.setGaplessPlayback(gapless)
                    engine.setCrossfadeDuration(crossfadeMs)
                    val last = PlayableTrack("last", file.toURI().toString())
                    val next = PlayableTrack("next", file.toURI().toString())
                    engine.load(last, positionMs = 2_000)
                    withTimeout(10_000) { engine.status.first { it.ended && !it.playWhenReady } }
                    engine.updateQueue(listOf(last, next))
                    val resumed = withTimeout(5_000) { engine.status.first {
                        it.entryId == next.entryId && it.positionMs > 100
                    } }
                    assertEquals("Advancing audio must be reported as playing", true, resumed.playing)
                    assertEquals("The transport must show Pause", true, resumed.playWhenReady && !resumed.ended)
                    engine.pause()
                    withTimeout(5_000) { engine.status.first { !it.playWhenReady && !it.playing } }
                    Unit
                } finally { engine.pause(); engine.setGaplessPlayback(true); engine.setCrossfadeDuration(0); engine.close() }
            }
        } finally { file.delete() }
    }

    @Test
    fun repeatingASongTwiceThenContinuesWithoutRepeatingTheFollowingSong() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "finite-repeat-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
                try {
                    engine.setRepeat(RepeatMode.OFF)
                    engine.load(PlayableTrack("previous", file.toURI().toString()), positionMs = 2_700)
                    withTimeout(10_000) { engine.status.first { it.ended } }
                    engine.setRepeat(RepeatMode.ONE, playCount = 2)
                    engine.loadQueue(listOf(PlayableTrack("song", file.toURI().toString(), entryId = "first"),
                        PlayableTrack("song", file.toURI().toString(), entryId = "next")), positionMs = 2_000)
                    val first = withTimeout(10_000) { engine.status.first { it.entryId == "first" && it.playing && it.positionMs >= 1_950 } }
                    assertEquals(RepeatMode.ONE to 2, first.repeatMode to first.repeatPlays)
                    assertNotNull(first.playbackId)
                    engine.pause()
                    withTimeout(5_000) { engine.status.first { !it.playing } }
                    engine.seek(1_500)
                    withTimeout(5_000) { engine.status.first { !it.playing && it.positionMs >= 1_450 } }
                    assertEquals(first.playbackId, engine.status.value.playbackId)
                    engine.play()
                    val repeated = withTimeout(5_000) { engine.status.first { it.playing && it.positionMs in 50..500 } }
                    assertEquals("first" to 1, repeated.entryId to repeated.repeatPlays)
                    assertNotNull(repeated.playbackId)
                    assertNotEquals(first.playbackId, repeated.playbackId)
                    engine.seek(2_000)
                    val next = withTimeout(5_000) { engine.status.first { it.entryId == "next" && it.playing } }
                    assertEquals(RepeatMode.OFF to null, next.repeatMode to next.repeatPlays)
                    assertNotNull(next.playbackId)
                    assertNotEquals(repeated.playbackId, next.playbackId)
                    engine.seek(2_000)
                    val ended = withTimeout(5_000) { engine.status.first { it.ended } }
                    assertEquals("next" to false, ended.entryId to ended.playing)
                    Unit
                } finally { engine.setRepeat(RepeatMode.OFF); engine.pause(); engine.close() }
            }
        } finally { file.delete() }
    }

    @Test
    fun repeatSongKeepsTheSameMediaItemAndTurningItOffAdvancesTheQueue() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "repeat-song-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
                try {
                    engine.setRepeat(RepeatMode.ONE)
                    engine.loadQueue(listOf(PlayableTrack("song", file.toURI().toString(), entryId = "first"),
                        PlayableTrack("song", file.toURI().toString(), entryId = "next")), positionMs = 2_000)
                    withTimeout(10_000) { engine.status.first { it.entryId == "first" && it.playing && it.positionMs >= 1_950 } }
                    val repeated = withTimeout(5_000) { engine.status.first { it.playing && it.positionMs in 50..500 } }
                    assertEquals("first", repeated.entryId)
                    engine.setRepeat(RepeatMode.OFF)
                    engine.seek(2_700)
                    val next = withTimeout(5_000) { engine.status.first { it.entryId == "next" && it.playing } }
                    assertEquals("song", next.trackId)
                    Unit
                } finally { engine.setRepeat(RepeatMode.OFF); engine.pause(); engine.close() }
            }
        } finally { file.delete() }
    }

    @Test
    fun repeatQueueReturnsToTheFirstMediaItemAndTurningItOffAllowsPlaybackToEnd() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "repeat-queue-${UUID.randomUUID()}.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
                try {
                    engine.setRepeat(RepeatMode.ALL)
                    engine.loadQueue(listOf(PlayableTrack("song", file.toURI().toString(), entryId = "first"),
                        PlayableTrack("song", file.toURI().toString(), entryId = "last")), startIndex = 1, positionMs = 2_000)
                    withTimeout(10_000) { engine.status.first { it.entryId == "last" && it.playing } }
                    val repeated = withTimeout(5_000) { engine.status.first { it.entryId == "first" && it.playing } }
                    assertEquals("song", repeated.trackId)
                    engine.setRepeat(RepeatMode.OFF)
                    engine.seek(2_700)
                    withTimeout(5_000) { engine.status.first { it.entryId == "last" && it.playing } }
                    engine.seek(2_700)
                    val ended = withTimeout(5_000) { engine.status.first { it.ended } }
                    assertEquals("last" to false, ended.entryId to ended.playing)
                    Unit
                } finally { engine.setRepeat(RepeatMode.OFF); engine.pause(); engine.close() }
            }
        } finally { file.delete() }
    }

    @Test
    fun aCorruptLocalFileFallsBackToItsDownloadedCopyWithoutChangingTheQueueOccurrence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "decode-fallback-${UUID.randomUUID()}.db"
        val corrupt = File(context.cacheDir, "corrupt-fallback.flac").apply { writeText("This is not FLAC audio") }
        val downloaded = File(context.cacheDir, "download-fallback.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> downloaded.outputStream().use { input.copyTo(it) } }
        val store = openMusicStore(context, name)
        val library = MusicLibrary(store)
        val original = library.importTrack(TrackMetadata("Decode fallback"), TrackSource("corrupt-local", SourceKind.LOCAL, corrupt.toURI().toString()))
        library.importTrack(original.metadata, TrackSource("valid-download", SourceKind.DOWNLOAD, downloaded.toURI().toString()), linkTo = original.id)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = MusicController(library, PlaybackQueue(store), AndroidPlaybackEngine(context, PlaybackService::class.java))
                try {
                    withTimeout(10_000) { controller.state.first { !it.loading } }
                    controller.playTrack(original.id, listOf(original.id)).join()
                    val entry = controller.queue.snapshot().currentEntryId
                    val playing = withTimeout(10_000) { controller.state.first {
                        it.playback.playing && it.playback.sourceId == "valid-download" && it.playback.durationMs == 3_000L
                    } }
                    assertEquals(original.id, playing.playback.trackId)
                    assertEquals(entry, playing.playback.entryId)
                    assertEquals(1, playing.queue.entries.size)
                    assertEquals(null, playing.error)
                } finally { controller.closeAndJoin() }
            }
        } finally { store.close(); context.deleteDatabase(name); corrupt.delete(); downloaded.delete() }
    }

    @Test
    fun insertingANextTrackKeepsThePausedMediaItemAndItsPlayhead() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "insert-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
            try {
                val first = PlayableTrack("song", file.toURI().toString(), entryId = "first")
                val last = PlayableTrack("song", file.toURI().toString(), entryId = "last")
                engine.loadQueue(listOf(first, last), positionMs = 2_000, play = false)
                withTimeout(10_000) { engine.status.first { it.durationMs == 3_000L } }
                engine.updateQueue(listOf(first, PlayableTrack("song", file.toURI().toString(), entryId = "inserted"), last))
                kotlinx.coroutines.delay(200)
                assertEquals(Triple("first", false, 2_000L), Triple(engine.status.value.entryId,
                    engine.status.value.playing, engine.status.value.positionMs))
                engine.play()
                withTimeout(5_000) { engine.status.first { it.entryId == "inserted" && it.playing } }
                Unit
            } finally { engine.close(); file.delete() }
        }
    }

    @Test
    fun theMediaSessionAdvancesBetweenRepeatedQueueOccurrences() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "queue-fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val engine = AndroidPlaybackEngine(context, PlaybackService::class.java)
            try {
                engine.loadQueue(listOf(PlayableTrack("song", file.toURI().toString(), entryId = "first"),
                    PlayableTrack("song", file.toURI().toString(), entryId = "repeat")), positionMs = 2_700)
                val next = withTimeout(10_000) { engine.status.first { it.entryId == "repeat" && it.playing } }
                assertEquals("song", next.trackId)
            } finally { engine.close(); file.delete() }
        }
    }

    @Test
    fun aLocalFlacPlaysThroughTheAndroidMediaSessionAndCanBePaused() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "fixture.flac")
        instrumentation.context.assets.open("fixture.flac").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            lateinit var engine: AndroidPlaybackEngine
            instrumentation.runOnMainSync { engine = AndroidPlaybackEngine(context, PlaybackService::class.java) }
            try {
                engine.load(PlayableTrack("flac-fixture", file.toURI().toString()))
                withTimeout(10_000) { engine.status.first { it.playing && it.positionMs > 50 } }
                engine.pause()
                val paused = withTimeout(5_000) { engine.status.first { !it.playing && it.durationMs > 0 } }

                assertEquals("flac-fixture" to 3_000L, paused.trackId to paused.durationMs)
            } finally { engine.close(); file.delete() }
        }
    }
}
