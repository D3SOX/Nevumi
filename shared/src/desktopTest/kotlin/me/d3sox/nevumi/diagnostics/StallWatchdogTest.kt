package me.d3sox.nevumi.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals

class StallWatchdogTest {
    private var now = 0L
    /** Runs once when the clock is next read, as the watched thread would while a check is descheduled. */
    private var meanwhile: (() -> Unit)? = null
    private var samples = 0
    private val events = mutableListOf<String>()
    private val watchdog = StallWatchdog(1_000, { meanwhile?.also { meanwhile = null }?.invoke(); now }, { ++samples },
        stalled = { _, stalledMs -> events += "stalled $stalledMs" },
        ended = { _, stalledMs, resumed, before, after -> events += "ended $stalledMs resumed=$resumed $before->$after" })

    @Test fun reportsAStallWhileItLastsAndAgainWhenTheThreadMovesOn() {
        watchdog.start()
        watchdog.progress()
        now = 250; watchdog.check()
        now = 1_000; watchdog.check()
        now = 1_250; watchdog.check()
        assertEquals(listOf("stalled 1000"), events)
        now = 2_100; watchdog.progress()
        now = 2_250; watchdog.check()
        assertEquals(listOf("stalled 1000", "ended 2100 resumed=true 1->2"), events)
        // A thread that keeps moving is never reported.
        repeat(10) { now += 250; watchdog.progress(); watchdog.check() }
        assertEquals(2, events.size)
    }

    @Test fun aPauseEndsAStallWithoutAResumeAndStopsWatching() {
        watchdog.start()
        watchdog.progress()
        now = 1_500; watchdog.check()
        now = 1_800; watchdog.stop()
        assertEquals(listOf("stalled 1500", "ended 1800 resumed=false null->1"), events)
        now = 10_000; watchdog.check()
        assertEquals(2, events.size, "A paused thread does not move and is not stalled")
    }

    @Test fun aCheckThatResumesLateDoesNotMistakeItsOwnDelayForAStall() {
        watchdog.start()
        watchdog.progress()
        now = 250; watchdog.check()
        meanwhile = { now = 2_250; watchdog.progress() }
        watchdog.check()
        assertEquals(emptyList(), events)
    }

    @Test fun aThreadThatNeverRanIsNotWatched() {
        watchdog.start()
        now = 5_000; watchdog.check()
        assertEquals(emptyList(), events)
    }
}
