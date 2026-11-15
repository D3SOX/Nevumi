package me.d3sox.nevumi.youtube

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.playlists.Playlists
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
import kotlin.test.assertNull

class YouTubeControllerTest {
    private object Source : YouTubeSource {
        override suspend fun search(query: String, kind: YouTubeSearchKind, continuation: YouTubeContinuation?) = YouTubePage(emptyList(), null)
        override suspend fun browse(link: YouTubeLink) = YouTubeBrowsePage("")
        override suspend fun page(videoId: String, kind: YouTubeCollectionKind): YouTubeCollection? = null
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

    @Test fun startupDeletesYouTubeSongsThatNothingRefersToWithTheirCovers() = runBlocking {
        val directory = Files.createTempDirectory("youtube-cleanup")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val artwork = ArtworkLibrary(directory.resolve("artwork").toString())
        suspend fun video(id: String) = library.importTrack(TrackMetadata("Video $id"), TrackSource("youtube:$id", SourceKind.YOUTUBE, id))
        val cover = java.io.ByteArrayOutputStream().also {
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", it)
        }.toByteArray()
        // Queueing a YouTube list kept a song for every video; this one left the queue again.
        val unused = video("aaaaaaaaaaa").also { artwork.save(it.id, cover) }
        val playlisted = video("bbbbbbbbbbb").also { artwork.save(it.id, cover) }
        val favorite = video("ccccccccccc")
        val local = library.importTrack(TrackMetadata("Local"), TrackSource("local:/music/local.flac", SourceKind.LOCAL, "/music/local.flac"))
        Playlists(store).create("Mix", listOf(playlisted.id))
        library.setFavorite(favorite.id, true)
        // Any record that names a song keeps it, also of a kind the cleanup does not know.
        val named = video("ddddddddddd")
        store.database.records().put("elsewhere:1", "elsewhere", """{"trackId":"${named.id}"}""")
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = MusicPreferences(store), artwork = artwork)
        try {
            withTimeout(5_000) { controller.state.first { it.started } }
            assertNull(library.trackForSource("youtube:aaaaaaaaaaa"))
            assertNull(artwork.path(unused.id))
            assertEquals(setOf(playlisted.id, favorite.id, named.id, local.id), library.tracks().map { it.id }.toSet())
            assertEquals(true, artwork.path(playlisted.id) != null)
            // Later songs wait for the next weekly cleanup.
            val later = video("eeeeeeeeeee")
            assertEquals(emptySet(), library.removeUnusedYouTubeTracks())
            assertEquals(setOf(later.id), library.removeUnusedYouTubeTracks(System.currentTimeMillis() + 8 * 24 * 3_600_000L))
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
