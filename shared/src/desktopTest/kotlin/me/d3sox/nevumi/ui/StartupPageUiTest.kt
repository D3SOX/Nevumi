package me.d3sox.nevumi.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.library.LocalFileEdits
import me.d3sox.nevumi.library.MusicFileEdits
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class StartupPageUiTest {
    @Test
    fun albumsWithTheSameNameKeepSeparateSongListPositions() {
        val directory = Files.createTempDirectory("album-scroll-identities")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        val library = MusicLibrary(store)
        runBlocking {
            for (artist in listOf("Artist A", "Artist B")) repeat(36) { index ->
                library.importTrack(TrackMetadata("$artist ${index.toString().padStart(2, '0')}", artist, "Greatest hits"),
                    TrackSource("$artist:$index", SourceKind.LOCAL, "/music/$artist/$index.flac"))
            }
        }
        fun controller() = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        var player = controller()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitForLibrary(player)
                openLibraryPage("Albums")
                fun open(artist: String) { onNode(hasText(artist, substring = true) and hasClickAction()).performClick() }
                fun scroll(index: Int) { onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(index) }
                open("Artist A"); scroll(24)
                onNodeWithText("Artist A 24").assertIsDisplayed()
                onNode(hasText("Artist A 24") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
                open("Artist B"); scroll(4)
                onNodeWithText("Artist B 04").assertIsDisplayed()
                onNode(hasText("Artist B 04") and hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
            }
            runBlocking { player.closeAndJoin() }; store.close()
            store = openMusicStore(path); player = controller()
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitForLibrary(player)
                onNodeWithText("Albums").assertIsSelected()
                onNode(hasText("Artist A", substring = true) and hasClickAction()).performClick()
                onNodeWithText("Artist A 24").assertIsDisplayed()
                onNodeWithText("Artist A 04").assertDoesNotExist()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun eachLibraryPageRestoresItsScrollPositionAfterRestart() {
        val directory = Files.createTempDirectory("library-scroll-restart")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        runBlocking {
            val library = MusicLibrary(store)
            repeat(40) { i ->
                val number = i.toString().padStart(2, '0')
                library.importTrack(TrackMetadata("Song $number", artist = "Artist $number", album = "Album $number"),
                    TrackSource("local:$number", SourceKind.LOCAL, "/music/$number.flac"))
            }
        }
        fun controller() = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        var player = controller()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitForLibrary(player)
                onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(24)
                onNodeWithText("Song 24").assertIsDisplayed()
                onNodeWithContentDescription("Search").performClick()
                onNodeWithContentDescription("Search your library").performTextInput("Song 00")
                onNode(hasText("Song 00") and !hasSetTextAction() and hasAnyAncestor(hasTestTag("library-search-page"))).assertIsDisplayed()
                onNodeWithContentDescription("Close search").performClick()
                onNodeWithText("Song 24").assertIsDisplayed()
                openLibraryPage("Albums")
                onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(16)
                onNodeWithText("Album 16").assertIsDisplayed()
                waitForIdle()
            }
            runBlocking { player.closeAndJoin() }; store.close()
            store = openMusicStore(path); player = controller()
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitForLibrary(player)
                onNodeWithText("Albums").assertIsSelected()
                onNodeWithText("Album 16").assertIsDisplayed()
                onNodeWithText("Album 00").assertDoesNotExist()
                openLibraryPage("Songs")
                onNodeWithText("Song 24").assertIsDisplayed()
                onNodeWithText("Song 00").assertDoesNotExist()
            }
        } finally {
            runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachedLibraryIsUsableBeforeFileHistoryFinishesLoading() {
        val directory = Files.createTempDirectory("cached-startup-before-history")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        runBlocking {
            library.importTrack(TrackMetadata("Cached song", artist = "Cached artist"),
                TrackSource("cached", SourceKind.LOCAL, "/music/cached.flac"))
            preferences.update { it.copy(lastLibraryTab = LibraryTab.ARTISTS) }
        }
        val historyStarted = CompletableDeferred<Unit>()
        val releaseHistory = CompletableDeferred<Unit>()
        val files = LocalFileEdits(store, library, directory.resolve("backups").toString())
        val slowFiles = object : MusicFileEdits by files {
            override suspend fun history(): List<me.d3sox.nevumi.library.LocalFileEdit> {
                historyStarted.complete(Unit)
                releaseHistory.await()
                return files.history()
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, fileEdits = slowFiles)
        try {
            runBlocking { withTimeout(3_000) { historyStarted.await() } }
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                onNodeWithText("Artists").assertIsSelected()
                onNodeWithText("Cached artist").assertIsDisplayed()
                assertFalse(player.state.value.loading)
            }
        } finally {
            releaseHistory.complete(Unit)
            runBlocking { player.closeAndJoin() }
            store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun cachedLastPageIsVisibleWhileFileRecoveryIsStillPending() {
        val directory = Files.createTempDirectory("cached-startup-before-recovery")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        runBlocking {
            MusicLibrary(store).importTrack(TrackMetadata("Cached song", artist = "Cached artist", album = "Cached album"),
                TrackSource("cached", SourceKind.LOCAL, "/music/cached.flac"))
            MusicPreferences(store).update { it.copy(lastLibraryTab = LibraryTab.ARTISTS) }
        }
        store.close(); store = openMusicStore(path)
        val library = MusicLibrary(store)
        val recoveryStarted = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val edits = LocalFileEdits(store, library, directory.resolve("backups").toString())
        val slowFiles = object : MusicFileEdits by edits {
            override suspend fun recover() {
                recoveryStarted.complete(Unit)
                releaseRecovery.await()
                edits.recover()
            }
        }
        val player = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            preferences = MusicPreferences(store), fileEdits = slowFiles)
        try {
            runBlocking { withTimeout(3_000) { recoveryStarted.await() } }
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                onNodeWithText("Artists").assertIsSelected()
                onNodeWithText("Cached artist").assertIsDisplayed()
                onNodeWithText("Songs").assertIsNotSelected()
                assertFalse(player.state.value.loading, "Cached library must not wait for startup recovery")
                openLibraryPage("Albums")
                waitUntil(timeoutMillis = 1_000) { player.state.value.preferences.lastLibraryTab == LibraryTab.ALBUMS }
                kotlin.test.assertEquals(LibraryTab.ALBUMS, runBlocking { MusicPreferences(store).load().lastLibraryTab })
                releaseRecovery.complete(Unit)
                waitForIdle()
                onNodeWithText("Albums").assertIsSelected()
            }
        } finally {
            releaseRecovery.complete(Unit)
            runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun reopeningTheAppReturnsToTheLastLibraryPageByDefault() {
        val directory = Files.createTempDirectory("startup-page")
        val path = directory.resolve("music.db").toString()
        var store = openMusicStore(path)
        fun controller() = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store))
        var player = controller()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                openLibraryPage("Albums")
                onNodeWithText("Albums").assertIsSelected()
                waitUntil(timeoutMillis = 3_000) { player.state.value.preferences.lastLibraryTab == me.d3sox.nevumi.library.LibraryTab.ALBUMS }
                runBlocking { player.reload().join() }
            }
            runBlocking { player.closeAndJoin() }; store.close()
            store = openMusicStore(path); player = controller()
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithText("Albums").assertIsSelected()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }
    @Test
    fun aChosenStartupPageOverridesTheLastPageOnlyWhenOpeningTheApp() {
        val directory = Files.createTempDirectory("chosen-startup-page")
        val store = openMusicStore(directory.resolve("music.db").toString())
        val preferences = MusicPreferences(store)
        fun controller() = MusicController(MusicLibrary(store), PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences)
        var player = controller()
        try {
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                openLibraryPage("Artists")
                onNodeWithContentDescription("Settings").performClick()
                onNodeWithText("Library & gestures").performClick()
                onNodeWithContentDescription("On launch").performClick()
                onNodeWithText("Albums").performClick()
                waitUntil(timeoutMillis = 3_000) { player.state.value.preferences.startupTab == me.d3sox.nevumi.library.LibraryTab.ALBUMS }
                onNodeWithContentDescription("Back").performClick()
                onNodeWithContentDescription("Back").performClick()
                onNodeWithText("Artists").assertIsSelected()
            }
            runBlocking { player.closeAndJoin() }
            player = controller()
            runComposeUiTest {
                setContent { MusicPlayerApp(player) }
                waitUntil(timeoutMillis = 3_000) { !player.state.value.loading }
                onNodeWithText("Albums").assertIsSelected()
            }
        } finally { runBlocking { player.closeAndJoin() }; store.close(); directory.toFile().deleteRecursively() }
    }

}
