package me.d3sox.nevumi.sync

import me.d3sox.nevumi.app.MusicController
import me.d3sox.nevumi.library.*
import me.d3sox.nevumi.playback.*
import me.d3sox.nevumi.queue.*
import me.d3sox.nevumi.security.CredentialVault
import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PlaybackTransferTest {
    @Test fun identicalLocalFilesTransferWithoutMusicBrainzTagsAndMissingContextIsSkipped() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-local-transfer")
        val stores = (0..1).map { openMusicStore(directory.resolve("$it.db").toString()) }
        val libraries = stores.map(::MusicLibrary)
        val songs = libraries.mapIndexed { index, library ->
            listOf("fixture.flac", "fixture.mp3").mapIndexed { number, name ->
                val source = Path.of(System.getProperty("nevumi.testRoot"), "androidApp/src/androidTest/assets", name)
                val path = directory.resolve("$index-$name")
                Files.copy(source, path)
                library.importTrack(TrackMetadata("Song $number", durationMs = 3_000),
                    TrackSource("local:$index:$number", SourceKind.LOCAL, path.toString(), indexedSize = Files.size(path)))
            }
        }
        val missing = libraries[0].importTrack(TrackMetadata("Unavailable context"),
            TrackSource("missing", SourceKind.LOCAL, directory.resolve("missing.flac").toString()))
        val devices = stores.mapIndexed { index, store ->
            val vault = object : CredentialVault {
                private val values = mutableMapOf<String, String>()
                override suspend fun save(key: String, secret: String) { values[key] = secret }
                override suspend fun read(key: String) = values[key]
                override suspend fun delete(key: String) { values.remove(key) }
            }
            val firstHash = java.util.concurrent.atomic.AtomicBoolean(true)
            val sync = LibrarySync(store, libraries[index], audioContentId = { location ->
                if (firstHash.compareAndSet(true, false)) Thread.sleep(16_000)
                FileAudioAccess.contentId(location)
            })
            JvmPairedDevices(store, sync, vault, "Device $index")
        }
        val players = stores.mapIndexed { index, store ->
            MusicController(libraries[index], PlaybackQueue(store), MpvPlaybackEngine("null"), pairedDevices = devices[index])
        }
        try {
            players.forEach { withTimeout(5_000) { it.state.first { state -> state.libraryReady } } }
            devices[0].pair(devices[1].createInvitation("127.0.0.1"))
            val source = players[0]; val target = players[1]
            source.playTrack(songs[0][0].id, listOf(songs[0][0].id, missing.id), contextName = "Local songs").join()
            source.pause().join(); source.seek(500).join()
            source.enqueue(songs[0][1].id, QueueAction.PLAY_NEXT).join()
            val before = source.state.value.queue
            val notice = async(start = CoroutineStart.UNDISPATCHED) { source.notices.first() }
            source.transferPlayback(devices[0].state.value.peers.single().deviceId).join()
            assertNull(source.state.value.error, "Identical files must match without external IDs")
            assertEquals(songs[1].map { it.id }, target.state.value.queue.entries.map { it.trackId })
            assertEquals(before.entries.take(2).map { it.section }, target.state.value.queue.entries.map { it.section })
            assertTrue(target.state.value.playback.positionMs >= 450)
            val moved = withTimeout(3_000) { notice.await() }
            val movedText = org.jetbrains.compose.resources.getString(moved.message, *moved.arguments.toTypedArray())
            assertTrue(movedText.contains("1 unavailable"), movedText)
            requireNotNull(moved.undo)().join()
            assertEquals(before.entries.take(2), source.state.value.queue.entries)
            source.enqueue(missing.id, QueueAction.PLAY_NEXT).join()
            source.transferPlayback(devices[0].state.value.peers.single().deviceId).join()
            assertNotNull(source.state.value.error, "Missing manual requests must block the transfer")
            Unit
        } finally {
            players.forEach { it.closeAndJoin() }; stores.forEach { it.close() }; directory.toFile().deleteRecursively()
        }
    }

    @Test fun movingPlaybackPreservesBothQueueSectionsAndUndoReturnsTheCurrentPosition() = runBlocking {
        val directory = Files.createTempDirectory("nevumi-playback-transfer")
        val stores = (0..1).map { openMusicStore(directory.resolve("$it.db").toString()) }
        val libraries = stores.map(::MusicLibrary)
        val path = Path.of(System.getProperty("nevumi.testRoot"), ".test-environment/music/Fixture Artist/Fixture Album/01 Northern Light.flac").toString()
        val songs = libraries.mapIndexed { index, library ->
            listOf("First", "Context next", "Manual next").mapIndexed { number, title ->
                library.importTrack(TrackMetadata(title, "Fixture Artist", "Fixture Album", durationMs = 3_000,
                    recordingId = "11111111-1111-4111-8111-11111111111${number}", releaseId = "22222222-2222-4222-8222-222222222222",
                    trackNumber = number + 1, discNumber = 1),
                    TrackSource("local:$index:$title", SourceKind.LOCAL, path))
            }
        }
        var blockTransfer = false
        val connecting = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        val devices = stores.mapIndexed { index, store ->
            val vault = object : CredentialVault {
                val values = mutableMapOf<String, String>()
                override suspend fun save(key: String, secret: String) { values[key] = secret }
                override suspend fun read(key: String) = values[key]
                override suspend fun delete(key: String) { values.remove(key) }
            }
            JvmPairedDevices(store, LibrarySync(store, libraries[index]), vault, "Device $index", networkAccess = {
                if (index == 0 && blockTransfer) { connecting.complete(Unit); connected.await() }
            })
        }
        val slowTarget = java.util.concurrent.atomic.AtomicBoolean(false)
        val players = stores.mapIndexed { index, store -> MusicController(libraries[index], PlaybackQueue(store), MpvPlaybackEngine("null"), pairedDevices = devices[index],
            sourceAccessible = { location ->
                if (index == 1 && slowTarget.compareAndSet(true, false)) Thread.sleep(800)
                FileAudioAccess.available(location)
            }) }
        val phone = players[0]; val desktop = players[1]
        try {
            players.forEach { withTimeout(5_000) { it.state.first { state -> state.libraryReady } } }
            devices[0].pair(devices[1].createInvitation("127.0.0.1"))
            phone.playTrack(songs[0][0].id, songs[0].take(2).map { it.id }, contextName = "Fixture Album").join()
            phone.pause().join()
            phone.enqueue(songs[0][2].id, QueueAction.PLAY_NEXT).join()
            phone.seek(500).join()
            withTimeout(3_000) { phone.state.first { it.playback.positionMs >= 500 } }
            val before = phone.state.value.queue
            val notice = async(start = CoroutineStart.UNDISPATCHED) { phone.notices.first() }
            phone.transferPlayback(devices[0].state.value.peers.single().deviceId).join()
            assertNull(phone.state.value.error, "The paired device must accept playback")
            assertEquals(songs[1].map { it.id }.let { listOf(it[0], it[2], it[1]) }, desktop.state.value.queue.entries.map { it.trackId })
            assertEquals(before.entries.map { it.section }, desktop.state.value.queue.entries.map { it.section })
            assertEquals(before.currentEntryId, desktop.state.value.queue.currentEntryId)
            assertEquals("Fixture Album", desktop.state.value.queue.contextName)
            assertTrue(desktop.state.value.playback.positionMs >= 450)
            assertFalse(desktop.state.value.playback.playWhenReady)
            desktop.seek(1_500).join()
            withTimeout(3_000) { desktop.state.first { it.playback.positionMs >= 1_500 } }
            val undo = assertNotNull(withTimeout(3_000) { notice.await() }.undo)
            undo().join()
            assertEquals(before.entries, phone.state.value.queue.entries)
            assertTrue(phone.state.value.playback.positionMs >= 1_450)
            assertFalse(desktop.state.value.playback.playWhenReady)
            phone.setQueueRepeat(RepeatMode.ONE).join()
            phone.play().join()
            withTimeout(3_000) { phone.state.first { it.playback.playing } }
            blockTransfer = true
            val moving = phone.transferPlayback(devices[0].state.value.peers.single().deviceId)
            withTimeout(3_000) { connecting.await() }
            phone.enqueue(songs[0][2].id, QueueAction.APPEND).join()
            val changedQueue = phone.state.value.queue.entries
            connected.complete(Unit)
            moving.join()
            assertTrue(phone.state.value.playback.playWhenReady, "Editing the queue while connecting must cancel the transfer without pausing this device")
            assertEquals(changedQueue, phone.state.value.queue.entries)
            assertFalse(desktop.state.value.playback.playWhenReady, "A canceled transfer must restore the receiving device")
            blockTransfer = false
            phone.pause().join(); phone.seek(0).join(); phone.play().join()
            withTimeout(3_000) { phone.state.first { it.playback.playing } }
            slowTarget.set(true)
            phone.transferPlayback(devices[0].state.value.peers.single().deviceId).join()
            withTimeout(3_000) { desktop.engine.status.first { it.playing } }
            assertFalse(phone.engine.status.value.playWhenReady)
            // The receiver keeps playing after the handover, so on a loaded machine it can be well ahead; only a rewind is wrong.
            val source = phone.engine.status.value.positionMs
            val target = desktop.engine.status.value.positionMs
            assertTrue(target >= source - 250 && target - source < 5_000,
                "Preparing a slow receiver must not rewind playback: source=$source, target=$target")
        } finally {
            connected.complete(Unit)
            players.forEach { it.closeAndJoin() }; stores.forEach { it.close() }; directory.toFile().deleteRecursively()
        }
    }
}
