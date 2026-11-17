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
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun dismissingThePromptWorksWhenTheMarkerCannotBeWritten() {
        val directory = Files.createTempDirectory("crash-log-marker").toFile()
        try {
            val log = CrashLog(directory, { "Nevumi 1.0 on Test" })
            log.record("main", IllegalStateException("crash"))
            // A directory where the marker file belongs makes writing it fail.
            File(directory, "seen").mkdir()
            log.markSeen()
            assertNull(log.unseen.value)
        } finally { directory.deleteRecursively() }
    }
}
