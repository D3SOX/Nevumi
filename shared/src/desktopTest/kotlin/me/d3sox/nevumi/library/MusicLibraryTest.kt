package me.d3sox.nevumi.library

import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MusicLibraryTest {
    @Test
    fun ratingsCanFilterAndSortTheLibraryIncludingUnratedSongsAndCombinedConditions() = runBlocking {
        val directory = Files.createTempDirectory("rating-query")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val songs = listOf("Alpha", "Bravo", "Charlie").map { title ->
                library.importTrack(TrackMetadata(title), TrackSource("local:$title", SourceKind.LOCAL, "/music/$title.flac"))
            }
            library.setRating(songs[0].id, 4); library.setRating(songs[1].id, 5)
            library.setFavorite(songs[0].id, true)
            assertEquals(listOf(songs[1].id, songs[0].id), library.tracks(LibraryQuery(ratingFrom = 4, sort = LibrarySort.RATING)).map { it.id })
            assertEquals(listOf(songs[0].id), library.tracks(LibraryQuery(ratingFrom = 4, favoritesOnly = true)).map { it.id })
            assertEquals(listOf(songs[2].id), library.tracks(LibraryQuery(ratingTo = 0)).map { it.id })
            assertFailsWith<IllegalArgumentException> { LibraryQuery(ratingFrom = 6) }
            assertFailsWith<IllegalArgumentException> { LibraryQuery(ratingFrom = 5, ratingTo = 4) }
            Unit
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun manuallyLinkingCopiesKeepsTheChosenRatingAndUsesTheOtherRatingWhenTheChosenCopyIsUnrated() = runBlocking {
        val directory = Files.createTempDirectory("linked-ratings")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val local = library.importTrack(TrackMetadata("Local song"), TrackSource("local:a", SourceKind.LOCAL, "/music/a.flac"))
            val server = library.importTrack(TrackMetadata("Server song"), TrackSource("server:test:a", SourceKind.SERVER, "a"))
            library.setRating(server.id, 5)
            assertEquals(5, library.linkTracks(local.id, server.id).rating)
            val chosen = library.importTrack(TrackMetadata("Another local song"), TrackSource("local:b", SourceKind.LOCAL, "/music/b.flac"))
            val other = library.importTrack(TrackMetadata("Another server song"), TrackSource("server:test:b", SourceKind.SERVER, "b"))
            library.setRating(chosen.id, 4)
            library.setRating(other.id, 3)
            assertEquals(4, library.linkTracks(chosen.id, other.id).rating)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun metadataFiltersMatchTypedNamesRegardlessOfLetterCase() = runBlocking {
        val directory = Files.createTempDirectory("case-insensitive-library-filters")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val song = library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album",
                genres = listOf("Ambient"), albumArtist = "Various Artists"), TrackSource("local", SourceKind.LOCAL, "/music/song.flac"))
            assertEquals(listOf(song.id), library.tracks(LibraryQuery(artist = "fixture artist", album = "fixture album",
                albumArtist = "various artists", genre = "ambient")).map { it.id })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLibraryQueryCombinesFolderYearDurationAvailabilityAndResultLimit() = runBlocking {
        val directory = Files.createTempDirectory("library-filters")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val metadata = TrackMetadata("Evening", durationMs = 180_000, folder = "/music/evening", year = 2024)
            val expected = library.importTrack(metadata, TrackSource("local:expected", SourceKind.LOCAL, "/music/evening/song.flac"))
            library.importTrack(metadata.copy(title = "Other folder", folder = "/music/morning"), TrackSource("other-folder", SourceKind.LOCAL, "/music/morning/song.flac"))
            library.importTrack(metadata.copy(title = "Old recording", year = 1990), TrackSource("old", SourceKind.LOCAL, "/music/evening/old.flac"))
            library.importTrack(metadata.copy(title = "Short recording", durationMs = 30_000), TrackSource("short", SourceKind.LOCAL, "/music/evening/short.flac"))
            library.importTrack(metadata.copy(title = "Missing recording"), TrackSource("missing", SourceKind.LOCAL, "/music/evening/missing.flac", available = false))
            library.importTrack(metadata.copy(title = "Z second matching"), TrackSource("second", SourceKind.LOCAL, "/music/evening/second.flac"))
            val query = LibraryQuery(folder = "/music/evening", yearFrom = 2020, yearTo = 2025,
                durationFromMs = 60_000, durationToMs = 240_000, availableOnly = true, limit = 1)
            assertEquals(listOf(expected.id), library.tracks(query).map { it.id })
            assertEquals(2, library.tracks(query.copy(limit = null)).size)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun manuallyLinkingCopiesKeepsFavoritesAndRepeatedQueuePlaylistAndSavedQueueEntriesAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("manual-song-link")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        try {
            val library = MusicLibrary(store)
            val local = library.importTrack(TrackMetadata("Northern Light", "Fixture Artist"),
                TrackSource("local:song", SourceKind.LOCAL, "/music/song.flac"))
            val server = library.importTrack(local.metadata, TrackSource("server:fixture:song", SourceKind.SERVER, "song"))
            library.setFavorite(server.id, true)
            val queue = me.d3sox.nevumi.queue.PlaybackQueue(store)
            queue.replace(listOf(server.id, local.id, server.id), startIndex = 2)
            queue.setPosition(1_250)
            val originalQueue = queue.snapshot()
            val saved = queue.save("Evening")
            val playlist = me.d3sox.nevumi.playlists.Playlists(store).create("Mixed", listOf(local.id, server.id, local.id))
            library.linkTracks(local.id, server.id)
            store.close(); store = openMusicStore(database)
            val reopened = MusicLibrary(store)
            val linked = reopened.tracks().single()
            assertEquals(local.id, linked.id)
            assertEquals(true, linked.favorite)
            assertEquals(setOf(SourceKind.LOCAL, SourceKind.SERVER), linked.sources.map { it.kind }.toSet())
            val current = me.d3sox.nevumi.queue.PlaybackQueue(store).snapshot()
            assertEquals(originalQueue.entries.map { it.id }, current.entries.map { it.id })
            assertEquals(List(3) { local.id }, current.entries.map { it.trackId })
            assertEquals(originalQueue.currentEntryId, current.currentEntryId)
            assertEquals(1_250L, current.positionMs)
            assertEquals(List(3) { local.id }, me.d3sox.nevumi.queue.PlaybackQueue(store).saved().single { it.id == saved.id }.snapshot.entries.map { it.trackId })
            assertEquals(List(3) { local.id }, me.d3sox.nevumi.playlists.Playlists(store).get(playlist.id)!!.trackIds)
            assertEquals(local.id, reopened.importTrack(server.metadata, server.sources.single()).id)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun sourceMatchingKeepsDifferentEditionsPositionsAndUncertainRecordingsSeparate() = runBlocking {
        val directory = Files.createTempDirectory("conservative-source-match")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val metadata = TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", durationMs = 180_000,
                trackNumber = 1, discNumber = 1, recordingId = "189002e7-3285-4e2e-92a3-7f6c30d407a2",
                releaseId = "6e1d48f7-717c-416e-af35-5d2454a13af2")
            val variants = listOf(
                metadata.copy(releaseId = "189002e7-3285-4e2e-92a3-7f6c30d407a2"),
                metadata.copy(recordingId = "6e1d48f7-717c-416e-af35-5d2454a13af2"),
                metadata.copy(trackNumber = 2), metadata.copy(discNumber = 2),
                metadata.copy(durationMs = 185_000), metadata.copy(durationMs = 0),
                metadata.copy(recordingId = ""), metadata.copy(releaseId = ""), metadata.copy(trackNumber = 0),
            )
            val library = MusicLibrary(store)
            val original = library.importTrack(metadata, TrackSource("local:original", SourceKind.LOCAL, "/music/original.flac"))
            variants.forEachIndexed { index, variant ->
                val imported = library.importTrack(variant, TrackSource("server:variant-$index", SourceKind.SERVER, "variant-$index"))
                kotlin.test.assertNotEquals(original.id, imported.id)
            }
            assertEquals(10, library.tracks().size)
            assertEquals(1, library.findTrack(original.id)!!.sources.size)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun localAndServerCopiesWithTheSameRecordingReleaseAndPositionShareOneSong() = runBlocking {
        val directory = Files.createTempDirectory("identified-source-match")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val metadata = TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", durationMs = 180_000,
                trackNumber = 1, discNumber = 1, recordingId = "189002e7-3285-4e2e-92a3-7f6c30d407a2",
                releaseId = "6e1d48f7-717c-416e-af35-5d2454a13af2")
            val localSource = TrackSource("local:/music/song.flac", SourceKind.LOCAL, "/music/song.flac")
            val local = library.importTrack(metadata, localSource)
            library.setFavorite(local.id, true)
            val serverSource = TrackSource("server:fixture:song", SourceKind.SERVER, "song")
            val server = library.importTrack(metadata.copy(durationMs = 181_000), serverSource)
            assertEquals(local.id, server.id)
            assertEquals(true, server.favorite)
            assertEquals(setOf(localSource, serverSource), library.tracks().single().sources.toSet())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLocalFileUploadedToTheServerIsFoundByItsSizeTitleArtistAndLength() = runBlocking {
        val directory = Files.createTempDirectory("same-file-copies")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            suspend fun song(kind: SourceKind, title: String, size: Long, durationMs: Long = 180_000, artist: String = "Fixture Artist",
                album: String = "", releaseId: String = "") =
                library.importTrack(TrackMetadata(title, artist, album, durationMs = durationMs, releaseId = releaseId),
                    TrackSource("${kind.name}:$title:$artist:$size:$durationMs", kind, "$title.mp3", indexedSize = size)).id
            val local = song(SourceKind.LOCAL, "Northern Light", 1_000)
            val server = song(SourceKind.SERVER, " northern light", 1_000, 179_000)
            song(SourceKind.LOCAL, "Re-encoded", 2_000); song(SourceKind.SERVER, "Re-encoded", 2_001)
            song(SourceKind.LOCAL, "Edited", 3_000, 180_000); song(SourceKind.SERVER, "Edited", 3_000, 190_000)
            song(SourceKind.LOCAL, "Twice on the server", 4_000)
            song(SourceKind.SERVER, "Twice on the server", 4_000); song(SourceKind.SERVER, "Twice on the server", 4_000, 180_500)
            song(SourceKind.LOCAL, "Unknown size", 0); song(SourceKind.SERVER, "Unknown size", 0)
            song(SourceKind.LOCAL, "Unknown length", 5_000, 0); song(SourceKind.SERVER, "Unknown length", 5_000, 0)
            song(SourceKind.LOCAL, "Intro", 6_000); song(SourceKind.SERVER, "Intro", 6_000, artist = "Another Artist")
            song(SourceKind.LOCAL, "Remaster", 8_000, album = "First Edition")
            song(SourceKind.SERVER, "Remaster", 8_000, album = "Deluxe Edition")
            song(SourceKind.LOCAL, "Reissue", 9_000, releaseId = "6e1d48f7-717c-416e-af35-5d2454a13af2")
            song(SourceKind.SERVER, "Reissue", 9_000, releaseId = "189002e7-3285-4e2e-92a3-7f6c30d407a2")
            song(SourceKind.SERVER, "Deleted file", 7_000)
            library.importTrack(TrackMetadata("Deleted file", "Fixture Artist", durationMs = 180_000),
                TrackSource("local:deleted", SourceKind.LOCAL, "deleted.mp3", available = false, indexedSize = 7_000))
            assertEquals(listOf(server to local), library.sameFileCopies())
            library.linkTracks(server, local)
            assertEquals(emptyList(), library.sameFileCopies())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSongIdentityDoesNotContainItsDeviceLocationAndStaysTheSameAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("private-song-identity")
        val path = directory.resolve("library.db").toString()
        var store = openMusicStore(path)
        try {
            val source = TrackSource("local:/home/listener/private-music/song.flac", SourceKind.LOCAL,
                "/home/listener/private-music/song.flac")
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Northern Light"), source)
            kotlin.test.assertNotEquals(source.id, track.id)
            java.util.UUID.fromString(track.id)
            library.setFavorite(track.id, true)
            store.close(); store = openMusicStore(path)
            val reopened = MusicLibrary(store)
            val refreshed = reopened.importTrack(track.metadata.copy(album = "Evening"), source)
            assertEquals(track.id, refreshed.id)
            assertEquals(true, refreshed.favorite)
            assertEquals(listOf(track.id), reopened.tracks().map { it.id })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun rescanningALinkedSourceKeepsOneTrackAndItsFavorite() = runBlocking {
        val directory = Files.createTempDirectory("linked-source-refresh")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val server = TrackSource("server:fixture:song", SourceKind.SERVER, "song")
            val canonical = library.importTrack(TrackMetadata("Northern Light"), server)
            val local = TrackSource("local:/music/song.flac", SourceKind.LOCAL, "/music/song.flac")
            library.importTrack(canonical.metadata, local, linkTo = canonical.id)
            library.setFavorite(canonical.id, true)
            val refreshed = library.importTrack(canonical.metadata.copy(album = "Edited album"), local)
            assertEquals(canonical.id, refreshed.id)
            assertEquals(1, library.tracks().size)
            assertEquals(true, refreshed.favorite)
            assertEquals(setOf(server, local), refreshed.sources.toSet())
            assertEquals("Edited album", refreshed.metadata.album)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun recentlyAddedOrderIsPreservedWhenExistingMetadataIsRefreshed() = runBlocking {
        val directory = Files.createTempDirectory("recent-library")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val source = TrackSource("first", SourceKind.SERVER, "opaque-first")
            val first = library.importTrack(TrackMetadata("Northern Light"), source)
            kotlinx.coroutines.delay(20)
            val second = library.importTrack(TrackMetadata("Night Walk"), TrackSource("second", SourceKind.SERVER, "opaque-second"))
            library.importTrack(TrackMetadata("Northern Light (edited)"), source)
            assertEquals(listOf(second.id, first.id), library.tracks(LibraryQuery(sort = LibrarySort.RECENT)).map { it.id })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun browsingByAlbumArtistGenreAndFavoritesUsesThePersistedOfflineCatalogue() = runBlocking {
        val directory = Files.createTempDirectory("library-browse")
        val database = directory.resolve("library.db").toString()
        try {
            val store = openMusicStore(database)
            val library = MusicLibrary(store)
            val wanted = library.importTrack(TrackMetadata("Northern Light", "Guest Artist", "Evening",
                genres = listOf("Ambient", "Electronic"), albumArtist = "Fixture Artist"),
                TrackSource("server-wanted", SourceKind.SERVER, "opaque-wanted"))
            library.importTrack(TrackMetadata("Northern Light", "Other Artist", "Evening",
                genres = listOf("Ambient"), albumArtist = "Other Artist"),
                TrackSource("local-other", SourceKind.LOCAL, "/music/live.flac"))
            library.setFavorite(wanted.id, true)
            store.close()
            val reopened = openMusicStore(database)
            try {
                val matches = MusicLibrary(reopened).tracks(LibraryQuery(album = "Evening", albumArtist = "Fixture Artist",
                    genre = "Electronic", favoritesOnly = true, text = "northern"))
                assertEquals(listOf(wanted.id), matches.map { it.id })
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun aMixedLibraryCanBeSearchedWithoutMergingTracksWithTheSameTitle() = runBlocking {
        val directory = Files.createTempDirectory("music-library-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val original = library.importTrack(TrackMetadata("Northern Light", "Original Artist", "Studio"),
                TrackSource("local-original", SourceKind.LOCAL, "/music/original.flac"))
            val live = library.importTrack(TrackMetadata("Northern Light", "Other Artist", "Live"),
                TrackSource("server-live", SourceKind.SERVER, "opaque-live"))
            library.importTrack(TrackMetadata("Night Walk"),
                TrackSource("server-night", SourceKind.SERVER, "opaque-night"))

            assertEquals(setOf(original.id, live.id), library.tracks("northern").map { it.id }.toSet())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun importedMusicIsAvailableAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("music-library-test")
        val path = directory.resolve("library.db").toString()
        try {
            val store = openMusicStore(path)
            val track = MusicLibrary(store).importTrack(
                TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album"),
                TrackSource("local-1", SourceKind.LOCAL, "file:///music/northern-light.flac"),
            )
            store.close()

            val restored = openMusicStore(path)
            try {
                assertEquals("Northern Light", MusicLibrary(restored).findTrack(track.id)?.metadata?.title)
            } finally { restored.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun downloadingAServerTrackPreservesItsLibraryIdentity() = runBlocking {
        val directory = Files.createTempDirectory("music-library-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val original = library.importTrack(TrackMetadata("Northern Light"),
                TrackSource("server-1", SourceKind.SERVER, "opaque-server-id"))
            val downloaded = library.importTrack(TrackMetadata("Northern Light"),
                TrackSource("download-1", SourceKind.DOWNLOAD, "file:///cache/northern-light.flac"),
                linkTo = original.id)

            assertEquals(original.id, downloaded.id)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun refreshingAServerTrackKeepsItsDownloadedVariant() = runBlocking {
        val directory = Files.createTempDirectory("music-library-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val source = TrackSource("server-1", SourceKind.SERVER, "opaque-server-id")
            val original = library.importTrack(TrackMetadata("Northern Light"), source)
            library.importTrack(original.metadata,
                TrackSource("download-1", SourceKind.DOWNLOAD, "file:///cache/northern-light.flac"),
                linkTo = original.id)

            val refreshed = library.importTrack(TrackMetadata("Northern Light (edited)"), source)

            assertEquals(setOf("server-1", "download-1"), refreshed.sources.map { it.id }.toSet())
            assertEquals("Northern Light (edited)", refreshed.metadata.title)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
