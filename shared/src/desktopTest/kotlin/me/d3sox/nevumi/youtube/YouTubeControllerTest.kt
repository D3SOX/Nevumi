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
        override suspend fun audio(videoId: String, fresh: Boolean) = YouTubeAudio("https://media.test/$videoId", "audio/mp4", 1, 128)
        override fun playbackLocation(videoId: String) = "http://127.0.0.1:9/$videoId"
        override suspend fun artwork(url: String) = ByteArray(0)
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
