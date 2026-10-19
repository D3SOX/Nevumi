package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
class FileEditingUiTest {
    @Test
    fun editingALinkedSongStartsWithTheTagsInItsLocalFileAndKeepsUnchangedFields() {
        val directory = Files.createTempDirectory("linked-file-tags-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        val track = runBlocking {
            val server = library.importTrack(TrackMetadata("Server title", "Server artist", "Server album"),
                TrackSource("server:song", SourceKind.SERVER, "song"))
            library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album"),
                TrackSource("local:$audio", SourceKind.LOCAL, audio.toString()), linkTo = server.id)
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Server title").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Edit tags").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodes(hasText("Northern Light") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasText("Artist") and hasSetTextAction()).assertTextContains("Fixture Artist")
                onNode(hasText("Album") and hasSetTextAction()).performTextReplacement("Edited local album")
                onNodeWithText("Save changes").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.any { it.applied } }
                val tags = org.jaudiotagger.audio.AudioFileIO.read(audio.toFile()).tag
                assertEquals("Northern Light", tags.getFirst(org.jaudiotagger.tag.FieldKey.TITLE))
                assertEquals("Fixture Artist", tags.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST))
                assertEquals("Edited local album", tags.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM))
                assertEquals(2024, controller.state.value.tracks.single().metadata.year)
                assertEquals(track.id, controller.state.value.tracks.single().id)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun swipingBetweenSongAndFileActionsKeepsTheCoverVisible() {
        val directory = Files.createTempDirectory("file-actions-swipe-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val audio = directory.resolve("song.flac")
        Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), audio)
        runBlocking { library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album"),
            TrackSource("local:$audio", SourceKind.LOCAL, audio.toString())) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Play next").performTouchInput { swipe(center, center.copy(y = center.y - 600f), 300) }
                onNodeWithText("Edit tags").assertIsDisplayed()
                onNodeWithText("Play next").assertDoesNotExist()
                onNodeWithTag("track-action-artwork").assertIsDisplayed()
                onNodeWithText("Edit tags").performTouchInput { swipe(center, center.copy(y = center.y + 600f), 300) }
                onNodeWithText("Play next").assertIsDisplayed()
                onNodeWithText("Edit tags").assertDoesNotExist()
                onNodeWithText("File actions").performClick()
                onNodeWithTag("track-action-artwork").assertIsDisplayed()
                onNodeWithContentDescription("Back to song actions").performClick()
                onNodeWithText("Play next").assertIsDisplayed()
                onNodeWithContentDescription("Back to song actions").assertDoesNotExist()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anUnappliedEditCanBeDiscardedAfterRestartWithoutChangingTheSong() {
        val directory = Files.createTempDirectory("discard-prepared-edit-ui")
        var store = openMusicStore(directory.resolve("library.db").toString())
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        var library = MusicLibrary(store)
        val track = runBlocking { LocalFiles(library).scan(audio.toString()).single() }
        val prepared = runBlocking { LocalFileEdits(store, library, directory.resolve("edits").toString())
            .prepareTags(track.id, track.metadata.copy(title = "Unsaved title")) }
        store.close(); store = openMusicStore(directory.resolve("library.db").toString())
        library = MusicLibrary(store)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Files & recovery").performClick()
                onNodeWithText("Review interrupted edit").performClick()
                onNodeWithText("Discard prepared edit").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.isEmpty() }
                assertEquals(-1L, Files.mismatch(original, audio))
                assertEquals(false, File(prepared.backupPath).exists())
                assertEquals(false, File(prepared.stagedPath).exists())
                assertEquals(track.id, controller.state.value.tracks.single().id)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun theTagEditorValidatesAndSavesTheYearToTheMusicFile() {
        val directory = Files.createTempDirectory("edit-year-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        val track = runBlocking { LocalFiles(library).scan(audio.toString()).single() }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Edit tags").performClick()
                waitUntil(timeoutMillis = 3_000) {
                    onAllNodes(hasText("Year") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
                }
                onNode(hasText("Year") and hasSetTextAction()).performScrollTo().performTextReplacement("10000")
                onNodeWithText("Save changes").assertIsNotEnabled()
                onNode(hasText("Year") and hasSetTextAction()).performTextReplacement("2026")
                onNodeWithText("Save changes").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.any { it.applied } }
                assertEquals("2026", org.jaudiotagger.audio.AudioFileIO.read(audio.toFile()).tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR))
                assertEquals(2026, controller.state.value.tracks.single { it.id == track.id }.metadata.year)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun deletingAFileRequiresConfirmationAndTheTrackMenuCanRestoreIt() {
        val directory = Files.createTempDirectory("delete-file-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        val track = runBlocking { LocalFiles(library).scan(audio.toString()).single() }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Delete local file").performClick()
                onNodeWithText("Delete local file?").assertIsDisplayed()
                assertEquals(-1L, Files.mismatch(original, audio))
                onNodeWithText("Cancel").performClick()
                assertEquals(-1L, Files.mismatch(original, audio))
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Delete local file").performClick()
                onNodeWithText("Delete", substring = false).performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.any { it.applied } }
                kotlin.test.assertFalse(Files.exists(audio))
                onNodeWithText("Local file deleted").assertIsDisplayed()
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.single().undone }
                assertEquals(-1L, Files.mismatch(original, audio))
                assertEquals(true, controller.state.value.tracks.single { it.id == track.id }.sources.single().available)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aMusicFileCanBeRenamedAndRestoredThroughTheTrackMenu() {
        val directory = Files.createTempDirectory("rename-file-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        val track = runBlocking { LocalFiles(library).scan(audio.toString()).single() }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Rename file").performClick()
                waitUntil(timeoutMillis = 3_000) { onAllNodes(hasText("song.flac") and hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
                onNode(hasText("File name") and hasSetTextAction()).performTextReplacement("My song.flac")
                onNodeWithText("Rename", substring = false).performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.any { it.applied } }
                assertEquals(track.id, controller.state.value.tracks.single().id)
                assertEquals(-1L, Files.mismatch(original, directory.resolve("My song.flac")))
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.single().undone }
                assertEquals(-1L, Files.mismatch(original, audio))
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aChosenImageCanBeEmbeddedAndUndoneThroughTheTrackMenu() {
        val directory = Files.createTempDirectory("artwork-picker-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        val track = runBlocking { LocalFiles(library).scan(audio.toString()).single() }
        val image = Files.readAllBytes(Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "cover.png"))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, artworkPicker = ArtworkPicker { image }) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Replace artwork").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.any { it.applied } }
                kotlin.test.assertTrue(org.jaudiotagger.audio.AudioFileIO.read(audio.toFile()).tag.firstArtwork.binaryData.contentEquals(image))
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.single().undone }
                assertEquals(-1L, Files.mismatch(original, audio))
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun successfulBulkEditsRemainVisibleForUndoWhenAnotherFileFails() {
        val directory = Files.createTempDirectory("partial-bulk-editor-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album")
        val music = Files.createDirectory(directory.resolve("music"))
        for (name in listOf("01 Northern Light.flac", "02 Long Way Home.mp3", "03 Night Walk.wav")) Files.copy(fixtures.resolve(name), music.resolve(name))
        val tracks = runBlocking { LocalFiles(library).scan(music.toString()) }.associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Select song").performClick()
                for (title in listOf("Long Way Home", "Night Walk")) onNodeWithTag("track-row:${tracks.getValue(title).id}").performClick()
                onNodeWithText("3 songs selected").assertIsDisplayed()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Edit tags").performClick()
                onNodeWithText("Change album").performClick()
                onNode(hasText("Album") and hasSetTextAction()).performTextReplacement("Shared album")
                Files.delete(music.resolve("02 Long Way Home.mp3"))
                onNodeWithText("Save changes").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.error != null && controller.state.value.fileEdits.count { it.applied } == 2 }
                kotlin.test.assertTrue(controller.state.value.error!!.contains("02 Long Way Home.mp3"))
                onNodeWithText("Cancel").performClick()
                onNodeWithText("3 songs selected").assertIsDisplayed()
                onNodeWithContentDescription("Clear selection").performClick()
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").assertIsDisplayed()
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun selectingTwoSongsLetsTheirAlbumChangeTogetherWithoutChangingTheOtherSong() {
        val directory = Files.createTempDirectory("bulk-editor-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album")
        val music = Files.createDirectory(directory.resolve("music"))
        for (name in listOf("01 Northern Light.flac", "02 Long Way Home.mp3", "03 Night Walk.wav")) Files.copy(fixtures.resolve(name), music.resolve(name))
        val tracks = runBlocking { LocalFiles(library).scan(music.toString()) }.associateBy { it.metadata.title }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            fileEdits = LocalFileEdits(store, library, directory.resolve("edits").toString()), preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("Select song").performClick()
                onNodeWithTag("track-row:${tracks.getValue("Long Way Home").id}").performClick()
                onNodeWithText("2 songs selected").assertIsDisplayed()
                onNodeWithContentDescription("Selection actions").performClick()
                onNodeWithText("Edit tags").performClick()
                onNodeWithText("Change album").performClick()
                onNode(hasText("Album") and hasSetTextAction()).performTextReplacement("Shared album")
                onNodeWithText("Change year").performScrollTo().performClick()
                onNode(hasText("Year") and hasSetTextAction()).performScrollTo().performTextReplacement("2026")
                onNodeWithText("Save changes").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.count { it.applied && !it.undone } == 2 }
            }
            val albums = runBlocking { library.tracks() }.associate { it.metadata.title to it.metadata.album }
            assertEquals(mapOf("Northern Light" to "Shared album", "Long Way Home" to "Shared album", "Night Walk" to "Fixture Album"), albums)
            val years = runBlocking { library.tracks() }.associate { it.metadata.title to it.metadata.year }
            assertEquals(mapOf("Northern Light" to 2026, "Long Way Home" to 2026, "Night Walk" to 2024), years)
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anInterruptedEditIsOfferedForReviewAndRestorationWhenTheAppReopens() {
        val directory = Files.createTempDirectory("file-recovery-ui")
        var store = openMusicStore(directory.resolve("library.db").toString())
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        val initialLibrary = MusicLibrary(store)
        class ProcessExit : Error()
        val interruptedProvider = object : FileEditAccess by FileSystemEditAccess {
            override suspend fun replace(location: String, staged: File, originalHash: String?) {
                Files.write(Path.of(location), staged.inputStream().use { it.readNBytes(128) })
                throw ProcessExit()
            }
        }
        runBlocking {
            val track = LocalFiles(initialLibrary).scan(audio.toString()).single()
            val edits = LocalFileEdits(store, initialLibrary, directory.resolve("edits").toString(), files = interruptedProvider)
            assertFailsWith<ProcessExit> { edits.editTags(track.id, track.metadata.copy(title = "Interrupted edit")) }
        }
        store.close()
        store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), fileEdits = edits)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 5_000) { !controller.state.value.loading && controller.state.value.fileEdits.any { it.needsRecovery } }
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Files & recovery").performClick()
                onNodeWithText("Review interrupted edit").performClick()
                onNodeWithText("Restore original file").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.fileEdits.none { it.needsRecovery } }
                onNodeWithText("Review interrupted edit").assertDoesNotExist()
            }
            assertEquals(-1L, Files.mismatch(original, audio))
            assertEquals("Northern Light", runBlocking { library.tracks().single() }.metadata.title)
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun tagsCanBeSavedToTheMusicFileAndUndoneFromTheTrackMenu() {
        val directory = Files.createTempDirectory("tag-editor-ui")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
            "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
        val audio = directory.resolve("song.flac")
        Files.copy(original, audio)
        val track = runBlocking { LocalFiles(library).scan(audio.toString()).single() }
        val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), fileEdits = edits, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { !controller.state.value.loading }
                onNodeWithContentDescription("Track options: Northern Light").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Edit tags").performClick()
                onNode(hasText("Title") and hasSetTextAction()).performTextReplacement("An edited song")
                onNode(hasText("Artist") and hasSetTextAction()).performTextReplacement("Edited artist")
                onNodeWithText("Save changes").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.tracks.single().metadata.title == "An edited song" }
                onNodeWithContentDescription("Track options: An edited song").performClick()
                onNodeWithText("File actions").performClick()
                onNodeWithText("Undo file edit").performClick()
                waitUntil(timeoutMillis = 5_000) { controller.state.value.tracks.single().metadata.title == "Northern Light" }
            }
            assertEquals(-1L, Files.mismatch(original, audio))
            assertEquals(track.id, runBlocking { library.tracks().single() }.id)
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
