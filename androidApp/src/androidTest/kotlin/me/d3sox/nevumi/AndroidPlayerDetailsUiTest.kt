package me.d3sox.nevumi

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.settings.ColorMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class AndroidPlayerDetailsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Test fun artworkGlowStaysBehindThePlayingTitleArtistAndButtons() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val original = app.controller.state.value.preferences
        val audio = File(app.cacheDir, "glow-layer.flac")
        instrumentation.context.assets.open("fixture.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                val track = runBlocking {
                    app.controller.updatePreferences { it.copy(themeMode = ThemeMode.DARK, amoledBlack = true) }.join()
                    app.controller.library.importTrack(TrackMetadata("Glow track", "Glow artist"),
                        TrackSource("glow-layer", SourceKind.LOCAL, audio.path)).also {
                        app.controller.reload().join(); app.controller.playTrack(it.id).join(); app.controller.pause().join()
                    }
                }
                compose.onNodeWithTag("mini-player").performClick()
                val foreground = listOf(
                    compose.onNode(hasText("Glow track") and hasAnyAncestor(hasTestTag("now-playing")), useUnmergedTree = true),
                    compose.onNode(hasText("Glow artist") and hasAnyAncestor(hasTestTag("now-playing")), useUnmergedTree = true),
                    compose.onNodeWithContentDescription("Collapse"),
                    compose.onNodeWithContentDescription("Favorite"),
                )
                fun brightest(node: SemanticsNodeInteraction): Color {
                    val pixels = node.captureToImage().toPixelMap()
                    return (0 until pixels.width).flatMap { x -> (0 until pixels.height).map { y -> pixels[x, y] } }
                        .maxBy { it.green + it.blue }
                }
                val originalColors = foreground.map(::brightest)
                val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(0xFFE05040.toInt())
                val bytes = ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                bitmap.recycle()
                runBlocking {
                    ArtworkLibrary(File(app.filesDir, "artwork").path).save(track.id, bytes)
                    app.controller.reload().join()
                }
                val root = compose.onRoot()
                val titleBounds = foreground.first().fetchSemanticsNode().boundsInRoot
                val rootBounds = root.fetchSemanticsNode().boundsInRoot
                val titleY = (titleBounds.center.y - rootBounds.top).toInt()
                // Sample beside the title; the glow fades toward the screen edge as artwork shrinks.
                val titleX = (titleBounds.left - rootBounds.left - 8).toInt().coerceAtLeast(0)
                compose.waitUntil(5_000) {
                    val color = root.captureToImage().toPixelMap()[titleX, titleY]
                    color.red > .1f && color.red > color.blue + .05f
                }
                foreground.forEachIndexed { index, node ->
                    val before = originalColors[index]
                    val after = brightest(node)
                    assertTrue("The glow stays behind foreground element $index: $before / $after",
                        kotlin.math.abs(after.red - before.red) < .01f && kotlin.math.abs(after.green - before.green) < .01f &&
                            kotlin.math.abs(after.blue - before.blue) < .01f)
                }
                fun capture(name: String) {
                    compose.waitForIdle()
                    Thread.sleep(80) // Allow the render thread to present the manually advanced frame.
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    File(app.filesDir, "details-check/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                        screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                }
                capture("glow-background")
                val player = compose.onNodeWithTag("now-playing")
                val glowY = (foreground.first().fetchSemanticsNode().boundsInRoot.center.y - player.fetchSemanticsNode().boundsInRoot.top).toInt()
                fun assertGlow() {
                    val color = player.captureToImage().toPixelMap()[8, glowY]
                    assertTrue("The glow stays visible while the track menu is open: $color", color.red > color.blue + .02f)
                }
                val screenTitleY = foreground.first().fetchSemanticsNode().let {
                    (it.boundsInRoot.center.y + it.positionOnScreen.y - it.positionInRoot.y).toInt()
                }
                fun backgroundBrightness(): Float {
                    compose.waitForIdle()
                    // A manually held test frame disables normal drawing; render the visible window first.
                    if (compose.onAllNodesWithTag("track-actions").fetchSemanticsNodes().isNotEmpty())
                        compose.onNodeWithTag("track-actions").captureToImage()
                    else compose.onNodeWithTag("now-playing").captureToImage()
                    Thread.sleep(100) // Capture the presented window, including the system dim layer.
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    val pixel = screenshot.getPixel(8, screenTitleY)
                    screenshot.recycle()
                    return android.graphics.Color.red(pixel) / 255f
                }
                val restingBrightness = backgroundBrightness()
                compose.mainClock.autoAdvance = false
                compose.onNodeWithContentDescription("Track options: Glow track").performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithTag("track-action-artwork", useUnmergedTree = true).assertIsDisplayed()
                val startingBrightness = backgroundBrightness()
                assertTrue("Opening must start with an undimmed background: $restingBrightness / $startingBrightness",
                    startingBrightness > restingBrightness * .9f)
                compose.mainClock.advanceTimeBy(96)
                compose.onNodeWithTag("track-action-artwork", useUnmergedTree = true).assertIsDisplayed()
                val enteringBrightness = backgroundBrightness()
                capture("fade-entering")
                compose.mainClock.advanceTimeBy(400)
                compose.onNodeWithTag("track-actions").assertIsDisplayed()
                assertGlow()
                capture("glow-menu-open")
                val openedBrightness = backgroundBrightness()
                assertTrue("The background fades through intermediate shades: $restingBrightness / $enteringBrightness / $openedBrightness",
                    enteringBrightness < restingBrightness * .95f && enteringBrightness > openedBrightness + restingBrightness * .05f)
                compose.onNodeWithContentDescription("Close song actions").performClick()
                compose.mainClock.advanceTimeBy(96)
                assertGlow()
                val returningBrightness = backgroundBrightness()
                assertTrue("Closing must restore the background gradually: $openedBrightness / $returningBrightness / $restingBrightness",
                    returningBrightness > openedBrightness + restingBrightness * .1f && returningBrightness < restingBrightness * .95f)
                capture("glow-menu-returning")
                compose.mainClock.advanceTimeBy(400)
                compose.onNodeWithTag("track-actions").assertDoesNotExist()
                assertGlow()
                compose.mainClock.autoAdvance = true
                compose.onNodeWithContentDescription("Collapse").performClick()
                runBlocking { app.controller.updatePreferences { it.copy(colors = ColorMode.ARTWORK) }.join() }
                compose.waitUntil(5_000) {
                    val pixels = compose.onNode(hasContentDescription("Play") and hasClickAction()).captureToImage().toPixelMap()
                    val color = pixels[pixels.width / 2, pixels.height / 4]
                    color.red > color.blue + .05f
                }
                fun assertWarmIndicator(label: String) {
                    val page = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("library-navigation"))).assertIsSelected()
                    val pixels = page.captureToImage().toPixelMap()
                    val color = pixels[pixels.width / 4, pixels.height * 3 / 10]
                    assertTrue("The selected $label indicator uses the artwork accent: $color", color.red > color.blue + .05f)
                }
                assertWarmIndicator("Songs")
                capture("artwork-navigation-songs")
                compose.onNodeWithText("More").performClick()
                compose.onNodeWithText("Folders").performClick()
                assertWarmIndicator("More")
                capture("artwork-navigation-more")
            }
        } finally { compose.mainClock.autoAdvance = true; runBlocking { app.controller.pause().join(); app.controller.updatePreferences { original }.join() }; audio.delete() }
    }

    @Test fun trackMenusUseThePlayingCoverAndTheSelectedQueueOccurrence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val audio = File(app.cacheDir, "player-cover-menu.flac")
        instrumentation.context.assets.open("fixture-cover.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                val tracks = runBlocking { listOf("Playing cover", "Queued cover").map { title ->
                    app.controller.library.importTrack(TrackMetadata(title, "Cover artist", durationMs = 90_000),
                        TrackSource(title, SourceKind.LOCAL, audio.path))
                } }
                val artwork = org.jaudiotagger.audio.AudioFileIO.read(audio).tag.firstArtwork.binaryData
                runBlocking {
                    tracks.forEach { ArtworkLibrary(File(app.filesDir, "artwork").path).save(it.id, artwork) }
                    app.controller.reload().join(); app.controller.playTrack(tracks[0].id).join(); app.controller.pause().join()
                    app.controller.enqueue(listOf(tracks[1].id, tracks[1].id), me.d3sox.nevumi.queue.QueueAction.APPEND).join()
                }
                fun bounds(node: SemanticsNodeInteraction): androidx.compose.ui.geometry.Rect {
                    val semantics = node.fetchSemanticsNode()
                    return semantics.boundsInRoot.translate(semantics.positionOnScreen - semantics.positionInRoot)
                }
                fun capture(name: String) {
                    compose.waitForIdle()
                    Thread.sleep(80) // Let the render thread present the manually advanced animation frame.
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    File(app.filesDir, "details-check/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                fun check(source: SemanticsNodeInteraction, options: SemanticsNodeInteraction, name: String) {
                    val origin = bounds(source)
                    compose.mainClock.autoAdvance = false
                    options.performClick(); compose.mainClock.advanceTimeByFrame()
                    compose.mainClock.advanceTimeByFrame()
                    val moving = compose.onNodeWithTag("track-action-artwork", useUnmergedTree = true)
                    val start = bounds(moving)
                    assertTrue("The menu starts at its visible cover: $origin / $start",
                        (start.center - origin.center).getDistance() < 3f && kotlin.math.abs(start.width - origin.width) < 3f)
                    compose.mainClock.advanceTimeBy(400)
                    val opened = bounds(moving)
                    val frame = compose.onNodeWithTag("app-window").captureToImage().toPixelMap()
                    val artworkPixels = (0 until frame.height step 4).sumOf { y ->
                        (0 until frame.width step 4).count { x ->
                            val color = frame[x, y]
                            kotlin.math.abs(color.red - 224f / 255) < .015f && kotlin.math.abs(color.green - 80f / 255) < .015f &&
                                kotlin.math.abs(color.blue - 64f / 255) < .015f
                        }
                    }
                    assertTrue("The app frame keeps drawing the artwork while the menu is open", artworkPixels > 100)
                    assertTrue((opened.center - origin.center).getDistance() > 20f)
                    capture("$name-open")
                    compose.onNodeWithContentDescription("Close song actions").performClick()
                    compose.mainClock.advanceTimeBy(96)
                    val returning = bounds(moving)
                    assertTrue((returning.center - opened.center).getDistance() > 2f && (returning.center - origin.center).getDistance() > 2f)
                    capture("$name-returning")
                    compose.mainClock.advanceTimeBy(400)
                    compose.onNodeWithTag("track-actions").assertDoesNotExist()
                    assertTrue((bounds(source).center - origin.center).getDistance() < 2f)
                    compose.mainClock.autoAdvance = true
                }
                compose.onNodeWithTag("mini-player").performClick()
                check(compose.onNodeWithTag("player-artwork", useUnmergedTree = true),
                    compose.onNodeWithContentDescription("Track options: Playing cover"), "playing-menu")
                compose.onNodeWithContentDescription("Queue").performClick()
                val entry = app.controller.state.value.queue.upNext.last()
                val row = hasTestTag("queue-entry:${entry.id}")
                compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Up next"))).performScrollToNode(row)
                check(compose.onNodeWithContentDescription("Queue artwork: ${entry.id}", useUnmergedTree = true),
                    compose.onNode(hasContentDescription("Track options: Queued cover") and hasAnyAncestor(row)), "queue-menu")
                assertEquals(entry.id, app.controller.state.value.queue.upNext.last().id)
                assertFalse(app.controller.state.value.playback.playing)
            }
        } finally { compose.mainClock.autoAdvance = true; runBlocking { app.controller.pause().join() }; audio.delete() }
    }

    @Test fun audioDetailsFollowThePlayingSourceAndCanBeHidden() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val original = app.controller.state.value.preferences
        val files = listOf("audio-info.wav", "fixture.mp3", "fixture.flac").map { name ->
            File(app.cacheDir, "details-$name").also { file ->
                instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                assertTrue(app.controller.state.value.preferences.showAudioInfo)
                val tracks = runBlocking { files.mapIndexed { index, file ->
                    app.controller.library.importTrack(TrackMetadata("Audio details $index", durationMs = 3_000),
                        TrackSource("details:$index", SourceKind.LOCAL, file.toURI().toString()))
                }.also { app.controller.reload().join() } }
                for ((index, format) in listOf("WAV", "MP3", "FLAC").withIndex()) {
                    runBlocking { app.controller.playTrack(tracks[index].id).join(); app.controller.pause().join() }
                    compose.waitUntil(5_000) { app.controller.state.value.playback.audioInfo?.let { it.format == format && it.sampleRateHz == 44_100 } == true }
                    if (index == 0) compose.onNodeWithTag("mini-player").performClick()
                    compose.waitUntil(5_000) {
                        runCatching {
                            compose.onNodeWithTag("playing-audio-info").assertIsDisplayed().assertTextContains(format, substring = true)
                        }.isSuccess
                    }
                    val details = compose.onNodeWithTag("playing-audio-info").assertIsDisplayed()
                    details.assertTextContains(format, substring = true).assertTextContains("44.1 kHz", substring = true)
                    if (format == "WAV") details.assertTextEquals("WAV · 706 kb/s · 44.1 kHz")
                    if (format == "MP3") details.assertTextEquals("MP3 · 64 kb/s · 44.1 kHz")
                    val info = details.fetchSemanticsNode().boundsInRoot
                    val output = compose.onNodeWithContentDescription("Audio output").fetchSemanticsNode().boundsInRoot
                    val queue = compose.onNodeWithContentDescription("Queue").fetchSemanticsNode().boundsInRoot
                    assertTrue(kotlin.math.abs(info.center.x - (output.center.x + queue.center.x) / 2) < 2f)
                }
                compose.onNodeWithContentDescription("Collapse").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Show audio info").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithTag("playing-audio-info").assertDoesNotExist()
            }
        } finally {
            runBlocking { app.controller.pause().join(); app.controller.updatePreferences { original }.join() }
            files.forEach { it.delete() }
        }
    }

    @Test fun trackMenuCoverMovesFromTheRowAndQuickActionsShowTheirCurrentState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val original = app.controller.state.value.preferences
        val audio = File(app.cacheDir, "menu-details.flac")
        instrumentation.context.assets.open("fixture-cover.flac").use { input -> audio.outputStream().use(input::copyTo) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(5_000) { app.controller.state.value.libraryReady }
                val track = runBlocking { app.controller.library.importTrack(TrackMetadata("Menu fixture", "Menu artist", durationMs = 90_000),
                    TrackSource("menu-details", SourceKind.LOCAL, audio.path)) }
                val cover = org.jaudiotagger.audio.AudioFileIO.read(audio).tag.firstArtwork.binaryData
                runBlocking { ArtworkLibrary(File(app.filesDir, "artwork").path).save(track.id, cover); app.controller.reload().join() }
                fun screenCenter(node: SemanticsNodeInteraction): Offset {
                    val semantics = node.fetchSemanticsNode()
                    return semantics.positionOnScreen - semantics.positionInRoot + semantics.boundsInRoot.center
                }
                fun capture(name: String) {
                    val bitmap = instrumentation.uiAutomation.takeScreenshot()
                    File(app.filesDir, "details-check/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                val origin = screenCenter(compose.onNodeWithContentDescription("Song artwork: Menu fixture", useUnmergedTree = true))
                compose.mainClock.autoAdvance = false
                compose.onNodeWithContentDescription("Track options: Menu fixture").performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeByFrame()
                val moving = compose.onNodeWithTag("track-action-artwork", useUnmergedTree = true)
                val start = screenCenter(moving)
                assertTrue("Artwork starts at the song row: $origin / $start", (origin - start).getDistance() < 3f)
                val menuStart = compose.onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot.top
                compose.mainClock.advanceTimeBy(96)
                val middle = screenCenter(moving)
                capture("menu-middle")
                compose.mainClock.advanceTimeBy(400)
                val end = screenCenter(moving)
                assertTrue((middle - start).getDistance() > 2f && (middle - end).getDistance() > 2f)
                assertTrue(compose.onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot.top < menuStart)
                capture("menu-open")
                val openedTop = compose.onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot.top
                compose.onNodeWithContentDescription("Close song actions").performClick()
                compose.mainClock.advanceTimeBy(96)
                val returning = screenCenter(moving)
                assertTrue((returning - end).getDistance() > 2f && (returning - origin).getDistance() > 2f)
                assertTrue(compose.onNodeWithTag("track-actions").fetchSemanticsNode().boundsInRoot.top > openedTop)
                capture("menu-closing")
                compose.mainClock.advanceTimeBy(400)
                compose.onNodeWithTag("track-actions").assertDoesNotExist()
                assertTrue((screenCenter(compose.onNodeWithContentDescription("Song artwork: Menu fixture", useUnmergedTree = true)) - origin).getDistance() < 2f)
                compose.mainClock.autoAdvance = true
                compose.onNodeWithContentDescription("Track options: Menu fixture").performClick()
                compose.onNodeWithText("Favorite").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Library & gestures").performScrollTo().performClick()
                compose.onNodeWithContentDescription("Quick action").performScrollTo().performClick()
                compose.onNode(hasText("Favorite") and hasAnyAncestor(isPopup())).performScrollTo().performClick()
                capture("settings-gesture-icons")
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                val quick = compose.onNodeWithContentDescription("Quick action: Remove favorite: Menu fixture").assertIsSelected()
                val bounds = quick.fetchSemanticsNode().boundsInRoot
                val duration = compose.onNodeWithContentDescription("Duration: 1:30", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val menu = compose.onNodeWithContentDescription("Track options: Menu fixture").fetchSemanticsNode().boundsInRoot
                assertTrue(duration.center.y < bounds.center.y && bounds.right <= menu.left + 1f)
                capture("quick-action")
                compose.onNodeWithContentDescription("Favorited", useUnmergedTree = true).assertDoesNotExist()
                quick.performTouchInput { click(center) }
                compose.waitUntil(5_000) { !app.controller.state.value.tracks.first { it.id == track.id }.favorite }
                compose.onNodeWithContentDescription("Quick action: Favorite: Menu fixture").assertIsNotSelected()
                compose.onNodeWithContentDescription("Quick action: Favorite: Menu fixture").performTouchInput { click(center) }
                compose.waitUntil(5_000) { app.controller.state.value.tracks.first { it.id == track.id }.favorite }
                compose.onNodeWithContentDescription("Quick action: Remove favorite: Menu fixture").performTouchInput { click(center) }
                compose.waitUntil(5_000) { !app.controller.state.value.tracks.first { it.id == track.id }.favorite }
            }
        } finally {
            compose.mainClock.autoAdvance = true
            runBlocking { app.controller.updatePreferences { original }.join() }
            audio.delete()
        }
    }
}
