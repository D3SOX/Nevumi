package me.d3sox.nevumi.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
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
        override fun close() { soundCloud.close(); scrobbling.close(); http.close(); store.close() }
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
        preferences.update { it.copy(themeMode = ThemeMode.DARK, speed = 1.25, includeFolders = listOf("/home/someone/Music")) }
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
    fun aRoundTripRestoresTheSameDataOnAnotherDevice() = devices { source, target ->
        source.fill()
        target.sameSongs()
        val summary = target.backup.restore(source.backup.export("1.2.3"))

        assertEquals(source.preferences.load().copy(includeFolders = emptyList()), target.preferences.load())
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
            unmatchedTracks = 4,
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
        val text = source.backup.export()
        for (secret in source.vault.secrets.values + "viewer-password-secret" + "/home/someone/Music")
            assertFalse(secret in text, "The backup contains $secret")
        assertFalse("password" in text.lowercase())
        assertTrue("listener" in text && "https://music.test" in text && "music.test" in text)
    }

    @Test
    fun aSmartPlaylistOnALocalFolderIsKeptAsItsSongsWithoutThePath() = devices { source, target ->
        source.library.importTrack(nightWalk.copy(folder = "/home/someone/Music/Night"), TrackSource("local:3", SourceKind.LOCAL, "/music/03.wav"))
        source.playlists.createSmart("Night folder", LibraryQuery(folder = "/home/someone/Music/Night"))
        val text = source.backup.export()
        assertFalse("/home/someone" in text)
        target.sameSongs()
        target.backup.restore(text)
        val restored = target.playlists.all().single { it.name == "Night folder" }
        assertNull(restored.rule)
        assertEquals(listOf(nightWalk.title), target.titles(restored.trackIds))
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
        assertEquals(RestoreSummary(settings = true, unmatchedTracks = 4, signInRequired = first.signInRequired), second)
        assertEquals(merged.trackIds, target.playlists.get(road.id)!!.trackIds)
        assertEquals(1, target.queue.saved().size)
        assertEquals(2, target.history.all().size)
    }
}
