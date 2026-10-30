package me.d3sox.nevumi.playback

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.artwork.ArtworkLibrary
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class TrackFeedbackPerformanceTest {
    @Test
    fun favoriteRatingAndPlaylistCreationStayResponsiveInALargeLocalLibrary() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-track-feedback")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val library = MusicLibrary(store)
        val tracks = store.transaction {
            (0 until 3_000).map { index -> library.importTrack(TrackMetadata("Song $index"),
                TrackSource("local:$index", SourceKind.LOCAL, "/music/$index.flac")) }
        }
        val artwork = directory.resolve("artwork")
        Files.createDirectories(artwork)
        val image = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aAVkAAAAASUVORK5CYII=")
        val hash = "a".repeat(64)
        tracks.forEach { track ->
            val key = track.id.encodeUtf8().sha256().hex()
            Files.writeString(artwork.resolve("$key.current"), hash)
            Files.write(artwork.resolve("$key-$hash.image"), image)
        }
        val controller = MusicController(library, PlaybackQueue(store), MpvPlaybackEngine("null"),
            artwork = ArtworkLibrary(artwork.toString()), playlists = Playlists(store))
        try {
            withTimeout(15_000) { while (!controller.state.value.started) delay(10) }
            val id = tracks.first().id
            val favoriteStart = TimeSource.Monotonic.markNow()
            controller.setFavorite(id, true).join()
            val favoriteMs = favoriteStart.elapsedNow().inWholeMilliseconds
            assertTrue(controller.state.value.tracks.first { it.id == id }.favorite)
            val ratingStart = TimeSource.Monotonic.markNow()
            controller.setRating(id, 4).join()
            val ratingMs = ratingStart.elapsedNow().inWholeMilliseconds
            assertEquals(4, controller.state.value.tracks.first { it.id == id }.rating)
            println("Track feedback, 3000 local songs: favorite=${favoriteMs}ms, rating=${ratingMs}ms")
            assertTrue(favoriteMs < 100 && ratingMs < 100,
                "Favorite/rating must update within 100ms: favorite=${favoriteMs}ms, rating=${ratingMs}ms")
            val playlistStart = TimeSource.Monotonic.markNow()
            controller.createPlaylist("A playlist", listOf(id)).join()
            val playlistMs = playlistStart.elapsedNow().inWholeMilliseconds
            assertEquals("A playlist", controller.state.value.playlists.single().name)
            println("Playlist creation, 3000 local songs: ${playlistMs}ms")
            assertTrue(playlistMs < 100, "New playlists must appear within 100ms: ${playlistMs}ms")
            controller.setFavorite(id, false).join()
            controller.setRating(id, 0).join()
            assertEquals(false, library.findTrack(id)!!.favorite)
            assertEquals(0, library.findTrack(id)!!.rating)
        } finally {
            controller.closeAndJoin(); store.close(); directory.toFile().deleteRecursively()
        }
    }
}
