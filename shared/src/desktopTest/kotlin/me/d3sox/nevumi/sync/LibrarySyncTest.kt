package me.d3sox.nevumi.sync

import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import io.ktor.client.engine.mock.respond
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNotEquals

class LibrarySyncTest {
    @Test fun fixedSnapshotsShareTheirContentsAndSectionsButRecoveryAndPlaybackCheckpointsRemainLocal() = runBlocking {
        val directory = Files.createTempDirectory("snapshot-sharing")
        val phoneStore = openMusicStore(directory.resolve("phone.db").toString())
        val desktopStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val phoneLibrary = MusicLibrary(phoneStore)
            val desktopLibrary = MusicLibrary(desktopStore)
            val phone = (1..3).map { phoneLibrary.importTrack(TrackMetadata("Song $it"), TrackSource("server:fixture:$it", SourceKind.SERVER, "$it")).id }
            val desktop = (1..3).map { desktopLibrary.importTrack(TrackMetadata("Song $it"), TrackSource("server:fixture:$it", SourceKind.SERVER, "$it")).id }
            val queue = PlaybackQueue(phoneStore)
            queue.replace(listOf(phone[0], phone[1], phone[1]), startIndex = 2, contextName = "Album")
            queue.append(phone[2]); queue.setPosition(1_250)
            val fixed = queue.save("Checkpoint", kind = me.d3sox.nevumi.queue.SavedQueueKind.SNAPSHOT)
            queue.captureRecovery()
            val sync = LibrarySync(phoneStore, phoneLibrary)
            val shared = sync.snapshot()
            assertEquals(listOf(fixed.id), shared.savedQueues.map { it.id })
            assertFalse(Json.encodeToString(shared.savedQueues).contains("positionMs"))
            LibrarySync(desktopStore, desktopLibrary).merge(shared)
            val desktopQueue = PlaybackQueue(desktopStore)
            val copy = desktopQueue.saved().single()
            assertEquals(fixed.kind, copy.kind)
            assertEquals(fixed.capturedAt, copy.capturedAt)
            assertEquals(listOf(desktop[0], desktop[1], desktop[1], desktop[2]), copy.snapshot.entries.map { it.trackId })
            assertEquals(fixed.snapshot.entries.map { it.section }, copy.snapshot.entries.map { it.section })
            assertEquals(0, copy.snapshot.currentIndex)
            assertEquals(0L, copy.snapshot.positionMs)
            desktopQueue.restoreSaved(copy.id)
            assertEquals(null, desktopQueue.snapshot().selectedSavedQueueId)
            desktopQueue.append(desktop[0])
            assertEquals(copy, desktopQueue.saved().single())
            queue.setPosition(1_900)
            assertEquals(shared.savedQueues, sync.snapshot().savedQueues)
            Unit
        } finally { phoneStore.close(); desktopStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aSharedShuffledSavedQueueKeepsItsShuffleModeAndCanRestoreItsOriginalOrder() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-shuffled-queues")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val phone = (1..4).map { first.importTrack(TrackMetadata("Song $it"), TrackSource("server:fixture:$it", SourceKind.SERVER, "$it")).id }
            val desktop = (1..4).map { second.importTrack(TrackMetadata("Song $it"), TrackSource("server:fixture:$it", SourceKind.SERVER, "$it")).id }
            val queue = PlaybackQueue(firstStore)
            queue.replace(listOf(phone[0], phone[1], phone[1], phone[2], phone[3]))
            queue.shuffleAlbums(listOf(phone.take(2), phone.drop(2)), kotlin.random.Random(4))
            val saved = queue.save("Album shuffle")
            val desktopQueue = PlaybackQueue(secondStore)
            LibrarySync(secondStore, second).merge(LibrarySync(firstStore, first).snapshot())
            val copied = desktopQueue.saved().single()
            assertEquals(true, copied.snapshot.albumShuffled)
            kotlin.test.assertNotNull(copied.snapshot.unshuffledEntryIds)
            desktopQueue.restoreSaved(saved.id)
            desktopQueue.shuffle(false)
            assertEquals(listOf(desktop[0], desktop[1], desktop[1], desktop[2], desktop[3]), desktopQueue.snapshot().entries.map { it.trackId })
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun syncedServerFavoritesAndRatingsSurviveRefreshAndReachNavidromeAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-server-changes")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        var secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp)
        val client = me.d3sox.nevumi.navidrome.NavidromeClient(http, "http://127.0.0.1:14533",
            me.d3sox.nevumi.navidrome.ServerCredentials("fixture", "fixture-password"))
        val original = client.songs().first { it.metadata.title == "Northern Light" }
        var second = MusicLibrary(secondStore)
        var changes = me.d3sox.nevumi.navidrome.NavidromeChanges(secondStore, second) { client }
        try {
            client.setFavorite(original.id, false); client.setRating(original.id, 0)
            val desktop = changes.refreshLibrary(client).single { it.second.id == original.id }.first
            val first = MusicLibrary(firstStore)
            val phone = first.importTrack(original.metadata, TrackSource("server:${client.serverId}:${original.id}", SourceKind.SERVER, original.id))
            first.setFavorite(phone.id, true); first.setRating(phone.id, 4)
            LibrarySync(secondStore, second, changes).merge(LibrarySync(firstStore, first).snapshot())
            changes.refreshLibrary(client)
            assertEquals(true, second.findTrack(desktop.id)!!.favorite)
            assertEquals(4, second.findTrack(desktop.id)!!.rating)
            changes.closeAndJoin(); secondStore.close()
            secondStore = openMusicStore(directory.resolve("desktop.db").toString())
            second = MusicLibrary(secondStore)
            changes = me.d3sox.nevumi.navidrome.NavidromeChanges(secondStore, second) { client }
            changes.synchronize()
            val persisted = client.songs().single { it.id == original.id }
            assertEquals(true, persisted.favorite)
            assertEquals(4, persisted.rating)
            assertEquals(0, changes.pendingCount())
            Unit
        } finally {
            changes.closeAndJoin()
            client.setFavorite(original.id, original.favorite); client.setRating(original.id, original.rating)
            http.close(); firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun aConfirmedSongMatchFollowsTheChosenCanonicalSongWhenLocalCopiesAreLinked() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-linked-songs")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val phone = first.importTrack(TrackMetadata("Shared song"), TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.mp3"))
            val desktop = second.importTrack(TrackMetadata("My song"), TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.mp3"))
            val canonical = second.importTrack(TrackMetadata("Chosen copy"), TrackSource("local:chosen", SourceKind.LOCAL, "/desktop/copy.mp3"))
            first.setFavorite(phone.id, true)
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            val initial = phoneSync.snapshot()
            desktopSync.merge(initial)
            desktopSync.mapTrack(initial.deviceId, phone.id, desktop.id)
            second.linkTracks(canonical.id, desktop.id)
            first.setFavorite(phone.id, false)
            desktopSync.merge(phoneSync.snapshot())
            assertEquals(false, second.findTrack(canonical.id)!!.favorite)
            assertEquals(emptyList(), desktopSync.unmatched())
            second.setRating(canonical.id, 4)
            phoneSync.merge(desktopSync.snapshot(initial.deviceId))
            assertEquals(4, first.findTrack(phone.id)!!.rating)
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun clearingThisDevicesHistorySurvivesRestartAndDoesNotEraseTheOtherDevicesHistory() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-clear-history")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        var secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Night walk", durationMs = 120_000)
            val phone = first.importTrack(metadata, TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
            second.importTrack(metadata, TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
            var elapsed = 0L
            val phoneHistory = me.d3sox.nevumi.history.ListeningHistory(firstStore, first,
                wallClock = { 1_000 }, elapsedClock = { elapsed })
            val desktopHistory = me.d3sox.nevumi.history.ListeningHistory(secondStore, second)
            val playing = me.d3sox.nevumi.playback.PlaybackStatus(phone.id, playing = true, durationMs = 120_000, playbackId = "phone-playback")
            phoneHistory.record(playing)
            elapsed = 60_000
            phoneHistory.record(playing.copy(playing = false, ended = true))
            val phoneSync = LibrarySync(firstStore, first)
            LibrarySync(secondStore, second).merge(phoneSync.snapshot())
            assertEquals(1, desktopHistory.all().size)
            desktopHistory.clear()
            secondStore.close()
            secondStore = openMusicStore(directory.resolve("desktop.db").toString())
            val restoredLibrary = MusicLibrary(secondStore)
            val restoredSync = LibrarySync(secondStore, restoredLibrary)
            restoredSync.merge(phoneSync.snapshot())
            assertEquals(emptyList(), me.d3sox.nevumi.history.ListeningHistory(secondStore, restoredLibrary).all())
            assertEquals(emptyList(), restoredSync.snapshot().listens)
            assertEquals(1, phoneHistory.all().size)
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun playlistEditsMadeIndependentlyKeepBothVersionsAndConvergeAfterRepeatedSyncs() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-concurrent-playlists")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Night walk", durationMs = 120_000)
            val phone = first.importTrack(metadata, TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
            val desktop = second.importTrack(metadata, TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
            val phonePlaylists = me.d3sox.nevumi.playlists.Playlists(firstStore)
            val desktopPlaylists = me.d3sox.nevumi.playlists.Playlists(secondStore)
            val original = phonePlaylists.create("Shared", listOf(phone.id))
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            desktopSync.merge(phoneSync.snapshot())
            phoneSync.merge(desktopSync.snapshot())
            phonePlaylists.rename(original.id, "Phone edit")
            desktopPlaylists.rename(original.id, "Desktop edit")
            desktopPlaylists.append(original.id, desktop.id)
            val phoneChanges = phoneSync.snapshot()
            val desktopChanges = desktopSync.snapshot()
            desktopSync.merge(phoneChanges)
            phoneSync.merge(desktopChanges)
            repeat(3) {
                desktopSync.merge(phoneSync.snapshot())
                phoneSync.merge(desktopSync.snapshot())
            }
            val phoneLists = phonePlaylists.all().sortedBy { it.id }
            val desktopLists = desktopPlaylists.all().sortedBy { it.id }
            assertEquals(2, phoneLists.size)
            assertEquals(phoneLists.map { it.id to it.name }, desktopLists.map { it.id to it.name })
            assertEquals(listOf(1, 2), phoneLists.map { it.trackIds.size }.sorted())
            assertEquals(listOf(1, 2), desktopLists.map { it.trackIds.size }.sorted())
            assertEquals(setOf("Phone edit", "Desktop edit"), phoneLists.map { it.name.removeSuffix(" (conflict copy)") }.toSet())
            phonePlaylists.rename(original.id, "After syncing")
            desktopSync.merge(phoneSync.snapshot())
            assertEquals(2, desktopPlaylists.all().size)
            assertEquals("After syncing", desktopPlaylists.get(original.id)!!.name)
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun deletingSharedCollectionsRemovesTheirCopiesAndOlderSnapshotsCannotResurrectThem() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-collection-deletion")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Night walk", durationMs = 120_000)
            val phone = first.importTrack(metadata, TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
            val desktop = second.importTrack(metadata, TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
            val phonePlaylists = me.d3sox.nevumi.playlists.Playlists(firstStore)
            val desktopPlaylists = me.d3sox.nevumi.playlists.Playlists(secondStore)
            val playlist = phonePlaylists.create("Shared playlist", listOf(phone.id))
            val phoneQueue = PlaybackQueue(firstStore)
            phoneQueue.replace(listOf(phone.id))
            val saved = phoneQueue.save("Shared queue")
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            val earlier = phoneSync.snapshot()
            desktopSync.merge(earlier)
            assertEquals(1, desktopPlaylists.all().size)
            val desktopQueue = PlaybackQueue(secondStore)
            assertEquals(1, desktopQueue.saved().size)
            desktopQueue.replace(listOf(desktop.id)); desktopQueue.setPosition(12_000)
            val active = desktopQueue.snapshot()
            phonePlaylists.delete(playlist.id)
            phoneQueue.deleteSaved(saved.id)
            desktopSync.merge(phoneSync.snapshot())
            assertEquals(null, desktopPlaylists.get(playlist.id))
            assertEquals(emptyList(), desktopQueue.saved())
            desktopSync.merge(earlier)
            assertEquals(null, desktopPlaylists.get(playlist.id))
            assertEquals(emptyList(), desktopQueue.saved())
            assertEquals(active, desktopQueue.snapshot())
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun syncingDoesNotExportAnUntouchedLocalMusicCatalogue() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-minimal-export")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            library.importTrack(TrackMetadata("Private unshared song", folder = "/private/music"),
                TrackSource("local:private", SourceKind.LOCAL, "/private/music/song.flac"))
            val snapshot = LibrarySync(store, library).snapshot()
            assertEquals(emptyList(), snapshot.tracks)
            assertEquals(emptyList(), snapshot.favorites)
            assertEquals(emptyList(), snapshot.ratings)
            assertFalse(Json.encodeToString(snapshot).contains("Private unshared song"))
            Unit
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun folderRulesWaitForALocalFolderMappingWithoutSharingOrDroppingTheOriginalPath() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-folders")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Northern Light", durationMs = 180_000, trackNumber = 1,
                folder = "/private/phone/ambient", recordingId = "b6e013dc-9d19-4f79-82f0-8027c6c0683d",
                releaseId = "43b582a8-f4c2-4631-8d7f-96667bad4271")
            first.importTrack(metadata, TrackSource("local:phone", SourceKind.LOCAL, "/private/phone/song.flac"))
            val desktop = second.importTrack(metadata.copy(folder = "/private/desktop/ambient"),
                TrackSource("local:desktop", SourceKind.LOCAL, "/private/desktop/song.flac"))
            second.importTrack(TrackMetadata("Other folder", folder = "/private/desktop/other"),
                TrackSource("local:other", SourceKind.LOCAL, "/private/desktop/other/other.mp3"))
            val phonePlaylists = me.d3sox.nevumi.playlists.Playlists(firstStore)
            val desktopPlaylists = me.d3sox.nevumi.playlists.Playlists(secondStore)
            val playlist = phonePlaylists.createSmart("My ambient folder", LibraryQuery(folder = metadata.folder))
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            val snapshot = phoneSync.snapshot()
            desktopSync.merge(snapshot)
            assertEquals(null, desktopPlaylists.get(playlist.id))
            val request = desktopSync.pendingFolders().single()
            assertEquals(playlist.id, request.playlistId)
            assertEquals("My ambient folder", request.playlistName)
            assertFalse(Json.encodeToString(snapshot).contains("/private/"))
            desktopSync.mapFolder(request.folderToken, "/private/desktop/ambient")
            assertEquals("/private/desktop/ambient", desktopPlaylists.get(playlist.id)!!.rule!!.folder)
            assertEquals(listOf(desktop.id), desktopPlaylists.get(playlist.id)!!.trackIds)
            assertEquals(emptyList(), desktopSync.pendingFolders())
            phoneSync.merge(desktopSync.snapshot())
            assertEquals("/private/phone/ambient", phonePlaylists.get(playlist.id)!!.rule!!.folder)
            assertEquals(emptyList(), phoneSync.pendingFolders())
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun savedQueuesShareTheirOrderAndRepeatOptionsWithoutChangingActivePlayback() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-saved-queues")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Northern Light", durationMs = 180_000, trackNumber = 1,
                recordingId = "b6e013dc-9d19-4f79-82f0-8027c6c0683d", releaseId = "43b582a8-f4c2-4631-8d7f-96667bad4271")
            val phone = first.importTrack(metadata, TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.flac"))
            val desktop = second.importTrack(metadata, TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.flac"))
            val phoneQueue = PlaybackQueue(firstStore)
            phoneQueue.replace(listOf(phone.id, phone.id)); phoneQueue.setPosition(91_000)
            phoneQueue.setRepeat(me.d3sox.nevumi.playback.RepeatMode.ONE, 3)
            val saved = phoneQueue.save("My commute")
            val desktopQueue = PlaybackQueue(secondStore)
            desktopQueue.replace(listOf(desktop.id)); desktopQueue.setPosition(41_000)
            val active = desktopQueue.snapshot()
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            val snapshot = phoneSync.snapshot()
            desktopSync.merge(snapshot)
            val copied = desktopQueue.saved().single()
            assertEquals(saved.id, copied.id)
            assertEquals("My commute", copied.name)
            assertEquals(listOf(desktop.id, desktop.id), copied.snapshot.entries.map { it.trackId })
            assertEquals(me.d3sox.nevumi.playback.RepeatMode.ONE, copied.snapshot.repeatMode)
            assertEquals(3, copied.snapshot.repeatPlays)
            assertEquals(0, copied.snapshot.positionMs)
            assertEquals(active, desktopQueue.snapshot())
            val serialized = Json.encodeToString(snapshot)
            assertFalse(serialized.contains("positionMs"))
            assertFalse(serialized.contains("currentEntryId"))
            desktopQueue.restoreSaved(saved.id)
            assertEquals(listOf(desktop.id, desktop.id), desktopQueue.snapshot().entries.map { it.trackId })
            assertEquals(0, desktopQueue.snapshot().positionMs)
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun savedQueueUpdatesPreserveEachDevicesLocalPlaybackPositionAndOccurrence() = runBlocking {
        val directory = Files.createTempDirectory("saved-queue-local-positions")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Shared song", durationMs = 180_000, trackNumber = 1, discNumber = 1,
                recordingId = "b6e013dc-9d19-4f79-82f0-8027c6c0683d",
                releaseId = "43b582a8-f4c2-4631-8d7f-96667bad4271")
            val phone = first.importTrack(metadata, TrackSource("phone", SourceKind.LOCAL, "/phone/song.flac"))
            val desktop = second.importTrack(metadata, TrackSource("desktop", SourceKind.LOCAL, "/desktop/song.flac"))
            val phoneQueue = PlaybackQueue(firstStore)
            val desktopQueue = PlaybackQueue(secondStore)
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            phoneQueue.replace(listOf(phone.id, phone.id), startIndex = 1)
            val saved = phoneQueue.save("Commute", select = true)
            phoneQueue.setPosition(91_000)
            desktopSync.merge(phoneSync.snapshot())
            desktopQueue.restoreSaved(saved.id)
            desktopQueue.select(desktopQueue.snapshot().entries.last().id)
            desktopQueue.setPosition(41_000)
            val occurrence = desktopQueue.snapshot().currentEntryId
            phoneQueue.append(phone.id)
            desktopSync.merge(phoneSync.snapshot())
            assertEquals(3, desktopQueue.saved().single().snapshot.entries.size)
            assertEquals(occurrence to 41_000L, desktopQueue.snapshot().currentEntryId to desktopQueue.snapshot().positionMs)
            assertEquals(41_000, desktopQueue.saved().single().snapshot.positionMs)
            assertEquals(91_000, phoneQueue.saved().single().snapshot.positionMs)
            val payload = Json.encodeToString(desktopSync.snapshot())
            assertFalse(payload.contains("positionMs"))
            assertFalse(payload.contains("selectedSavedQueueId"))
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun mixedAndSmartPlaylistsUseEachDevicesTrackIdsAndKeepDownloadPreferencesLocal() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-playlists")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Northern Light", "Fixture Artist", durationMs = 180_000, genres = listOf("Ambient"),
                trackNumber = 1, year = 2024, recordingId = "b6e013dc-9d19-4f79-82f0-8027c6c0683d",
                releaseId = "43b582a8-f4c2-4631-8d7f-96667bad4271")
            val phone = first.importTrack(metadata, TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.flac"))
            val desktop = second.importTrack(metadata, TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.flac"))
            val serverMetadata = TrackMetadata("Night walk", "Fixture Artist", durationMs = 120_000)
            val phoneServer = first.importTrack(serverMetadata, TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
            val desktopServer = second.importTrack(serverMetadata, TrackSource("server:fixture:night", SourceKind.SERVER, "night"))
            first.setFavorite(phone.id, true)
            val phonePlaylists = me.d3sox.nevumi.playlists.Playlists(firstStore)
            val desktopPlaylists = me.d3sox.nevumi.playlists.Playlists(secondStore)
            val mixed = phonePlaylists.create("Mixed favorites", listOf(phoneServer.id, phone.id, phoneServer.id))
            phonePlaylists.setAutoDownload(mixed.id, true)
            phonePlaylists.edit(mixed.id, mixed.name, "Music for late evenings")
            phonePlaylists.setShuffle(mixed.id, true)
            phonePlaylists.setSort(mixed.id, me.d3sox.nevumi.playlists.PlaylistSort.ARTIST, true)
            val rule = LibraryQuery(favoritesOnly = true, genre = "Ambient", yearFrom = 2020)
            val smart = phonePlaylists.createSmart("Recent ambient favorites", rule)
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            val snapshot = phoneSync.snapshot()
            desktopSync.merge(snapshot)
            assertEquals(listOf(desktopServer.id, desktop.id, desktopServer.id), desktopPlaylists.get(mixed.id)?.trackIds)
            assertEquals(false, desktopPlaylists.get(mixed.id)!!.autoDownload)
            assertEquals("Music for late evenings", desktopPlaylists.get(mixed.id)!!.description)
            assertTrue(desktopPlaylists.get(mixed.id)!!.shuffle)
            assertEquals(me.d3sox.nevumi.playlists.PlaylistSort.ARTIST, desktopPlaylists.get(mixed.id)!!.sort)
            assertTrue(desktopPlaylists.get(mixed.id)!!.descending)
            assertEquals(rule, desktopPlaylists.get(smart.id)!!.rule)
            assertEquals(listOf(desktop.id), desktopPlaylists.get(smart.id)!!.trackIds)
            assertFalse(Json.encodeToString(snapshot).contains("autoDownload"))
            desktopPlaylists.setAutoDownload(mixed.id, true)
            phonePlaylists.rename(mixed.id, "Changed on the phone")
            phonePlaylists.replaceTracks(mixed.id, listOf(phone.id, phoneServer.id))
            desktopSync.merge(phoneSync.snapshot())
            assertEquals("Changed on the phone", desktopPlaylists.get(mixed.id)!!.name)
            assertEquals(listOf(desktop.id, desktopServer.id), desktopPlaylists.get(mixed.id)!!.trackIds)
            assertEquals(true, desktopPlaylists.get(mixed.id)!!.autoDownload)
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anUnmatchedSongKeepsTheNewestChangeUntilItIsMappedEvenIfOlderSnapshotsArriveLater() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-pending-revisions")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Same title", durationMs = 180_000)
            val phone = first.importTrack(metadata, TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.mp3"))
            val desktop = second.importTrack(metadata, TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.mp3"))
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            first.setFavorite(phone.id, true)
            val earlier = phoneSync.snapshot()
            first.setFavorite(phone.id, false)
            desktopSync.merge(phoneSync.snapshot())
            desktopSync.merge(earlier)
            assertEquals(1, desktopSync.unmatched().size)
            desktopSync.mapTrack(earlier.deviceId, phone.id, desktop.id)
            assertEquals(false, second.findTrack(desktop.id)!!.favorite)
            assertEquals(emptyList(), desktopSync.unmatched())
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun sharedListeningHistoryCountsEachPlayOnceAndNeverRescrobblesImportedEvents() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-history")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { request ->
            check(request.url.encodedPath.endsWith("validate-token"))
            respond("""{"valid":true,"user_name":"fixture"}""", headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"))
        })
        val secrets = mutableMapOf<String, String>()
        val vault = object : me.d3sox.nevumi.security.CredentialVault {
            override suspend fun save(key: String, secret: String) { secrets[key] = secret }
            override suspend fun read(key: String) = secrets[key]
            override suspend fun delete(key: String) { secrets.remove(key) }
        }
        val first = MusicLibrary(firstStore)
        val second = MusicLibrary(secondStore)
        val scrobbling = me.d3sox.nevumi.history.Scrobbling(secondStore, second, http, vault, now = { 0 })
        try {
            val metadata = TrackMetadata("Northern Light", "Fixture Artist", durationMs = 180_000, trackNumber = 1,
                recordingId = "b6e013dc-9d19-4f79-82f0-8027c6c0683d", releaseId = "43b582a8-f4c2-4631-8d7f-96667bad4271")
            val phone = first.importTrack(metadata, TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.flac"))
            val desktop = second.importTrack(metadata, TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.flac"))
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            var elapsed = 0L
            val phoneHistory = me.d3sox.nevumi.history.ListeningHistory(firstStore, first,
                wallClock = { 1_000 }, elapsedClock = { elapsed })
            val desktopHistory = me.d3sox.nevumi.history.ListeningHistory(secondStore, second)
            val playing = me.d3sox.nevumi.playback.PlaybackStatus(phone.id, playing = true,
                durationMs = 180_000, playbackId = "phone-playback")
            phoneHistory.record(playing)
            val earlier = phoneSync.snapshot()
            elapsed = 90_000
            phoneHistory.record(playing.copy(playing = false, ended = true, positionMs = elapsed))
            scrobbling.connectListenBrainz("fixture-token", "https://listenbrainz.example.test")
            val complete = phoneSync.snapshot()
            desktopSync.merge(complete)
            desktopSync.merge(complete)
            desktopSync.merge(earlier)
            assertEquals(1, desktopHistory.all().size)
            assertEquals(desktop.id, desktopHistory.all().single().trackId)
            assertEquals(1, desktopHistory.statistics().getValue(desktop.id).playCount)
            assertEquals(90_000, desktopHistory.statistics().getValue(desktop.id).listenedMs)
            scrobbling.enqueueEligible()
            assertEquals(emptyList(), scrobbling.submissions())
            phoneSync.merge(desktopSync.snapshot())
            assertEquals(1, phoneHistory.all().size)
            Unit
        } finally { scrobbling.closeAndJoin(); http.close(); firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun favoritesAndRatingsMergeIndependentlyAndClearingARatingPropagates() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-independent-marks")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Northern Light", durationMs = 180_000, trackNumber = 1,
                recordingId = "b6e013dc-9d19-4f79-82f0-8027c6c0683d", releaseId = "43b582a8-f4c2-4631-8d7f-96667bad4271")
            val phone = first.importTrack(metadata, TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.flac"))
            val desktop = second.importTrack(metadata, TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.flac"))
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            first.setFavorite(phone.id, true)
            second.setRating(desktop.id, 5)
            val phoneSnapshot = phoneSync.snapshot()
            val desktopSnapshot = desktopSync.snapshot()
            desktopSync.merge(phoneSnapshot)
            phoneSync.merge(desktopSnapshot)
            assertEquals(5, first.findTrack(phone.id)!!.rating)
            assertEquals(true, first.findTrack(phone.id)!!.favorite)
            assertEquals(5, second.findTrack(desktop.id)!!.rating)
            assertEquals(true, second.findTrack(desktop.id)!!.favorite)
            second.setRating(desktop.id, 0)
            phoneSync.merge(desktopSync.snapshot())
            assertEquals(0, first.findTrack(phone.id)!!.rating)
            assertEquals(true, first.findTrack(phone.id)!!.favorite)
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aRetriedOlderSnapshotCannotRestoreAFavoriteThatWasRemovedOnTheOtherDevice() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-favorite-removal")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val database = directory.resolve("desktop.db").toString()
        var secondStore = openMusicStore(database)
        try {
            val first = MusicLibrary(firstStore)
            var second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Northern Light", durationMs = 180_000, trackNumber = 1,
                recordingId = "b6e013dc-9d19-4f79-82f0-8027c6c0683d", releaseId = "43b582a8-f4c2-4631-8d7f-96667bad4271")
            val phone = first.importTrack(metadata, TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.flac"))
            val desktop = second.importTrack(metadata, TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.flac"))
            val phoneSync = LibrarySync(firstStore, first)
            var desktopSync = LibrarySync(secondStore, second)
            first.setFavorite(phone.id, true)
            val older = phoneSync.snapshot()
            desktopSync.merge(older)
            second.setFavorite(desktop.id, false)
            val removal = desktopSync.snapshot()
            secondStore.close(); secondStore = openMusicStore(database)
            second = MusicLibrary(secondStore); desktopSync = LibrarySync(secondStore, second)
            desktopSync.merge(older)
            assertEquals(false, second.findTrack(desktop.id)!!.favorite)
            phoneSync.merge(removal)
            assertEquals(false, first.findTrack(phone.id)!!.favorite)
            desktopSync.merge(phoneSync.snapshot())
            desktopSync.merge(older)
            assertEquals(false, second.findTrack(desktop.id)!!.favorite)
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun songsSharedOnlyForAPlaybackTransferStopWaitingForAMatchAfterTheNextSync() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-transfer-songs")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val queued = first.importTrack(TrackMetadata("Queued", durationMs = 180_000), TrackSource("local:queued", SourceKind.LOCAL, "/phone/queued.mp3"))
            val favorite = first.importTrack(TrackMetadata("Favorite", durationMs = 180_000), TrackSource("local:favorite", SourceKind.LOCAL, "/phone/favorite.mp3"))
            first.setFavorite(favorite.id, true)
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            desktopSync.merge(phoneSync.snapshot(additionalTracks = setOf(queued.id)))
            assertEquals(setOf(queued.id, favorite.id), desktopSync.unmatched().map { it.track.key }.toSet())
            desktopSync.merge(phoneSync.snapshot())
            assertEquals(listOf(favorite.id), desktopSync.unmatched().map { it.track.key })
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun unidentifiedSongsWaitForAnExplicitMappingWhichSurvivesRestart() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-unmatched")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val database = directory.resolve("desktop.db").toString()
        var secondStore = openMusicStore(database)
        try {
            val first = MusicLibrary(firstStore)
            var second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Same title", "Same artist", "Same album", 180_000)
            val phone = first.importTrack(metadata, TrackSource("local:phone", SourceKind.LOCAL, "/phone/song.mp3"))
            val desktop = second.importTrack(metadata, TrackSource("local:desktop", SourceKind.LOCAL, "/desktop/song.mp3"))
            first.setFavorite(phone.id, true)
            val phoneSync = LibrarySync(firstStore, first)
            var desktopSync = LibrarySync(secondStore, second)
            val snapshot = phoneSync.snapshot()
            desktopSync.merge(snapshot)
            assertEquals(false, second.findTrack(desktop.id)!!.favorite)
            assertEquals(phone.id, desktopSync.unmatched().single().track.key)
            secondStore.close(); secondStore = openMusicStore(database)
            second = MusicLibrary(secondStore); desktopSync = LibrarySync(secondStore, second)
            assertEquals(snapshot.deviceId, desktopSync.unmatched().single().peerDeviceId)
            desktopSync.mapTrack(snapshot.deviceId, phone.id, desktop.id)
            assertEquals(true, second.findTrack(desktop.id)!!.favorite)
            assertEquals(emptyList(), desktopSync.unmatched())
            second.setRating(desktop.id, 4)
            phoneSync.merge(desktopSync.snapshot(snapshot.deviceId))
            assertEquals(4, first.findTrack(phone.id)!!.rating)
            assertEquals(emptyList(), phoneSync.unmatched())
            first.setFavorite(phone.id, false)
            desktopSync.merge(phoneSync.snapshot())
            assertEquals(false, second.findTrack(desktop.id)!!.favorite)
            assertEquals(1, second.tracks().size)
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun favoritesMatchIdentifiedSongsAcrossDevicesWithoutSharingPrivatePathsOrPlaybackState() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-library-sync-favorites")
        val firstStore = openMusicStore(directory.resolve("phone.db").toString())
        val secondStore = openMusicStore(directory.resolve("desktop.db").toString())
        try {
            val first = MusicLibrary(firstStore)
            val second = MusicLibrary(secondStore)
            val metadata = TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", 180_000,
                trackNumber = 1, folder = "/private/phone-music", recordingId = "b6e013dc-9d19-4f79-82f0-8027c6c0683d",
                releaseId = "43b582a8-f4c2-4631-8d7f-96667bad4271")
            val phone = first.importTrack(metadata, TrackSource("local:/private/phone-music/song.flac", SourceKind.LOCAL,
                "content://private-audio/17"))
            first.importTrack(metadata, TrackSource("server:fixture:song-17", SourceKind.SERVER,
                "https://music.example.test/song?password=fixture-secret", filePath = "/private/server-music/song.flac"))
            val desktop = second.importTrack(metadata.copy(folder = "/private/desktop-music"),
                TrackSource("local:/private/desktop-music/song.flac", SourceKind.LOCAL, "/private/desktop-music/song.flac"))
            assertNotEquals(phone.id, desktop.id)
            first.setFavorite(phone.id, true)
            val queue = PlaybackQueue(secondStore)
            queue.replace(listOf(desktop.id)); queue.setPosition(41_000)
            val originalQueue = queue.snapshot()
            val phoneSync = LibrarySync(firstStore, first)
            val desktopSync = LibrarySync(secondStore, second)
            val snapshot = phoneSync.snapshot()
            desktopSync.merge(snapshot)
            assertEquals(true, second.findTrack(desktop.id)!!.favorite)
            assertEquals(1, second.tracks().size)
            assertEquals(originalQueue, queue.snapshot())
            val serialized = Json.encodeToString(snapshot)
            for (privateValue in listOf("/private/", "content://", "fixture-secret", "music.example.test", "41_000"))
                assertFalse(serialized.contains(privateValue), "Sync exported $privateValue")
            assertEquals("", snapshot.tracks.single().metadata.folder)
            assertNotEquals(phoneSync.deviceId(), desktopSync.deviceId())
            Unit
        } finally { firstStore.close(); secondStore.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun laterSyncsShareOnlyRecentOrUnfinishedListens() = runBlocking {
        val directory = Files.createTempDirectory("incremental-listens")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val track = library.importTrack(TrackMetadata("Song"), TrackSource("server:fixture:1", SourceKind.SERVER, "1"))
            fun listen(id: String, startedAt: Long, ended: Boolean) = me.d3sox.nevumi.history.Listen(id, track.id, "play-$id", startedAt,
                track.metadata, listenedMs = 1_000, playedMs = 1_000, durationMs = 200_000, ended = ended)
            for (event in listOf(listen("old", 1_000, true), listen("running", 2_000, false), listen("new", 50_000, true)))
                store.put("listen:${event.id}", "listens", event)
            val sync = LibrarySync(store, library)
            assertEquals(setOf("old", "running", "new"), sync.snapshot().listens.map { it.id }.toSet())
            assertEquals(setOf("running", "new"), sync.snapshot(listensSince = 10_000).listens.map { it.id }.toSet())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
