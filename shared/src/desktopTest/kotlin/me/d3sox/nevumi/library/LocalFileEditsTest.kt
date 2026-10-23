package me.d3sox.nevumi.library

import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LocalFileEditsTest {
    @Test
    fun tagsCanBeReadFromMusicInAReadOnlyFolder() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-read-only-tags")
        val music = Files.createDirectory(directory.resolve("music"))
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val song = Files.copy(original, music.resolve("song.flac"))
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(song.toString()).single()
            Files.setPosixFilePermissions(music, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"))
            val tags = LocalFileEdits(store, library, directory.resolve("private-backups").toString()).readTags(track.id)
            assertEquals("Northern Light", tags.title)
            assertEquals("Fixture Artist", tags.artist)
            assertEquals(-1L, Files.mismatch(original, song))
        } finally {
            Files.setPosixFilePermissions(music, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            store.close(); directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun linkingAnEditedLocalCopyToAServerSongKeepsItsFileUndoAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("linked-file-undo")
        val database = directory.resolve("library.db").toString()
        var store = openMusicStore(database)
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val local = LocalFiles(library).scan(file.toString()).single()
            val server = library.importTrack(local.metadata, TrackSource("server:fixture:song", SourceKind.SERVER, "song"))
            val edit = LocalFileEdits(store, library, directory.resolve("edits").toString()).rename(local.id, "renamed.flac")
            library.linkTracks(server.id, local.id)
            store.close(); store = openMusicStore(database)
            val reopened = MusicLibrary(store)
            val edits = LocalFileEdits(store, reopened, directory.resolve("edits").toString())
            edits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
            assertEquals(server.id, edits.history().single().trackId)
            assertEquals(listOf(server.id), reopened.tracks().map { it.id })
            assertEquals(setOf(SourceKind.LOCAL, SourceKind.SERVER), reopened.findTrack(server.id)!!.sources.map { it.kind }.toSet())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aChangedFileAfterAnInterruptedRenameCanBeReviewedAndRestoredAtItsOriginalName() = runBlocking {
        val directory = Files.createTempDirectory("changed-interrupted-rename")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            val renamed = directory.resolve("renamed.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            library.setFavorite(track.id, true)
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun rename(location: String, name: String) {
                    FileSystemEditAccess.rename(location, name)
                    val audio = org.jaudiotagger.audio.AudioFileIO.read(renamed.toFile())
                    audio.tag.setField(org.jaudiotagger.tag.FieldKey.TITLE, "External change")
                    audio.commit()
                    throw SimulatedProcessExit()
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = provider)
            assertFailsWith<SimulatedProcessExit> { edits.rename(track.id, "renamed.flac") }
            val changedCopy = directory.resolve("external-copy.flac")
            Files.copy(renamed, changedCopy)
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val reopened = MusicLibrary(store)
            val recovered = LocalFileEdits(store, reopened, directory.resolve("edits").toString())
            recovered.recover()
            val edit = recovered.history().single()
            assertEquals(true, edit.needsRecovery)
            assertEquals(-1L, Files.mismatch(changedCopy, renamed))
            recovered.restoreOriginal(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
            assertEquals(false, Files.exists(renamed))
            assertEquals(listOf(track.id), reopened.tracks().map { it.id })
            assertEquals(true, reopened.findTrack(track.id)!!.favorite)
            assertEquals(file.toString(), reopened.findTrack(track.id)!!.sources.single().location)
            assertEquals(true, recovered.history().single().undone)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anInterruptedRestorationDoesNotClaimAnUnusedFileNameAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("unused-restoration-name")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            val restored = directory.resolve("restored.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            library.setFavorite(track.id, true)
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun restoreDeleted(location: String, original: File, name: String, folder: String,
                    reserve: suspend (String) -> Unit): String {
                    reserve(restored.toString())
                    throw SimulatedProcessExit()
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = provider)
            val edit = edits.delete(track.id)
            assertFailsWith<SimulatedProcessExit> { edits.undo(edit.id) }
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val reopened = MusicLibrary(store)
            val recovered = LocalFileEdits(store, reopened, directory.resolve("edits").toString())
            recovered.recover()
            Files.copy(original, restored)
            val newTrack = LocalFiles(reopened).scan(restored.toString()).single()
            assertEquals(2, reopened.tracks().size)
            assertEquals(false, newTrack.favorite)
            assertEquals(false, reopened.findTrack(track.id)!!.sources.single().available)
            assertEquals(file.toString(), reopened.findTrack(track.id)!!.sources.single().location)
            assertEquals("", recovered.history().single().destinationPath)
            recovered.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aRejectedRenameDoesNotForgetAnotherSongsIdentityDuringRecovery() = runBlocking {
        val directory = Files.createTempDirectory("rejected-rename-source-owner")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val music = Files.createDirectory(directory.resolve("music"))
            val firstFile = music.resolve("first.flac")
            val otherFile = music.resolve("other.flac")
            Files.copy(original, firstFile); Files.copy(original, otherFile)
            val library = MusicLibrary(store)
            val tracks = LocalFiles(library).scan(music.toString())
            val first = tracks.single { it.sources.single().location == firstFile.toString() }
            val other = tracks.single { it.sources.single().location == otherFile.toString() }
            library.setFavorite(other.id, true)
            Files.delete(otherFile)
            LocalFiles(library).scan(music.toString())
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            assertFailsWith<IllegalStateException> { edits.rename(first.id, "other.flac") }
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val reopened = MusicLibrary(store)
            LocalFileEdits(store, reopened, directory.resolve("edits").toString()).recover()
            Files.copy(original, otherFile)
            LocalFiles(reopened).scan(music.toString())
            assertEquals(setOf(first.id, other.id), reopened.tracks().map { it.id }.toSet())
            assertEquals(true, reopened.findTrack(other.id)!!.favorite)
            assertEquals(true, reopened.findTrack(other.id)!!.sources.single().available)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aPartialRestorationAtANewLocationCanBeReviewedAndRepairedAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("partial-recreated-music")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            val restored = directory.resolve("restored.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun restoreDeleted(location: String, original: File, name: String, folder: String,
                    reserve: suspend (String) -> Unit): String {
                    reserve(restored.toString())
                    original.inputStream().use { Files.write(restored, it.readNBytes(128)) }
                    throw SimulatedProcessExit()
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = provider)
            val edit = edits.delete(track.id)
            assertFailsWith<SimulatedProcessExit> { edits.undo(edit.id) }
            val partialCopy = directory.resolve("partial-copy.flac")
            Files.copy(restored, partialCopy)
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val reopened = MusicLibrary(store)
            val recovered = LocalFileEdits(store, reopened, directory.resolve("edits").toString())
            recovered.recover()
            assertEquals(true, recovered.history().single().needsRecovery)
            assertEquals(-1L, Files.mismatch(partialCopy, restored))
            recovered.restoreOriginal(edit.id)
            assertEquals(-1L, Files.mismatch(original, restored))
            assertEquals(true, recovered.history().single().undone)
            assertEquals(false, recovered.history().single().needsRecovery)
            assertEquals(restored.toString(), reopened.findTrack(track.id)!!.sources.single().location)
            assertEquals(true, reopened.findTrack(track.id)!!.sources.single().available)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aRestorationAtANewLocationIsFinishedAfterAnInterruptedUndoAndRestart() = runBlocking {
        val directory = Files.createTempDirectory("interrupted-recreated-music")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            val restored = directory.resolve("restored.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            library.setFavorite(track.id, true)
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun restoreDeleted(location: String, original: File, name: String, folder: String,
                    reserve: suspend (String) -> Unit): String {
                    reserve(restored.toString())
                    Files.copy(original.toPath(), restored)
                    throw SimulatedProcessExit()
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = provider)
            val edit = edits.delete(track.id)
            assertFailsWith<SimulatedProcessExit> { edits.undo(edit.id) }
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val reopened = MusicLibrary(store)
            val recovered = LocalFileEdits(store, reopened, directory.resolve("edits").toString())
            recovered.recover()
            assertEquals(true, recovered.history().single().undone)
            assertEquals(listOf(track.id), reopened.tracks().map { it.id })
            assertEquals(restored.toString(), reopened.findTrack(track.id)!!.sources.single().location)
            assertEquals(true, reopened.findTrack(track.id)!!.favorite)
            assertEquals(true, reopened.findTrack(track.id)!!.sources.single().available)
            assertEquals(-1L, Files.mismatch(original, restored))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun expiredUndoHistoryIsCleanedWhenThePlayerRestartsWithoutChangingItsMusic() = runBlocking {
        val directory = Files.createTempDirectory("undo-retention-restart")
        var store = openMusicStore(directory.resolve("library.db").toString())
        var player: me.d3sox.nevumi.app.MusicController? = null
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            library.setFavorite(track.id, true)
            val edit = LocalFileEdits(store, library, directory.resolve("edits").toString(), now = { System.currentTimeMillis() - 40 * 86_400_000L })
                .editTags(track.id, track.metadata.copy(title = "Keep my music"))
            val editedCopy = directory.resolve("edited-copy.flac")
            Files.copy(file, editedCopy)
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val restoredLibrary = MusicLibrary(store)
            val controller = me.d3sox.nevumi.app.MusicController(restoredLibrary,
                me.d3sox.nevumi.queue.PlaybackQueue(store), me.d3sox.nevumi.playback.MpvPlaybackEngine("null"),
                preferences = me.d3sox.nevumi.settings.MusicPreferences(store),
                fileEdits = LocalFileEdits(store, restoredLibrary, directory.resolve("edits").toString()))
            player = controller
            withTimeout(3_000) { controller.state.first { !it.loading } }
            withTimeout(3_000) { while (Files.exists(Path.of(edit.backupPath))) delay(10) }
            assertEquals(emptyList(), controller.state.value.fileEdits)
            kotlin.test.assertFalse(Files.exists(Path.of(edit.backupPath)))
            assertEquals(true, controller.state.value.tracks.single().favorite)
            assertEquals(-1L, Files.mismatch(editedCopy, file))
        } finally { player?.closeAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aBackupSizeLimitDropsOlderUndoButKeepsTheMostRecentEditRestorable() = runBlocking {
        val directory = Files.createTempDirectory("undo-backup-byte-limit")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            var time = 100L
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), now = { time })
            val first = edits.editTags(track.id, track.metadata.copy(title = "First edit"))
            val firstVersion = directory.resolve("first-version.flac")
            Files.copy(file, firstVersion)
            time = 200
            val second = edits.editTags(track.id, track.metadata.copy(title = "Second edit"))
            edits.trimHistory(maxAgeMs = 1_000, maxBytes = 1)
            assertEquals(listOf(second.id), edits.history().map { it.id })
            kotlin.test.assertFalse(Files.exists(Path.of(first.backupPath)))
            edits.undo(second.id)
            assertEquals(-1L, Files.mismatch(firstVersion, file))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun trimmingHistoryRemovesAnAbandonedPreparationBackupAndKeepsUnrelatedFiles() = runBlocking {
        val directory = Files.createTempDirectory("abandoned-edit-backup")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            Files.copy(original, file)
            val backupDirectory = Files.createDirectory(directory.resolve("edits"))
            val unrelated = backupDirectory.resolve("keep.flac")
            Files.writeString(unrelated, "Unrelated file")
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            var abandoned: File? = null
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override fun image(location: String, destination: File) {
                    FileSystemEditAccess.image(location, destination)
                    abandoned = destination
                    throw SimulatedProcessExit()
                }
            }
            val edits = LocalFileEdits(store, library, backupDirectory.toString(), files = provider)
            assertFailsWith<SimulatedProcessExit> { edits.prepareTags(track.id, track.metadata.copy(title = "Prepared edit")) }
            edits.trimHistory(maxAgeMs = 0, maxBytes = 0)
            kotlin.test.assertFalse(requireNotNull(abandoned).exists())
            assertEquals("Unrelated file", Files.readString(unrelated))
            assertEquals(-1L, Files.mismatch(original, file))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun expiredUndoBackupsCanBeRemovedWithoutLosingAPreparedEditOrChangingMusic() = runBlocking {
        val directory = Files.createTempDirectory("retain-prepared-file-edit")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val music = Files.createDirectory(directory.resolve("music"))
            for (name in listOf("first.flac", "second.flac", "prepared.flac")) Files.copy(original, music.resolve(name))
            val library = MusicLibrary(store)
            val tracks = LocalFiles(library).scan(music.toString()).associateBy { Path.of(it.sources.single().location).fileName.toString() }
            var time = 100L
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), now = { time })
            val first = edits.editTags(tracks.getValue("first.flac").id, tracks.getValue("first.flac").metadata.copy(title = "First edit"))
            time = 200
            val second = edits.editTags(tracks.getValue("second.flac").id, tracks.getValue("second.flac").metadata.copy(title = "Second edit"))
            time = 300
            val pending = edits.prepareTags(tracks.getValue("prepared.flac").id, tracks.getValue("prepared.flac").metadata.copy(title = "Prepared edit"))
            val editedBeforeTrim = directory.resolve("edited-before-trim.flac")
            Files.copy(music.resolve("first.flac"), editedBeforeTrim)
            time = 1_000
            edits.trimHistory(maxAgeMs = 100, maxBytes = 0)
            assertEquals(listOf(pending.id), edits.history().map { it.id })
            kotlin.test.assertFalse(Files.exists(Path.of(first.backupPath)))
            kotlin.test.assertFalse(Files.exists(Path.of(second.backupPath)))
            kotlin.test.assertTrue(Files.exists(Path.of(pending.backupPath)))
            kotlin.test.assertTrue(Files.exists(Path.of(pending.stagedPath)))
            assertEquals(-1L, Files.mismatch(editedBeforeTrim, music.resolve("first.flac")))
            edits.apply(pending.id); edits.undo(pending.id)
            assertEquals(-1L, Files.mismatch(original, music.resolve("prepared.flac")))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun recoveryDuringAnActiveRenameDoesNotReleaseItsLibraryIdentity() = runBlocking {
        val directory = Files.createTempDirectory("recover-during-rename")
        val store = openMusicStore(directory.resolve("library.db").toString())
        val released = CompletableDeferred<Unit>()
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val music = Files.createDirectory(directory.resolve("music"))
            Files.copy(original, music.resolve("song.flac"))
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(music.toString()).single()
            library.setFavorite(track.id, true)
            val moving = CompletableDeferred<Unit>()
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun rename(location: String, name: String) {
                    moving.complete(Unit); released.await()
                    FileSystemEditAccess.rename(location, name)
                    LocalFiles(library).scan(music.toString())
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = provider)
            val rename = async(Dispatchers.Default) { edits.rename(track.id, "Renamed.flac") }
            withTimeout(3_000) { moving.await() }
            val recovery = async(Dispatchers.Default) { edits.recover() }
            withTimeoutOrNull(250) { recovery.await() }
            released.complete(Unit)
            withTimeout(3_000) { rename.await(); recovery.await() }
            assertEquals(listOf(track.id), library.tracks().map { it.id })
            assertEquals(true, library.tracks().single().favorite)
            assertEquals(false, edits.history().single().undone)
        } finally { released.complete(Unit); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aBulkEditChangesOnlyChosenTagsWhenTheLocalFileIsLinkedToDifferentServerMetadata() = runBlocking {
        val directory = Files.createTempDirectory("edit-linked-local-tags")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val server = library.importTrack(TrackMetadata("Server title", "Server artist", "Server album"),
                TrackSource("server:fixture:song", SourceKind.SERVER, "song"))
            library.importTrack(TrackMetadata("Northern Light", "Fixture Artist", "Fixture Album"),
                TrackSource("local:$file", SourceKind.LOCAL, file.toString()), linkTo = server.id)
            library.setFavorite(server.id, true)
            LocalFileEdits(store, library, directory.resolve("edits").toString()).editTags(listOf(server.id), TagChanges(album = "Changed album"))
            val tag = org.jaudiotagger.audio.AudioFileIO.read(file.toFile()).tag
            assertEquals("Changed album", tag.getFirst(org.jaudiotagger.tag.FieldKey.ALBUM))
            assertEquals("Northern Light", tag.getFirst(org.jaudiotagger.tag.FieldKey.TITLE))
            assertEquals("Fixture Artist", tag.getFirst(org.jaudiotagger.tag.FieldKey.ARTIST))
            assertEquals("2024", tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR))
            assertEquals(true, library.findTrack(server.id)!!.favorite)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aBulkYearEditUpdatesRealTagsInAllSupportedFormatsAndCanBeUndone() = runBlocking {
        val directory = Files.createTempDirectory("edit-music-year")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album")
            val music = Files.createDirectory(directory.resolve("music"))
            val names = listOf("01 Northern Light.flac", "02 Long Way Home.mp3", "03 Night Walk.wav")
            names.forEach { Files.copy(fixtures.resolve(it), music.resolve(it)) }
            val library = MusicLibrary(store)
            val tracks = LocalFiles(library).scan(music.toString())
            val before = names.associateWith { decodedAudioHash(music.resolve(it)) }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val changed = edits.editTags(tracks.map { it.id }, TagChanges(year = 2026))
            names.forEach { name ->
                val audio = org.jaudiotagger.audio.AudioFileIO.read(music.resolve(name).toFile())
                val container = audio.tag
                val tag = if (container is org.jaudiotagger.tag.wav.WavTag) container.getID3Tag() else container
                assertEquals("2026", tag.getFirst(org.jaudiotagger.tag.FieldKey.YEAR))
                assertEquals(before.getValue(name), decodedAudioHash(music.resolve(name)))
            }
            assertEquals(listOf(2026, 2026, 2026), library.tracks().map { it.metadata.year })
            changed.reversed().forEach { edits.undo(it.id) }
            names.forEach { assertEquals(-1L, Files.mismatch(fixtures.resolve(it), music.resolve(it))) }
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aDeletionInterruptedAfterRemovingTheFileIsRecognizedAndCanBeUndoneAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("interrupted-file-deletion")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun delete(location: String) {
                    FileSystemEditAccess.delete(location)
                    throw SimulatedProcessExit()
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = provider)
            assertFailsWith<SimulatedProcessExit> { edits.delete(track.id) }
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val restoredLibrary = MusicLibrary(store)
            val restoredEdits = LocalFileEdits(store, restoredLibrary, directory.resolve("edits").toString())
            restoredEdits.recover()
            val edit = restoredEdits.history().single()
            assertEquals(true, edit.applied)
            assertEquals(false, edit.needsRecovery)
            assertEquals(false, restoredLibrary.findTrack(track.id)!!.sources.single().available)
            restoredEdits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun deletedMusicCanBeRestoredAfterRestartWithItsFavoriteQueueAndPlaylistIntact() = runBlocking {
        val directory = Files.createTempDirectory("delete-local-music")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album")
            val music = Files.createDirectory(directory.resolve("music"))
            val names = listOf("01 Northern Light.flac", "02 Long Way Home.mp3", "03 Night Walk.wav")
            names.forEach { Files.copy(fixtures.resolve(it), music.resolve(it)) }
            val library = MusicLibrary(store)
            val tracks = LocalFiles(library).scan(music.toString())
            tracks.forEach { library.setFavorite(it.id, true) }
            val queue = me.d3sox.nevumi.queue.PlaybackQueue(store)
            queue.replace(tracks.map { it.id }); queue.setPosition(1_200)
            val playlists = me.d3sox.nevumi.playlists.Playlists(store)
            val playlist = playlists.create("My music", tracks.map { it.id })
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val deleted = tracks.map { edits.delete(it.id) }
            names.forEach { kotlin.test.assertFalse(Files.exists(music.resolve(it))) }
            kotlin.test.assertTrue(library.tracks().all { it.sources.none { source -> source.available } })
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val restoredLibrary = MusicLibrary(store)
            val restoredEdits = LocalFileEdits(store, restoredLibrary, directory.resolve("edits").toString())
            restoredEdits.recover()
            deleted.reversed().forEach { restoredEdits.undo(it.id) }
            names.forEach { assertEquals(-1L, Files.mismatch(fixtures.resolve(it), music.resolve(it))) }
            assertEquals(tracks.map { it.id }.toSet(), restoredLibrary.tracks().map { it.id }.toSet())
            kotlin.test.assertTrue(restoredLibrary.tracks().all { it.favorite && it.sources.single().available })
            val restoredQueue = me.d3sox.nevumi.queue.PlaybackQueue(store).snapshot()
            assertEquals(tracks.map { it.id }, restoredQueue.entries.map { it.trackId })
            assertEquals(1_200L, restoredQueue.positionMs)
            assertEquals(tracks.map { it.id }, me.d3sox.nevumi.playlists.Playlists(store).get(playlist.id)!!.trackIds)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aNewFileAtTheOldNameDoesNotTakeOverTheRenamedSongAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("reuse-renamed-path")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album")
            val music = Files.createDirectory(directory.resolve("music"))
            val file = music.resolve("song.flac")
            Files.copy(fixtures.resolve("01 Northern Light.flac"), file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(music.toString()).single()
            library.setFavorite(track.id, true)
            LocalFileEdits(store, library, directory.resolve("edits").toString()).rename(track.id, "Renamed.flac")
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val restarted = MusicLibrary(store)
            Files.copy(fixtures.resolve("01 Northern Light.flac"), file)
            LocalFiles(restarted).scan(music.toString())
            val renamed = restarted.findTrack(track.id)!!
            assertEquals(listOf(music.resolve("Renamed.flac").toString()), renamed.sources.map { it.location })
            assertEquals(true, renamed.favorite)
            val replacement = restarted.tracks().single { it.sources.any { source -> source.location == file.toString() } }
            kotlin.test.assertNotEquals(track.id, replacement.id)
            assertEquals(false, replacement.favorite)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aFolderRefreshDuringRenameKeepsOneSongAndItsFavorite() = runBlocking {
        val directory = Files.createTempDirectory("rename-with-folder-watcher")
        val store = openMusicStore(directory.resolve("library.db").toString())
        var observer: Job? = null
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val music = Files.createDirectory(directory.resolve("music"))
            Files.copy(original, music.resolve("song.flac"))
            val library = MusicLibrary(store)
            val watching = WatchingLocalFiles(library)
            val track = watching.scan(music.toString()).single()
            library.setFavorite(track.id, true)
            val ready = CompletableDeferred<Unit>()
            val refreshedAfterMove = CompletableDeferred<Unit>()
            observer = launch(Dispatchers.Default) {
                watching.changes.collect {
                    watching.scan(music.toString())
                    ready.complete(Unit)
                    if (Files.exists(music.resolve("Renamed.flac"))) refreshedAfterMove.complete(Unit)
                }
            }
            // A clean folder emits changes only after an actual filesystem event.
            val probe = music.resolve("watcher-ready.txt")
            withTimeout(3_000) {
                while (!ready.isCompleted) {
                    Files.deleteIfExists(probe)
                    Files.writeString(probe, "ready")
                    delay(50)
                }
            }
            Files.deleteIfExists(probe)
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun rename(location: String, name: String) {
                    FileSystemEditAccess.rename(location, name)
                    withTimeout(3_000) { refreshedAfterMove.await() }
                }
            }
            LocalFileEdits(store, library, directory.resolve("edits").toString(), files = provider).rename(track.id, "Renamed.flac")
            assertEquals(1, library.tracks().size)
            assertEquals(track.id, library.tracks().single().id)
            assertEquals(true, library.tracks().single().favorite)
            assertEquals(1, library.tracks().single().sources.size)
        } finally { observer?.cancelAndJoin(); store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anUndoInterruptedBeforeRenamingKeepsTheNewNameAndCanBeRetriedAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("interrupted-rename-undo")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(file.toString()).single()
            val interruptUndo = AtomicBoolean(false)
            val provider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun rename(location: String, name: String) {
                    if (interruptUndo.get()) throw SimulatedProcessExit()
                    FileSystemEditAccess.rename(location, name)
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = provider)
            val edit = edits.rename(track.id, "Renamed.flac")
            interruptUndo.set(true)
            assertFailsWith<SimulatedProcessExit> { edits.undo(edit.id) }
            store.close(); store = openMusicStore(directory.resolve("library.db").toString())
            val restoredLibrary = MusicLibrary(store)
            val restoredEdits = LocalFileEdits(store, restoredLibrary, directory.resolve("edits").toString())
            restoredEdits.recover()
            assertEquals(directory.resolve("Renamed.flac").toString(), restoredLibrary.findTrack(track.id)!!.sources.single().location)
            assertEquals(false, restoredEdits.history().single().writing)
            restoredEdits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun renamingAMusicFilePreservesItsLibraryQueuePlaylistAndFavoriteAcrossRestartAndUndo() = runBlocking {
        val directory = Files.createTempDirectory("rename-local-music")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val music = Files.createDirectory(directory.resolve("music"))
            val file = music.resolve("song.flac")
            Files.copy(original, file)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(music.toString()).single()
            library.setFavorite(track.id, true)
            val queue = me.d3sox.nevumi.queue.PlaybackQueue(store)
            queue.replace(listOf(track.id)); queue.setPosition(1_200)
            val playlists = me.d3sox.nevumi.playlists.Playlists(store)
            val playlist = playlists.create("My music", listOf(track.id))
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val edit = edits.rename(track.id, "A renamed song.flac")
            kotlin.test.assertFalse(Files.exists(file))
            assertEquals(-1L, Files.mismatch(original, music.resolve("A renamed song.flac")))
            assertEquals(track.id, library.tracks().single().id)
            assertEquals(true, library.findTrack(track.id)!!.favorite)
            assertEquals(music.resolve("A renamed song.flac").toString(), library.findTrack(track.id)!!.sources.single().location)
            store.close()
            store = openMusicStore(directory.resolve("library.db").toString())
            val restoredLibrary = MusicLibrary(store)
            val restoredEdits = LocalFileEdits(store, restoredLibrary, directory.resolve("edits").toString())
            restoredEdits.recover()
            LocalFiles(restoredLibrary).scan(music.toString())
            assertEquals(track.id, restoredLibrary.tracks().single().id)
            assertEquals(1_200L, me.d3sox.nevumi.queue.PlaybackQueue(store).snapshot().positionMs)
            assertEquals(listOf(track.id), me.d3sox.nevumi.playlists.Playlists(store).get(playlist.id)!!.trackIds)
            restoredEdits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, file))
            kotlin.test.assertFalse(Files.exists(music.resolve("A renamed song.flac")))
            assertEquals(track.metadata, restoredLibrary.findTrack(track.id)!!.metadata)
            assertEquals(true, restoredLibrary.findTrack(track.id)!!.favorite)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun undoingAnArtworkAdditionAlsoRemovesItsCachedCover() = runBlocking {
        val directory = Files.createTempDirectory("undo-added-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val library = MusicLibrary(store)
            val artwork = me.d3sox.nevumi.artwork.ArtworkLibrary(directory.resolve("artwork").toString())
            val sources = LocalFiles(library, artwork)
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val file = directory.resolve("song.flac")
            Files.copy(original, file)
            val track = sources.scan(file.toString()).single()
            kotlin.test.assertNull(artwork.path(track.id))
            val image = Files.readAllBytes(Path.of(System.getProperty("nevumi.testRoot"), "androidApp", "src", "androidTest", "assets", "cover.png"))
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), localSources = sources)
            val edit = edits.replaceArtwork(track.id, image)
            kotlin.test.assertTrue(artwork.read(track.id)!!.contentEquals(image))
            edits.undo(edit.id)
            kotlin.test.assertNull(artwork.path(track.id))
            assertEquals(-1L, Files.mismatch(original, file))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun replacingEmbeddedArtworkPreservesTagsAndAudioAndCanRestoreTheOriginalFile() = runBlocking {
        val directory = Files.createTempDirectory("replace-file-artwork")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val image = java.awt.image.BufferedImage(48, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (x in 0..47) for (y in 0..47) image.setRGB(x, y, 0x2266CC)
            val output = java.io.ByteArrayOutputStream()
            javax.imageio.ImageIO.write(image, "png", output)
            val artwork = output.toByteArray()
            val library = MusicLibrary(store)
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music", "Fixture Artist", "Fixture Album")
            for (name in listOf("01 Northern Light.flac", "02 Long Way Home.mp3", "03 Night Walk.wav")) {
                val original = fixtures.resolve(name)
                val file = directory.resolve(name)
                Files.copy(original, file)
                val track = LocalFiles(library).scan(file.toString()).single()
                val hash = decodedAudioHash(file)
                val edit = edits.replaceArtwork(track.id, artwork)
                val container = org.jaudiotagger.audio.AudioFileIO.read(file.toFile()).tag
                val tag = if (container is org.jaudiotagger.tag.wav.WavTag) container.getID3Tag() else container
                kotlin.test.assertTrue(tag.firstArtwork.binaryData.contentEquals(artwork))
                assertEquals(track.metadata, library.findTrack(track.id)!!.metadata)
                assertEquals(hash, decodedAudioHash(file))
                edits.undo(edit.id)
                assertEquals(-1L, Files.mismatch(original, file))
            }
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aMissingFileInABulkEditDoesNotPreventOtherSongsBeingSavedAndUndone() = runBlocking {
        val directory = Files.createTempDirectory("bulk-missing-file")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val fixture = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val library = MusicLibrary(store)
            val tracks = (1..3).map { index ->
                val file = directory.resolve("song-$index.flac")
                Files.copy(fixture, file)
                LocalFiles(library).scan(file.toString()).single()
            }
            Files.delete(Path.of(tracks[1].sources.single().location))
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val error = assertFailsWith<Exception> { edits.editTags(tracks.map { it.id }, TagChanges(album = "Batch album")) }
            assertEquals("Batch album", library.findTrack(tracks[0].id)!!.metadata.album)
            assertEquals("Batch album", library.findTrack(tracks[2].id)!!.metadata.album)
            kotlin.test.assertTrue(error.message.orEmpty().contains("song-2.flac"))
            assertEquals(2, edits.history().count { it.applied })
            for (edit in edits.history().asReversed()) edits.undo(edit.id)
            assertEquals("Fixture Album", library.findTrack(tracks[0].id)!!.metadata.album)
            assertEquals("Fixture Album", library.findTrack(tracks[2].id)!!.metadata.album)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun bulkAlbumAndGenreEditsPreserveEachSongsTitleArtistAudioAndFavorite() = runBlocking {
        val directory = Files.createTempDirectory("bulk-local-tags")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val fixtures = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album")
            val music = Files.createDirectory(directory.resolve("music"))
            for (name in listOf("01 Northern Light.flac", "02 Long Way Home.mp3")) Files.copy(fixtures.resolve(name), music.resolve(name))
            val library = MusicLibrary(store)
            val tracks = LocalFiles(library).scan(music.toString())
            library.setFavorite(tracks.first().id, true)
            val before = tracks.associate { it.id to decodedAudioHash(Path.of(it.sources.single().location)) }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val changed = edits.editTags(tracks.map { it.id }, TagChanges(album = "Compilation", genres = listOf("Jazz")))
            assertEquals(2, changed.size)
            val after = library.tracks().associateBy { it.id }
            for (track in tracks) {
                assertEquals(track.metadata.copy(album = "Compilation", genres = listOf("Jazz")), after.getValue(track.id).metadata)
                assertEquals(before.getValue(track.id), decodedAudioHash(Path.of(track.sources.single().location)))
            }
            assertEquals(true, after.getValue(tracks.first().id).favorite)
            for (edit in changed.asReversed()) edits.undo(edit.id)
            for (track in tracks) assertEquals(track.metadata, library.findTrack(track.id)!!.metadata)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun recoveryRecognizesAnUndoThatFinishedBeforeItsCompletionWasRecorded() = runBlocking {
        val directory = Files.createTempDirectory("interrupted-completed-undo")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            val exitAfterWrite = AtomicBoolean(false)
            val interruptedProvider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun replace(location: String, staged: File, originalHash: String?) {
                    FileSystemEditAccess.replace(location, staged)
                    if (exitAfterWrite.get()) throw SimulatedProcessExit()
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = interruptedProvider)
            val edit = edits.editTags(track.id, track.metadata.copy(title = "Temporary edit"))
            exitAfterWrite.set(true)
            assertFailsWith<SimulatedProcessExit> { edits.undo(edit.id) }
            store.close()
            store = openMusicStore(directory.resolve("library.db").toString())
            val reopenedLibrary = MusicLibrary(store)
            val reopenedEdits = LocalFileEdits(store, reopenedLibrary, directory.resolve("edits").toString())
            reopenedEdits.recover()
            assertEquals(true, reopenedEdits.history().single().undone)
            assertEquals("Northern Light", reopenedLibrary.findTrack(track.id)!!.metadata.title)
            assertEquals(-1L, Files.mismatch(original, audio))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun anInterruptedProviderWriteIsReportedAfterRestartAndCanRestoreTheVerifiedOriginal() = runBlocking {
        val directory = Files.createTempDirectory("interrupted-provider-write")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            val interruptedProvider = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun replace(location: String, staged: File, originalHash: String?) {
                    Files.write(Path.of(location), staged.inputStream().use { it.readNBytes(128) })
                    throw SimulatedProcessExit()
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = interruptedProvider)
            val edit = edits.prepareTags(track.id, track.metadata.copy(title = "Interrupted title"))
            assertFailsWith<SimulatedProcessExit> { edits.apply(edit.id) }
            val interruptedCopy = directory.resolve("interrupted.part")
            Files.copy(audio, interruptedCopy)
            store.close()
            store = openMusicStore(directory.resolve("library.db").toString())
            val reopenedLibrary = MusicLibrary(store)
            val reopenedEdits = LocalFileEdits(store, reopenedLibrary, directory.resolve("edits").toString())
            reopenedEdits.recover()
            assertEquals(true, reopenedEdits.history().single().needsRecovery)
            assertEquals(-1L, Files.mismatch(interruptedCopy, audio))
            reopenedEdits.restoreOriginal(edit.id)
            assertEquals(-1L, Files.mismatch(original, audio))
            assertEquals("Northern Light", reopenedLibrary.findTrack(track.id)!!.metadata.title)
            assertEquals(false, reopenedEdits.history().single().needsRecovery)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aFailedUndoRestoresTheEditedVersionAndCanBeRetried() = runBlocking {
        val directory = Files.createTempDirectory("failed-file-undo")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            val failNextWrite = AtomicBoolean(false)
            val failingFileSystem = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun replace(location: String, staged: File, originalHash: String?) {
                    if (failNextWrite.compareAndSet(true, false)) {
                        Files.writeString(Path.of(location), "An interrupted undo write")
                        throw IOException("The provider stopped undoing")
                    }
                    FileSystemEditAccess.replace(location, staged)
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = failingFileSystem)
            val edit = edits.editTags(track.id, track.metadata.copy(title = "Keep this edit"))
            val editedCopy = directory.resolve("edited-copy.flac")
            Files.copy(audio, editedCopy)
            failNextWrite.set(true)
            assertFailsWith<IOException> { edits.undo(edit.id) }
            assertEquals(-1L, Files.mismatch(editedCopy, audio))
            assertEquals(false, edits.history().single().undone)
            edits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, audio))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aFailedWriteRestoresTheOriginalFileAndKeepsThePreparedEditRetryable() = runBlocking {
        val directory = Files.createTempDirectory("failed-file-write")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            val failed = AtomicBoolean(false)
            val failingFileSystem = object : FileEditAccess by FileSystemEditAccess {
                override suspend fun replace(location: String, staged: File, originalHash: String?) {
                    if (failed.compareAndSet(false, true)) {
                        Files.writeString(Path.of(location), "An interrupted provider write")
                        throw IOException("The provider stopped writing")
                    }
                    FileSystemEditAccess.replace(location, staged)
                }
            }
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), files = failingFileSystem)
            val edit = edits.prepareTags(track.id, track.metadata.copy(title = "Retryable title"))
            assertFailsWith<IOException> { edits.apply(edit.id) }
            assertEquals(-1L, Files.mismatch(original, audio))
            assertEquals(false, edits.history().single().applied)
            edits.apply(edit.id)
            assertEquals("Retryable title", library.findTrack(track.id)!!.metadata.title)
            edits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, audio))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun consecutiveFileEditsAreListedInCreationOrderAndCanBeUndoneInReverseOrder() = runBlocking {
        val directory = Files.createTempDirectory("ordered-file-edits")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            var time = 100L
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString(), now = { time++ })
            val first = edits.editTags(track.id, track.metadata.copy(title = "First title"))
            val second = edits.editTags(track.id, track.metadata.copy(title = "Second title"))
            assertEquals(listOf(first.id, second.id), edits.history().map { it.id })
            assertEquals(listOf(100L, 101L), edits.history().map { it.createdAt })
            edits.undo(second.id)
            assertEquals("First title", library.findTrack(track.id)!!.metadata.title)
            edits.undo(first.id)
            assertEquals(-1L, Files.mismatch(original, audio))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun undoRefusesADamagedBackupAndLeavesTheEditedSongPlayable() = runBlocking {
        val directory = Files.createTempDirectory("damaged-edit-backup")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val edit = edits.editTags(track.id, track.metadata.copy(title = "Edited title"))
            val before = decodedAudioHash(audio)
            Files.writeString(Path.of(edit.backupPath), "Damaged backup")
            assertFailsWith<IllegalStateException> { edits.undo(edit.id) }
            assertEquals(before, decodedAudioHash(audio))
            assertEquals("Edited title", library.findTrack(track.id)!!.metadata.title)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun recoveryFinishesAnEditWhenTheFileChangedBeforeItsCompletionWasRecorded() = runBlocking {
        val directory = Files.createTempDirectory("interrupted-tag-edit")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            val edit = LocalFileEdits(store, library, directory.resolve("edits").toString())
                .prepareTags(track.id, track.metadata.copy(title = "Recovered title"))
            // Reproduce a process exit after the filesystem replacement and before the database completion.
            Files.move(Path.of(edit.stagedPath), audio, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            store.close()
            store = openMusicStore(directory.resolve("library.db").toString())
            val reopenedLibrary = MusicLibrary(store)
            val reopenedEdits = LocalFileEdits(store, reopenedLibrary, directory.resolve("edits").toString())
            reopenedEdits.recover()
            assertEquals(true, reopenedEdits.history().single().applied)
            assertEquals("Recovered title", reopenedLibrary.findTrack(track.id)!!.metadata.title)
            reopenedEdits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, audio))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun preparingTagsLeavesTheSongUntouchedAndCanBeAppliedAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("prepared-tag-edit")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val music = Files.createDirectory(directory.resolve("music"))
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = music.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(music.toString()).single()
            val edit = LocalFileEdits(store, library, directory.resolve("edits").toString())
                .prepareTags(track.id, track.metadata.copy(title = "Prepared title"))
            assertEquals(-1L, Files.mismatch(original, audio))
            assertEquals(listOf("Northern Light"), LocalFiles(library).scan(music.toString()).map { it.metadata.title })
            store.close()
            store = openMusicStore(directory.resolve("library.db").toString())
            val reopenedLibrary = MusicLibrary(store)
            val reopenedEdits = LocalFileEdits(store, reopenedLibrary, directory.resolve("edits").toString())
            assertEquals(false, reopenedEdits.history().single().applied)
            reopenedEdits.apply(edit.id)
            assertEquals("Prepared title", reopenedLibrary.findTrack(track.id)!!.metadata.title)
            assertEquals(true, reopenedEdits.history().single().applied)
            reopenedEdits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, audio))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun mp3AndWavTagEditsKeepTheirAudioAndAllGenreValues() = runBlocking {
        val directory = Files.createTempDirectory("common-format-tags")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val fixtureDirectory = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album")
            val library = MusicLibrary(store)
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            for (name in listOf("02 Long Way Home.mp3", "03 Night Walk.wav")) {
                val audio = directory.resolve(name)
                Files.copy(fixtureDirectory.resolve(name), audio)
                val before = decodedAudioHash(audio)
                val track = LocalFiles(library).scan(audio.toString()).single()
                val metadata = track.metadata.copy(title = "Edited ${audio.fileName}", artist = "Changed artist",
                    album = "Changed album", albumArtist = "Various artists", genres = listOf("Jazz", "Ambient"),
                    trackNumber = 7, discNumber = 2)
                val edit = edits.editTags(track.id, metadata)
                val reread = LocalFiles(library).scan(audio.toString()).single()
                assertEquals(metadata, reread.metadata)
                assertEquals(before, decodedAudioHash(audio))
                edits.undo(edit.id)
                assertEquals(-1L, Files.mismatch(fixtureDirectory.resolve(name), audio))
            }
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun id3v23TagsWithEmptyFieldsCanBeEdited() = runBlocking {
        val directory = Files.createTempDirectory("id3v23-tags")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val audio = directory.resolve("song.mp3")
            val writer = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-i",
                Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets/fixture.mp3").toString(),
                "-c", "copy", "-id3v2_version", "3", "-metadata", "date=", "-metadata", "track=", "-metadata", "album=",
                "-metadata", "album_artist=", audio.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            check(writer.waitFor() == 0)
            val original = Files.readAllBytes(audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val tags = edits.readTags(track.id)
            assertEquals(listOf("", "", 0, 0), listOf(tags.album, tags.albumArtist, tags.year, tags.trackNumber))
            val metadata = tags.copy(title = "Edited title")
            val edit = edits.editTags(track.id, metadata)
            assertEquals(metadata, edits.readTags(track.id))
            val bulk = edits.editTags(listOf(track.id), TagChanges(album = "", year = 0, trackNumber = 0)).single()
            assertEquals(metadata, edits.readTags(track.id))
            edits.undo(bulk.id); edits.undo(edit.id)
            assertEquals(-1L, java.util.Arrays.mismatch(original, Files.readAllBytes(audio)).toLong())
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun undoRefusesToOverwriteAFileThatAnotherApplicationChanged() = runBlocking {
        val directory = Files.createTempDirectory("local-tag-conflict")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            val edits = LocalFileEdits(store, library, directory.resolve("edits").toString())
            val edit = edits.editTags(track.id, track.metadata.copy(title = "Nevumi title"))
            val external = directory.resolve("external.flac")
            val writer = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-i", audio.toString(),
                "-c", "copy", "-metadata", "title=External title", external.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            check(writer.waitFor() == 0)
            Files.copy(external, audio, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertFailsWith<IllegalStateException> { edits.undo(edit.id) }
            assertEquals(-1L, Files.mismatch(external, audio))
            assertEquals(false, edits.history().single().undone)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun aCompletedTagEditCanBeUndoneAfterRestartWithoutLosingFavorites() = runBlocking {
        val directory = Files.createTempDirectory("local-tag-undo")
        var store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            library.setFavorite(track.id, true)
            val edit = LocalFileEdits(store, library, directory.resolve("edits").toString())
                .editTags(track.id, track.metadata.copy(title = "Temporary title"))
            store.close()
            store = openMusicStore(directory.resolve("library.db").toString())
            val reopenedLibrary = MusicLibrary(store)
            val reopenedEdits = LocalFileEdits(store, reopenedLibrary, directory.resolve("edits").toString())
            assertEquals(edit, reopenedEdits.history().single())
            reopenedEdits.undo(edit.id)
            assertEquals(-1L, Files.mismatch(original, audio))
            val restored = reopenedLibrary.findTrack(track.id)!!
            assertEquals("Northern Light", restored.metadata.title)
            assertEquals(true, restored.favorite)
            assertEquals(true, reopenedEdits.history().single().undone)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun editingLocalTagsPreservesPlayableAudioAndAnExactUndoCopy() = runBlocking {
        val directory = Files.createTempDirectory("local-tag-edit")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val original = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment", "music",
                "Fixture Artist", "Fixture Album", "01 Northern Light.flac")
            val audio = directory.resolve("song.flac")
            Files.copy(original, audio)
            val before = decodedAudioHash(audio)
            val library = MusicLibrary(store)
            val track = LocalFiles(library).scan(audio.toString()).single()
            library.setFavorite(track.id, true)
            val edit = LocalFileEdits(store, library, directory.resolve("edits").toString()).editTags(track.id,
                track.metadata.copy(title = "A new title", artist = "A new artist", genres = listOf("Jazz")))
            val refreshed = LocalFiles(library).scan(audio.toString()).single()
            assertEquals(Triple("A new title", "A new artist", listOf("Jazz")),
                Triple(refreshed.metadata.title, refreshed.metadata.artist, refreshed.metadata.genres))
            assertEquals(track.id, refreshed.id)
            assertEquals(true, refreshed.favorite)
            assertEquals(-1L, Files.mismatch(original, Path.of(edit.backupPath)))
            assertEquals(before, decodedAudioHash(audio))
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    private fun decodedAudioHash(file: Path): String {
        val process = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-i", file.toString(),
            "-vn", "-f", "hash", "-hash", "sha256", "-").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "The audio must remain decodable" }
        return output
    }

    private class SimulatedProcessExit : Error("The provider process exited")
}
