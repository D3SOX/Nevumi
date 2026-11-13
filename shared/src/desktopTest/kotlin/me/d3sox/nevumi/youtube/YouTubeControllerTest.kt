package me.d3sox.nevumi.youtube

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.playback.MpvPlaybackEngine
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueAction
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class YouTubeControllerTest {
    private object Source : YouTubeSource {
        override suspend fun search(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation?) = YouTubePage(emptyList(), null)
        override suspend fun browse(link: YouTubeLink) = YouTubeBrowsePage("")
        override suspend fun browseMore(continuation: YouTubeContinuation) = YouTubePage(emptyList(), null)
        override suspend fun audio(videoId: String, fresh: Boolean) = YouTubeAudio("https://media.test/$videoId", "audio/mp4", 1, 128)
        override fun playbackLocation(videoId: String) = "http://127.0.0.1:9/$videoId"
        override suspend fun artwork(url: String) = ByteArray(0)
    }

    @Test fun aSlowVideoLookupDoesNotReplaceASongPlayedMeanwhile() = runBlocking {
        val directory = Files.createTempDirectory("youtube-controller")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        preferences.update { it.copy(youTube = true) }
        val chosen = library.importTrack(TrackMetadata("Night Drive", "Artist"), TrackSource("local", SourceKind.LOCAL, "/music/night-drive.flac"))
        val lookup = kotlinx.coroutines.CompletableDeferred<Unit>()
        var held = lookup
        val slow = object : YouTubeSource by Source {
            override suspend fun audio(videoId: String, fresh: Boolean): YouTubeAudio { held.await(); return Source.audio(videoId, fresh) }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, youTube = slow,
            sourceAccessible = { true })
        try {
            withTimeout(5_000) { controller.state.first { it.libraryReady && it.preferences.youTube } }
            val video = controller.playYouTube(YouTubeTrack("bbbbbbbbbbb", "Night Swim", "Other"))
            controller.playTrack(chosen.id, listOf(chosen.id), 0).join()
            lookup.complete(Unit)
            video.join()
            assertEquals(listOf(chosen.id), controller.state.value.queue.entries.map { it.trackId })
            // Clearing the queue during a lookup counts as a choice too.
            val cleared = kotlinx.coroutines.CompletableDeferred<Unit>()
            held = cleared
            val again = controller.playYouTube(YouTubeTrack("bbbbbbbbbbb", "Night Swim", "Other"))
            controller.clearQueue().join()
            val afterClearing = controller.state.value.queue.entries
            cleared.complete(Unit)
            again.join()
            assertEquals(afterClearing, controller.state.value.queue.entries)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun aListThatStartsPlayingAfterLoadingLosesToASongChosenMeanwhile() = runBlocking {
        val directory = Files.createTempDirectory("youtube-controller")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        preferences.update { it.copy(youTube = true) }
        val chosen = library.importTrack(TrackMetadata("Night Drive", "Artist"), TrackSource("local", SourceKind.LOCAL, "/music/night-drive.flac"))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, youTube = Source,
            sourceAccessible = { true })
        try {
            withTimeout(5_000) { controller.state.first { it.libraryReady && it.preferences.youTube } }
            // A playlist tapped first only starts once its remaining pages load, after another song was chosen.
            val tapped = controller.reservePlayRequest()
            controller.playTrack(chosen.id, listOf(chosen.id), 0).join()
            controller.playYouTube(listOf(YouTubeTrack("aaaaaaaaaaa", "Song A", "Other"), YouTubeTrack("bbbbbbbbbbb", "Song B", "Other")), 0, "Playlist",
                request = tapped).join()
            assertEquals(listOf(chosen.id), controller.state.value.queue.entries.map { it.trackId })
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun queueingAVideoAgainKeepsTheTagsOfTheSongItIsLinkedTo() = runBlocking {
        val directory = Files.createTempDirectory("youtube-controller")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        preferences.update { it.copy(youTube = true) }
        // A song linked to a local copy holds the copy's full tags beside its YouTube source.
        val local = library.importTrack(TrackMetadata("Night Swim", "Other", "Summer", year = 2020), TrackSource("local", SourceKind.LOCAL, "/music/night-swim.flac"))
        val linked = library.importTrack(TrackMetadata("Night Swim", "Other", "Summer", year = 2020),
            TrackSource("youtube:bbbbbbbbbbb", SourceKind.YOUTUBE, "bbbbbbbbbbb"), linkTo = local.id)
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, youTube = Source)
        try {
            withTimeout(5_000) { controller.state.first { it.libraryReady && it.preferences.youTube } }
            controller.playYouTube(YouTubeTrack("bbbbbbbbbbb", "Other - Night Swim (Official Video)", "OtherVEVO"), QueueAction.APPEND).join()
            val song = library.findTrack(linked.id)!!
            assertEquals(TrackMetadata("Night Swim", "Other", "Summer", year = 2020), song.metadata)
            assertEquals(linked.id, withTimeout(5_000) { controller.state.first { it.queue.entries.isNotEmpty() } }.queue.entries.single().trackId)
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }
}
