package me.d3sox.nevumi.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.history.Listen
import me.d3sox.nevumi.history.ListeningHistory
import me.d3sox.nevumi.history.Scrobbling
import me.d3sox.nevumi.history.ScrobblingProfile
import me.d3sox.nevumi.history.ScrobblingService
import me.d3sox.nevumi.library.LibraryQuery
import me.d3sox.nevumi.library.MusicLibrary
import me.d3sox.nevumi.library.SourceKind
import me.d3sox.nevumi.library.TrackMetadata
import me.d3sox.nevumi.library.TrackSource
import me.d3sox.nevumi.navidrome.ServerProfile
import me.d3sox.nevumi.playback.AudioEffects
import me.d3sox.nevumi.playback.AutoEqProfiles
import me.d3sox.nevumi.playlists.PlaylistSort
import me.d3sox.nevumi.playlists.Playlists
import me.d3sox.nevumi.queue.PlaybackQueue
import me.d3sox.nevumi.queue.QueueEntry
import me.d3sox.nevumi.queue.QueueSnapshot
import me.d3sox.nevumi.queue.SavedQueueKind
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.settings.MusicPreferences
import me.d3sox.nevumi.settings.ThemeMode
import me.d3sox.nevumi.sftp.SftpConfiguration
import me.d3sox.nevumi.sftp.SftpProfile
import me.d3sox.nevumi.soundcloud.SoundCloudDownloads
import me.d3sox.nevumi.soundcloud.SoundCloudProfile
import me.d3sox.nevumi.storage.MusicStore
import me.d3sox.nevumi.storage.openMusicStore
import java.nio.file.Files
import me.d3sox.nevumi.library.LibraryTab
import me.d3sox.nevumi.settings.CollectionLayout
import me.d3sox.nevumi.queue.QueueOrigin
import me.d3sox.nevumi.settings.CollectionSort
import me.d3sox.nevumi.settings.CollectionView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MusicBackupTest {
    private val northern = TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", 200_000, trackNumber = 1,
        recordingId = "11111111-1111-1111-1111-111111111111", releaseId = "22222222-2222-2222-2222-222222222222")
    private val longWay = TrackMetadata("Long Way Home", "Fixture Artist", "Fixture Album", 180_000, trackNumber = 2)
    private val nightWalk = TrackMetadata("Night Walk", "Fixture Artist", "Fixture Album", 240_000, trackNumber = 3)
    private val missing = TrackMetadata("Only There", "Elsewhere", "Gone", 100_000)
    private val serverSource = TrackSource("server:abc:song-2", SourceKind.SERVER, "song-2")

    private class Vault : CredentialVault {
        val secrets = mutableMapOf<String, String>()
        override suspend fun save(key: String, secret: String) { secrets[key] = secret }
        override suspend fun read(key: String) = secrets[key]
        override suspend fun delete(key: String) { secrets.remove(key) }
    }

    /** One device's store with the services a backup reads and writes. */
    private class Device(val store: MusicStore) : AutoCloseable {
        val http = HttpClient(MockEngine { error("No network in backup tests") })
        val vault = Vault()
        val library = MusicLibrary(store)
        val scrobbling = Scrobbling(store, library, http, vault)
        val soundCloud = SoundCloudDownloads(store, http)
        val equalizer = AutoEqProfiles(store, http)
        val backup = MusicBackup(store, library, scrobbling = scrobbling, soundCloud = soundCloud, equalizer = equalizer)
        val playlists = Playlists(store)
        val queue = PlaybackQueue(store)
        val history = ListeningHistory(store, library)
        val preferences = MusicPreferences(store)
        suspend fun id(title: String) = library.tracks().single { it.metadata.title == title }.id
        suspend fun titles(ids: List<String>) = ids.map { id -> library.findTrack(id)!!.metadata.title }
        override fun close() = runBlocking { soundCloud.closeAndJoin(); scrobbling.closeAndJoin(); http.close(); store.close() }
    }

    private fun devices(block: suspend (Device, Device) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("backup")
        try {
            Device(openMusicStore(directory.resolve("source.db").toString())).use { source ->
                Device(openMusicStore(directory.resolve("target.db").toString())).use { target -> block(source, target) }
            }
        } finally { directory.toFile().deleteRecursively() }
    }

    private suspend fun Device.fill() {
        val northernId = library.importTrack(northern, TrackSource("local:1", SourceKind.LOCAL, "/music/01.flac")).id
        val longWayId = library.importTrack(longWay, serverSource).id
        val nightWalkId = library.importTrack(nightWalk, TrackSource("local:3", SourceKind.LOCAL, "/music/03.wav")).id
        val missingId = library.importTrack(missing, TrackSource("local:4", SourceKind.LOCAL, "/music/04.mp3")).id
        library.setFavorite(northernId, true)
        library.setRating(longWayId, 4)
        library.setFavorite(missingId, true)
        val road = playlists.create("Road", listOf(northernId, longWayId, nightWalkId, missingId))
        playlists.edit(road.id, "Road", "Driving songs")
        playlists.setSort(road.id, PlaylistSort.ARTIST, descending = true)
        playlists.create("Music ▸ Night", listOf(nightWalkId))
        playlists.createSmart("Loved", LibraryQuery(favoritesOnly = true))
        queue.addSaved("Evening", QueueSnapshot(listOf(QueueEntry("e1", northernId), QueueEntry("e2", missingId), QueueEntry("e3", nightWalkId)),
            currentEntryId = "e3", positionMs = 5_000, contextName = "Road"), SavedQueueKind.SNAPSHOT, 1_000)
        history.import(listOf(Listen("listen-1", northernId, "play-1", 10_000, northern, 150_000, 150_000, 200_000, ended = true),
            Listen("listen-2", missingId, "play-2", 20_000, missing, 50_000, 50_000, 100_000)))
        preferences.update { it.copy(themeMode = ThemeMode.DARK, speed = 1.25, includeFolders = listOf("/home/someone/Music"),
            libraryFilter = LibraryQuery(folder = "/home/someone/Music/Sky"),
            collectionLayouts = mapOf(LibraryTab.FOLDERS to CollectionLayout(order = listOf("/home/someone/Music/Sky", "\\\\nas\\music\\Sky", "Rock/Live")))) }
        equalizer.save("Warm", AudioEffects(enabled = true, preampDb = -2.0))
        store.put("scrobbling-profile:lb", "scrobbling-profiles", ScrobblingProfile("lb", ScrobblingService.LISTENBRAINZ, "listener",
            "https://api.listenbrainz.org", 5))
        vault.save("scrobbling:lb", "listenbrainz-token-secret")
        store.put("server:navidrome", "servers", ServerProfile("https://music.test", "nico"))
        vault.save("navidrome", "navidrome-password-secret")
        val sftp = SftpConfiguration("server", 1, "Music", SftpProfile("music.test", 22, "nico", "/srv/music", "host-key-fingerprint"))
        store.put("sftp:profile:${sftp.id}", "sftp-profiles", sftp)
        vault.save(sftp.id, "sftp-private-key-secret")
        store.put("soundcloud:profile", "soundcloud-settings", SoundCloudProfile("https://gate.test",
            browserViewUrl = "https://gate.test/vnc?password=viewer-password-secret"))
    }

    /** The same songs on another device, imported in another order so every track ID differs, without the missing song. */
    private suspend fun Device.sameSongs(northernTitle: String = northern.title, longWayTitle: String = longWay.title) {
        library.importTrack(nightWalk, TrackSource("local:a", SourceKind.LOCAL, "/other/c.wav"))
        library.importTrack(longWay.copy(title = longWayTitle), serverSource)
        library.importTrack(northern.copy(title = northernTitle), TrackSource("local:b", SourceKind.LOCAL, "/other/a.flac"))
    }

    @Test
    fun exportingOneSectionExcludesEveryOtherSection() {
        for (section in BackupSection.entries) devices { source, _ ->
            source.fill()
            val document = Json.decodeFromString<BackupDocument>(source.backup.export(sections = setOf(section)))
            val included = buildSet {
                if (document.settings != null) add(BackupSection.SETTINGS)
                if (document.playlists.isNotEmpty()) add(BackupSection.PLAYLISTS)
                if (document.savedQueues.isNotEmpty()) add(BackupSection.SAVED_QUEUES)
                if (document.tracks.values.any { it.favorite }) add(BackupSection.FAVORITES)
                if (document.tracks.values.any { it.rating != 0 }) add(BackupSection.RATINGS)
                if (document.listens.isNotEmpty()) add(BackupSection.LISTENING_HISTORY)
                if (document.scrobblingProfiles.isNotEmpty() || document.server != null || document.sftpProfiles.isNotEmpty() || document.soundCloud != null)
                    add(BackupSection.SERVICE_PROFILES)
                if (document.equalizerProfiles.isNotEmpty()) add(BackupSection.EQUALIZER_PROFILES)
            }
            assertEquals(setOf(section), included, "Exporting $section included other data")
            if (section in setOf(BackupSection.SETTINGS, BackupSection.SERVICE_PROFILES, BackupSection.EQUALIZER_PROFILES))
                assertTrue(document.tracks.isEmpty())
        }
    }

    @Test
    fun restoringOnlyPlaylistsLeavesSettingsFavoritesRatingsAndHistoryAlone() = devices { source, target ->
        source.fill()
        target.sameSongs()
        val settings = target.preferences.load()
        val summary = target.backup.restore(source.backup.export(sections = setOf(BackupSection.PLAYLISTS)))
        assertEquals(3, summary.playlistsAdded)
        assertFalse(summary.settings)
        assertEquals(settings, target.preferences.load())
        assertTrue(target.library.tracks().none { it.favorite || it.rating != 0 })
        assertTrue(target.history.all().isEmpty())
        assertTrue(target.queue.saved().isEmpty())
        assertTrue(target.equalizer.saved().isEmpty())
        assertTrue(summary.signInRequired.isEmpty())
        assertEquals(listOf("Northern Light", "Long Way Home", "Night Walk"),
            target.titles(target.playlists.all().single { it.name == "Road" }.trackIds))
    }

    @Test
    fun restoringSettingsWithoutPlaylistsPreservesTheTargetsPlaylistOrder() = devices { source, target ->
        val sourcePlaylist = source.playlists.create("Source", emptyList())
        val sourceLayout = CollectionLayout(view = CollectionView.LIST, sort = CollectionSort.CUSTOM, order = listOf(sourcePlaylist.id))
        source.preferences.update { it.copy(themeMode = ThemeMode.DARK, collectionLayouts = mapOf(LibraryTab.PLAYLISTS to sourceLayout)) }
        val first = target.playlists.create("First", emptyList())
        val second = target.playlists.create("Second", emptyList())
        val order = listOf(second.id, first.id)
        target.preferences.update { it.copy(collectionLayouts = mapOf(LibraryTab.PLAYLISTS to CollectionLayout(order = order))) }

        val text = source.backup.export(sections = setOf(BackupSection.SETTINGS))
        assertFalse(sourcePlaylist.id in text)
        target.backup.restore(text)

        val restored = target.preferences.load()
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertEquals(sourceLayout.copy(order = order), restored.collectionLayout(LibraryTab.PLAYLISTS))
        assertEquals(setOf(first.id, second.id), target.playlists.all().map { it.id }.toSet())
    }

    @Test
    fun fullBackupsCanClearTheTargetsPlaylistOrder() {
        for (explicitEmptyOrder in listOf(false, true)) devices { source, target ->
            if (explicitEmptyOrder) source.preferences.update { it.copy(collectionLayouts = mapOf(
                LibraryTab.PLAYLISTS to CollectionLayout(sort = CollectionSort.CUSTOM, order = emptyList()))) }
            val playlist = target.playlists.create("Target", emptyList())
            target.preferences.update { it.copy(collectionLayouts = mapOf(
                LibraryTab.PLAYLISTS to CollectionLayout(sort = CollectionSort.CUSTOM, order = listOf(playlist.id)))) }

            target.backup.restore(source.backup.export())

            assertTrue(target.preferences.load().collectionLayout(LibraryTab.PLAYLISTS).order.isEmpty())
        }
    }

    @Test
    fun playlistOnlyBackupsRestoreCustomOrderWithoutChangingOtherSettings() = devices { source, target ->
        val sourceFirst = source.playlists.create("First", emptyList())
        val sourceSecond = source.playlists.create("Second", emptyList())
        source.preferences.update { it.copy(themeMode = ThemeMode.DARK, collectionLayouts = mapOf(
            LibraryTab.PLAYLISTS to CollectionLayout(sort = CollectionSort.CUSTOM, order = listOf(sourceSecond.id, sourceFirst.id)))) }
        val existing = target.playlists.create("Existing", emptyList())
        val layout = CollectionLayout(view = CollectionView.LIST, sort = CollectionSort.CUSTOM, order = listOf(existing.id))
        val before = target.preferences.update { it.copy(themeMode = ThemeMode.LIGHT, speed = 1.25,
            collectionLayouts = mapOf(LibraryTab.PLAYLISTS to layout)) }

        val text = source.backup.export(sections = setOf(BackupSection.PLAYLISTS))
        assertNull(Json.decodeFromString<BackupDocument>(text).settings)
        assertFalse(sourceFirst.id in text || sourceSecond.id in text)
        val summary = target.backup.restore(text)

        assertFalse(summary.settings)
        val ids = target.playlists.all().associate { it.name to it.id }
        val order = listOf(ids.getValue("Second"), ids.getValue("First"))
        assertEquals(before.copy(collectionLayouts = mapOf(LibraryTab.PLAYLISTS to layout.copy(order = order))), target.preferences.load())
    }

    @Test
    fun exportingNothingIsRejected() = devices { source, _ ->
        assertFailsWith<IllegalArgumentException> { source.backup.export(sections = emptySet()) }
    }

    @Test
    fun aRoundTripRestoresTheSameDataOnAnotherDevice() = devices { source, target ->
        source.fill()
        target.sameSongs()
        val summary = target.backup.restore(source.backup.export("1.2.3"))

        assertEquals(source.preferences.load().copy(includeFolders = emptyList(), libraryFilter = LibraryQuery(),
            collectionLayouts = mapOf(LibraryTab.FOLDERS to CollectionLayout(order = listOf("Rock/Live")))), target.preferences.load())
        val playlists = target.playlists.all().associateBy { it.name }
        assertEquals(setOf("Road", "Music ▸ Night", "Loved"), playlists.keys)
        assertEquals(listOf("Northern Light", "Long Way Home", "Night Walk"), target.titles(playlists.getValue("Road").trackIds))
        assertEquals("Driving songs", playlists.getValue("Road").description)
        assertEquals(PlaylistSort.ARTIST to true, playlists.getValue("Road").let { it.sort to it.descending })
        assertEquals(listOf("Night Walk"), target.titles(playlists.getValue("Music ▸ Night").trackIds))
        assertEquals(LibraryQuery(favoritesOnly = true), playlists.getValue("Loved").rule)
        assertEquals(listOf("Northern Light"), target.titles(playlists.getValue("Loved").trackIds))

        val queue = target.queue.saved().single()
        assertEquals("Evening" to SavedQueueKind.SNAPSHOT, queue.name to queue.kind)
        assertEquals(listOf("Northern Light", "Night Walk"), target.titles(queue.snapshot.entries.map { it.trackId }))
        assertEquals("e3" to 5_000L, queue.snapshot.currentEntryId to queue.snapshot.positionMs)

        val listen = target.history.all().single()
        assertEquals(target.id("Northern Light") to 150_000L, listen.trackId to listen.playedMs)
        assertTrue(target.library.findTrack(target.id("Northern Light"))!!.favorite)
        assertEquals(4, target.library.findTrack(target.id("Long Way Home"))!!.rating)
        assertEquals(source.equalizer.saved(), target.equalizer.saved())
        assertEquals(SoundCloudProfile("https://gate.test"), target.store.read<SoundCloudProfile>("soundcloud:profile"))

        assertEquals(RestoreSummary(settings = true, favorites = 1, ratings = 1, playlistsAdded = 3, savedQueues = 1, listens = 1,
            equalizerProfiles = 1, soundCloud = true,
            // The song missing here is referenced by its flags, the Road playlist, the saved queue and a listen.
            unmatchedTracks = 1,
            signInRequired = listOf(SignInRequired(BackupService.LISTENBRAINZ, "listener"),
                SignInRequired(BackupService.MUSIC_SERVER, "nico · https://music.test"),
                SignInRequired(BackupService.SFTP, "nico@music.test:22"))), summary)
    }

    @Test
    fun songsAreMatchedByTheirIdentityOrServerSongWhenTheirTagsDiffer() = devices { source, target ->
        source.fill()
        target.sameSongs(northernTitle = "Northern Light (Remaster)", longWayTitle = "Long Way Home (Live)")
        target.backup.restore(source.backup.export())
        assertEquals(listOf("Northern Light (Remaster)", "Long Way Home (Live)", "Night Walk"),
            target.titles(target.playlists.all().single { it.name == "Road" }.trackIds))
        assertTrue(target.library.findTrack(target.id("Northern Light (Remaster)"))!!.favorite)
        assertEquals(4, target.library.findTrack(target.id("Long Way Home (Live)"))!!.rating)
    }

    @Test
    fun theBackupContainsNoSecretsOrDevicePaths() = devices { source, _ ->
        source.fill()
        source.queue.addSaved("Sky folder", QueueSnapshot(listOf(QueueEntry("f1", source.id(northern.title))), currentEntryId = "f1",
            contextName = "/home/someone/Music/Sky", origin = QueueOrigin(LibraryTab.FOLDERS, "/home/someone/Music/Sky")), SavedQueueKind.SNAPSHOT, 2_000)
        val text = source.backup.export()
        for (secret in source.vault.secrets.values + "viewer-password-secret" + "/home/someone/Music" + "nas\\\\music")
            assertFalse(secret in text, "The backup contains $secret")
        assertFalse("password" in text.lowercase())
        assertTrue("listener" in text && "https://music.test" in text && "music.test" in text)
    }

    @Test
    fun aSmartPlaylistOnALocalFolderIsKeptAsItsSongsWithoutThePath() = devices { source, target ->
        source.library.importTrack(nightWalk.copy(folder = "/home/someone/Music/Night"), TrackSource("local:3", SourceKind.LOCAL, "/music/03.wav"))
        // A song already in a playlist stays out of a rule that excludes playlisted songs.
        val playlisted = source.library.importTrack(northern.copy(folder = "/home/someone/Music/Night"), TrackSource("local:1", SourceKind.LOCAL, "/music/01.flac"))
        source.playlists.create("Elsewhere", listOf(playlisted.id))
        source.playlists.createSmart("Night folder", LibraryQuery(folder = "/home/someone/Music/Night", notInPlaylist = true))
        // Android stores local folders relative to its storage.
        source.library.importTrack(longWay.copy(folder = "Music/Phone"), TrackSource("local:2", SourceKind.LOCAL, "content://media/external/audio/media/2"))
        source.playlists.createSmart("Phone folder", LibraryQuery(folder = "Music/Phone"))
        val text = source.backup.export()
        assertFalse("/home/someone" in text)
        assertFalse("Music/Phone" in text)
        target.sameSongs()
        target.backup.restore(text)
        val restored = target.playlists.all().single { it.name == "Night folder" }
        assertNull(restored.rule)
        assertEquals(listOf(nightWalk.title), target.titles(restored.trackIds))
    }

    @Test
    fun playlistsSharingANameStaySeparateAndRestoredListensAreNotScrobbledAgain() = devices { source, target ->
        source.fill()
        source.playlists.create("Road", listOf(source.id(nightWalk.title)))
        source.playlists.createSmart("Road", LibraryQuery(favoritesOnly = true))
        val text = source.backup.export()
        target.sameSongs()
        target.backup.restore(text)
        val roads = target.playlists.all().filter { it.name == "Road" }
        assertEquals(2, roads.count { it.rule == null })
        assertEquals(1, roads.count { it.rule != null })
        assertTrue(target.history.all().isNotEmpty() && target.history.all().all { it.originDeviceId != null })
        // A matched playlist takes the backed-up description, shuffle and order.
        val road = target.playlists.all().first { it.name == "Road" && it.rule == null && it.description == "Driving songs" }
        target.playlists.edit(road.id, "Road", "Mine")
        target.playlists.setSort(road.id, PlaylistSort.CUSTOM, false)
        target.backup.restore(text)
        target.playlists.all().single { it.id == road.id }.let {
            assertEquals("Driving songs", it.description); assertEquals(PlaylistSort.ARTIST, it.sort); assertTrue(it.descending)
        }
        // A same-named smart playlist with another rule does not stand in for the backed-up one.
        target.playlists.createSmart("Mix", LibraryQuery(ratingFrom = 4))
        source.playlists.createSmart("Mix", LibraryQuery(favoritesOnly = true))
        target.backup.restore(source.backup.export())
        assertEquals(setOf(LibraryQuery(ratingFrom = 4), LibraryQuery(favoritesOnly = true)),
            target.playlists.all().filter { it.name == "Mix" }.mapNotNull { it.rule }.toSet())
        // A song the backup lists twice is added until the matched playlist has it twice too.
        target.playlists.create("Twice", listOf(target.id(nightWalk.title)))
        source.playlists.create("Twice", List(2) { source.id(nightWalk.title) })
        target.backup.restore(source.backup.export())
        assertEquals(List(2) { nightWalk.title }, target.titles(target.playlists.all().single { it.name == "Twice" }.trackIds))
        // A second restore matches each playlist once instead of adding more.
        target.backup.restore(text)
        assertEquals(3, target.playlists.all().count { it.name == "Road" })
    }

    @Test
    fun aCustomPlaylistOrderFollowsThePlaylistsToTheirNewIds() {
        for (olderBackup in listOf(false, true)) devices { source, target ->
            source.fill()
            val names = listOf("Loved", "Music ▸ Night", "Road")
            val ids = source.playlists.all().associate { it.name to it.id }
            source.preferences.update { it.copy(collectionLayouts = mapOf(LibraryTab.PLAYLISTS to
                CollectionLayout(sort = CollectionSort.CUSTOM, order = names.map { name -> ids.getValue(name) }))) }
            val exported = source.backup.export()
            val text = if (olderBackup) JsonObject(Json.parseToJsonElement(exported).jsonObject - "playlistOrder").toString() else exported
            assertFalse(ids.values.any { it in text })
            target.sameSongs()
            target.backup.restore(text)
            val restored = target.playlists.all().associate { it.id to it.name }
            assertEquals(names, target.preferences.load().collectionLayouts.getValue(LibraryTab.PLAYLISTS).order.map { restored.getValue(it) })
        }
    }

    @Test
    fun aSavedQueueWithRepeatedEntryIdsIsSkipped() = devices { source, target ->
        source.fill()
        val night = source.id(nightWalk.title)
        source.queue.addSaved("Broken", QueueSnapshot(listOf(QueueEntry("x", night), QueueEntry("x", night)), currentEntryId = "x"),
            SavedQueueKind.SNAPSHOT, 3_000)
        target.sameSongs()
        target.backup.restore(source.backup.export())
        assertTrue(target.queue.saved().none { it.name == "Broken" })
        assertTrue(target.queue.saved().any { it.name == "Evening" })
    }

    @Test
    fun unknownVersionsAndOtherFilesAreRejected() = devices { _, target ->
        assertEquals(2, assertFailsWith<BackupFormatException> {
            target.backup.restore("""{"format":"nevumi-backup","version":2,"createdAt":0,"somethingNew":true}""")
        }.version)
        for (text in listOf("not json", "[]", """{"version":1,"createdAt":0}""", """{"format":"nevumi-backup","version":"one"}""",
            """{"format":"nevumi-backup","version":1,"createdAt":0,"playlists":"Road"}"""))
            assertNull(assertFailsWith<BackupFormatException>(text) { target.backup.restore(text) }.version)
        assertEquals(emptyList(), target.playlists.all())
    }

    @Test
    fun restoringOnlyAddsAndASecondRestoreChangesNothing() = devices { source, target ->
        source.fill()
        val backup = source.backup.export()
        target.sameSongs()
        val own = target.library.importTrack(TrackMetadata("Mine", "Me"), TrackSource("local:mine", SourceKind.LOCAL, "/other/mine.flac")).id
        target.library.setFavorite(own, true)
        target.library.setRating(target.id("Night Walk"), 2)
        val road = target.playlists.create("Road", listOf(own, target.id("Night Walk")))
        val kept = target.playlists.create("Kept", listOf(own))
        target.history.import(listOf(Listen("own-listen", own, "own-play", 1, TrackMetadata("Mine", "Me"))))
        target.equalizer.save("Warm", AudioEffects(enabled = true, preampDb = -6.0))
        target.store.put("soundcloud:profile", "soundcloud-settings", SoundCloudProfile("https://own-gate.test"))

        val first = target.backup.restore(backup)
        val merged = target.playlists.get(road.id)!!
        assertEquals(listOf("Mine", "Night Walk", "Northern Light", "Long Way Home"), target.titles(merged.trackIds))
        assertEquals(kept, target.playlists.get(kept.id))
        assertTrue(target.library.findTrack(own)!!.favorite)
        assertEquals(2, target.library.findTrack(target.id("Night Walk"))!!.rating)
        assertEquals(setOf("own-listen", "listen-1"), target.history.all().map { it.id }.toSet())
        assertEquals(-6.0, target.equalizer.saved().single().effects.preampDb)
        assertEquals("https://own-gate.test", target.store.read<SoundCloudProfile>("soundcloud:profile")!!.baseUrl)
        assertEquals(listOf(2, 1, 1, 0, false), listOf(first.playlistsAdded, first.playlistsMerged, first.listens, first.equalizerProfiles, first.soundCloud))

        val second = target.backup.restore(backup)
        assertEquals(RestoreSummary(settings = true, unmatchedTracks = 1, signInRequired = first.signInRequired), second)
        assertEquals(merged.trackIds, target.playlists.get(road.id)!!.trackIds)
        assertEquals(1, target.queue.saved().size)
        assertEquals(2, target.history.all().size)
    }
}
