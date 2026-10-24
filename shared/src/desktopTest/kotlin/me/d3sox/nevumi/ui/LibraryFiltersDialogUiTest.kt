package me.d3sox.nevumi.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicSettings
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.storage.openMusicStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryFiltersDialogUiTest {
    @Test fun desktopActionsShareOneRow() = checkLayout("desktop", Size(1100f, 900f), wraps = false)
    @Test fun portraitKeepsSecondaryActionsTogetherAboveApply() = checkLayout("portrait", Size(390f, 844f), wraps = true)
    @Test fun landscapeUsesAvailableWidthForOneActionRow() = checkLayout("landscape", Size(844f, 390f), wraps = false)

    private fun checkLayout(name: String, size: Size, wraps: Boolean) {
        val directory = Files.createTempDirectory("filter-dialog")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        try {
            runSkikoComposeUiTest(size = size) {
                setContent { MusicTheme(MusicSettings(themeMode = ThemeMode.DARK), null) {
                    LibraryFiltersDialog(controller.state.value, controller) {}
                } }
                val apply = onNodeWithText("Apply filters").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val clear = onNodeWithText("Clear filters").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val cancel = onNodeWithText("Cancel").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(clear.center.y, cancel.center.y, "Secondary actions must share a row")
                assertTrue(clear.right <= cancel.left)
                if (wraps) {
                    assertTrue(apply.top >= cancel.bottom)
                    assertEquals(cancel.right, apply.right)
                } else {
                    assertEquals(cancel.center.y, apply.center.y, "Wide dialog actions must share a row")
                    assertTrue(cancel.right <= apply.left)
                }
                System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { output ->
                    val pixels = onNode(isDialog()).captureToImage().toPixelMap()
                    val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
                    ImageIO.write(image, "png", Path.of(output).also(Files::createDirectories).resolve("filters-$name.png").toFile())
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
