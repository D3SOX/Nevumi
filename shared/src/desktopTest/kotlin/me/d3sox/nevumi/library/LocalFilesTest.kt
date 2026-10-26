package me.d3sox.nevumi.library

import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalFilesTest {
    @Test
    fun configuredFoldersCanBeScannedWithoutASeparateImportPicker() = runBlocking {
        val directory = Files.createTempDirectory("configured-music-folder")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac"), music.resolve("song.flac"))
            me.d3sox.nevumi.settings.MusicPreferences(store).update { it.copy(includeFolders = listOf(music.toString())) }
            val scanner = WatchingLocalFiles(MusicLibrary(store))
            assertEquals(listOf(music.toString()), scanner.roots())
            assertEquals(listOf(music.toString()), scanner.pendingRefreshLocations())
            assertEquals("Northern Light", scanner.scan(scanner.roots().single()).single().metadata.title)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun scanningReportsProgressWhileNewSongsAreAlreadyAvailable() = runBlocking {
        val directory = Files.createTempDirectory("local-scan-progress")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            Files.copy(fixture, music.resolve("first.flac")); Files.copy(fixture, music.resolve("second.flac"))
            val library = MusicLibrary(store)
            val progress = mutableListOf<LocalScanProgress>()
            LocalFiles(library).scan(music.toString(), onProgress = {
                progress += it
                if (it.processed == 1) assertEquals(1, library.tracks().size)
            })
            assertEquals(LocalScanProgress(0, 2), progress.firstOrNull())
            kotlin.test.assertTrue(LocalScanProgress(1, 2) in progress)
            assertEquals(LocalScanProgress(2, 2), progress.lastOrNull())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun rescanningUnchangedMusicKeepsItsCachedCoverAcrossScannerRestart() = runBlocking {
        val directory = Files.createTempDirectory("incremental-local-index")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = Files.copy(fixture, directory.resolve("song.flac")).toFile()
            val png = directory.resolve("cover.png").toFile()
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png)
            org.jaudiotagger.audio.AudioFileIO.read(audio).also {
                it.tag.setField(org.jaudiotagger.tag.images.ArtworkFactory.createArtworkFromFile(png)); it.commit()
            }
            val library = MusicLibrary(store)
            val covers = me.d3sox.nevumi.artwork.ArtworkLibrary(directory.resolve("artwork").toString())
            val first = LocalFiles(library, covers).scan(audio.path).single()
            val cached = Path.of(requireNotNull(covers.path(first.id)))
            Files.setLastModifiedTime(cached, java.nio.file.attribute.FileTime.fromMillis(1_000))
            library.setFavorite(first.id, true)
            val again = LocalFiles(library, covers).scan(audio.path).single()
            assertEquals(first.id, again.id)
            assertEquals(true, again.favorite)
            assertEquals(1_000L, Files.getLastModifiedTime(cached).toMillis(), "An unchanged scan must reuse the indexed cover")
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun includeAndExcludeFoldersApplyToSubfoldersAndCanBeReversedWithoutLosingFavorites() = runBlocking {
        val directory = Files.createTempDirectory("local-folder-rules")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val selected = Files.createDirectory(music.resolve("selected"))
            val excluded = Files.createDirectory(selected.resolve("podcasts"))
            val sibling = Files.createDirectory(selected.resolve("podcasts-music"))
            val elsewhere = Files.createDirectory(music.resolve("elsewhere"))
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            listOf(selected, excluded, sibling, elsewhere).forEach { Files.copy(fixture, it.resolve("song.flac")) }
            val library = MusicLibrary(store)
            val scanner = LocalFiles(library)
            val original = scanner.scan(music.toString())
            val excludedTrack = original.single { it.metadata.folder == excluded.toString() }
            library.setFavorite(excludedTrack.id, true)
            val preferences = me.d3sox.nevumi.settings.MusicPreferences(store)
            preferences.update { it.copy(includeFolders = listOf(selected.toString()), excludeFolders = listOf(excluded.toString())) }
            assertEquals(setOf(selected.toString(), sibling.toString()), scanner.scan(music.toString()).map { it.metadata.folder }.toSet())
            assertEquals(false, library.findTrack(excludedTrack.id)!!.sources.single().available)
            preferences.update { it.copy(includeFolders = emptyList(), excludeFolders = emptyList()) }
            assertEquals(original.map { it.id }.toSet(), scanner.scan(music.toString()).map { it.id }.toSet())
            assertEquals(true, library.findTrack(excludedTrack.id)!!.favorite)
            assertEquals(true, library.findTrack(excludedTrack.id)!!.sources.single().available)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun recentlyAddedUsesTheFilesModificationTimeAndUpdatesOnRescan() = runBlocking {
        val directory = Files.createTempDirectory("recent-file-times")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val newest = Files.copy(fixture, music.resolve("a-newest.flac"))
            val oldest = Files.copy(fixture, music.resolve("z-oldest.flac"))
            Files.setLastModifiedTime(newest, java.nio.file.attribute.FileTime.fromMillis(2_000))
            Files.setLastModifiedTime(oldest, java.nio.file.attribute.FileTime.fromMillis(1_000))
            val library = MusicLibrary(store)
            val scanner = LocalFiles(library)
            scanner.scan(music.toString())
            assertEquals(listOf(newest.toString(), oldest.toString()), library.tracks(LibraryQuery(sort = LibrarySort.RECENT)).map { it.sources.single().location })
            Files.setLastModifiedTime(oldest, java.nio.file.attribute.FileTime.fromMillis(3_000))
            scanner.scan(music.toString())
            assertEquals(listOf(oldest.toString(), newest.toString()), library.tracks(LibraryQuery(sort = LibrarySort.RECENT)).map { it.sources.single().location })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun musicBrainzTagsLinkARealLocalFileToItsIdentifiedServerCopy() = runBlocking {
        val directory = Files.createTempDirectory("identified-local-file")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val file = directory.resolve("song.flac")
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), file)
            val audio = org.jaudiotagger.audio.AudioFileIO.read(file.toFile())
            audio.tag.setField(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_TRACK_ID, "189002e7-3285-4e2e-92a3-7f6c30d407a2")
            audio.tag.setField(org.jaudiotagger.tag.FieldKey.MUSICBRAINZ_RELEASEID, "6e1d48f7-717c-416e-af35-5d2454a13af2")
            audio.commit()
            val library = MusicLibrary(store)
            val server = library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album", 3_000,
                trackNumber = 1, recordingId = "189002e7-3285-4e2e-92a3-7f6c30d407a2", releaseId = "6e1d48f7-717c-416e-af35-5d2454a13af2"),
                TrackSource("server:fixture:song", SourceKind.SERVER, "song"))
            library.setFavorite(server.id, true)
            val local = LocalFiles(library).scan(file.toString()).single()
            assertEquals(server.id, local.id)
            assertEquals(1, library.tracks().size)
            assertEquals(true, local.favorite)
            assertEquals(setOf(SourceKind.LOCAL, SourceKind.SERVER), local.sources.map { it.kind }.toSet())
            val tags = LocalFileEdits(store, library, directory.resolve("edits").toString()).readTags(local.id)
            assertEquals("189002e7-3285-4e2e-92a3-7f6c30d407a2", tags.recordingId)
            assertEquals("6e1d48f7-717c-416e-af35-5d2454a13af2", tags.releaseId)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun selectedMusicFoldersAreRememberedAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("remembered-music-roots")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), music.resolve("song.flac"))
            LocalFiles(MusicLibrary(store)).scan(music.toString())
            store.close()
            store = openMusicStore(database)
            assertEquals(listOf(music.toRealPath().toString()), LocalFiles(MusicLibrary(store)).roots())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun rescanningAFolderMarksDeletedAudioUnavailableAndKeepsItsFavorite() = runBlocking {
        val directory = Files.createTempDirectory("local-folder-reconcile")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val file = music.resolve("Northern Light.flac")
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), file)
            val library = MusicLibrary(store)
            val scanner = LocalFiles(library)
            val original = scanner.scan(music.toString()).single()
            library.setFavorite(original.id, true)
            Files.delete(file)
            scanner.scan(music.toString())
            val missing = library.findTrack(original.id)!!
            assertEquals(null, SourceResolver.resolve(missing.sources, online = false))
            assertEquals(true, missing.favorite)
            assertEquals("Northern Light", missing.metadata.title)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aCorruptAudioFileDoesNotPreventTheRestOfAFolderFromBeingImported() = runBlocking {
        val directory = Files.createTempDirectory("partly-corrupt-library")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            Files.writeString(music.resolve("01 unreadable.flac"), "This is not an audio file")
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), music.resolve("02 valid.flac"))
            val library = MusicLibrary(store)
            val imported = LocalFiles(library).scan(music.toString())
            assertEquals(listOf("Northern Light"), imported.map { it.metadata.title })
            assertEquals(listOf("Northern Light"), library.tracks().map { it.metadata.title })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aRelativeAudioFileCanBeImportedWithItsActualFolder() = runBlocking {
        val directory = Files.createTempDirectory("relative-audio-library")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val audio = Files.createTempFile(Path.of("."), "nevumi-relative-", ".flac")
        try {
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac"), audio,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            val library = MusicLibrary(store)
            LocalFiles(library).scan(audio.fileName.toString())
            val track = library.tracks().single()
            assertEquals("Northern Light", track.metadata.title)
            assertEquals(Path.of(".").toRealPath().toString(), track.metadata.folder)
            assertEquals(audio.toRealPath().toString(), track.sources.single().location)
        } finally { Files.deleteIfExists(audio); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aLocalFolderAddsMp3WavAndFlacWithTheirEmbeddedMetadata() = runBlocking {
        val directory = Files.createTempDirectory("local-library-test")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            LocalFiles(library).scan(Path.of(System.getProperty("nevumi.testRoot"),
                ".test-environment", "music").toString())

            assertEquals(mapOf(
                "Northern Light" to ("Fixture Artist" to "Fixture Album"),
                "Long Way Home" to ("Fixture Artist" to "Fixture Album"),
                "Night Walk" to ("Fixture Artist" to "Fixture Album"),
            ), library.tracks().associate { it.metadata.title to (it.metadata.artist to it.metadata.album) })
            assertEquals(mapOf(
                "Northern Light" to Triple("Fixture Artist", 1, "Fixture Album"),
                "Long Way Home" to Triple("Fixture Artist", 2, "Fixture Album"),
                "Night Walk" to Triple("Fixture Artist", 3, "Fixture Album"),
            ), library.tracks().associate { it.metadata.title to Triple(it.metadata.albumArtist,
                it.metadata.trackNumber, Path.of(it.metadata.folder).fileName.toString()) })
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun rescanningASingleFileDoesNotRegisterItAsALibraryRoot() = runBlocking {
        val directory = Files.createTempDirectory("single-file-rescan")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val song = music.resolve("song.flac")
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac"), song)
            val library = MusicLibrary(store)
            val scanner = LocalFiles(library)
            scanner.scan(music.toString())
            assertEquals("Northern Light", scanner.scan(song.toString(), force = true).single().metadata.title)
            assertEquals(listOf(music.toFile().canonicalPath), library.localRoots())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun scanningAFolderInsideALibraryRootIndexesItWithoutAddingAnotherRoot() = runBlocking {
        val directory = Files.createTempDirectory("nested-root")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val album = Files.createDirectory(music.resolve("Album"))
            val library = MusicLibrary(store)
            val scanner = LocalFiles(library)
            scanner.scan(music.toString())
            Files.copy(Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "fixture.flac"), album.resolve("song.flac"))
            assertEquals("Northern Light", scanner.scan(album.toString()).single().metadata.title)
            // Rescans walk every root, so a folder inside one would be scanned twice.
            assertEquals(listOf(music.toFile().canonicalPath), library.localRoots())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
