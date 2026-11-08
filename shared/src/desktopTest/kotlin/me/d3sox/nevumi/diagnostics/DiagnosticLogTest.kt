package me.d3sox.nevumi.diagnostics

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticLogTest {
    @Test fun keepsTheNewestLinesOnceTheLogIsFull() {
        val directory = createTempDirectory("diagnostic-log").toFile()
        try {
            val file = File(directory, "nested/underruns.log")
            val log = DiagnosticLog(file, maxBytes = 100)
            repeat(20) { log.append("event $it") }
            val lines = file.readLines()
            assertTrue(file.length() <= 100, "The log must stay within its limit: ${file.length()} bytes")
            assertEquals("event 19", lines.last())
            assertEquals((20 - lines.size until 20).map { "event $it" }, lines)
        } finally { directory.deleteRecursively() }
    }

    @Test fun anEventStaysOnOneLine() {
        val directory = createTempDirectory("diagnostic-log").toFile()
        try {
            val file = File(directory, "underruns.log")
            DiagnosticLog(file).append("first\nsecond")
            assertEquals(listOf("first second"), file.readLines())
        } finally { directory.deleteRecursively() }
    }
}
