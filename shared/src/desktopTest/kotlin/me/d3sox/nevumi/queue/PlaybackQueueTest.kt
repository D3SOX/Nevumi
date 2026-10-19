package me.d3sox.nevumi.queue

import me.d3sox.nevumi.storage.openMusicStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.random.Random
import me.d3sox.nevumi.playback.RepeatMode

class PlaybackQueueTest {
    @Test
    fun shufflingOnlyChangesTheContextAndKeepsManualRequestsInTheirChosenOrder() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("playing", "a", "b", "c", "d", "e"))
            queue.append("requested-one")
            queue.playAfter("requested-two")
            val original = queue.snapshot()
            queue.shuffle(true, Random(71))
            assertEquals(listOf("playing", "requested-one", "requested-two"), queue.snapshot().entries.take(3).map { it.trackId })
            assertNotEquals(original.context, queue.snapshot().context)
            queue.shuffle(false)
            assertEquals(original, queue.snapshot())
        }
    }

    @Test
    fun movingTracksBetweenTheManualAndContextQueueChangesTheirSectionAndSurvivesRestart() = runBlocking {
        val directory = Files.createTempDirectory("queue-sections")
        val path = directory.resolve("library.db").toString()
        try {
            val store = openMusicStore(path)
            val queue = PlaybackQueue(store)
            queue.replace(listOf("playing", "album-next", "album-last"))
            queue.append("requested")
            queue.reorder(queue.snapshot().entries.single { it.trackId == "album-next" }.id, 1)
            assertEquals(listOf("album-next", "requested"), queue.snapshot().upNext.map { it.trackId })
            queue.reorder(queue.snapshot().entries.single { it.trackId == "requested" }.id, 3)
            assertEquals(listOf("album-next"), queue.snapshot().upNext.map { it.trackId })
            assertEquals(listOf("album-last", "requested"), queue.snapshot().context.map { it.trackId })
            store.close()
            val reopened = openMusicStore(path)
            try {
                assertEquals(listOf("playing", "album-next", "album-last", "requested"),
                    PlaybackQueue(reopened).snapshot().entries.map { it.trackId })
                assertEquals(listOf("album-next"), PlaybackQueue(reopened).snapshot().upNext.map { it.trackId })
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun insertingMultipleSongsPreservesTheirOrderAndPlayAfterUsesTheLastInsertedSong() = runBlocking {
        val directory = Files.createTempDirectory("batch-queue")
        val store = openMusicStore(directory.resolve("library.db").toString())
        try {
            val queue = PlaybackQueue(store)
            queue.replace(listOf("current", "later"))
            queue.setPosition(1_250)
            val playing = queue.snapshot().currentEntryId
            queue.enqueue(listOf("b", "c"), QueueAction.PLAY_NEXT)
            queue.enqueue(listOf("d", "e"), QueueAction.PLAY_AFTER)
            queue.enqueue(listOf("f", "g"), QueueAction.APPEND)
            val snapshot = queue.snapshot()
            assertEquals(listOf("current", "b", "c", "d", "e", "f", "g", "later"), snapshot.entries.map { it.trackId })
            assertEquals(playing, snapshot.currentEntryId)
            assertEquals(1_250L, snapshot.positionMs)
            assertEquals(snapshot.entries.single { it.trackId == "g" }.id, snapshot.lastInsertedEntryId)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun turningShuffleOffKeepsAManuallyMovedSongImmediatelyAfterThePlayingSong() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("playing", "a", "b", "c", "d"))
            val playing = queue.snapshot().currentEntryId
            queue.shuffle(true, Random(71))
            queue.reorder(queue.snapshot().entries.single { it.trackId == "b" }.id, 1)
            queue.shuffle(false)
            assertEquals(listOf("playing", "b", "a", "c", "d"), queue.snapshot().entries.map { it.trackId })
            assertEquals(playing, queue.snapshot().currentEntryId)
        }
    }

    @Test
    fun removingTheRepeatedSongDoesNotApplyItsRepeatCountToTheFollowingSong() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("repeated", "following"))
            queue.setRepeat(RepeatMode.ONE, playCount = 3)
            queue.remove(requireNotNull(queue.snapshot().currentEntryId))
            val remaining = queue.snapshot()
            assertEquals("following", remaining.currentTrackId)
            assertEquals(RepeatMode.OFF to null, remaining.repeatMode to remaining.repeatPlays)
        }
    }

    @Test
    fun albumShuffleFinishesTheCurrentAlbumAndKeepsOtherAlbumsTogetherInTrackOrder() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("a1", "b2", "c2", "a2", "b1", "c1"))
            val original = queue.snapshot()
            queue.shuffleAlbums(listOf(listOf("a1", "a2"), listOf("b1", "b2"), listOf("c1", "c2")), Random(71))
            val tracks = queue.snapshot().entries.map { it.trackId }
            assertEquals(listOf("a1", "a2"), tracks.take(2))
            assertEquals(setOf(listOf("b1", "b2"), listOf("c1", "c2")), tracks.drop(2).chunked(2).toSet())
            assertEquals(original.currentEntryId, queue.snapshot().currentEntryId)
            queue.shuffle(false)
            assertEquals(original, queue.snapshot())
        }
    }

    @Test
    fun turningShuffleOffKeepsRequestedTracksAtTheirInsertionPoints() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("playing", "a", "b", "c", "d", "e"))
            queue.shuffle(true, Random(71))
            queue.playNext("requested-one")
            queue.playAfter("requested-two")
            queue.append("last")
            queue.remove(queue.snapshot().entries.first { it.trackId == "b" }.id)
            queue.shuffle(false)
            assertEquals(listOf("playing", "requested-one", "requested-two", "last", "a", "c", "d", "e"),
                queue.snapshot().entries.map { it.trackId })
        }
    }

    @Test
    fun shuffleKeepsThePlayingOccurrenceAndCanRestoreTheOrderAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("queue-shuffle-test")
        val path = directory.resolve("queue.db").toString()
        try {
            val store = openMusicStore(path)
            val queue = PlaybackQueue(store)
            queue.replace(listOf("before", "playing", "duplicate", "three", "four", "duplicate", "six", "seven"), 1)
            queue.setPosition(42_000)
            val original = queue.snapshot()
            queue.shuffle(true, Random(71))
            val shuffled = queue.snapshot()
            assertEquals(original.entries.take(2), shuffled.entries.take(2))
            assertNotEquals(original.entries, shuffled.entries)
            assertEquals(original.entries.map { it.id }.toSet(), shuffled.entries.map { it.id }.toSet())
            assertEquals(original.currentEntryId to 42_000L, shuffled.currentEntryId to shuffled.positionMs)
            store.close()

            val reopened = openMusicStore(path)
            try {
                val restored = PlaybackQueue(reopened)
                restored.shuffle(false)
                assertEquals(original, restored.snapshot())
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun namedQueuesSurviveRestartAndRestoreTheirExactOccurrenceAndPosition() = runBlocking {
        val directory = Files.createTempDirectory("saved-queue-test")
        val path = directory.resolve("queue.db").toString()
        try {
            val store = openMusicStore(path)
            val queue = PlaybackQueue(store)
            queue.replace(listOf("duplicate", "middle", "duplicate"), startIndex = 2)
            queue.setPosition(42_000)
            queue.playAfter("requested")
            val evening = queue.snapshot()
            val saved = queue.save("Evening")
            queue.replace(listOf("morning"))
            queue.save("Morning")
            assertEquals(listOf("morning"), queue.snapshot().entries.map { it.trackId })
            store.close()

            val reopened = openMusicStore(path)
            try {
                val restored = PlaybackQueue(reopened)
                assertEquals(setOf("Evening", "Morning"), restored.saved().map { it.name }.toSet())
                restored.restoreSaved(saved.id)
                assertEquals(evening.copy(selectedSavedQueueId = saved.id), restored.snapshot())
                restored.playAfter("requested-again")
                assertEquals(listOf("duplicate", "middle", "duplicate", "requested", "requested-again"),
                    restored.snapshot().entries.map { it.trackId })
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun playAfterBuildsAnOrderedRunAfterTheMostRecentInsertion() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("playing", "album-next", "album-last"))
            queue.playNext("requested-one")
            queue.playAfter("requested-two")
            queue.playAfter("requested-three")

            assertEquals(listOf("playing", "requested-one", "requested-two", "requested-three",
                "album-next", "album-last"), queue.snapshot().entries.map { it.trackId })
        }
    }

    @Test
    fun playNextIsRestoredInTheCorrectOrderAfterRestart() = runBlocking {
        val directory = Files.createTempDirectory("playback-queue-test")
        val path = directory.resolve("queue.db").toString()
        try {
            val store = openMusicStore(path)
            val queue = PlaybackQueue(store)
            queue.replace(listOf("playing", "later"))
            queue.playNext("requested-next")
            store.close()

            val reopened = openMusicStore(path)
            try {
                assertEquals(listOf("playing", "requested-next", "later"),
                    PlaybackQueue(reopened).snapshot().entries.map { it.trackId })
            } finally { reopened.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun manuallyQueuedSongsPlayBeforeTheRemainingContext() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("playing", "later"))
            queue.append("appended")
            val snapshot = queue.snapshot()

            assertEquals(listOf("playing", "appended", "later") to "playing",
                snapshot.entries.map { it.trackId } to snapshot.currentTrackId)
        }
    }

    @Test
    fun reorderingDuplicateSongsKeepsTheSameOccurrencePlaying() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("duplicate", "playing", "duplicate"), startIndex = 1)
            val before = queue.snapshot()
            queue.reorder(before.entries.first().id, 2)
            val after = queue.snapshot()

            assertEquals(listOf("playing", "duplicate", "duplicate") to before.currentEntryId,
                after.entries.map { it.trackId } to after.currentEntryId)
        }
    }

    @Test
    fun removingThePlayingEntrySelectsTheFollowingSong() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("before", "playing", "following"), startIndex = 1)
            queue.remove(requireNotNull(queue.snapshot().currentEntryId))

            assertEquals("following", queue.snapshot().currentTrackId)
        }
    }

    @Test
    fun thePlayheadPositionSurvivesRestart() = runBlocking {
        val directory = Files.createTempDirectory("playback-queue-test")
        val path = directory.resolve("queue.db").toString()
        try {
            val store = openMusicStore(path)
            val queue = PlaybackQueue(store)
            queue.replace(listOf("playing"))
            queue.setPosition(42_000)
            store.close()

            val restored = openMusicStore(path)
            try {
                assertEquals(42_000L, PlaybackQueue(restored).snapshot().positionMs)
            } finally { restored.close() }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun selectingAQueuedSongStartsThatOccurrenceAtTheBeginning() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("playing", "selected-song"))
            queue.setPosition(42_000)
            queue.select(queue.snapshot().entries.last().id)
            val selected = queue.snapshot()

            assertEquals("selected-song" to 0L, selected.currentTrackId to selected.positionMs)
        }
    }


    @Test
    fun playAfterFollowsTheLastManualInsertionAfterMovingSongsBetweenSections() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("playing", "album-next", "album-last"))
            queue.append("requested")
            queue.reorder(queue.snapshot().context.first().id, 1, QueueSection.MANUAL)
            queue.reorder(queue.snapshot().upNext.last().id, 3, QueueSection.CONTEXT)
            queue.playAfter("after")
            assertEquals(listOf("playing", "album-next", "after", "album-last", "requested"), queue.snapshot().entries.map { it.trackId })
        }
    }

    private suspend fun withQueue(block: suspend (PlaybackQueue) -> Unit) {
        val directory = Files.createTempDirectory("playback-queue-test")
        val store = openMusicStore(directory.resolve("queue.db").toString())
        try { block(PlaybackQueue(store)) }
        finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun selectedQueuesSaveEveryEditAndDeletingOneKeepsPlaybackWithoutRecreatingIt() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("a", "b", "a"), startIndex = 2)
            val saved = queue.save("Evening", select = true)
            queue.setPosition(42_000)
            queue.append("requested")
            queue.reorder(queue.snapshot().entries.first().id, 1)
            queue.setRepeat(RepeatMode.ALL)
            queue.shuffle(true, Random(71))
            assertEquals(queue.snapshot().copy(selectedSavedQueueId = null), queue.saved().single().snapshot)
            val live = queue.snapshot()
            queue.deleteSaved(saved.id)
            assertEquals(live.copy(selectedSavedQueueId = null), queue.snapshot())
            queue.append("after-delete")
            assertEquals(emptyList(), queue.saved())
        }
    }

    @Test fun startingAnEmptyQueueKeepsOnlyTheCurrentSongAndLeavesTheSavedQueueIntact() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("a", "b", "c"), startIndex = 1, contextName = "Album", origin = QueueOrigin(collectionId = "album"))
            queue.append("requested")
            queue.save("Evening", select = true)
            queue.setPosition(42_000)
            val before = queue.snapshot()
            queue.startEmpty()
            val empty = queue.snapshot()
            assertEquals(listOf(before.entries[1]), empty.entries)
            assertEquals(before.currentEntryId, empty.currentEntryId)
            assertEquals(42_000, empty.positionMs)
            assertEquals(QueueSnapshot().contextName, empty.contextName)
            assertEquals(null, empty.origin)
            assertEquals(null, empty.selectedSavedQueueId)
            assertEquals(before.copy(selectedSavedQueueId = null), queue.saved().single { it.name == "Evening" }.snapshot)
        }
    }

    @Test fun startingAnEmptyQueueFromAnUpNextSongLeavesNoUpNext() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("a", "b"))
            queue.playNext("requested")
            queue.select(queue.snapshot().upNext.single().id)
            queue.startEmpty()
            assertEquals(listOf(QueueSection.CONTEXT), queue.snapshot().entries.map { it.section })
        }
    }

    @Test fun overwriteKeepsTheSavedIdentityAndNameAndSelectsTheReplacement() = runBlocking {
        withQueue { queue ->
            queue.replace(listOf("old"))
            val target = queue.save("Evening")
            queue.replace(listOf("new", "new"), startIndex = 1)
            queue.setPosition(19_000)
            queue.overwriteSaved(target.id)
            assertEquals(target.id, queue.snapshot().selectedSavedQueueId)
            val overwritten = queue.saved().single()
            assertEquals(target.id to target.name, overwritten.id to overwritten.name)
            assertEquals(queue.snapshot().copy(selectedSavedQueueId = null), overwritten.snapshot)
            queue.append("more")
            assertEquals(listOf("new", "new", "more"), queue.saved().single().snapshot.entries.map { it.trackId })
        }
    }

    @Test fun switchingAndRestartingKeepEachQueuesOccurrenceAndPositionAndNewAlbumsAreTemporary() = runBlocking {
        val directory = Files.createTempDirectory("selected-queue-restart")
        val path = directory.resolve("queue.db").toString()
        var store = openMusicStore(path)
        try {
            var queue = PlaybackQueue(store)
            queue.replace(listOf("a", "b", "a"), startIndex = 2)
            val evening = queue.save("Evening", select = true)
            queue.setPosition(42_000)
            val occurrence = queue.snapshot().currentEntryId
            queue.replace(listOf("morning"))
            assertEquals(null, queue.snapshot().selectedSavedQueueId)
            assertEquals(42_000, queue.saved().single().snapshot.positionMs)
            val morning = queue.save("Morning", select = true)
            queue.setPosition(19_000)
            queue.restoreSaved(evening.id)
            assertEquals(occurrence to 42_000L, queue.snapshot().currentEntryId to queue.snapshot().positionMs)
            store.close(); store = openMusicStore(path); queue = PlaybackQueue(store)
            assertEquals(evening.id, queue.snapshot().selectedSavedQueueId)
            queue.restoreSaved(morning.id)
            assertEquals("morning" to 19_000L, queue.snapshot().currentTrackId to queue.snapshot().positionMs)
            assertEquals(42_000, queue.saved().first { it.id == evening.id }.snapshot.positionMs)
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }

    @Test
    fun queueReadsStayCurrentAcrossInstancesAndTrackRemaps() = runBlocking {
        val directory = Files.createTempDirectory("queue-read-cache")
        val store = openMusicStore(directory.resolve("queue.db").toString())
        try {
            val queue = PlaybackQueue(store)
            val other = PlaybackQueue(store)
            queue.replace(listOf("a", "b"))
            assertEquals(listOf("a", "b"), other.snapshot().entries.map { it.trackId })
            other.append("c")
            assertEquals(listOf("a", "c", "b"), queue.snapshot().entries.map { it.trackId })
            remapQueueTracks(store, "b", "linked")
            assertEquals(listOf("a", "c", "linked"), queue.snapshot().entries.map { it.trackId })
            val first = queue.snapshot()
            assertSame(first, queue.snapshot(), "An unchanged queue is decoded once")
        } finally { store.close(); directory.toFile().deleteRecursively() }
    }
}
