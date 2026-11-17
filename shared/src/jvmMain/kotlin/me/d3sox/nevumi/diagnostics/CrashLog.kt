package me.d3sox.nevumi.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

/**
 * Writes uncaught exceptions to [directory] before the platform's own handler ends the process, and keeps the newest
 * [limit] of them. [describe] names the app version and platform for the report's header.
 */
class CrashLog(private val directory: File, private val describe: () -> String, private val limit: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis) : CrashReports {
    private val seenFile = File(directory, "seen")
    private val sequenceFile = File(directory, "sequence")
    /** Reports already offered; kept in memory too, so an unwritable marker still holds for this run. */
    private var seen: Set<String> = runCatching { seenFile.readLines().toSet() }.getOrDefault(emptySet())
    private val mutableReports = MutableStateFlow(read())
    override val reports: StateFlow<List<CrashReport>> = mutableReports.asStateFlow()
    private val mutableUnseen = MutableStateFlow(unseenReport())
    override val unseen: StateFlow<CrashReport?> = mutableUnseen.asStateFlow()

    /** Records crashes from now on, then lets the previous handler, such as Android's crash dialog, run as before. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record(thread.name, error) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized fun record(threadName: String, error: Throwable) {
        directory.mkdirs()
        val time = clock()
        val header = listOf("Nevumi crash report", describe(), "Time: ${Instant.ofEpochMilli(time)}", "Thread: $threadName")
        // A sequence number, not the clock, names and orders reports, so a clock change neither reuses a name nor sorts a new crash
        // behind old ones; the counter outlives deleted reports, so a new crash never takes a name that was already offered.
        val sequence = maxOf(runCatching { sequenceFile.readText().trim().toLong() }.getOrDefault(0), files().maxOfOrNull(::sequenceOf) ?: 0) + 1
        try { sequenceFile.writeText(sequence.toString()) } catch (_: java.io.IOException) { }
        File(directory, "crash-$sequence-$time.txt").writeText(header.joinToString("") { "${CrashReport.HEADER_PREFIX}$it\n" } + "\n" + error.stackTraceToString())
        files().drop(limit).forEach { it.delete() }
        refresh()
    }

    /** Remembers the reports by name, not time, so a crash after the clock went back is still offered. */
    @Synchronized override fun markSeen() {
        // The marker only spares the next launch the prompt; failing to write it must not fail the dismissal.
        seen = seen + reports.value.map { it.id }
        try { directory.mkdirs(); seenFile.writeText(seen.joinToString("\n")) }
        catch (_: java.io.IOException) { }
        mutableUnseen.value = null
    }

    @Synchronized override fun delete(id: String) {
        files().firstOrNull { it.name == id }?.delete()
        refresh()
    }

    @Synchronized override fun deleteAll() {
        files().forEach { it.delete() }
        seenFile.delete()
        seen = emptySet()
        refresh()
    }

    private fun refresh() {
        mutableReports.value = read()
        mutableUnseen.value = unseenReport()
    }

    private fun files(): List<File> = directory.listFiles { file -> file.isFile && file.name.matches(reportName) }.orEmpty()
        .sortedByDescending(::sequenceOf)

    private fun sequenceOf(file: File) = requireNotNull(reportName.matchEntire(file.name)).groupValues[1].toLong()

    private fun read(): List<CrashReport> = files().mapNotNull { file ->
        runCatching { CrashReport(file.name, requireNotNull(reportName.matchEntire(file.name)).groupValues[2].toLong(), file.readText()) }.getOrNull()
    }

    private fun unseenReport(): CrashReport? = mutableReports.value.firstOrNull { it.id !in seen }

    private companion object { val reportName = Regex("crash-(\\d+)-(\\d+)\\.txt") }
}
