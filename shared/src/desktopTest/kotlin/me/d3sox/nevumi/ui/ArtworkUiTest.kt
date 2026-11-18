package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import me.d3sox.nevumi.settings.MusicPreferences

@OptIn(ExperimentalTestApi::class)
class ArtworkUiTest {
    private fun image(color: Int): ByteArray {
        val bitmap = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (x in 0..31) for (y in 0..31) bitmap.setRGB(x, y, color)
        return ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
    }

    @Test
    fun returningToACoverKeepsTheDecodedImageVisibleWithoutReadingTheFileAgain() {
        val directory = Files.createTempDirectory("warm-artwork")
        val file = directory.resolve("cover.png").toFile().apply { writeBytes(image(0xE05040)) }
        val visible = androidx.compose.runtime.mutableStateOf(true)
        try {
            runComposeUiTest {
                setContent {
                    if (visible.value) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(64.dp)) {
                        MusicArtwork(file.path, "Cover", "Cover")
                    }
                }
                waitUntil(timeoutMillis = 3_000) {
                    onNodeWithContentDescription("Cover").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] } == Color(0xFFE05040)
                }
                runOnIdle { visible.value = false }
                waitForIdle()
                // Simulate storage being unavailable while a recycled row returns to the viewport.
                file.delete()
                runOnIdle { visible.value = true }
                waitForIdle()
                kotlin.test.assertEquals(Color(0xFFE05040), onNodeWithContentDescription("Cover").captureToImage().toPixelMap().let {
                    it[it.width / 2, it.height / 2]
                })
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun replacingAndUndoingEmbeddedArtworkUpdatesTheVisibleCoverWithoutRestarting() {
        val directory = Files.createTempDirectory("edited-artwork-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val audio = directory.resolve("song.flac")
        Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
            "Fixture Album", "01 Northern Light.flac"), audio)
        val red = directory.resolve("red.png").toFile().apply { writeBytes(image(0xE05040)) }
        org.jaudiotagger.audio.AudioFileIO.read(audio.toFile()).also {
            it.tag.setField(org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(red)); it.commit()
        }
        val sources = LocalFiles(library, artwork)
        val track = runBlocking { sources.scan(audio.toString()).single() }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = artwork,
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString(), localSources = sources))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForStartup(controller)
                runBlocking { controller.playTrack(track.id, listOf(track.id)).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.pause().join() }
                onNodeWithTag("mini-player").performClick()
                fun coverColor(): Color = onNodeWithContentDescription("Album artwork: Northern Light")
                    .captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFFE05040) }
                runBlocking { controller.replaceArtwork(track.id, image(0x3050E0)).join() }
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFF3050E0) }
                runBlocking { controller.undoFileEdit(controller.state.value.fileEdits.single().id).join() }
                waitUntil(timeoutMillis = 3_000) { coverColor() == Color(0xFFE05040) }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun artworkColorsFollowTheSelectedTrack() {
        val directory = Files.createTempDirectory("artwork-colors")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val tracks = runBlocking {
            listOf("Red Cover" to 0x00E05040, "Blue Cover" to 0x003050E0).map { (title, color) ->
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
                for (x in 0..31) for (y in 0..31) image.setRGB(x, y, color)
                val output = ByteArrayOutputStream()
                ImageIO.write(image, "png", output)
                val track = library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL,
                    Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                        "Fixture Album", "01 Northern Light.flac").toString()))
                artwork.save(track.id, output.toByteArray())
                track
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = MusicPreferences(store), artwork = artwork)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitForStartup(controller)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Appearance").performScrollTo().performClick()
                onNodeWithContentDescription("Theme").performClick()
                onNodeWithText("Light").performClick()
                onNodeWithContentDescription("Colors").performClick()
                onNodeWithText("Artwork colors").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                runBlocking { controller.playTrack(tracks[0].id, listOf(tracks[0].id), 0).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join() }
                onNodeWithTag("mini-player").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithContentDescription("Album artwork: Red Cover").fetchSemanticsNodes().isNotEmpty()
                }
                fun queueColor(): Color = onNodeWithContentDescription("Queue").captureToImage().toPixelMap().let { it[it.width / 2, 5] }
                var red = Color.Unspecified
                waitUntil(timeoutMillis = 3_000) { val color = queueColor(); (color == red).also { red = color } }
                runBlocking { controller.playTrack(tracks[1].id, listOf(tracks[1].id), 0).join() }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join() }
                waitUntil(timeoutMillis = 3_000) { queueColor() != red }
                assertNotEquals(red, queueColor())
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun nowPlayingDisplaysTheRetainedAlbumCover() {
        val directory = Files.createTempDirectory("artwork-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB)
        for (x in 0..31) for (y in 0..31) image.setRGB(x, y, 0x00E05040)
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        runBlocking {
            val track = library.importTrack(TrackMetadata("Artwork Fixture"), TrackSource("artwork-fixture", SourceKind.LOCAL,
                Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist",
                    "Fixture Album", "01 Northern Light.flac").toString()))
            artwork.save(track.id, output.toByteArray())
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), artwork = artwork)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.started }
                onNodeWithText("Artwork Fixture").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playback.playing }
                runBlocking { controller.togglePlayback().join() }
                onNodeWithTag("mini-player").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithContentDescription("Album artwork: Artwork Fixture").fetchSemanticsNodes().isNotEmpty()
                }
                val pixels = onNodeWithContentDescription("Album artwork: Artwork Fixture").captureToImage().toPixelMap()
                assertEquals(Color(0xFFE05040), pixels[pixels.width / 2, pixels.height / 2])
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    /** Hands out each cover only once the test releases it. */
    private class SlowCovers : me.d3sox.nevumi.youtube.YouTubeSource {
        val covers = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<ByteArray>>()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        fun cover(url: String) = covers.getOrPut(url) { kotlinx.coroutines.CompletableDeferred() }
        override suspend fun artwork(url: String) = cover(url).also { requests.incrementAndGet() }.await()
        override suspend fun search(query: String, kind: me.d3sox.nevumi.youtube.YouTubeSearchKind,
            continuation: me.d3sox.nevumi.youtube.YouTubeContinuation?) = me.d3sox.nevumi.youtube.YouTubePage(emptyList(), null)
        override suspend fun browse(link: me.d3sox.nevumi.youtube.YouTubeLink) = me.d3sox.nevumi.youtube.YouTubeBrowsePage("")
        override suspend fun page(videoId: String, kind: me.d3sox.nevumi.youtube.YouTubeCollectionKind): me.d3sox.nevumi.youtube.YouTubeCollection? = null
        override suspend fun browseMore(continuation: me.d3sox.nevumi.youtube.YouTubeContinuation) = me.d3sox.nevumi.youtube.YouTubePage(emptyList(), null)
        override suspend fun audio(videoId: String, fresh: Boolean) = throw UnsupportedOperationException()
        override fun playbackLocation(videoId: String) = ""
    }

    @Test
    fun coversShowAsLoadingUntilStartupKnowsWhichCoversExist() {
        val directory = Files.createTempDirectory("startup-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        runBlocking {
            val track = library.importTrack(TrackMetadata("Night Drive"), TrackSource("startup-cover", SourceKind.LOCAL, directory.resolve("song.flac").toString()))
            artwork.save(track.id, image(0xE05040))
        }
        // Startup applies the audio effects after it shows the songs and before it looks up their covers, so it waits here.
        val startup = kotlinx.coroutines.CompletableDeferred<Unit>()
        val real = MpvPlaybackEngine("null")
        val engine = object : me.d3sox.nevumi.playback.PlaybackEngine by real {
            override suspend fun setAudioEffects(effects: me.d3sox.nevumi.playback.AudioEffects) { startup.await(); real.setAudioEffects(effects) }
        }
        val controller = MusicController(library, PlaybackQueue(store), engine, preferences = MusicPreferences(store), artwork = artwork)
        val loading = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Loading cover")
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithContentDescription("Song artwork: Night Drive", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                onNodeWithContentDescription("Song artwork: Night Drive", useUnmergedTree = true).assert(loading)
                startup.complete(Unit)
                waitUntil(timeoutMillis = 3_000) {
                    onNodeWithContentDescription("Song artwork: Night Drive", useUnmergedTree = true).captureToImage().toPixelMap()
                        .let { it[it.width / 2, it.height / 2] } == Color(0xFFE05040)
                }
            }
        } finally { startup.complete(Unit); runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aCoverThatIsStillLoadingShowsALoadingIndicatorInsteadOfTheMissingCoverIcon() {
        val directory = Files.createTempDirectory("loading-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val covers = SlowCovers()
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), youTube = covers)
        val loading = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Loading cover")
        val indicator = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo)
        try {
            runComposeUiTest {
                setContent {
                    androidx.compose.foundation.layout.Row {
                        for (url in listOf("https://covers.test/found", "https://covers.test/broken"))
                            YouTubeImage(url, controller, androidx.compose.ui.Modifier.size(48.dp))
                        YouTubeImage("https://covers.test/large", controller, androidx.compose.ui.Modifier.size(200.dp))
                    }
                }
                waitUntil(timeoutMillis = 3_000) { onAllNodes(indicator, useUnmergedTree = true).fetchSemanticsNodes().size == 3 }
                onAllNodes(loading, useUnmergedTree = true).assertCountEquals(3)
                // A large cover keeps the indicator at its standard size instead of filling the slot.
                onAllNodes(indicator, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                    kotlin.test.assertTrue(with(density) { node.size.width.toDp() } <= 48.dp, "Indicator is ${node.size}")
                }
                covers.cover("https://covers.test/large").complete(ByteArray(0))

                covers.cover("https://covers.test/found").complete(image(0xE05040))
                covers.cover("https://covers.test/broken").complete(ByteArray(0))
                waitUntil(timeoutMillis = 3_000) { onAllNodes(loading, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                onAllNodes(indicator, useUnmergedTree = true).assertCountEquals(0)
                onAllNodesWithTag("youtube-artwork").assertCountEquals(1)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aQueuedYouTubeSongShowsItsCoverAsLoadingUntilTheDownloadFinishes() {
        val directory = Files.createTempDirectory("downloading-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(youTube = true) } }
        val covers = SlowCovers()
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences,
            artwork = ArtworkLibrary(directory.resolve("artwork").toString()), youTube = covers)
        val loading = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Loading cover")
        try {
            runComposeUiTest {
                setContent {
                    val state by controller.state.collectAsState()
                    // A song row, which updates only for the parts of the app state it shows.
                    androidx.compose.runtime.CompositionLocalProvider(LocalArtworkPicker provides ArtworkPicker { null }) {
                        state.tracks.firstOrNull()?.let { track -> TrackRow(track, controller) }
                    }
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.youTube }
                // No state ever has the song queued with neither its cover nor a download of it.
                val shownAsMissing = java.util.concurrent.atomic.AtomicBoolean()
                val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
                    controller.state.collect { state ->
                        if (state.queue.entries.any { it.trackId !in state.artwork && it.trackId !in state.downloadingArtwork }) shownAsMissing.set(true)
                    }
                }
                val song = me.d3sox.nevumi.youtube.YouTubeTrack("aaaaaaaaaaa", "Night Drive", "Artist",
                    artworkUrl = "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", song = true)
                controller.enqueueYouTube(listOf(song), me.d3sox.nevumi.queue.QueueAction.APPEND)
                // The song is queued while its cover is still on the way.
                waitUntil(timeoutMillis = 3_000) { covers.covers.isNotEmpty() && onAllNodes(loading, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
                // Queuing it again leaves the cover to the download already running, which alone ends the loading state.
                runBlocking { kotlinx.coroutines.withTimeout(3_000) { controller.enqueueYouTube(listOf(song), me.d3sox.nevumi.queue.QueueAction.APPEND).join() } }
                assertEquals(2, controller.state.value.queue.entries.size)
                assertEquals(1, covers.requests.get())

                // Frame by frame, the slot goes from loading straight to the cover, never showing the missing-cover icon in between.
                mainClock.autoAdvance = false
                covers.covers.values.forEach { it.complete(image(0xE05040)) }
                val deadline = System.currentTimeMillis() + 3_000
                while (true) {
                    mainClock.advanceTimeByFrame()
                    val shown = onNodeWithContentDescription("Song artwork: Night Drive", useUnmergedTree = true).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] } == Color(0xFFE05040)
                    if (shown) break
                    onNodeWithContentDescription("Song artwork: Night Drive", useUnmergedTree = true).assert(loading)
                    check(System.currentTimeMillis() < deadline) { "The cover did not appear" }
                    Thread.sleep(5)
                }
                onAllNodes(loading, useUnmergedTree = true).assertCountEquals(0)
                assertEquals(emptySet(), controller.state.value.downloadingArtwork)
                watcher.cancel()
                assertEquals(false, shownAsMissing.get())
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aCoverRetriedByAnotherActionStaysLoadingWhenTheFirstActionFinishes() {
        val directory = Files.createTempDirectory("retried-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(youTube = true) } }
        // Each request waits on its own answer, so a retry of a failed cover can stay pending.
        val requests = java.util.concurrent.LinkedBlockingQueue<Pair<String, kotlinx.coroutines.CompletableDeferred<ByteArray>>>()
        val covers = object : me.d3sox.nevumi.youtube.YouTubeSource by SlowCovers() {
            override suspend fun artwork(url: String) = kotlinx.coroutines.CompletableDeferred<ByteArray>().also { requests.put(url to it) }.await()
        }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences,
            artwork = ArtworkLibrary(directory.resolve("artwork").toString()), youTube = covers)
        fun song(id: String) = me.d3sox.nevumi.youtube.YouTubeTrack(id.repeat(11), id, "Artist", artworkUrl = "https://i.ytimg.com/vi/${id.repeat(11)}/hq.jpg", song = true)
        fun next(id: String) = requests.poll(3, java.util.concurrent.TimeUnit.SECONDS).also { assertEquals(true, it?.first?.contains(id.repeat(11))) }!!.second
        try {
            runBlocking {
                kotlinx.coroutines.withTimeout(3_000) { while (!controller.state.value.preferences.youTube) kotlinx.coroutines.delay(10) }
                val first = controller.enqueueYouTube(listOf(song("a"), song("b")), me.d3sox.nevumi.queue.QueueAction.APPEND)
                next("a").completeExceptionally(IllegalStateException("Offline"))
                val pendingB = next("b")
                // Another action retries the failed cover while the first action still waits for the other one.
                controller.enqueueYouTube(listOf(song("a")), me.d3sox.nevumi.queue.QueueAction.APPEND)
                val retry = next("a")
                pendingB.complete(ByteArray(0))
                first.join()
                val a = controller.state.value.tracks.single { it.metadata.title == "a" }.id
                assertEquals(setOf(a), controller.state.value.downloadingArtwork)
                retry.complete(ByteArray(0))
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSongRowShowsTheMissingCoverIconOnceItsCoverDownloadFails() {
        val directory = Files.createTempDirectory("failed-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val preferences = MusicPreferences(store)
        runBlocking { preferences.update { it.copy(youTube = true) } }
        val covers = SlowCovers()
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences,
            artwork = ArtworkLibrary(directory.resolve("artwork").toString()), youTube = covers)
        val loading = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Loading cover")
        try {
            runComposeUiTest {
                setContent {
                    val state by controller.state.collectAsState()
                    androidx.compose.runtime.CompositionLocalProvider(LocalArtworkPicker provides ArtworkPicker { null }) {
                        state.tracks.firstOrNull()?.let { track -> TrackRow(track, controller) }
                    }
                }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.preferences.youTube }
                controller.enqueueYouTube(listOf(me.d3sox.nevumi.youtube.YouTubeTrack("aaaaaaaaaaa", "Night Drive", "Artist",
                    artworkUrl = "https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", song = true)), me.d3sox.nevumi.queue.QueueAction.APPEND)
                waitUntil(timeoutMillis = 3_000) { onAllNodes(loading, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
                // Only the download state changes, which the row must still follow.
                covers.covers.values.forEach { it.complete(ByteArray(0)) }
                waitUntil(timeoutMillis = 3_000) { onAllNodes(loading, useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
