package me.d3sox.nevumi.ui

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlaylistDetailsUiTest {
    @Test fun automaticDownloadsHaveADistinctCheckedBackgroundInLightAndDarkThemes() {
        val directory = Files.createTempDirectory("playlist-download-toggle")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val playlists = Playlists(store)
        runBlocking { playlists.create("Evening", emptyList()) }
        val controller = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"),
            playlists = playlists, preferences = me.d3sox.nevumi.settings.MusicPreferences(store))
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isNotEmpty() }
                openLibraryPage("Playlists"); onNodeWithText("Evening").performClick()
                for (theme in listOf(me.d3sox.nevumi.settings.ThemeMode.LIGHT, me.d3sox.nevumi.settings.ThemeMode.DARK)) {
                    runBlocking { controller.updatePreferences { it.copy(themeMode = theme) }.join() }
                    val toggle = onNodeWithContentDescription("Automatically download playlist")
                    toggle.assertIsOff()
                    fun background(): androidx.compose.ui.graphics.Color {
                        val pixels = toggle.captureToImage().toPixelMap()
                        return pixels[pixels.width / 2, (pixels.height * .15f).toInt()]
                    }
                    val off = background()
                    toggle.performClick()
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().autoDownload }
                    toggle.assertIsOn()
                    mainClock.advanceTimeBy(500)
                    val on = background()
                    val difference = kotlin.math.abs(on.red - off.red) + kotlin.math.abs(on.green - off.green) + kotlin.math.abs(on.blue - off.blue)
                    assertTrue(difference > .4f, "Enabled downloads need a clearly different background in $theme: off=$off on=$on")
                    toggle.performClick()
                    waitUntil(timeoutMillis = 3_000) { !controller.state.value.playlists.single().autoDownload }
                    toggle.assertIsOff()
                }
            }
            kotlin.test.assertFalse(runBlocking { playlists.all().single().autoDownload })
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun playlistOptionsExposeAllQueueActionsAndUseItsSavedSortOrder() {
        val directory = Files.createTempDirectory("playlist-queue-actions")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val audio = java.nio.file.Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val songs = runBlocking { listOf("Zulu", "Alpha", "Bravo").map { title ->
            library.importTrack(TrackMetadata(title, durationMs = 3_000), TrackSource(title, SourceKind.LOCAL, audio))
        } }
        val playlists = Playlists(store)
        val playlist = runBlocking { playlists.create("Evening", listOf(songs[0].id, songs[1].id, songs[0].id, songs[2].id)) }
        val ordered = listOf(songs[0].id, songs[0].id, songs[2].id, songs[1].id)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isNotEmpty() }
                runBlocking { controller.setPlaylistSort(playlist.id, me.d3sox.nevumi.playlists.PlaylistSort.TITLE, true).join() }
                openLibraryPage("Playlists"); onNodeWithText("Evening").performClick()
                for (label in listOf("Play next", "Play after", "Add to queue")) {
                    runBlocking {
                        controller.playTrack(songs[0].id, listOf(songs[0].id)).join(); controller.pause().join()
                        controller.enqueue(listOf(songs[0].id, songs[2].id), me.d3sox.nevumi.queue.QueueAction.APPEND).join()
                        controller.enqueue(songs[1].id, me.d3sox.nevumi.queue.QueueAction.PLAY_NEXT).join()
                    }
                    val before = controller.state.value.queue.upNext.map { it.trackId }
                    onNodeWithContentDescription("Playlist options").performClick()
                    onNodeWithText(label, substring = false).performClick()
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.upNext.size == 7 }
                    val expected = when (label) {
                        "Play next" -> ordered + before
                        "Play after" -> before.take(1) + ordered + before.drop(1)
                        else -> before + ordered
                    }
                    kotlin.test.assertEquals(expected, controller.state.value.queue.upNext.map { it.trackId })
                }
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun removingARepeatedSongFromASortedPlaylistRemovesTheSelectedCustomOrderOccurrence() {
        val directory = Files.createTempDirectory("sorted-playlist-occurrence")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val playlists = Playlists(store)
        val songs = runBlocking { listOf("Bravo", "Alpha", "Charlie").map { title ->
            library.importTrack(TrackMetadata(title), TrackSource(title, SourceKind.LOCAL, "/music/$title.flac"))
        } }
        val custom = listOf(songs[0].id, songs[1].id, songs[2].id, songs[0].id)
        val playlist = runBlocking { playlists.create("Evening", custom) }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = playlists, preferences = MusicPreferences(store).withOptionsButtons())
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isNotEmpty() }
                openLibraryPage("Playlists"); onNodeWithText("Evening").performClick()
                onNodeWithContentDescription("Playlist song order").performClick()
                onNode(hasText("Title") and hasAnyAncestor(isPopup())).performClick()
                onNode(hasScrollAction()).performScrollToIndex(3)
                onAllNodesWithContentDescription("Track options: Bravo")[1].performClick()
                onNodeWithText("Remove from playlist").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().trackIds.size == 3 }
                kotlin.test.assertEquals(custom.dropLast(1), runBlocking { playlists.get(playlist.id)!! }.trackIds)
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun playlistSortingAndShuffleAreSavedAndShuffledPlaybackKeepsEveryOccurrence() {
        val directory = Files.createTempDirectory("playlist-sort-shuffle")
        val database = directory.resolve("music.db").toString()
        var store = openMusicStore(database)
        val audio = java.nio.file.Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac").toString()
        val tracks = runBlocking {
            val library = MusicLibrary(store)
            listOf(Triple("Bravo", "Zed", 1_000L), Triple("Alpha", "Ada", 3_000L), Triple("Charlie", "Bob", 2_000L)).mapIndexed { index, (title, artist, modified) ->
                library.importTrack(TrackMetadata(title, artist = artist, album = listOf("Second", "First", "Third")[index], durationMs = 3_000),
                    TrackSource("local:$title", SourceKind.LOCAL, audio, modifiedAt = modified))
            }
        }
        val custom = listOf(tracks[0].id, tracks[1].id, tracks[2].id, tracks[0].id)
        runBlocking { Playlists(store).create("Evening", custom) }
        fun player() = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), playlists = Playlists(store))
        var controller = player()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isNotEmpty() }
                openLibraryPage("Playlists"); onNodeWithText("Evening").performClick()
                val orders = listOf(
                    "Title" to listOf(tracks[1].id, tracks[0].id, tracks[0].id, tracks[2].id),
                    "Artist" to listOf(tracks[1].id, tracks[2].id, tracks[0].id, tracks[0].id),
                    "Album" to listOf(tracks[1].id, tracks[0].id, tracks[0].id, tracks[2].id),
                    "Recently added" to listOf(tracks[1].id, tracks[2].id, tracks[0].id, tracks[0].id),
                    "Custom order" to custom)
                for ((sort, expected) in orders) {
                    onNodeWithContentDescription("Playlist song order").performScrollTo().performClick()
                    onNode(hasText(sort) and hasAnyAncestor(isPopup())).performClick()
                    onNodeWithContentDescription("Play playlist").performClick()
                    waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.map { it.trackId } == expected }
                    runBlocking { controller.pause().join() }
                }
                onNodeWithContentDescription("Playlist song order").performClick()
                onNodeWithContentDescription("Playlist sort direction").performClick()
                onNodeWithContentDescription("Play playlist").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.entries.map { it.trackId } == custom.reversed() }
                runBlocking { controller.pause().join() }
                onNodeWithContentDescription("Shuffle playlist").performClick().assertIsSelected()
                onNodeWithContentDescription("Play playlist").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.unshuffledEntryIds != null }
                kotlin.test.assertEquals(custom.sorted(), controller.state.value.queue.entries.map { it.trackId }.sorted())
                runBlocking { controller.pause().join() }
            }
            runBlocking { controller.closeAndJoin() }; store.close()
            store = openMusicStore(database); controller = player()
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isNotEmpty() }
                openLibraryPage("Playlists"); onNodeWithText("Evening").performClick()
                onNodeWithContentDescription("Shuffle playlist").assertIsSelected()
                onNodeWithContentDescription("Playlist song order").assert(SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Custom order · Descending"))
                onNodeWithContentDescription("Play playlist").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.queue.unshuffledEntryIds != null }
                kotlin.test.assertEquals(custom.sorted(), controller.state.value.queue.entries.map { it.trackId }.sorted())
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun playlistArtworkTitleAndDescriptionCanBeEditedAndRestoreAfterRestart() {
        val directory = Files.createTempDirectory("playlist-details")
        val database = directory.resolve("music.db").toString()
        var store = openMusicStore(database)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        runBlocking { Playlists(store).create("Evening", emptyList()) }
        fun player() = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"),
            playlists = Playlists(store), artwork = artwork)
        var controller = player()
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().let { graphics -> graphics.color = java.awt.Color.RED; graphics.fillRect(0, 0, 32, 32); graphics.dispose() }
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, artworkPicker = ArtworkPicker { bytes }) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isNotEmpty() }
                openLibraryPage("Playlists")
                onNodeWithText("Evening").performClick()
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Edit playlist").performClick()
                onNodeWithContentDescription("Playlist title").performTextReplacement("After hours")
                onNodeWithContentDescription("Playlist description").performTextInput("Music for late evenings")
                onNodeWithText("Change cover").performClick()
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.single().name == "After hours" }
                onNodeWithText("Music for late evenings").assertIsDisplayed()
                val pixels = onNodeWithContentDescription("Playlist artwork: After hours").captureToImage().toPixelMap()
                assertTrue(pixels[pixels.width / 2, pixels.height / 2].red > .8f)
            }
            runBlocking { controller.closeAndJoin() }; store.close()
            store = openMusicStore(database); controller = player()
            runComposeUiTest {
                setContent { MusicPlayerApp(controller) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.playlists.isNotEmpty() }
                openLibraryPage("Playlists")
                onNodeWithText("After hours").performClick()
                onNodeWithText("Music for late evenings").assertIsDisplayed()
                val pixels = onNodeWithContentDescription("Playlist artwork: After hours").captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                assertTrue(center.red > .8f && center.green < .2f, "The custom artwork must survive restart")
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun aServerPlaylistCoverReplacesSongArtworkUntilACustomCoverIsChosen() {
        val directory = Files.createTempDirectory("server-playlist-cover")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        fun png(color: java.awt.Color) = ByteArrayOutputStream().also { output ->
            ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().let { graphics -> graphics.color = color; graphics.fillRect(0, 0, 32, 32); graphics.dispose() }
            }, "png", output)
        }.toByteArray()
        val library = MusicLibrary(store)
        val playlist = runBlocking {
            val track = library.importTrack(TrackMetadata("Song"), TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            artwork.save(track.id, png(java.awt.Color.BLUE))
            Playlists(store).create("Evening", listOf(track.id)).also { artwork.save("server-playlist-cover:${it.id}", png(java.awt.Color.GREEN)) }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            playlists = Playlists(store), artwork = artwork)
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(controller, artworkPicker = ArtworkPicker { png(java.awt.Color.RED) }) }
                waitUntil(timeoutMillis = 3_000) { controller.state.value.artwork.containsKey("server-playlist-cover:${playlist.id}") }
                openLibraryPage("Playlists")
                onNodeWithText("Evening").performClick()
                fun center() = onNodeWithContentDescription("Playlist artwork: Evening").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
                assertTrue(center().let { it.green > .8f && it.blue < .2f }, "The server cover must replace song artwork")
                onNodeWithContentDescription("Playlist options").performClick()
                onNodeWithText("Edit playlist").performClick()
                onNodeWithText("Change cover").performClick()
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = 3_000) { controller.state.value.artwork.containsKey("playlist:${playlist.id}") }
                waitForIdle()
                assertTrue(center().let { it.red > .8f && it.green < .2f }, "A custom cover must replace the server cover")
            }
        } finally { runBlocking { controller.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
}
