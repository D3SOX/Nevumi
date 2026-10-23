package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.CompositionLocalProvider
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.HomeSectionId
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class HomeUiTest {
    @Test fun aLoadingHomeFeedDoesNotClaimAnExistingLibraryIsEmpty() {
        val directory = Files.createTempDirectory("nevumi-home-loading")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        runBlocking { library.importTrack(TrackMetadata("Home song"), TrackSource("home", SourceKind.LOCAL, "/music/song.flac")) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            runComposeUiTest {
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                val state = controller.state.value
                val tracks = object : AbstractList<Track>() {
                    override val size = state.tracks.size
                    override fun get(index: Int): Track {
                        if (!EventQueue.isDispatchThread()) {
                            started.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        return state.tracks[index]
                    }
                }
                setContent {
                    CompositionLocalProvider(LocalArtworkPicker provides ArtworkPicker { null }) {
                        MusicTheme(state.preferences, null) { HomeScreen(state, controller, tracks, null, true, {}) }
                    }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                onNodeWithText("No music available").assertDoesNotExist()
                onNodeWithTag("home-loading").assertIsDisplayed()
                release.countDown()
                waitUntil(timeoutMillis = 3_000) { onAllNodesWithTag("home-section:Mixes").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithTag("home-loading").assertDoesNotExist()
            }
        } finally {
            release.countDown()
            runBlocking { controller.closeAndJoin() }
            store.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun homeSectionsCanBeDraggedAndMovedWithButtonsAndTheirOrderSurvivesRestart() {
        val directory = Files.createTempDirectory("nevumi-home-order")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var library = MusicLibrary(store)
        runBlocking { library.importTrack(TrackMetadata("Home song"), TrackSource("home", SourceKind.LOCAL, "/home/song.flac")) }
        var controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            val initial = controller
            runComposeUiTest {
                setContent { MusicPlayerApp(initial) }
                waitUntil(timeoutMillis = 5_000) { initial.state.value.libraryReady }
                onNodeWithContentDescription("Home").performClick()
                onNodeWithContentDescription("Customize Home").performClick()
                captureUiSnapshot("home-customization")
                val mixes = onNodeWithTag("home-reorder:MIXES")
                val next = onNodeWithTag("home-reorder:RECENT").fetchSemanticsNode().boundsInRoot.center
                val origin = mixes.fetchSemanticsNode().boundsInRoot.topLeft
                mixes.performTouchInput { swipe(center, next - origin, 600) }
                onNodeWithContentDescription("Move Mixes up").assertIsEnabled()
                onNodeWithTag("home-section-order").performScrollToNode(hasContentDescription("Move Recently added up"))
                repeat(HomeSectionId.entries.lastIndex) {
                    onNodeWithContentDescription("Move Recently added up").performScrollTo().performClick()
                }
                onNodeWithContentDescription("Show Recent listens").performScrollTo().performClick()
                onNodeWithText("Save order").performClick()
                waitUntil(timeoutMillis = 5_000) { initial.state.value.preferences.homeSections.first() == HomeSectionId.ADDED }
                val added = onNodeWithTag("home-section:Recently added").fetchSemanticsNode().boundsInRoot.top
                val mixesTop = onNodeWithTag("home-section:Mixes").fetchSemanticsNode().boundsInRoot.top
                assertTrue(added < mixesTop)
                assertEquals(listOf(HomeSectionId.ADDED, HomeSectionId.RECENT, HomeSectionId.MIXES), initial.state.value.preferences.homeSections.take(3))
                onNodeWithTag("home-section:Recent listens").assertDoesNotExist()
            }
            runBlocking { controller.closeAndJoin() }; store.close(); store = openMusicStore(database)
            library = MusicLibrary(store)
            controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
            val restored = controller
            runComposeUiTest {
                setContent { MusicPlayerApp(restored) }
                waitUntil(timeoutMillis = 5_000) { restored.state.value.libraryReady }
                assertEquals(HomeSectionId.ADDED, restored.state.value.preferences.homeSections.first())
                assertEquals(setOf(HomeSectionId.RECENT), restored.state.value.preferences.disabledHomeSections)
                onNodeWithTag("home-section:Recent listens").assertDoesNotExist()
                assertTrue(onNodeWithTag("home-section:Recently added").fetchSemanticsNode().boundsInRoot.top < onNodeWithTag("home-section:Mixes").fetchSemanticsNode().boundsInRoot.top)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun homeCardsPlayTheirSectionAndOpenAlbumsWhileTheLastOpenedPageRemainsTheStartupDefault() {
        val directory = Files.createTempDirectory("nevumi-home-ui")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        var library = MusicLibrary(store)
        val audio = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.flac").toString()
        val tracks = runBlocking { (1..4).map { number -> library.importTrack(TrackMetadata("Song $number", "Artist", "Album", durationMs = 3_000,
            trackNumber = number), TrackSource("source:$number", SourceKind.LOCAL, audio)) } }
        runBlocking {
            library.setFavorite(tracks[0].id, true)
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            tracks.take(2).forEachIndexed { index, track ->
                val listen = Listen("listen:$index", track.id, "playback:$index", now - index * 1_000, track.metadata, listenedMs = 2_000, playedMs = 2_000, durationMs = 3_000)
                store.put("listen:${listen.id}", "listens", listen)
            }
        }
        var controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), listeningHistory = ListeningHistory(store, library))
        try {
            val initial = controller
            runComposeUiTest {
                setContent { MusicPlayerApp(initial) }
                waitUntil(timeoutMillis = 5_000) { initial.state.value.libraryReady }
                onNodeWithContentDescription("Songs").assertIsSelected()
                assertNull(initial.state.value.preferences.startupTab)
                onNodeWithContentDescription("Home").performClick()
                onNodeWithText("Mixes").assertIsDisplayed()
                System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
                    val pixels = onRoot().captureToImage().toPixelMap()
                    val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                    val target = Path.of(directory).also(Files::createDirectories).resolve("home-desktop.png")
                    javax.imageio.ImageIO.write(image, "png", target.toFile())
                }
                onNodeWithContentDescription("Open mix: Favorites mix").performClick()
                onNodeWithText("Play mix").performClick()
                waitUntil(timeoutMillis = 5_000) { initial.state.value.playback.trackId == tracks[0].id }
                assertEquals(listOf(tracks[0].id), initial.state.value.queue.entries.map { it.trackId })
                assertEquals(LibraryTab.HOME, initial.state.value.queue.origin?.tab)
                runBlocking { initial.pause().join() }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithTag("home-page").performScrollToNode(hasTestTag("home-section:Recent albums"))
                onNode(hasContentDescription("Open album: Album") and hasAnyAncestor(hasTestTag("home-section:Recent albums"))).performClick()
                onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("track-row:${tracks[3].id}"))
                onNodeWithText("Song 4").assertExists()
                onNodeWithTag("track-row:${tracks[3].id}").performClick()
                waitUntil(timeoutMillis = 5_000) { initial.state.value.queue.origin?.tab == LibraryTab.ALBUMS }
                assertEquals(tracks.map { it.id }, initial.state.value.queue.entries.map { it.trackId })
                runBlocking { initial.pause().join() }
                onNodeWithContentDescription("Home").performClick()
                waitUntil(timeoutMillis = 5_000) { initial.state.value.preferences.lastLibraryTab == LibraryTab.HOME }
                assertNull(initial.state.value.preferences.startupTab)
            }
            runBlocking { initial.closeAndJoin() }; store.close(); store = openMusicStore(database)
            library = MusicLibrary(store)
            controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), listeningHistory = ListeningHistory(store, library))
            val restored = controller
            runComposeUiTest {
                setContent { MusicPlayerApp(restored) }
                waitUntil(timeoutMillis = 5_000) { restored.state.value.libraryReady }
                onNodeWithContentDescription("Home").assertIsSelected()
                onNodeWithTag("home-page").assertIsDisplayed()
                assertNull(restored.state.value.preferences.startupTab)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun theEmptyHomepageOffersMusicSettings() {
        val directory = Files.createTempDirectory("nevumi-home-empty")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { controller.state.value.libraryReady }
                onNodeWithContentDescription("Home").performClick()
                onNodeWithText("No music yet").assertIsDisplayed()
                onNodeWithText("Music settings").performClick()
                onNodeWithText("Local music").assertExists()
                onNodeWithText("Music server").assertExists()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
