package me.d3sox.nevumi.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.math.PI
import kotlin.math.sin

class LinuxPlaybackTest {
    @Test
    fun theDefaultAudioOutputUsesTheSystemDeviceAndAdvancesPlayback() = runBlocking {
        MpvPlaybackEngine().use { engine ->
            engine.setVolume(0.0)
            val source = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            engine.load(PlayableTrack("system-output", source.toString()))
            val loaded = withTimeout(5_000) { engine.status.first { it.error != null || it.playing && it.positionMs > 100 } }
            assertNull(loaded.error, "Default playback must use a real automatic output: ${loaded.error}")
            assertTrue(loaded.playing)
        }
    }

    @Test
    fun localFilesWithSpacesAccentsAndUriCharactersProduceAudibleSamples() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-local-audio")
        try {
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac")
            val song = Files.copy(fixture, directory.resolve("ROSALÍA - A pale #1 100%.flac"))
            for ((index, location) in listOf(song.toString(), song.toUri().toString()).withIndex()) {
                val output = directory.resolve("output-$index.wav")
                MpvPlaybackEngine("pcm", pcmOutputFile = output.toString()).use { engine ->
                    engine.load(PlayableTrack("local", location))
                    val terminal = withTimeout(5_000) { engine.status.first { it.ended || it.error != null } }
                    assertNull(terminal.error, "Playing $location failed: ${terminal.error}")
                }
                val bytes = Files.readAllBytes(output)
                assertTrue(bytes.size > 10_000, "Playback must output decoded samples, not an empty WAV")
                assertTrue(bytes.drop(44).any { it != 0.toByte() }, "Playback must be audible")
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun pausingTheBufferedEndOfARepeatingSongKeepsItsPositionAndRemainingPlayCount() = runBlocking {
        MpvPlaybackEngine("null").use { engine ->
            val source = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                "Fixture Album", "01 Northern Light.flac").toString()
            engine.setRepeat(RepeatMode.ONE, 2)
            engine.load(PlayableTrack("loop-tail", source), positionMs = 2_600)
            val first = withTimeout(2_000) { engine.status.first { it.playing && it.positionMs >= 2_550 } }
            assertEquals(2, first.repeatPlays)
            engine.pause()
            delay(150)
            val paused = engine.status.value
            assertTrue(!paused.playing && !paused.ended)
            assertTrue(paused.positionMs >= 2_550)
            assertEquals(2, paused.repeatPlays)
            assertEquals(first.playbackId, paused.playbackId)
            engine.play()
            val repeated = try { withTimeout(2_000) { engine.status.first { it.playing && it.positionMs < 250 && it.repeatPlays == 1 } } }
            catch (error: Exception) { throw AssertionError("Resumed repeat: ${engine.status.value}; paused: $paused", error) }
            assertNotEquals(first.playbackId, repeated.playbackId)
            withTimeout(4_000) { engine.status.first { it.ended } }
            Unit
        }
    }

    @Test
    fun theBufferedTailOfASongRemainsPlayingAndCanBePausedBeforeItActuallyEnds() = runBlocking {
        val engine = MpvPlaybackEngine("null")
        try {
            val source = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                "Fixture Album", "01 Northern Light.flac").toString()
            engine.load(PlayableTrack("tail", source), positionMs = 2_600)
            withTimeout(2_000) { engine.status.first { it.playing && !it.ended && it.positionMs >= 2_550 } }
            engine.pause()
            withTimeout(1_000) { engine.status.first { !it.playing } }
            kotlinx.coroutines.delay(100)
            assertEquals(false, engine.status.value.ended, "Paused buffered tail: ${engine.status.value}")
            engine.play()
            withTimeout(2_000) { engine.status.first { it.ended } }
            assertEquals(3_000, engine.status.value.positionMs)
        } finally { engine.close() }
    }

    @Test
    fun aPlaybackIdentitySurvivesPauseSeekAndQueueEditsButChangesForRepeatsAndTheNextEntry() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            val tracks = listOf(PlayableTrack("song", audio, entryId = "first"), PlayableTrack("song", audio, entryId = "next"))
            engine.loadQueue(tracks)
            val first = withTimeout(3_000) { engine.status.first { it.playing } }
            assertNotNull(first.playbackId)
            engine.pause()
            withTimeout(2_000) { engine.status.first { !it.playing } }
            engine.seek(500)
            withTimeout(2_000) { engine.status.first { !it.playing && it.positionMs >= 450 } }
            engine.updateQueue(tracks.map { it.copy(title = "Updated title") })
            assertEquals(first.playbackId, engine.status.value.playbackId)
            engine.setRepeat(RepeatMode.ONE, playCount = 2)
            engine.play()
            withTimeout(2_000) { engine.status.first { it.playing } }
            assertEquals(first.playbackId, engine.status.value.playbackId)
            engine.seek(2_000)
            withTimeout(2_000) { engine.status.first { it.playing && it.positionMs >= 1_950 } }
            val repeated = withTimeout(4_000) { engine.status.first { it.playing && it.positionMs in 50..500 } }
            assertNotNull(repeated.playbackId)
            assertNotEquals(first.playbackId, repeated.playbackId)
            engine.seek(2_700)
            val next = withTimeout(4_000) { engine.status.first { it.entryId == "next" && it.playing } }
            assertNotNull(next.playbackId)
            assertNotEquals(repeated.playbackId, next.playbackId)
        }
    }

    @Test
    fun repeatingASongTwiceContinuesTheQueueWithoutRepeatingTheFollowingSong() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            engine.setRepeat(RepeatMode.ONE, playCount = 2)
            engine.loadQueue(listOf(PlayableTrack("song", audio, entryId = "first"),
                PlayableTrack("song", audio, entryId = "next")), positionMs = 2_000)
            val first = withTimeout(3_000) { engine.status.first { it.entryId == "first" && it.positionMs >= 1_950 && it.playing } }
            assertEquals(RepeatMode.ONE to 2, first.repeatMode to first.repeatPlays)
            val repeated = withTimeout(4_000) { engine.status.first { it.playing && it.positionMs in 50..500 } }
            assertEquals("first" to 1, repeated.entryId to repeated.repeatPlays)
            engine.seek(2_000)
            val next = withTimeout(4_000) { engine.status.first { it.entryId == "next" && it.playing } }
            assertEquals(RepeatMode.OFF to null, next.repeatMode to next.repeatPlays)
            engine.seek(2_000)
            val ended = withTimeout(4_000) { engine.status.first { it.ended } }
            assertEquals("next" to false, ended.entryId to ended.playing)
        }
    }

    @Test
    fun repeatingTheWholeQueueReturnsToItsFirstOccurrenceAndCanBeTurnedOff() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            engine.setRepeat(RepeatMode.ALL)
            engine.loadQueue(listOf(PlayableTrack("song", audio, entryId = "first"),
                PlayableTrack("song", audio, entryId = "last")), startIndex = 1, positionMs = 2_000)
            withTimeout(3_000) { engine.status.first { it.entryId == "last" && it.playing } }
            val repeated = withTimeout(4_000) { engine.status.first { it.entryId == "first" && it.playing } }
            assertEquals("song", repeated.trackId)
            engine.setRepeat(RepeatMode.OFF)
            engine.seek(2_700)
            withTimeout(4_000) { engine.status.first { it.entryId == "last" && it.playing } }
            engine.seek(2_700)
            val ended = withTimeout(4_000) { engine.status.first { it.ended } }
            assertEquals("last" to false, ended.entryId to ended.playing)
        }
    }

    @Test
    fun repeatingOneSongKeepsTheSameOccurrenceAndTurningRepeatOffAdvancesTheQueue() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            engine.setRepeat(RepeatMode.ONE)
            engine.loadQueue(listOf(PlayableTrack("song", audio, entryId = "first"),
                PlayableTrack("song", audio, entryId = "next")), positionMs = 2_000)
            val first = try { withTimeout(3_000) { engine.status.first { it.entryId == "first" && it.positionMs >= 1_950 && it.playing } } }
            catch (error: Exception) { throw AssertionError("Repeat load: ${engine.status.value}", error) }
            val repeated = withTimeout(4_000) { engine.status.first { it.playing && it.positionMs in 50..500 } }
            assertEquals("first", repeated.entryId)
            assertNotNull(repeated.playbackId)
            assertNotEquals(first.playbackId, repeated.playbackId)
            engine.pause()
            withTimeout(2_000) { engine.status.first { !it.playing } }
            engine.seek(1_000)
            withTimeout(2_000) { engine.status.first { !it.playing && it.positionMs >= 950 } }
            engine.seek(0)
            withTimeout(2_000) { engine.status.first { !it.playing && it.positionMs < 50 } }
            assertEquals(repeated.playbackId, engine.status.value.playbackId)
            engine.setRepeat(RepeatMode.OFF)
            engine.seek(2_700)
            engine.play()
            val next = withTimeout(4_000) { engine.status.first { it.entryId == "next" && it.playing } }
            assertEquals("song", next.trackId)
        }
    }

    @Test
    fun aFailedAudioLoadReportsAnErrorAndANewLoadCanRecover() = runBlocking {
        val directory = Files.createTempDirectory("missing-audio")
        try {
            MpvPlaybackEngine(audioOutput = "null").use { engine ->
                engine.load(PlayableTrack("unreadable", directory.resolve("missing.flac").toString(), entryId = "unreadable-entry"))
                val failed = withTimeout(3_000) { engine.status.first { it.error != null } }
                assertEquals("unreadable" to "unreadable-entry", failed.trackId to failed.entryId)
                assertEquals(false, failed.playing)
                val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                    "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
                engine.load(PlayableTrack("readable", audio, entryId = "readable-entry"))
                val recovered = withTimeout(3_000) { engine.status.first { it.playing && it.positionMs > 50 } }
                assertEquals("readable", recovered.trackId)
                assertEquals(null, recovered.error)
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun seekingDuringAnInitialPausedLoadUsesTheRequestedPlayheadAfterDecoding() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            engine.load(PlayableTrack("song", audio), play = false)
            engine.seek(1_500)
            val loaded = withTimeout(3_000) { engine.status.first { it.durationMs == 3_000L } }
            assertEquals(1_500L, loaded.positionMs)
            assertEquals(false, loaded.playing)
        }
    }

    @Test
    fun playingImmediatelyAfterLoadingAPausedTrackStartsItWhenDecodingFinishes() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            engine.load(PlayableTrack("song", audio), play = false)
            engine.play()
            val loaded = withTimeout(3_000) { engine.status.first { it.durationMs == 3_000L } }
            assertEquals(true, loaded.playing)
        }
    }

    @Test
    fun pausingImmediatelyAfterStartingALoadPreventsPlaybackWhenDecodingFinishes() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            engine.load(PlayableTrack("song", audio))
            engine.pause()
            val loaded = withTimeout(3_000) { engine.status.first { it.durationMs == 3_000L } }
            assertEquals(false, loaded.playing)
        }
    }

    @Test
    fun changingPlaybackSpeedChangesTheDecodedTimelineAndKeepsPauseAndSeekWorking() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            engine.load(PlayableTrack("song", audio), play = false)
            withTimeout(3_000) { engine.status.first { it.durationMs == 3_000L } }
            engine.setSpeed(0.5)
            withTimeout(3_000) { engine.status.first { it.speed == 0.5 } }
            engine.play()
            withTimeout(3_000) { engine.status.first { it.playing } }
            delay(500)
            engine.pause()
            val slow = withTimeout(3_000) { engine.status.first { !it.playing } }
            assertTrue(slow.positionMs in 150..400, "Half speed: $slow")
            engine.setSpeed(2.0)
            engine.seek(0)
            withTimeout(3_000) { engine.status.first { it.speed == 2.0 && it.positionMs == 0L } }
            engine.play()
            withTimeout(3_000) { engine.status.first { it.playing } }
            delay(500)
            engine.pause()
            val fast = withTimeout(3_000) { engine.status.first { !it.playing } }
            assertTrue(fast.positionMs in 700..1_400, "Double speed: $fast")
            assertEquals(3_000L, fast.durationMs)
        }
    }

    @Test
    fun editingTheQueuePreservesPausedAudioAndPlaysTheInsertedNextEntry() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            val first = PlayableTrack("song", audio, entryId = "first")
            val last = PlayableTrack("song", audio, entryId = "last")
            engine.loadQueue(listOf(first, last), positionMs = 2_000, play = false)
            withTimeout(3_000) { engine.status.first { it.durationMs == 3_000L } }
            engine.updateQueue(listOf(first, PlayableTrack("song", audio, entryId = "inserted"), last))
            delay(150)
            assertEquals("first" to false, engine.status.value.entryId to engine.status.value.playing)
            kotlin.test.assertTrue(engine.status.value.positionMs in 1_900..2_100)
            engine.play()
            val next = withTimeout(4_000) { engine.status.first { it.entryId == "inserted" && it.playing } }
            assertEquals("song", next.trackId)
        }
    }

    @Test
    fun aQueueAdvancesToTheNextOccurrenceWithoutAnApplicationReload() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
            engine.loadQueue(listOf(PlayableTrack("song", audio, entryId = "first"),
                PlayableTrack("song", audio, entryId = "repeat")), positionMs = 2_700)
            val next = withTimeout(4_000) { engine.status.first { it.entryId == "repeat" && it.playing } }
            assertEquals("song", next.trackId)
        }
    }

    @Test
    fun aLocalFlacCanBeDecodedPlayedPausedAndSought() = runBlocking {
        MpvPlaybackEngine(audioOutput = "null").use { engine ->
            val audio = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment",
                "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            engine.load(PlayableTrack("flac-fixture", audio.toString()))
            val playing = withTimeout(3_000) { engine.status.first { it.playing && it.positionMs > 50 } }
            engine.pause()
            withTimeout(3_000) { engine.status.first { !it.playing && it.durationMs > 0 } }
            engine.seek(1_500)
            val sought = try {
                withTimeout(3_000) { engine.status.first { it.positionMs in 1_490..1_510 } }
            } catch (error: Exception) { throw AssertionError("Seek result: ${engine.status.value}", error) }
            delay(100)

            assertEquals(Triple("flac-fixture", 3_000L, false),
                Triple(playing.trackId, sought.durationMs, engine.status.value.playing))
        }
    }

    @Test
    fun advancingToAStalledStreamReportsBufferingInsteadOfPlaying() = runBlocking {
        // Ten seconds of a mono 16-bit sine; the server sends one and stalls, so mpv is still opening the stream.
        val rate = 44_100
        val samples = rate * 10
        val audio = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
            repeat(samples) { putShort((sin(2 * PI * 440 * it / rate) * 8_000).toInt().toShort()) }
        }.array()
        val sent = 44 + rate * 2
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/song.wav") { exchange ->
                exchange.responseHeaders.add("Content-Type", "audio/wav")
                exchange.sendResponseHeaders(200, audio.size.toLong())
                exchange.responseBody.use { body ->
                    body.write(audio, 0, sent)
                    body.flush()
                    release.await(20, TimeUnit.SECONDS)
                    body.write(audio, sent, audio.size - sent)
                }
            }
            start()
        }
        try {
            MpvPlaybackEngine("null").use { engine ->
                val local = Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac").toString()
                engine.loadQueue(listOf(PlayableTrack("local", local, entryId = "local"),
                    PlayableTrack("stream", "http://127.0.0.1:${server.address.port}/song.wav", entryId = "stream")), positionMs = 2_700)
                withTimeout(5_000) { engine.status.first { it.entryId == "stream" } }
                delay(500)
                val opening = engine.status.value
                assertEquals(listOf("stream", true, false, null), listOf(opening.entryId, opening.playWhenReady, opening.playing, opening.error),
                    "A stream that is still opening must not report playing: $opening")
                release.countDown()
                withTimeout(10_000) { engine.status.first { it.entryId == "stream" && it.playing && it.positionMs > 100 } }
                Unit
            }
        } finally {
            release.countDown()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }
}
