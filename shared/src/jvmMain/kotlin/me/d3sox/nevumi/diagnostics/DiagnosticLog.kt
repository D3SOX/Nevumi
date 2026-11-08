package me.d3sox.nevumi.diagnostics

import java.io.File

/** A local text log that keeps its newest lines once it grows past [maxBytes]. Nothing in it leaves the device. */
class DiagnosticLog(private val file: File, private val maxBytes: Long = 64 * 1024) {
    @Synchronized fun append(line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line.replace('\n', ' ') + "\n")
        if (file.length() <= maxBytes) return
        // Trimming to half the limit means a full log is rewritten only now and then, not on every line.
        val kept = ArrayDeque<String>()
        var bytes = 0L
        for (entry in file.readLines().asReversed()) {
            bytes += entry.toByteArray().size + 1
            if (bytes > maxBytes / 2) break
            kept.addFirst(entry)
        }
        file.writeText(kept.joinToString("") { "$it\n" })
    }
}
