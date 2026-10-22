package me.d3sox.nevumi

import android.Manifest
import android.content.ContentValues
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidFileEditingUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val optionsButtons = ShowOptionsButtonsRule()

    @Before
    fun awaitPreviousDataClear() {
        InstrumentationRegistry.getInstrumentation().awaitMediaStoreIdle()
    }

    @Test
    fun theTrackMenuConfirmsDeletionAndRestoresTheActualDeviceFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${app.packageName} $permission")).use { it.readBytes() }
        val resolver = app.contentResolver
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-delete-ui-${UUID.randomUUID()}")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val includeTrashed = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
        fun trashed() = requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.IS_TRASHED), includeTrashed, null)).use {
            check(it.moveToFirst()); it.getInt(0)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.importSource("device").join() }
                compose.waitUntil(5_000) { app.controller.state.value.songAt(uri.toString()) != null }
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-pager")))
                    .performScrollToNode(hasTestTag("track-row:${app.controller.state.value.songAt(uri.toString())!!.id}"))
                fun options() = compose.onNode(hasContentDescription("Track options: Northern Light") and
                    hasAnyAncestor(hasTestTag("track-row:${app.controller.state.value.songAt(uri.toString())!!.id}"))).performClick()
                options()
                compose.openFileAction("Delete local file")
                compose.onNodeWithText("Delete local file?").assertIsDisplayed()
                org.junit.Assert.assertEquals(0, trashed())
                compose.onNodeWithText("Cancel").performClick()
                org.junit.Assert.assertEquals(0, trashed())
                options()
                compose.openFileAction("Delete local file")
                compose.onNodeWithText("Delete", substring = false).performClick()
                compose.waitUntil(5_000) { app.controller.state.value.fileEdits.any { edit -> edit.trackId == app.controller.state.value.songAt(uri.toString())!!.id && edit.applied } }
                org.junit.Assert.assertEquals(1, trashed())
                options()
                compose.openFileAction("Undo file edit")
                compose.waitUntil(5_000) { app.controller.state.value.fileEdits.single { edit -> edit.trackId == app.controller.state.value.songAt(uri.toString())!!.id }.undone }
                org.junit.Assert.assertEquals(0, trashed())
                assertTrue(requireNotNull(resolver.openInputStream(uri)).use { input -> input.readBytes() }.contentEquals(bytes))
            }
        } finally { resolver.delete(uri, includeTrashed) }
    }

    @Test
    fun anImageChosenInAndroidsFilePickerIsEmbeddedIntoTheMusicFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${app.packageName} $permission")).use { it.readBytes() }
        val resolver = app.contentResolver
        val id = UUID.randomUUID().toString()
        val song = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-artwork-$id")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        instrumentation.context.assets.open("fixture.flac").use { input -> requireNotNull(resolver.openOutputStream(song)).use { input.copyTo(it) } }
        resolver.update(song, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        val imageName = "Nevumi-cover-$id.png"
        val imageBytes = instrumentation.context.assets.open("cover.png").use { it.readBytes() }
        val image = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nevumi-artwork-$id")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(image)).use { it.write(imageBytes) }
        resolver.update(image, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val imagePath = requireNotNull(resolver.query(image, arrayOf(MediaStore.Images.Media.DATA), null, null, null)).use {
            check(it.moveToFirst()); it.getString(0)
        }
        val scanned = kotlinx.coroutines.CompletableDeferred<android.net.Uri?>()
        android.media.MediaScannerConnection.scanFile(app, arrayOf(imagePath), arrayOf("image/png")) { _, uri -> scanned.complete(uri) }
        runBlocking { kotlinx.coroutines.withTimeout(5_000) { check(scanned.await() != null) { "The image fixture was not indexed by Android" } } }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.importSource("device").join() }
                compose.waitUntil(5_000) { app.controller.state.value.songAt(song.toString()) != null }
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-pager")))
                    .performScrollToNode(hasTestTag("track-row:${app.controller.state.value.songAt(song.toString())!!.id}"))
                compose.onNode(hasContentDescription("Track options: Northern Light") and
                    hasAnyAncestor(hasTestTag("track-row:${app.controller.state.value.songAt(song.toString())!!.id}"))).performClick()
                compose.openFileAction("Replace artwork")
                // A cold DocumentsUI launch can spend more than five seconds loading providers.
                compose.waitUntil(15_000) {
                    instrumentation.uiAutomation.rootInActiveWindow?.packageName?.let { it.toString() != app.packageName } == true
                }
                fun clickPickerItem(text: String) {
                    var requested = false
                    try {
                    compose.waitUntil(15_000) {
                        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@waitUntil false
                        if (waitOnNotRespondingDialog(root)) return@waitUntil false
                        fun nodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
                            (0 until node.childCount).mapNotNull { node.getChild(it) }.flatMap(::nodes)
                        val visible = nodes(root).filter { it.isVisibleToUser }
                        val sidebarOpen = visible.any { it.text?.toString() == "Images" && it.viewIdResourceName == "android:id/title" }
                        val imageVisible = visible.any { it.text?.toString() == imageName ||
                            it.contentDescription?.toString()?.let { name -> name == imageName || name.startsWith("$imageName,") } == true }
                        val folder = visible.lastOrNull { it.viewIdResourceName?.endsWith(":id/breadcrumb_text") == true }?.text?.toString()
                        if (!sidebarOpen && folder == text && imageVisible) return@waitUntil true
                        if (requested) return@waitUntil when (text) {
                            "Show roots" -> sidebarOpen
                            "Images" -> !sidebarOpen
                            else -> !sidebarOpen && imageVisible
                        }
                        val item = visible.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
                            ?: return@waitUntil false
                        var clickable: AccessibilityNodeInfo? = item
                        while (clickable != null && clickable.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK })
                            clickable = clickable.parent
                        requested = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                        false
                    }
                    } catch (error: Throwable) {
                        fun describe(node: AccessibilityNodeInfo?): List<String> = if (node == null) emptyList() else {
                            val own = if (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
                                listOf("${node.text} | ${node.contentDescription} | ${node.viewIdResourceName} | ${node.actionList}") else emptyList()
                            own + (0 until node.childCount).flatMap { describe(node.getChild(it)) }
                        }
                        throw AssertionError("Could not click image picker item '$text':\n${describe(instrumentation.uiAutomation.rootInActiveWindow).joinToString("\n")}", error)
                    }
                }
                clickPickerItem("Show roots")
                clickPickerItem("Images")
                clickPickerItem("Nevumi-artwork-$id")
                compose.waitUntil(5_000) {
                    instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(imageName)?.isNotEmpty() == true
                }
                val choice = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(imageName)
                    .first()
                // DocumentsUI exposes an Open action even when the image node reports clickable=false.
                check(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "The image's Open action failed: $choice" }
                compose.waitUntil(8_000) { app.controller.state.value.fileEdits.any { it.trackId == app.controller.state.value.songAt(song.toString())!!.id && it.applied } }
                val metadata = android.media.MediaMetadataRetriever()
                try {
                    metadata.setDataSource(app, song)
                    assertTrue(metadata.embeddedPicture?.contentEquals(imageBytes) == true)
                } finally { metadata.release() }
            }
        } finally { resolver.delete(song, null, null); resolver.delete(image, null, null) }
    }

    @Test
    fun selectingSongsChangesTheirAlbumTogetherOnAndroid() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${app.packageName} $permission")).use { it.readBytes() }
        val resolver = app.contentResolver
        val uris = listOf("flac", "mp3").map { extension ->
            val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.$extension")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-bulk-${UUID.randomUUID()}")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }))
            instrumentation.context.assets.open("fixture.$extension").use { input ->
                requireNotNull(resolver.openOutputStream(uri)).use { input.copyTo(it) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.importSource("device").join() }
                compose.waitUntil(5_000) { uris.all { uri -> app.controller.state.value.songAt(uri.toString()) != null } }
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-pager")))
                    .performScrollToNode(hasTestTag("track-row:${app.controller.state.value.songAt(uris[0].toString())!!.id}"))
                compose.onNode(hasContentDescription("Track options: Northern Light") and
                    hasAnyAncestor(hasTestTag("track-row:${app.controller.state.value.songAt(uris[0].toString())!!.id}"))).performClick()
                compose.onNodeWithText("Select song").performClick()
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-pager")))
                    .performScrollToNode(hasTestTag("track-row:${app.controller.state.value.songAt(uris[1].toString())!!.id}"))
                compose.onNodeWithTag("track-row:${app.controller.state.value.songAt(uris[1].toString())!!.id}").performClick()
                compose.onNodeWithText("2 songs selected").assertIsDisplayed()
                compose.onNodeWithContentDescription("Selection actions").performClick()
                compose.onNodeWithText("Edit tags").performClick()
                compose.onNodeWithText("Change album").performScrollTo().performClick()
                compose.onNode(hasText("Album") and hasSetTextAction()).performScrollTo().performTextReplacement("Android shared album")
                compose.onNodeWithText("Change year").performScrollTo().performClick()
                compose.onNode(hasText("Year") and hasSetTextAction()).performScrollTo().performTextReplacement("2026")
                compose.onNodeWithText("Save changes").performClick()
                compose.waitUntil(8_000) {
                    uris.all { uri -> app.controller.state.value.tracks.any { it.sources.any { source -> source.location == uri.toString() } && it.metadata.album == "Android shared album" && it.metadata.year == 2026 } } &&
                        compose.onAllNodesWithText("Save changes").fetchSemanticsNodes().isEmpty()
                }
                assertTrue(app.controller.state.value.fileEdits.count { it.trackId in uris.map { uri -> app.controller.state.value.songAt(uri.toString())!!.id } && it.applied } == 2)
            }
        } finally { uris.forEach { resolver.delete(it, null, null) } }
    }

    @Test
    fun theAndroidTrackMenuSavesTagsAndUndoesTheActualFileEdit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MusicApplication
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${app.packageName} $permission")).use { it.readBytes() }
        val resolver = app.contentResolver
        val bytes = instrumentation.context.assets.open("fixture.flac").use { it.readBytes() }
        val uri = requireNotNull(resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "fixture.flac")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Nevumi-editor-${UUID.randomUUID()}")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking { app.controller.importSource("device").join() }
                compose.waitUntil(5_000) { app.controller.state.value.songAt(uri.toString()) != null }
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-pager")))
                    .performScrollToNode(hasTestTag("track-row:${app.controller.state.value.songAt(uri.toString())!!.id}"))
                compose.onNode(hasContentDescription("Track options: Northern Light") and
                    hasAnyAncestor(hasTestTag("track-row:${app.controller.state.value.songAt(uri.toString())!!.id}"))).performClick()
                compose.openFileAction("Edit tags")
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("Save changes") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("Android edited music")
                compose.onNode(hasText("Year") and hasSetTextAction()).performScrollTo().performTextReplacement("2026")
                compose.onNodeWithText("Save changes").performClick()
                compose.waitUntil(15_000) {
                    app.controller.state.value.tracks.any { track -> track.sources.any { source -> source.location == uri.toString() } && track.metadata.title == "Android edited music" && track.metadata.year == 2026 } &&
                        compose.onAllNodesWithText("Save changes").fetchSemanticsNodes().isEmpty()
                }
                compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("library-pager")))
                    .performScrollToNode(hasTestTag("track-row:${app.controller.state.value.songAt(uri.toString())!!.id}"))
                compose.onNodeWithContentDescription("Track options: Android edited music").performClick()
                compose.onNodeWithText("File actions").performClick()
                compose.openFileAction("Undo file edit")
                compose.waitUntil(5_000) {
                    app.controller.state.value.tracks.any { track -> track.sources.any { source -> source.location == uri.toString() } && track.metadata.title == "Northern Light" }
                }
                assertTrue(requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }.contentEquals(bytes))
                compose.onNode(hasContentDescription("Track options: Northern Light") and
                    hasAnyAncestor(hasTestTag("track-row:${app.controller.state.value.songAt(uri.toString())!!.id}"))).performClick()
                compose.openFileAction("Rename file")
                compose.waitUntil(5_000) { compose.onAllNodes(hasText("fixture.flac") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("File name") and hasSetTextAction()).performTextReplacement("Android renamed music.flac")
                compose.onNodeWithText("Rename", substring = false).performClick()
                compose.waitUntil(5_000) { app.controller.state.value.fileEdits.any { it.changedName == "Android renamed music.flac" && it.applied && !it.undone } }
                requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)).use { cursor ->
                    check(cursor.moveToFirst()); org.junit.Assert.assertEquals("Android renamed music.flac", cursor.getString(0))
                }
                compose.onNode(hasContentDescription("Track options: Northern Light") and
                    hasAnyAncestor(hasTestTag("track-row:${app.controller.state.value.songAt(uri.toString())!!.id}"))).performClick()
                compose.openFileAction("Undo file edit")
                compose.waitUntil(5_000) { app.controller.state.value.fileEdits.filter { it.changedName == "Android renamed music.flac" }.all { it.undone } }
                requireNotNull(resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)).use { cursor ->
                    check(cursor.moveToFirst()); org.junit.Assert.assertEquals("fixture.flac", cursor.getString(0))
                }
                assertTrue(requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }.contentEquals(bytes))
            }
        } finally {
            // After Nevumi renames the file and undoes it, Android can refuse the test's own delete. A leftover file
            // duplicates the track in a retry, so let the shell remove it instead.
            try { resolver.delete(uri, null, null) }
            catch (_: SecurityException) {
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "content delete --uri $uri")).use { it.readBytes() }
            }
        }
    }
}
