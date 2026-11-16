package me.d3sox.nevumi.playlists

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

class PlaylistsTest {
    @Test
    fun aFollowedYouTubePlaylistTakesItsSongsAndNameOnlyFromYouTubeMusic() = runBlocking {
        val directory = Files.createTempDirectory("followed-playlist")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            var now = 1_000L
            val playlists = Playlists(store, clock = { now })
            val link = me.d3sox.nevumi.youtube.YouTubeLink("VLPLmix")
            val followed = playlists.follow(link, "Mix", "Fresh songs", listOf("first", "second"))
            assertEquals(link, followed.youTube)
            assertFailsWith<IllegalArgumentException> { playlists.append(followed.id, listOf("third")) }
            assertFailsWith<IllegalArgumentException> { playlists.moveTrack(followed.id, 0, 1) }
            assertFailsWith<IllegalArgumentException> { playlists.rename(followed.id, "Mine") }
            assertFailsWith<IllegalArgumentException> { playlists.edit(followed.id, "Mine", "") }
            assertFailsWith<IllegalArgumentException> { playlists.updateMembership(listOf("third"), mapOf(followed.id to true)) }
            // Following again brings the same playlist up to date; nothing new leaves it as it was.
            now = 2_000
            assertEquals(followed, playlists.follow(link, "Mix", "Fresh songs", listOf("first", "second")))
            val updated = playlists.follow(link, "Mix 2", "", listOf("second", "third"))
            assertEquals(followed.id, updated.id)
            assertEquals(listOf("second", "third"), playlists.get(followed.id)!!.trackIds)
            assertEquals("Mix 2", updated.name)
            assertEquals(2_000, updated.updatedAt)
            assertEquals(1, playlists.all().size)
            playlists.delete(followed.id)
            assertEquals(null, playlists.followed(link))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun addingSeveralSongsKeepsTheirOrderAndRepeatedOccurrences() = runBlocking {
        val directory = Files.createTempDirectory("batch-playlist")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val playlists = Playlists(store)
            val playlist = playlists.create("Evening", listOf("first"))
            playlists.append(playlist.id, listOf("second", "third", "second"))
            assertEquals(listOf("first", "second", "third", "second"), playlists.get(playlist.id)!!.trackIds)
            val smart = playlists.createSmart("Smart", LibraryQuery())
            assertFailsWith<IllegalArgumentException> { playlists.append(smart.id, listOf("first", "second")) }
            Unit
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun smartPlaylistSongsAreChangedThroughRulesRatherThanManualEdits() = runBlocking {
        val directory = Files.createTempDirectory("smart-playlist-rules")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val song = library.importTrack(TrackMetadata("Northern Light"), TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            val playlists = Playlists(store)
            val smart = playlists.createSmart("All songs", LibraryQuery())
            assertFailsWith<IllegalArgumentException> { playlists.append(smart.id, song.id) }
            assertFailsWith<IllegalArgumentException> { playlists.replaceTracks(smart.id, emptyList()) }
            assertFailsWith<IllegalArgumentException> { playlists.moveTrack(smart.id, 0, 0) }
            assertEquals(listOf(song.id), playlists.get(smart.id)!!.trackIds)
            playlists.updateRule(smart.id, LibraryQuery(favoritesOnly = true))
            assertEquals(emptyList(), playlists.get(smart.id)!!.trackIds)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSmartPlaylistFindsDeviceOnlySongsThatAreNotInAnyPlaylist() = runBlocking {
        val directory = Files.createTempDirectory("smart-playlist-unsorted")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val unsorted = library.importTrack(TrackMetadata("Northern Light"), TrackSource("local:unsorted", SourceKind.LOCAL, "/music/unsorted.flac"))
            val sorted = library.importTrack(TrackMetadata("Evening"), TrackSource("local:sorted", SourceKind.LOCAL, "/music/sorted.flac"))
            val onServer = library.importTrack(TrackMetadata("Night Walk"), TrackSource("local:shared", SourceKind.LOCAL, "/music/shared.flac"))
            library.importTrack(onServer.metadata, TrackSource("server:fixture:shared", SourceKind.SERVER, "shared"), linkTo = onServer.id)
            // A refresh keeps the server copy of a song deleted from the server, but marks it unavailable.
            val deleted = library.importTrack(TrackMetadata("Old Demo"), TrackSource("local:deleted", SourceKind.LOCAL, "/music/deleted.flac"))
            library.importTrack(deleted.metadata, TrackSource("server:fixture:deleted", SourceKind.SERVER, "deleted", available = false), linkTo = deleted.id)
            val playlists = Playlists(store)
            val regular = playlists.create("Evening", listOf(sorted.id, deleted.id))
            val rule = LibraryQuery(notOnServer = true, notInPlaylist = true)
            val smart = playlists.createSmart("Unsorted", rule)
            assertEquals(listOf(unsorted.id), smart.trackIds)
            assertEquals(listOf(unsorted.id), library.tracks(rule).map { it.id })
            assertEquals(setOf(sorted.id, unsorted.id, deleted.id), playlists.createSmart("Device only", LibraryQuery(notOnServer = true)).trackIds.toSet())
            // Songs that only appear in smart playlists still count as unsorted.
            assertEquals(listOf(unsorted.id), playlists.get(smart.id)!!.trackIds)
            val update = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(3_000) { playlists.observe().first { it.single { p -> p.id == smart.id }.trackIds.isEmpty() } }
            }
            playlists.append(regular.id, unsorted.id)
            assertEquals(emptyList(), update.await().single { it.id == smart.id }.trackIds)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun observingASmartPlaylistShowsItsCurrentSongsAndUpdatesWhenAFavoriteChanges() = runBlocking {
        val directory = Files.createTempDirectory("live-smart-playlist")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Northern Light"), TrackSource("local:song", SourceKind.LOCAL, "/music/song.flac"))
            library.setFavorite(track.id, true)
            val playlists = Playlists(store)
            val smart = playlists.createSmart("Favorites", LibraryQuery(favoritesOnly = true))
            val visible = withTimeout(3_000) { playlists.observe().first { it.single { p -> p.id == smart.id }.trackIds == listOf(track.id) } }
            assertEquals(listOf(track.id), visible.single().trackIds)
            val update = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(3_000) { playlists.observe().first { it.single { p -> p.id == smart.id }.trackIds.isEmpty() } }
            }
            library.setFavorite(track.id, false)
            assertEquals(emptyList(), update.await().single().trackIds)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSmartPlaylistTracksFavoriteGenreChangesAndKeepsItsRulesAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("smart-playlist")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        try {
            val library = MusicLibrary(store)
            val ambient = library.importTrack(TrackMetadata("Northern Light", genres = listOf("Ambient")),
                TrackSource("local:ambient", SourceKind.LOCAL, "/music/ambient.flac"))
            val electronic = library.importTrack(TrackMetadata("Night Walk", genres = listOf("Electronic")),
                TrackSource("server:electronic", SourceKind.SERVER, "electronic"))
            library.setFavorite(ambient.id, true)
            library.setFavorite(electronic.id, true)
            val playlists = Playlists(store)
            val smart = playlists.createSmart("Favorite ambient", LibraryQuery(genre = "Ambient", favoritesOnly = true))
            assertEquals(listOf(ambient.id), smart.trackIds)
            library.setFavorite(ambient.id, false)
            assertEquals(emptyList(), playlists.get(smart.id)!!.trackIds)
            library.setFavorite(ambient.id, true)
            store.close(); store = openMusicStore(database)
            val restored = Playlists(store).get(smart.id)!!
            assertEquals(listOf(ambient.id), restored.trackIds)
            assertEquals("Favorite ambient", restored.name)
            assertEquals(LibraryQuery(genre = "Ambient", favoritesOnly = true), restored.rule)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aMixedPlaylistKeepsItsOrderAndRepeatedSongsAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("playlist-test")
        val path = directory.resolve("library.db").toString()
        try {
            val store = openMusicStore(path)
            val library = MusicLibrary(store)
            val local = library.importTrack(TrackMetadata("Northern Light"),
                TrackSource("local-flac", SourceKind.LOCAL, "/music/northern.flac"))
            val server = library.importTrack(TrackMetadata("Night Walk"),
                TrackSource("server-wav", SourceKind.SERVER, "opaque-id"))
            val created = Playlists(store).create("Evening", listOf(server.id, local.id, server.id))
            store.close()
            val restored = openMusicStore(path)
            try {
                val playlist = Playlists(restored).get(created.id)
                assertEquals("Evening" to listOf(server.id, local.id, server.id),
                    playlist?.name to playlist?.trackIds)
            } finally { restored.close() }
        } finally { directory.toFile().deleteRecursively() }
    }
}
