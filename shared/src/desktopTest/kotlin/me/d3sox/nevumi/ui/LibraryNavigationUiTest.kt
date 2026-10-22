package me.d3sox.nevumi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.settings.MusicSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryNavigationUiTest {
    private val tabs = listOf(LibraryTab.HOME) + LibraryTab.entries.filter { it != LibraryTab.HOME }
    private val navigationItem = hasClickAction() and hasAnyAncestor(hasTestTag("library-navigation"))

    @Test fun resizingRevealsMorePagesAndPreservesSelectionAcrossOverflow() = runSkikoComposeUiTest(size = Size(2400f, 900f)) {
        var width by mutableStateOf(360.dp)
        var current by mutableStateOf(LibraryTab.SONGS)
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(width)) { LibraryNavigation(tabs, current) { current = it } }
        } }
        onAllNodes(navigationItem).assertCountEquals(5)
        onNodeWithText("Folders").assertDoesNotExist()
        onNodeWithText("More").performClick()
        onNodeWithTag("library-more-pages").performScrollToNode(hasText("Folders"))
        onNodeWithText("Folders").performClick()
        onNodeWithText("More").assertIsSelected()

        runOnIdle { width = 720.dp }
        assertTrue(onAllNodes(navigationItem).fetchSemanticsNodes().size > 5)
        onNodeWithContentDescription("Folders").assertIsSelected()
        onNodeWithText("More").performClick()
        onNode(hasText("Folders") and hasAnyAncestor(hasTestTag("library-more-pages"))).assertDoesNotExist()
        onNodeWithTag("library-more-pages").performScrollToNode(hasText("Recommendations"))
        onNodeWithText("Recommendations").assertIsDisplayed()

        runOnIdle { width = 2400.dp }
        onNodeWithTag("library-more-pages").assertDoesNotExist()
        onNodeWithText("More").assertDoesNotExist()
        onAllNodes(navigationItem).assertCountEquals(tabs.size)
        captureNavigation("navigation-wide")
        onNodeWithContentDescription("Folders").assertIsSelected()
        onNodeWithContentDescription("Recommendations").performClick().assertIsSelected()

        runOnIdle { width = 360.dp }
        onNodeWithTag("library-more-pages").assertDoesNotExist()
        onNodeWithText("More").assertIsSelected()
        assertEquals(LibraryTab.RECOMMENDATIONS, current)
        runOnIdle { width = 2400.dp }
        onNodeWithContentDescription("Recommendations").assertIsSelected()
    }

    @Test fun enlargedTextReducesCapacityWithoutChangingTheSelectedPage() = runComposeUiTest {
        var fontScale by mutableStateOf(1f)
        var current by mutableStateOf(LibraryTab.GENRES)
        setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MusicTheme(MusicSettings(), null) {
                    Box(Modifier.width(720.dp)) { LibraryNavigation(tabs, current) { current = it } }
                }
            }
        }
        val original = onAllNodes(navigationItem).fetchSemanticsNodes().size
        onNodeWithContentDescription("Genres").assertIsSelected()
        runOnIdle { fontScale = 2f }
        assertTrue(onAllNodes(navigationItem).fetchSemanticsNodes().size < original)
        onNodeWithText("More").assertIsSelected()
        onAllNodes(navigationItem).fetchSemanticsNodes().forEach { assertTrue(it.boundsInRoot.width >= 48) }
        captureNavigation("navigation-large-text")
        assertEquals(LibraryTab.GENRES, current)
        runOnIdle { fontScale = 1f }
        onNodeWithContentDescription("Genres").assertIsSelected()
    }

    @Test fun configuredOrderIsKeptAndEverySlotIsAvailableWhenAllPagesFit() = runComposeUiTest {
        val configured = listOf(LibraryTab.HOME, LibraryTab.SONGS, LibraryTab.RECENT, LibraryTab.PLAYLISTS,
            LibraryTab.HISTORY, LibraryTab.FAVORITES, LibraryTab.ARTISTS)
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(720.dp)) { LibraryNavigation(configured, LibraryTab.HISTORY) {} }
        } }
        onNodeWithText("More").assertDoesNotExist()
        onAllNodes(navigationItem).assertCountEquals(configured.size)
        val positions = configured.map { onNodeWithContentDescription(it.label).fetchSemanticsNode().boundsInRoot.left }
        assertEquals(positions.sorted(), positions)
        onNodeWithContentDescription("History").assertIsSelected()
    }

    @Test fun chosenTabsLeaveUnusedSpaceAndOverflowRemainsReachable() = runComposeUiTest {
        val chosen = setOf(LibraryTab.HOME, LibraryTab.PLAYLISTS)
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(720.dp)) { LibraryNavigation(tabs, LibraryTab.SONGS, bottomNavigationTabs = chosen) {} }
        } }
        onAllNodes(navigationItem).assertCountEquals(3)
        onNodeWithText("More").assertIsSelected().performClick()
        onNodeWithTag("library-more-pages").performScrollToNode(hasText("Songs"))
        onNode(hasText("Songs") and hasAnyAncestor(hasTestTag("library-more-pages"))).assertIsDisplayed()
    }

    @Test fun threeEnabledChosenTabsNeedNoMoreButton() = runComposeUiTest {
        val chosen = listOf(LibraryTab.HOME, LibraryTab.SONGS, LibraryTab.PLAYLISTS)
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(360.dp)) { LibraryNavigation(chosen, LibraryTab.HOME, bottomNavigationTabs = chosen.toSet()) {} }
        } }
        onAllNodes(navigationItem).assertCountEquals(3)
        onNodeWithText("More").assertDoesNotExist()
        captureNavigation("navigation-three-tabs")
    }

    @Test fun chosenTabsOverflowOnSmallScreensAndReturnOnWideScreens() = runComposeUiTest {
        var width by mutableStateOf(360.dp)
        val chosen = tabs.take(7).toSet()
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(width)) { LibraryNavigation(tabs, tabs[6], bottomNavigationTabs = chosen) {} }
        } }
        onAllNodes(navigationItem).assertCountEquals(5)
        onNodeWithText("More").assertIsSelected()
        runOnIdle { width = 720.dp }
        onAllNodes(navigationItem).assertCountEquals(8)
        onNodeWithContentDescription(tabs[6].label).assertIsSelected()
    }

    @Test fun customizationMovesTabsBetweenSectionsAndEnforcesCapacity() = runComposeUiTest {
        var saved: Set<LibraryTab>? = null
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(360.dp)) {
                LibraryTabsEditor(tabs, null, onSave = { _, bottom -> saved = bottom }, dismiss = {})
            }
        } }
        captureNavigation("library-tabs-customization", "settings-page")
        onNodeWithContentDescription("Move Home to More").performClick()
        onNodeWithTag("library-tabs-editor").performScrollToNode(hasContentDescription("Add Home to bottom navigation"))
        onNodeWithContentDescription("Add Home to bottom navigation").assertIsEnabled().performClick()
        val overflowTab = tabs[4]
        onNodeWithTag("library-tabs-editor").performScrollToNode(hasContentDescription("Add ${overflowTab.label} to bottom navigation"))
        onNodeWithContentDescription("Add ${overflowTab.label} to bottom navigation").assertIsNotEnabled()
        onNodeWithText("Save tabs").performClick()
        assertEquals(tabs.take(4).toSet(), saved)
    }

    @Test fun disablingAnOverflowTabPreservesAutomaticSizingAfterSave() = runSkikoComposeUiTest(size = Size(2400f, 900f)) {
        var editing by mutableStateOf(true)
        var savedTabs by mutableStateOf(tabs)
        var savedBottomTabs by mutableStateOf<Set<LibraryTab>?>(null)
        var width by mutableStateOf(360.dp)
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(width)) {
                if (editing) LibraryTabsEditor(savedTabs, savedBottomTabs, onSave = { enabled, bottom ->
                    savedTabs = enabled
                    savedBottomTabs = bottom
                    editing = false
                }, dismiss = {})
                else LibraryNavigation(savedTabs, LibraryTab.HOME, bottomNavigationTabs = savedBottomTabs) {}
            }
        } }
        onNodeWithTag("library-tabs-editor").performScrollToNode(hasContentDescription("Show Recommendations"))
        onNodeWithContentDescription("Show Recommendations").performClick()
        onNodeWithText("Save tabs").performClick()
        assertEquals(null, savedBottomTabs)
        onAllNodes(navigationItem).assertCountEquals(5)
        runOnIdle { width = 2400.dp }
        onAllNodes(navigationItem).assertCountEquals(tabs.size - 1)
        onNodeWithText("More").assertDoesNotExist()
        onNodeWithContentDescription("Recommendations").assertDoesNotExist()
        onNodeWithContentDescription("Folders").assertIsDisplayed()
    }

    @Test fun addingATabPreservesConfiguredTabsThatOverflowOnNarrowScreens() = runComposeUiTest {
        val enabled = listOf(LibraryTab.HOME, LibraryTab.SONGS, LibraryTab.RECOMMENDATIONS, LibraryTab.ALBUMS, LibraryTab.ARTISTS)
        val configured = enabled.take(4).toSet()
        var width by mutableStateOf(360.dp)
        var saved: Set<LibraryTab>? = null
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(width)) {
                LibraryTabsEditor(enabled, configured, onSave = { _, bottom -> saved = bottom }, dismiss = {})
            }
        } }
        onNodeWithTag("library-tabs-editor").performScrollToNode(hasContentDescription("Add Artists to bottom navigation"))
        onNodeWithContentDescription("Add Artists to bottom navigation").assertIsNotEnabled()
        runOnIdle { width = 720.dp }
        onNodeWithTag("library-tabs-editor").performScrollToNode(hasContentDescription("Add Artists to bottom navigation"))
        onNodeWithContentDescription("Add Artists to bottom navigation").assertIsEnabled().performClick()
        onNodeWithText("Save tabs").performClick()
        assertEquals(configured + LibraryTab.ARTISTS, saved)
    }

    @Test fun reenablingATabKeepsAutomaticSizing() = runSkikoComposeUiTest(size = Size(2400f, 900f)) {
        var width by mutableStateOf(360.dp)
        var savedTabs = emptyList<LibraryTab>()
        var savedBottom: Set<LibraryTab>? = emptySet()
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(width)) {
                LibraryTabsEditor(tabs - LibraryTab.RECOMMENDATIONS, null, onSave = { enabled, bottom ->
                    savedTabs = enabled
                    savedBottom = bottom
                }, dismiss = {})
            }
        } }
        onNodeWithTag("library-tabs-editor").performScrollToNode(hasContentDescription("Show Recommendations"))
        onNodeWithContentDescription("Show Recommendations").performClick()
        runOnIdle { width = 2400.dp }
        onNodeWithTag("library-tabs-editor").performScrollToNode(hasContentDescription("Move Recommendations to More"))
        onNodeWithContentDescription("Move Recommendations to More").assertIsEnabled()
        onNodeWithText("Save tabs").performClick()
        assertEquals(tabs.toSet(), savedTabs.toSet())
        assertEquals(null, savedBottom)
    }

    @Test fun configuredOverflowTabsCanBeRemovedWithoutDisablingOrReorderingThem() = runComposeUiTest {
        val enabled = listOf(LibraryTab.HOME, LibraryTab.SONGS, LibraryTab.RECOMMENDATIONS, LibraryTab.ALBUMS, LibraryTab.ARTISTS)
        val configured = enabled.take(4).toSet()
        var savedTabs = emptyList<LibraryTab>()
        var savedBottom: Set<LibraryTab>? = null
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(360.dp)) {
                LibraryTabsEditor(enabled, configured, onSave = { tabs, bottom ->
                    savedTabs = tabs
                    savedBottom = bottom
                }, dismiss = {})
            }
        } }
        onNodeWithTag("library-tabs-editor").performScrollToNode(hasContentDescription("Remove Recommendations from bottom navigation"))
        onNodeWithContentDescription("Remove Recommendations from bottom navigation").assertIsEnabled().performClick()
        onNodeWithContentDescription("Show Recommendations").assertIsOn()
        onNodeWithText("Save tabs").performClick()
        assertEquals(enabled, savedTabs)
        assertEquals(configured - LibraryTab.RECOMMENDATIONS, savedBottom)
    }

    @Test fun draggedTabsStopAtTheEdgesOfTheirSection() = runComposeUiTest {
        var saved: List<LibraryTab>? = null
        setContent { MusicTheme(MusicSettings(), null) {
            Box(Modifier.width(360.dp)) { LibraryTabsEditor(tabs, null, onSave = { enabled, _ -> saved = enabled }, dismiss = {}) }
        } }
        listOf(tabs[0] to -1f, tabs[3] to 1f).forEach { (tab, direction) ->
            val handle = onNodeWithContentDescription("Reorder tab: ${tab.label}", useUnmergedTree = true)
            val start = handle.fetchSemanticsNode().boundsInRoot.top
            handle.performTouchInput { down(center) }
            repeat(6) {
                handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, direction * 60f), delayMillis = 50) }
                waitForIdle()
                assertEquals(start, handle.fetchSemanticsNode().boundsInRoot.top, 1f)
            }
            handle.performTouchInput { up() }
            waitForIdle()
        }
        onNodeWithText("Save tabs").performClick()
        assertEquals(tabs, saved)
    }

    private fun ComposeUiTest.captureNavigation(name: String, tag: String = "library-navigation") {
        System.getenv("NEVUMI_UI_SNAPSHOTS")?.let { directory ->
            val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
            val image = java.awt.image.BufferedImage(pixels.width, pixels.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
            val target = java.nio.file.Path.of(directory).also(java.nio.file.Files::createDirectories).resolve("$name.png")
            javax.imageio.ImageIO.write(image, "png", target.toFile())
        }
    }
}
