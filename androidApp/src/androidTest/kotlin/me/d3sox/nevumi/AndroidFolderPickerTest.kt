package me.d3sox.nevumi

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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
class AndroidFolderPickerTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun includeAndExcludeUseTheAndroidFolderPickerAndCanBeRemoved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val folder = "AAA Nevumi picker ${UUID.randomUUID()}"
        val path = "/storage/emulated/0/Music/$folder"
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${app.packageName} $permission")).use { it.readBytes() }
        val resolver = app.contentResolver
        val song = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$folder")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(root) +
            (0 until root.childCount).mapNotNull { root.getChild(it) }.flatMap(::nodes)
        fun clickNative(label: String, titleOnly: Boolean = false) {
            // DocumentsUI can be slow to list folders on CI emulators.
            compose.waitUntil(15_000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
                if (waitOnNotRespondingDialog(root)) return@waitUntil false
                val node = nodes(root).firstOrNull { it.isVisibleToUser &&
                    (it.text?.toString()?.equals(label, ignoreCase = true) == true || it.contentDescription?.toString() == label) &&
                    (!titleOnly || it.viewIdResourceName == "android:id/title") } ?: return@waitUntil false
                var button: AccessibilityNodeInfo? = node
                while (button != null && !button.isClickable) button = button.parent
                button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        }
        fun chooseFolder() {
            clickNative(folder, titleOnly = true)
            clickNative("Use this folder")
            clickNative("Allow")
        }
        try {
            instrumentation.context.assets.open("fixture.flac").use { input -> requireNotNull(resolver.openOutputStream(song)).use { input.copyTo(it) } }
            resolver.update(song, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.importSource("device").join() }
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Local music").performClick()
                compose.onNodeWithContentDescription("Add included folder").performClick()
                chooseFolder()
                compose.waitUntil(10_000) { app.controller.state.value.preferences.includeFolders == listOf(path) }
                compose.onNodeWithContentDescription("Remove included folder: $path").assertIsDisplayed()
                compose.onNodeWithContentDescription("Add excluded folder").performScrollTo().performClick()
                chooseFolder()
                compose.waitUntil(10_000) { app.controller.state.value.preferences.excludeFolders == listOf(path) }
                compose.onNodeWithContentDescription("Remove excluded folder: $path").performScrollTo().performClick()
                compose.waitUntil(10_000) { app.controller.state.value.preferences.excludeFolders.isEmpty() }
                compose.onNodeWithContentDescription("Remove included folder: $path").performScrollTo().performClick()
                compose.waitUntil(10_000) { app.controller.state.value.preferences.includeFolders.isEmpty() }
                assertEquals(emptyList<String>(), app.controller.state.value.preferences.excludeFolders)
            }
        } finally { resolver.delete(song, null, null) }
    }
}
