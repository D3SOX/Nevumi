package me.d3sox.nevumi.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AboutUiTest {
    @Test fun aboutHasCenteredBrandingAndButtonsThatOpenTheSourceAndOfflineLicense() {
        val directory = Files.createTempDirectory("about-nevumi")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"))
        var opened: String? = null
        try {
            runComposeUiTest {
                setContent {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened = uri }
                    }) { MusicPlayerApp(controller) }
                }
                waitForLibrary(controller)
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("About Nevumi").performScrollTo().performClick()
                val logo = onNodeWithContentDescription("Nevumi logo").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val page = onNodeWithTag("settings-page").fetchSemanticsNode().boundsInRoot
                assertTrue(kotlin.math.abs(logo.center.x - page.center.x) < 2f, "The app logo must be centered")
                val version = java.util.Properties().apply {
                    Files.newInputStream(java.nio.file.Path.of(System.getProperty("nevumi.testRoot"), "gradle.properties"))
                        .use { load(it) }
                }.getProperty("appVersion")
                onNodeWithText("Version $version").assertIsDisplayed()
                captureUiSnapshot("about-nevumi")
                for (label in listOf("Source code", "View license")) {
                    val bounds = onNodeWithText(label).assertHasClickAction().fetchSemanticsNode().boundsInRoot
                    assertTrue(kotlin.math.abs(bounds.center.x - page.center.x) < 2f, "$label must be centered")
                }
                onNodeWithText("Source code").performClick()
                assertEquals("https://github.com/D3SOX/Nevumi", opened)
                onNodeWithText("View license").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodesWithText("GNU AFFERO GENERAL PUBLIC LICENSE", substring = true).fetchSemanticsNodes().isNotEmpty()
                }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Nevumi logo").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
