package me.d3sox.nevumi

import android.content.ContentValues
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.d3sox.nevumi.playback.EqualizerKind
import me.d3sox.nevumi.settings.MusicPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidAutoEqUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun aProfileIsImportedThroughTheSystemPickerAndReusedFromTheLocalList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val resolver = app.contentResolver
        val folder = "Nevumi-equalizer-zz-${UUID.randomUUID()}"
        val name = "Headphones-${UUID.randomUUID()}"
        val document = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$folder")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        val original = runBlocking { MusicPreferences(app.store).load() }
        fun clickPickerItem(text: String, titleOnly: Boolean = false) {
            var requested = false
            fun update(allowClick: Boolean): Boolean {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
                if (waitOnNotRespondingDialog(root)) return false
                val titles = root.findAccessibilityNodeInfosByViewId("android:id/title").filter { it.isVisibleToUser }
                val sidebarOpen = titles.any { it.text?.toString() == "Downloads" && !it.isEditable }
                if (text == "Show roots" && sidebarOpen) return true
                if (titleOnly) {
                    val breadcrumbs = root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/breadcrumb_text")
                        .filter { it.isVisibleToUser }.map { it.text?.toString() }
                    if (!sidebarOpen && breadcrumbs.lastOrNull() == text) return true
                } else if (requested && root.packageName?.toString() == app.packageName) return true
                if (!allowClick) return false
                val item = root.findAccessibilityNodeInfosByText(text).firstOrNull {
                    it.isVisibleToUser &&
                        (it.text?.toString()?.equals(text, ignoreCase = true) == true || it.contentDescription?.toString() == text) &&
                        (!titleOnly || it.viewIdResourceName == "android:id/title" && !it.isEditable)
                } ?: return false
                var clickable: AccessibilityNodeInfo? = item
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                val target = clickable ?: return false
                // A node can become stale while the picker opens; retry rejected clicks.
                requested = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return requested
            }
            try {
                // Finding the control and observing its resulting window each need their own wait. The first includes opening the
                // picker, which takes several seconds on a busy emulator.
                compose.waitUntil(15_000) { update(allowClick = true) }
                compose.waitUntil(5_000) { update(allowClick = false) }
            } catch (failure: ComposeTimeoutException) {
                fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
                    (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::nodes)
                val tree = instrumentation.uiAutomation.rootInActiveWindow?.let(::nodes)
                    ?.filter { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }?.joinToString("\n") {
                        "${it.text} | ${it.contentDescription} | ${it.viewIdResourceName} | visible=${it.isVisibleToUser}"
                    }
                throw AssertionError("Picker '$text', click accepted=$requested:\n$tree", failure)
            }
        }
        try {
            requireNotNull(resolver.openOutputStream(document)).bufferedWriter().use {
                it.write("Preamp: -3 dB\nFilter 1: ON LSC Fc 200 Hz Gain 3 dB Q 0.7\n")
            }
            resolver.update(document, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                compose.waitUntil(5_000) { !controller.state.value.loading }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Playback").performScrollTo().performClick()
                compose.onNodeWithText("Audio effects").performScrollTo().performClick()
                compose.onNodeWithText("Headphone profiles").performScrollTo().performClick()
                compose.onNodeWithText("Import profile").performClick()
                clickPickerItem("Show roots")
                clickPickerItem("Downloads", titleOnly = true)
                clickPickerItem(folder, titleOnly = true)
                clickPickerItem("$name.txt")
                compose.waitUntil(10_000) { controller.state.value.playback.audioEffects.profileName == name }
                val effects = controller.state.value.playback.audioEffects
                assertEquals(-3.0, effects.preampDb, 0.001)
                assertEquals(EqualizerKind.LOW_SHELF, effects.bands.single().kind)
                assertEquals(effects, runBlocking { MusicPreferences(app.store).load() }.audioEffects)
                runBlocking { controller.updatePreferences { it.copy(audioEffects = me.d3sox.nevumi.playback.AudioEffects()) }.join() }
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText(name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Search headphones")))
                    .performScrollToNode(hasContentDescription("Use saved profile: $name"))
                compose.onNodeWithContentDescription("Use saved profile: $name").assertIsEnabled().performClick()
                compose.waitUntil(5_000) { controller.state.value.playback.audioEffects == effects }
                assertEquals(1, runBlocking { requireNotNull(controller.equalizerProfiles).saved() }.size)
            }
        } finally {
            runBlocking { app.controller.updatePreferences { original }.join() }
            resolver.delete(document, null, null)
        }
    }
}
