package me.d3sox.nevumi

import android.content.ContentValues
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidM3uPlaylistsUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun anM3uPlaylistCanBeImportedExportedAndOpenedAgainThroughAndroidsFilePicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val resolver = app.contentResolver
        val id = UUID.randomUUID().toString()
        val song = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-m3u-$id")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        val folder = "Nevumi-m3u-$id"
        val name = "Evening-$id"
        val exported = "Exported-$id"
        val document = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.m3u8")
            put(MediaStore.Downloads.MIME_TYPE, "audio/x-mpegurl")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$folder")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        fun clickPickerItem(text: String, titleOnly: Boolean = false) {
            var requested = false
            var requestedAt = 0L
            try {
            compose.waitUntil(10_000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
                fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
                    (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::nodes)
                val visible = nodes(root).filter { it.isVisibleToUser }
                if (waitOnNotRespondingDialog(root)) return@waitUntil false
                val sidebarOpen = visible.any { it.text?.toString() == "Downloads" && it.viewIdResourceName == "android:id/title" && !it.isEditable }
                if (text == "Show roots" && requested && sidebarOpen) return@waitUntil true
                if (titleOnly) {
                    val breadcrumbs = visible.filter { it.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true }
                        .map { it.text?.toString() }
                    val selected = breadcrumbs.lastOrNull() == text
                    if (selected && (text != "Downloads" || requested || !sidebarOpen)) return@waitUntil true
                } else if (requested && root.packageName?.toString() == app.packageName) return@waitUntil true
                // A slow DocumentsUI can drop navigation clicks, so retry one that had no effect.
                val retryable = titleOnly || text == "Show roots"
                if (requested && (!retryable || SystemClock.uptimeMillis() - requestedAt < 3_000)) return@waitUntil false
                val item = visible.firstOrNull {
                    (it.text?.toString()?.equals(text, ignoreCase = true) == true || it.contentDescription?.toString() == text) &&
                        (!titleOnly || it.viewIdResourceName == "android:id/title" && !it.isEditable)
                } ?: return@waitUntil false
                var clickable: AccessibilityNodeInfo? = item
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                val target = clickable ?: return@waitUntil false
                requested = true
                requestedAt = SystemClock.uptimeMillis()
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                false
            }
            } catch (error: Throwable) {
                fun describe(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else {
                    val own = if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
                        listOf("${node.text} | ${node.contentDescription} | ${node.viewIdResourceName} | ${node.actionList}") else emptyList()
                    own + (0 until node.childCount).flatMap { describe(node.getChild(it)) }
                }
                throw AssertionError("Could not click picker item '$text':\n${describe(instrumentation.uiAutomation.rootInActiveWindow).joinToString("\n")}", error)
            }
        }
        fun openDocument(filename: String) {
            clickPickerItem("Show roots")
            clickPickerItem("Downloads", titleOnly = true)
            clickPickerItem(folder, titleOnly = true)
            clickPickerItem(filename)
        }
        try {
            instrumentation.context.assets.open("fixture.flac").use { input -> requireNotNull(resolver.openOutputStream(song)).use { input.copyTo(it) } }
            resolver.update(song, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            requireNotNull(resolver.openOutputStream(document)).bufferedWriter().use {
                it.write("#EXTM3U\nMusic/$folder/fixture.flac\n$song\n")
            }
            resolver.update(document, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            ActivityScenario.launch(MainActivity::class.java).use {
                val controller = app.controller
                compose.waitUntil(5_000) { !controller.state.value.loading }
                runBlocking { controller.importSource(song.toString()).join() }
                val trackId = requireNotNull(controller.state.value.songAt(song.toString())).id
                compose.openLibraryPage("Playlists")
                compose.onNodeWithContentDescription("Import M3U").performClick()
                openDocument("$name.m3u8")
                compose.waitUntil(10_000) { controller.state.value.playlists.any { it.name == name } }
                assertEquals(listOf(trackId, trackId), controller.state.value.playlists.single { it.name == name }.trackIds)
                compose.waitUntil(5_000) { compose.onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithText(name).performClick()
                compose.onNodeWithContentDescription("Playlist options").performClick()
                compose.onNodeWithText("Export M3U").performClick()
                compose.waitUntil(5_000) { instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("$name.m3u8")?.any { it.isEditable } == true }
                val field = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("$name.m3u8").first { it.isEditable }
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$exported.m3u8")
                }))
                clickPickerItem("Show roots")
                clickPickerItem("Downloads", titleOnly = true)
                clickPickerItem(folder, titleOnly = true)
                clickPickerItem("Save")
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Playlist exported").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithContentDescription("Import M3U").performClick()
                openDocument("$exported.m3u8")
                compose.waitUntil(10_000) { controller.state.value.playlists.any { it.name == exported } }
                assertEquals(listOf(trackId, trackId), controller.state.value.playlists.single { it.name == exported }.trackIds)
            }
        } finally {
            resolver.delete(song, null, null); resolver.delete(document, null, null)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "rm -f /sdcard/Download/$folder/$exported.m3u8")).use { it.readBytes() }
        }
    }
}
