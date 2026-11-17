package me.d3sox.nevumi.diagnostics

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CrashLogTest {
    @Test
    fun crashesAreKeptNewestFirstUntilTheLimitAndOfferedOnceAfterTheyHappen() {
        val directory = Files.createTempDirectory("crash-log").toFile()
        try {
            var now = 1_000L
            val log = CrashLog(directory, { "Nevumi 1.0 on Test" }, limit = 2, clock = { now })
            assertTrue(log.reports.value.isEmpty())
            assertNull(log.unseen.value)
            for (message in listOf("first", "second", "third")) {
                log.record("main", IllegalStateException(message))
                now += 1_000
            }
            assertEquals(listOf("third", "second"), log.reports.value.map { it.summary.substringAfter(": ") })
            val newest = log.reports.value.first()
            assertTrue(newest.text.startsWith("# Nevumi crash report\n# Nevumi 1.0 on Test\n"))
            assertTrue("Thread: main" in newest.text && "CrashLogTest" in newest.text, "The report names the thread and holds the stack trace")
            assertEquals(newest, log.unseen.value)

            log.markSeen()
            assertNull(log.unseen.value)
            // A new instance, as after the next launch, remembers what was offered.
            val reopened = CrashLog(directory, { "" })
            assertEquals(2, reopened.reports.value.size)
            assertNull(reopened.unseen.value)

            reopened.delete(newest.id)
            assertEquals(listOf("second"), reopened.reports.value.map { it.summary.substringAfter(": ") })
            reopened.deleteAll()
            assertTrue(reopened.reports.value.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun aCrashAfterTheClockWentBackIsStillOffered() {
        val directory = Files.createTempDirectory("crash-log-clock").toFile()
        try {
            var now = 5_000L
            val log = CrashLog(directory, { "Nevumi 1.0 on Test" }, clock = { now })
            log.record("main", IllegalStateException("before"))
            log.markSeen()
            now = 1_000L
            log.record("main", IllegalStateException("after"))
            assertEquals("after", log.unseen.value?.summary?.substringAfter(": "))
            log.markSeen()
            assertNull(CrashLog(directory, { "" }).unseen.value)
            // The same moment again names a new report instead of overwriting one that was already offered.
            log.record("main", IllegalStateException("same moment"))
            assertEquals("same moment", log.unseen.value?.summary?.substringAfter(": "))
            assertEquals(3, log.reports.value.size)
            // With the limit reached, a crash at an earlier time is still the newest report and is kept.
            val limited = CrashLog(directory, { "" }, limit = 3, clock = { 0 })
            limited.record("main", IllegalStateException("earliest clock"))
            assertEquals(listOf("earliest clock", "same moment", "after"), limited.reports.value.map { it.summary.substringAfter(": ") })
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun dismissingThePromptWorksWhenTheMarkerCannotBeWritten() {
        val directory = Files.createTempDirectory("crash-log-marker").toFile()
        try {
            val log = CrashLog(directory, { "Nevumi 1.0 on Test" })
            log.record("main", IllegalStateException("older"))
            log.record("main", IllegalStateException("newer"))
            // A directory where the marker file belongs makes writing it fail.
            File(directory, "seen").mkdir()
            log.markSeen()
            assertNull(log.unseen.value)
            // Deleting a report refreshes the list; the dismissed one must not come back for lack of a marker.
            log.delete(log.reports.value.last().id)
            assertNull(log.unseen.value)
        } finally { directory.deleteRecursively() }
    }
}
