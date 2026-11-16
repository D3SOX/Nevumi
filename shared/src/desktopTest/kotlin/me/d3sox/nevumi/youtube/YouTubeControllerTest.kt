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

    /** A playlist page whose songs and name are whatever the test last set. */
    private class PlaylistSource(var name: String, var tracks: List<YouTubeTrack>) : YouTubeSource by Source {
        var browsed = 0
        override suspend fun browse(link: YouTubeLink): YouTubeBrowsePage { browsed++
            return YouTubeBrowsePage(name, description = "About", shelves = listOf(YouTubeShelf("", tracks, YouTubeShelfLayout.ROWS)))
        }
    }

    @Test fun aSavedYouTubeMusicPlaylistStreamsItsSongsAndCatchesUpWhenOpened() = runBlocking {
        val directory = Files.createTempDirectory("youtube-follow")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        preferences.update { it.copy(youTube = true) }
        val playlists = Playlists(store, library = library)
        val source = PlaylistSource("Road Mix", listOf(YouTubeTrack("aaaaaaaaaaa", "Song A", "Artist", song = true),
            YouTubeTrack("bbbbbbbbbbb", "Song B", "Artist", song = true)))
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, youTube = source,
            playlists = playlists)
        try {
            withTimeout(5_000) { controller.state.first { it.libraryReady && it.preferences.youTube } }
            val link = YouTubeLink("VLPLroad")
            controller.followYouTubePlaylist(YouTubeCollection(link, YouTubeCollectionKind.PLAYLIST, "Road Mix")).join()
            val saved = withTimeout(5_000) { controller.state.first { state -> state.playlists.any { it.youTube == link } } }.playlists.single()
            assertEquals("Road Mix", saved.name)
            assertEquals("About", saved.description)
            assertEquals(listOf("youtube:aaaaaaaaaaa", "youtube:bbbbbbbbbbb"), saved.trackIds.map { library.findTrack(it)!!.sources.single().id })
            // Just saved, so opening it right away does not load it again.
            controller.refreshYouTubePlaylist(saved.id).join()
            assertEquals(1, source.browsed)
            // The songs stay with the playlist through the weekly cleanup of unused YouTube songs.
            assertEquals(emptySet(), library.removeUnusedYouTubeTracks(System.currentTimeMillis() + 8 * 24 * 3_600_000L))

            // The playlist changed on YouTube Music since this device last saw it.
            source.name = "Road Mix 2"
            source.tracks = listOf(YouTubeTrack("bbbbbbbbbbb", "Song B", "Artist", song = true), YouTubeTrack("ccccccccccc", "Song C", "Artist", song = true))
            val other = playlists.follow(YouTubeLink("VLPLother"), "Other", "", emptyList())
            controller.refreshYouTubePlaylist(other.id).join()
            assertEquals(listOf("youtube:bbbbbbbbbbb", "youtube:ccccccccccc"),
                playlists.get(other.id)!!.trackIds.map { library.findTrack(it)!!.sources.single().id })
            assertEquals("Road Mix 2", playlists.get(other.id)!!.name)

            controller.unfollowYouTubePlaylist(saved.id).join()
            assertNull(playlists.followed(link))
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun aPlaylistRemovedWhileItsUpdateLoadsStaysRemoved() = runBlocking {
        val directory = Files.createTempDirectory("youtube-follow-removed")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val preferences = MusicPreferences(store)
        preferences.update { it.copy(youTube = true) }
        val playlists = Playlists(store, library = library)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val source = object : YouTubeSource by PlaylistSource("Mix", listOf(YouTubeTrack("aaaaaaaaaaa", "Song A", "Artist", song = true))) {
            var browsed = 0
            override suspend fun browse(link: YouTubeLink): YouTubeBrowsePage { browsed++; gate.await()
                return YouTubeBrowsePage("Mix", shelves = listOf(YouTubeShelf("", listOf(YouTubeTrack("aaaaaaaaaaa", "Song A", "Artist", song = true)),
                    YouTubeShelfLayout.ROWS)))
            }
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"), preferences = preferences, youTube = source,
            playlists = playlists)
        try {
            withTimeout(5_000) { controller.state.first { it.libraryReady && it.preferences.youTube } }
            val followed = playlists.follow(YouTubeLink("VLPLmix"), "Mix", "", emptyList())
            val refresh = controller.refreshYouTubePlaylist(followed.id)
            withTimeout(5_000) { while (source.browsed == 0) kotlinx.coroutines.yield() }
            // Opening it again while it loads starts no second request.
            controller.refreshYouTubePlaylist(followed.id).join()
            controller.unfollowYouTubePlaylist(followed.id).join()
            gate.complete(Unit)
            refresh.join()
            assertEquals(1, source.browsed)
            assertEquals(emptyList(), playlists.all())
        } finally { controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
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
        // Imported two days ago, so the cleanup no longer waits for something to refer to them.
        suspend fun video(id: String) = library.importTrack(TrackMetadata("Video $id"), TrackSource("youtube:$id", SourceKind.YOUTUBE, id))
            .copy(addedAt = System.currentTimeMillis() - 2 * 24 * 3_600_000L).also { store.put("track:${it.id}", "tracks", it) }
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

    @Test fun theCleanupKeepsYouTubeSongsImportedMomentsAgoThatNothingRefersToYet() = runBlocking {
        val directory = Files.createTempDirectory("youtube-cleanup-race")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        try {
            // A play or a saved playlist imports its songs first and refers to them a moment later, which the cleanup may run between.
            val fresh = library.importTrack(TrackMetadata("Video"), TrackSource("youtube:aaaaaaaaaaa", SourceKind.YOUTUBE, "aaaaaaaaaaa"))
            val now = System.currentTimeMillis()
            assertEquals(emptySet(), library.removeUnusedYouTubeTracks(now))
            assertEquals(fresh.id, library.trackForSource("youtube:aaaaaaaaaaa")?.id)
            // A cleanup that read the time just before the import still keeps it.
            store.put("youtube-cleanup", "maintenance", 0L)
            assertEquals(emptySet(), library.removeUnusedYouTubeTracks(fresh.addedAt - 1))
            // Still unused a week later, it goes.
            assertEquals(setOf(fresh.id), library.removeUnusedYouTubeTracks(now + 8 * 24 * 3_600_000L))
        } finally { store.close(); directory.toFile().deleteRecursively() }
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
